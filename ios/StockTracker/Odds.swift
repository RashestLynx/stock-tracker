import Foundation

/// Chance (0-1) that the home team wins, from each source that has the game.
struct OddsGame: Identifiable {
    let id: String
    let away: String          // "Commanders"
    let home: String          // "Giants"
    let kickoff: String       // "Thu 7:15 PM"
    let books: Double?        // sportsbooks, with their built-in cut removed
    let kalshi: Double?
    let polymarket: Double?
    let model: Double         // our rating model
    let kalshiURL: String
    let polymarketURL: String
    /// The matched markets, so a pick can be logged.
    var kalshiPick: GameMarket?
    var polymarketPick: GameMarket?

    /// Biggest gap in points between a prediction market and the sportsbooks (or the other market).
    var gap: Double {
        guard let ref = books else {
            if let k = kalshi, let p = polymarket { return abs(k - p) * 100 }
            return 0
        }
        return max(kalshi.map { abs($0 - ref) } ?? 0, polymarket.map { abs($0 - ref) } ?? 0) * 100
    }
}

struct GameMarket {
    let event: PredEvent
    let home: PredOutcome
    let away: PredOutcome
}

struct OddsResult {
    let games: [OddsGame]
    let modelRight: Double    // share of winners the model picked, since 2023
    let booksRight: Double    // share the sportsbook favorite won, same games
    let tested: Int
}

/// NFL odds compared: sportsbooks vs Kalshi vs Polymarket vs a simple rating model. Mirrors Odds.kt.
///
/// Game results and sportsbook lines come from the free, open nflverse data on GitHub. The model
/// is a standard "Elo" rating: wins against strong teams and big margins raise a rating more,
/// home field is worth a little, and ratings drift back toward average between seasons.
enum Odds {
    private static let gamesURL = "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
    private static let k = 20.0
    private static let homeEdge = 30.0
    private static let revert = 1.0 / 3

    /// Abbreviation -> (city, nickname). Old codes are mapped to the team's current one.
    static let teams: [String: (city: String, nick: String)] = [
        "ARI": ("Arizona", "Cardinals"), "ATL": ("Atlanta", "Falcons"), "BAL": ("Baltimore", "Ravens"),
        "BUF": ("Buffalo", "Bills"), "CAR": ("Carolina", "Panthers"), "CHI": ("Chicago", "Bears"),
        "CIN": ("Cincinnati", "Bengals"), "CLE": ("Cleveland", "Browns"), "DAL": ("Dallas", "Cowboys"),
        "DEN": ("Denver", "Broncos"), "DET": ("Detroit", "Lions"), "GB": ("Green Bay", "Packers"),
        "HOU": ("Houston", "Texans"), "IND": ("Indianapolis", "Colts"), "JAX": ("Jacksonville", "Jaguars"),
        "KC": ("Kansas City", "Chiefs"), "LA": ("Los Angeles", "Rams"), "LAC": ("Los Angeles", "Chargers"),
        "LV": ("Las Vegas", "Raiders"), "MIA": ("Miami", "Dolphins"), "MIN": ("Minnesota", "Vikings"),
        "NE": ("New England", "Patriots"), "NO": ("New Orleans", "Saints"), "NYG": ("New York", "Giants"),
        "NYJ": ("New York", "Jets"), "PHI": ("Philadelphia", "Eagles"), "PIT": ("Pittsburgh", "Steelers"),
        "SEA": ("Seattle", "Seahawks"), "SF": ("San Francisco", "49ers"), "TB": ("Tampa Bay", "Buccaneers"),
        "TEN": ("Tennessee", "Titans"), "WAS": ("Washington", "Commanders"),
    ]
    private static let old = ["OAK": "LV", "SD": "LAC", "STL": "LA"]

    private static var cache: OddsResult?
    private static var cachedAt = Date.distantPast

    static func load(force: Bool) async throws -> OddsResult {
        if !force, let c = cache, Date().timeIntervalSince(cachedAt) < 120 { return c }
        let csv = try await schedule(force: force)
        let all = (try? await Predictions.load(force: false)) ?? []
        let kalshi = await kalshiGames() + all.filter { $0.source == "Kalshi" }
        let poly = await polymarketGames() + all.filter { $0.source == "Polymarket" }
        let r = try compute(csv: csv, kalshi: kalshi, poly: poly, now: Date())
        cache = r
        cachedAt = Date()
        return r
    }

