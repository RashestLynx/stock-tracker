import Foundation

/// A league: its Kalshi game series and its Polymarket tag. Mirrors SportsData.kt.
struct League: Hashable {
    let name: String
    let kalshiSeries: String
    let polyTag: String
    var hasDraw = false
}

/// One side of a game (a team, a fighter, or the draw), with each site's chance.
struct SportSide: Identifiable {
    let label: String
    let kalshi: PredOutcome?
    let poly: PredOutcome?
    let kalshiP: Double?
    let polyP: Double?
    var id: String { label }
    /// Best estimate: the average of the sites that have it (each one's prices adjusted to add up to 100%).
    var best: Double { let v = [kalshiP, polyP].compactMap { $0 }; return v.isEmpty ? 0 : v.reduce(0, +) / Double(v.count) }
}

struct SportGame: Identifiable {
    let title: String
    let date: String
    let sortKey: String
    let sides: [SportSide]
    let kalshiEvent: PredEvent
    let polyEvent: PredEvent?
    let volume: Double
    var id: String { kalshiEvent.id }
    /// The side where Kalshi and Polymarket disagree most.
    var widest: SportSide? {
        var best: SportSide?
        var bestGap = -1.0
        for s in sides {
            guard let k = s.kalshiP, let p = s.polyP else { continue }
            if abs(k - p) > bestGap { bestGap = abs(k - p); best = s }
        }
        return best
    }

    /// Biggest gap in points between Kalshi and Polymarket on any side.
    var gap: Double {
        sides.map { s in (s.kalshiP != nil && s.polyP != nil) ? abs(s.kalshiP! - s.polyP!) * 100 : 0 }.max() ?? 0
    }
}

enum SportsData {
    static let leagues: [League] = [
        League(name: "NFL", kalshiSeries: "KXNFLGAME", polyTag: "nfl"),
        League(name: "College football", kalshiSeries: "KXNCAAFGAME", polyTag: "cfb"),
        League(name: "NBA", kalshiSeries: "KXNBAGAME", polyTag: "nba"),
        League(name: "College basketball", kalshiSeries: "KXNCAAMBGAME", polyTag: "cbb"),
        League(name: "NHL", kalshiSeries: "KXNHLGAME", polyTag: "nhl"),
        League(name: "MLB", kalshiSeries: "KXMLBGAME", polyTag: "mlb"),
        League(name: "UFC", kalshiSeries: "KXUFCFIGHT", polyTag: "ufc"),
        League(name: "MLS", kalshiSeries: "KXMLSGAME", polyTag: "mls", hasDraw: true),
        League(name: "Premier League", kalshiSeries: "KXEPLGAME", polyTag: "epl", hasDraw: true),
        League(name: "Champions League", kalshiSeries: "KXUCLGAME", polyTag: "ucl", hasDraw: true),
    ]

    private static var cache: [String: (Date, [SportGame])] = [:]

    static func load(_ league: League, force: Bool) async throws -> [SportGame] {
        if !force, let c = cache[league.name], Date().timeIntervalSince(c.0) < 120 { return c.1 }
        let kalshi = try await kalshiGames(league)
        var poly = await polyGames(league)
        poly += ((try? await Predictions.load(force: false)) ?? []).filter { $0.source == "Polymarket" }
        let games = kalshi.compactMap { build($0, poly) }
            .sorted { $0.sortKey != $1.sortKey ? $0.sortKey < $1.sortKey : $0.volume > $1.volume }
        cache[league.name] = (Date(), games)
        return games
    }

    static func build(_ k: PredEvent, _ poly: [PredEvent]) -> SportGame? {
        let kSides = k.all.isEmpty ? k.outcomes : k.all
        guard kSides.count >= 2 else { return nil }
        let kTotal = kSides.reduce(0) { $0 + $1.prob }
        guard kTotal > 0 else { return nil }
        let p = poly.first { matches(kSides, $0.all.isEmpty ? $0.outcomes : $0.all) }
        let pAll = p.map { $0.all.isEmpty ? $0.outcomes : $0.all } ?? []
        let pTotal = pAll.reduce(0) { $0 + $1.prob }
        let sides = kSides.map { ko -> SportSide in
            let po = pAll.first { same(ko.label, $0.label) }
            return SportSide(label: ko.label, kalshi: ko, poly: po, kalshiP: ko.prob / kTotal,
                             polyP: po != nil && pTotal > 0 ? po!.prob / pTotal : nil)
        }
        func order(_ s: SportSide) -> Int {
            if isDraw(s.label) { return Int.max }
            return k.title.range(of: s.label).map { k.title.distance(from: k.title.startIndex, to: $0.lowerBound) } ?? 1000
        }
        let (date, key) = dateOf(k.ticker)
        return SportGame(title: k.title, date: date, sortKey: key, sides: sides.sorted { order($0) < order($1) },
                         kalshiEvent: k, polyEvent: p, volume: k.volume24)
    }