    static func compute(csv: String, kalshi: [PredEvent], poly: [PredEvent], now: Date) throws -> OddsResult {
        let lines = csv.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.isEmpty }
        guard lines.count > 1 else { throw AppError("No NFL schedule data") }
        let head = splitCSV(lines[0])
        func col(_ n: String) throws -> Int {
            guard let i = head.firstIndex(of: n) else { throw AppError("NFL data is missing '\(n)'") }
            return i
        }
        let cSeason = try col("season"), cDay = try col("gameday"), cTime = try col("gametime")
        let cAway = try col("away_team"), cHome = try col("home_team")
        let cAs = try col("away_score"), cHs = try col("home_score"), cLoc = try col("location")
        let cAml = try col("away_moneyline"), cHml = try col("home_moneyline")
        let need = max(cHml, cLoc, cAml, cHs)

        let rows = lines.dropFirst().map(splitCSV).filter { $0.count > need }
            .sorted { ($0[cDay], $0[cTime]) < ($1[cDay], $1[cTime]) }

        let et = TimeZone(identifier: "America/New_York")!
        let dayFmt = DateFormatter()
        dayFmt.locale = Locale(identifier: "en_US_POSIX")
        dayFmt.timeZone = et
        dayFmt.dateFormat = "yyyy-MM-dd"
        let today = dayFmt.string(from: now)
        let horizon = dayFmt.string(from: now.addingTimeInterval(8 * 86_400))

        var elo: [String: Double] = [:]
        var season = -1
        var tested = 0, modelHits = 0, bookHits = 0
        var upcoming: [OddsGame] = []
        for r in rows {
            guard let s = Int(r[cSeason]) else { continue }
            if s != season {
                for key in elo.keys { elo[key] = elo[key]! * (1 - revert) + 1505 * revert }
                season = s
            }
            let a = old[r[cAway]] ?? r[cAway]
            let h = old[r[cHome]] ?? r[cHome]
            let ea = elo[a] ?? 1505, eh = elo[h] ?? 1505
            let diff = eh + (r[cLoc] == "Neutral" ? 0 : homeEdge) - ea
            let p = 1 / (1 + pow(10, -diff / 400))
            let book = noVig(home: r[cHml], away: r[cAml])

            guard let hs = Int(r[cHs]), let aw = Int(r[cAs]) else {
                let day = r[cDay]
                if day >= today && day <= horizon {
                    let km = match(kalshi, away: a, home: h)
                    let pm = match(poly, away: a, home: h)
                    upcoming.append(OddsGame(
                        id: "\(day)-\(a)-\(h)", away: teams[a]?.nick ?? a, home: teams[h]?.nick ?? h,
                        kickoff: kickoff(day, r[cTime], et), books: book,
                        kalshi: km?.prob, polymarket: pm?.prob, model: p,
                        kalshiURL: km?.market.event.url ?? "", polymarketURL: pm?.market.event.url ?? "",
                        kalshiPick: km?.market, polymarketPick: pm?.market))
                }
                continue
            }
            let out: Double = hs > aw ? 1 : (hs < aw ? 0 : 0.5)
            if s >= 2023, let b = book, out != 0.5 {
                tested += 1
                if (p > 0.5) == (out == 1) { modelHits += 1 }
                if (b > 0.5) == (out == 1) { bookHits += 1 }
            }
            let margin = Double(abs(hs - aw))
            let winnerDiff = out == 1 ? diff : -diff
            let mult = margin > 0 ? log(margin + 1) * 2.2 / (winnerDiff * 0.001 + 2.2) : 1
            let delta = k * mult * (out - p)
            elo[h] = eh + delta
            elo[a] = ea - delta
        }
        return OddsResult(
            games: upcoming.sorted { $0.gap > $1.gap },
            modelRight: tested > 0 ? Double(modelHits) / Double(tested) : 0,
            booksRight: tested > 0 ? Double(bookHits) / Double(tested) : 0,
            tested: tested)
    }

    /// Home team's chance from the two moneylines, with the sportsbook's cut taken out.
    private static func noVig(home: String, away: String) -> Double? {
        guard let hm = Double(home), let am = Double(away) else { return nil }
        let h = implied(hm), a = implied(am)
        return h / (h + a)
    }

    private static func implied(_ ml: Double) -> Double { ml > 0 ? 100 / (ml + 100) : -ml / (-ml + 100) }

    /// Finds this game in a market's list and returns the home team's chance (normalized) and a link.
    static func market(_ events: [PredEvent], away: String, home: String) -> (Double?, String) {
        guard let m = match(events, away: away, home: home) else { return (nil, "") }
        return (m.prob, m.market.event.url)
    }

    /// Like `market`, but also returns the matched event with its home and away outcomes.
    static func match(_ events: [PredEvent], away: String, home: String) -> (prob: Double, market: GameMarket)? {
        for e in events where e.count == 2 && e.outcomes.count == 2 {
            let o0 = e.outcomes[0], o1 = e.outcomes[1]
            guard let t0 = teamFor(o0.label, away: away, home: home),
                  let t1 = teamFor(o1.label, away: away, home: home), t0 != t1 else { continue }
            let homeO = t0 == home ? o0 : o1
            let awayO = t0 == home ? o1 : o0
            if homeO.prob + awayO.prob <= 0 { continue }
            return (homeO.prob / (homeO.prob + awayO.prob), GameMarket(event: e, home: homeO, away: awayO))
        }
        return nil
    }

    /// Which of the two teams an outcome label like "Detroit", "Lions" or "New York G" means.
    static func teamFor(_ label: String, away: String, home: String) -> String? {
        let cleaned = label.lowercased().map { $0.isLetter || $0.isNumber ? $0 : " " }
        let l = " " + String(cleaned).split(separator: " ").joined(separator: " ") + " "
        if l.replacingOccurrences(of: "49ers", with: "").contains(where: \.isNumber) { return nil } // spreads and totals
        let hits = [away, home].filter { code in
            guard let t = teams[code] else { return false }
            let c = t.city.lowercased(), n = t.nick.lowercased()
            let other = code == away ? home : away
            let sharedCity = teams[other]?.city == t.city
            return l.contains(" \(n) ") || l.trimmingCharacters(in: .whitespaces) == code.lowercased()
                || (!sharedCity && l.contains(" \(c) ")) || l.contains(" \(c) \(n.prefix(1)) ")
        }
        return hits.count == 1 ? hits[0] : nil
    }

    private static func kickoff(_ day: String, _ time: String, _ et: TimeZone) -> String {
        let parse = DateFormatter()
        parse.locale = Locale(identifier: "en_US_POSIX")
        parse.timeZone = et
        parse.dateFormat = "yyyy-MM-dd HH:mm"
        guard let d = parse.date(from: "\(day) \(time)") else { return day }
        let out = DateFormatter()
        out.locale = Locale(identifier: "en_US_POSIX")
        out.dateFormat = "EEE h:mm a"
        return out.string(from: d)
    }

    /// Splits one CSV line, keeping commas inside quotes.
    private static func splitCSV(_ line: String) -> [String] {
        var out: [String] = []
        var cur = ""
        var quoted = false
        var chars = Array(line)[...]
        while let ch = chars.popFirst() {
            if ch == "\"" {
                if quoted, chars.first == "\"" { cur.append("\""); chars = chars.dropFirst() } else { quoted.toggle() }
            } else if ch == "," && !quoted {
                out.append(cur)
                cur = ""
            } else {
                cur.append(ch)
            }
        }
        out.append(cur)
        return out
    }

    // ---------------- data ----------------
    private static var cacheFile: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("nfl_games.csv")
    }

    private static func schedule(force: Bool) async throws -> String {
        let maxAge: TimeInterval = force ? 1800 : 6 * 3600
        if let attrs = try? FileManager.default.attributesOfItem(atPath: cacheFile.path),
           let modified = attrs[.modificationDate] as? Date, Date().timeIntervalSince(modified) < maxAge,
           let text = try? String(contentsOf: cacheFile, encoding: .utf8) {
            return text
        }
        guard let u = URL(string: gamesURL) else { throw AppError("Bad URL") }
        let (data, resp) = try await URLSession.shared.data(from: u)
        guard ((resp as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2, let text = String(data: data, encoding: .utf8) else {
            throw AppError("Couldn't load the NFL schedule")
        }
        try? text.write(to: cacheFile, atomically: true, encoding: .utf8)
        return text
    }

    /// Game markets fetched directly, so they're never cut off by the general list.
    private static func kalshiGames() async -> [PredEvent] {
        for base in ["https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2"] {
            if let json = try? await Predictions.get("\(base)/events?series_ticker=KXNFLGAME&status=open&with_nested_markets=true&limit=200") {
                return Predictions.parse(json)
            }
        }
        return []
    }

    private static func polymarketGames() async -> [PredEvent] {
        guard let data = try? await Predictions.getData("https://gamma-api.polymarket.com/events?tag_slug=nfl&closed=false&active=true&limit=100"),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return Predictions.parsePolymarket(arr)
    }
}