    static func isDraw(_ label: String) -> Bool {
        let l = label.lowercased()
        return l == "tie" || l == "draw" || l.hasPrefix("draw ") || l.hasPrefix("draw(")
    }

    private static func words(_ s: String) -> [String] {
        String(s.lowercased().map { $0.isLetter || $0.isNumber ? $0 : " " }).split(separator: " ").map(String.init)
    }

    private static func wordMatch(_ a: String, _ b: String) -> Bool {
        a == b || (a.count <= 3 && b.hasPrefix(a)) || (b.count <= 3 && a.hasPrefix(b))
    }

    /// True when every word of one label is in the other ("Boston" ~ "Boston Celtics", "New York G" ~ "New York Giants").
    static func same(_ a: String, _ b: String) -> Bool {
        if isDraw(a) || isDraw(b) { return isDraw(a) && isDraw(b) }
        let wa = words(a), wb = words(b)
        guard !wa.isEmpty, !wb.isEmpty else { return false }
        let (short, long) = wa.count <= wb.count ? (wa, wb) : (wb, wa)
        return short.allSatisfy { w in long.contains { wordMatch(w, $0) } }
    }

    /// Same game: every side matches exactly one side on the other site, one to one.
    static func matches(_ k: [PredOutcome], _ p: [PredOutcome]) -> Bool {
        guard k.count == p.count, k.count >= 2 else { return false }
        var used = Set<Int>()
        for ko in k {
            let hits = p.indices.filter { !used.contains($0) && same(ko.label, p[$0].label) }
            guard hits.count == 1 else { return false }
            used.insert(hits[0])
        }
        return true
    }

    /// "KXNFLGAME-26NOV15CHIDET" → ("Sun, Nov 15", "2026-11-15").
    static func dateOf(_ ticker: String) -> (String, String) {
        guard let r = ticker.range(of: "-[0-9]{2}[A-Z]{3}[0-9]{2}", options: .regularExpression) else { return ("", "9999") }
        let code = String(ticker[r].dropFirst())
        let inF = DateFormatter()
        inF.locale = Locale(identifier: "en_US_POSIX")
        inF.dateFormat = "yyMMMdd"
        guard let d = inF.date(from: code.prefix(5).capitalized + code.suffix(2)) ?? inF.date(from: code) else { return ("", "9999") }
        let show = DateFormatter()
        show.locale = Locale(identifier: "en_US_POSIX")
        show.dateFormat = "EEE, MMM d"
        let key = DateFormatter()
        key.locale = Locale(identifier: "en_US_POSIX")
        key.dateFormat = "yyyy-MM-dd"
        return (show.string(from: d), key.string(from: d))
    }

    private static func kalshiGames(_ league: League) async throws -> [PredEvent] {
        var lastError: Error = AppError("Couldn't load \(league.name) games")
        for base in ["https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2"] {
            do {
                var out: [PredEvent] = []
                var cursor = ""
                for _ in 0..<3 {
                    var url = "\(base)/events?series_ticker=\(league.kalshiSeries)&status=open&with_nested_markets=true&limit=200"
                    if !cursor.isEmpty { url += "&cursor=\(cursor)" }
                    let json = try await Predictions.get(url)
                    out += Predictions.parse(json, minVolume: 0)
                    cursor = json["cursor"] as? String ?? ""
                    if cursor.isEmpty { break }
                }
                return out
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private static func polyGames(_ league: League) async -> [PredEvent] {
        guard let data = try? await Predictions.getData("https://gamma-api.polymarket.com/events?tag_slug=\(league.polyTag)&closed=false&active=true&limit=200"),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return Predictions.parsePolymarket(arr, minVolume: 0)
    }
}
