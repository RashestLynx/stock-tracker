import Foundation

/// One possible result of an event, with the market's odds (0-1) and how far they moved in a day.
struct PredOutcome: Identifiable {
    let label: String
    let prob: Double
    let change: Double?
    /// Identifies the exact market so a logged pick can be followed until it settles:
    /// "k:<Kalshi market ticker>" or "p:<Polymarket market id>:<outcome index>".
    var ref = ""
    var id: String { label }
}

struct PredEvent: Identifiable {
    let id: String
    let source: String          // "Kalshi" or "Polymarket"
    let title: String
    let category: String        // "Sports", "Politics", "Economics" or "Other"
    let url: String
    let outcomes: [PredOutcome] // most likely first, at most two
    let volume24: Double        // traded in the last 24 hours (Kalshi: contracts, Polymarket: dollars)
    let move: Double            // biggest one-day change in odds among its outcomes, signed
    var count = 2               // how many outcomes the event has in total (only the top two are kept)
}

/// Live odds from Kalshi and Polymarket, both open to Illinois residents.
///
/// A contract pays $1 if something happens, so its price is the crowd's estimate of the chance:
/// 62 cents means about 62%. Both publish their market data openly, with no key or account.
/// FanDuel Predicts and DraftKings Predictions don't publish their odds, so they aren't included.
/// Mirrors Predictions.kt in the Android app.
enum Predictions {
    private static let bases = [
        "https://external-api.kalshi.com/trade-api/v2",
        "https://api.elections.kalshi.com/trade-api/v2",
    ]
    private static let maxPages = 6
    private static var cache: [PredEvent] = []
    private static var cachedAt = Date.distantPast

    /// Shows whichever sources load; fails only if both fail.
    static func load(force: Bool) async throws -> [PredEvent] {
        if !force, !cache.isEmpty, Date().timeIntervalSince(cachedAt) < 120 { return cache }
        var out: [PredEvent] = []
        var firstError: Error?
        do { out += try await loadKalshi() } catch { firstError = error }
        do { out += try await loadPolymarket() } catch { if firstError == nil { firstError = error } }
        if out.isEmpty { throw firstError ?? AppError("No open markets right now") }
        cache = out
        cachedAt = Date()
        return out
    }

    private static func loadPolymarket() async throws -> [PredEvent] {
        var out: [PredEvent] = []
        for page in 0..<3 {
            let data = try await getData("https://gamma-api.polymarket.com/events?closed=false&active=true&order=volume24hr&ascending=false&limit=100&offset=\(page * 100)")
            let arr = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? []
            out += parsePolymarket(arr)
            if arr.count < 100 { break }
        }
        return out
    }

    /// Polymarket events: one market per outcome ("Will X win?"), or one market with named outcomes.
    static func parsePolymarket(_ events: [[String: Any]]) -> [PredEvent] {
        var out: [PredEvent] = []
        for e in events {
            let markets = e["markets"] as? [[String: Any]] ?? []
            var outcomes: [PredOutcome] = []
            for m in markets {
                if m["closed"] as? Bool == true || m["active"] as? Bool == false { continue }
                let names = strings(m["outcomes"])
                let prices = strings(m["outcomePrices"]).map { Double($0) ?? .nan }
                guard names.count == prices.count, !names.isEmpty else { continue }
                let change = (m["oneDayPriceChange"] as? NSNumber)?.doubleValue
                if let yes = names.firstIndex(where: { $0.lowercased() == "yes" }) {
                    let p = prices[yes]
                    guard p.isFinite, p > 0, p < 1 else { continue }
                    var label = m["groupItemTitle"] as? String ?? ""
                    if label.isEmpty { label = markets.count == 1 ? "Yes" : (m["question"] as? String ?? "Yes") }
                    outcomes.append(PredOutcome(label: label, prob: p, change: change, ref: "p:\(polyId(m)):\(yes)"))
                } else {
                    // Game events also hold point-spread and over/under markets; keep only who wins.
                    let kind = (m["sportsMarketType"] as? String ?? "").lowercased()
                    let q = (m["question"] as? String ?? "").lowercased()
                    if !kind.isEmpty && kind != "moneyline" { continue }
                    if kind.isEmpty && (q.replacingOccurrences(of: "49ers", with: "").contains(where: \.isNumber)
                        || q.contains("o/u") || q.contains("spread") || q.contains("total")) { continue }
                    // e.g. a game with outcomes ["Bears", "Lions"]; the daily change is for the first one.
                    for (j, n) in names.enumerated() {
                        let p = prices[j]
                        guard p.isFinite, p > 0, p < 1 else { continue }
                        outcomes.append(PredOutcome(label: n, prob: p, change: change.map { j == 0 ? $0 : -$0 }, ref: "p:\(polyId(m)):\(j)"))
                    }
                }
            }
            let volume = (e["volume24hr"] as? NSNumber)?.doubleValue ?? 0
            if outcomes.isEmpty || volume < 100 { continue }
            outcomes.sort { $0.prob > $1.prob }
            let top = outcomes[0]
            if top.prob >= 0.98 || (outcomes.count == 1 && top.prob <= 0.02) { continue }
            guard let title = e["title"] as? String, !title.isEmpty else { continue }
            var labels = e["category"] as? String ?? ""
            for t in e["tags"] as? [Any] ?? [] {
                if let d = t as? [String: Any] { labels += " " + (d["label"] as? String ?? "") }
                else if let str = t as? String { labels += " " + str }
            }
            let slug = e["slug"] as? String ?? ""
            out.append(PredEvent(
                id: "pm-" + (slug.isEmpty ? UUID().uuidString : slug),
                source: "Polymarket",
                title: title,
                category: category(labels),
                url: "https://polymarket.com/event/" + slug,
                outcomes: Array(outcomes.prefix(2)),
                volume24: volume,
                move: outcomes.compactMap(\.change).max { abs($0) < abs($1) } ?? 0,
                count: outcomes.count))
        }
        return out
    }

    private static func polyId(_ m: [String: Any]) -> String {
        if let s = m["id"] as? String { return s }
        return (m["id"] as? NSNumber)?.stringValue ?? ""
    }

    /// Current chance (0-1) for one Kalshi market, the same way `parse` works it out.
    static func kalshiProb(_ m: [String: Any]) -> Double? {
        let bid = price(m, "yes_bid"), ask = price(m, "yes_ask"), last = price(m, "last_price")
        if bid > 0 && ask > 0 && ask <= 1 && ask - bid <= 0.10 { return (bid + ask) / 2 }
        if last > 0 && last < 1 { return last }
        return nil
    }

    /// Polymarket sends some lists as JSON text ("[\"Yes\",\"No\"]") and some as real arrays.
    private static func strings(_ v: Any?) -> [String] {
        if let a = v as? [Any] { return a.map { "\($0)" } }
        if let s = v as? String, let d = s.data(using: .utf8),
           let a = try? JSONSerialization.jsonObject(with: d) as? [Any] {
            return a.map { "\($0)" }
        }
        return []
    }

    private static func loadKalshi() async throws -> [PredEvent] {
        var lastError: Error = AppError("Couldn't load prediction markets")
        for base in bases {
            do {
                var out: [PredEvent] = []
                var cursor = ""
                for _ in 0..<maxPages {
                    var url = "\(base)/events?status=open&with_nested_markets=true&limit=200"
                    if !cursor.isEmpty { url += "&cursor=\(cursor)" }
                    let json = try await get(url)
                    out += parse(json)
                    cursor = json["cursor"] as? String ?? ""
                    if cursor.isEmpty { break }
                }
                if out.isEmpty { throw AppError("No open markets right now") }
                return out
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    /// Turns one page of Kalshi events into rows, skipping dead or already-decided markets.
    static func parse(_ page: [String: Any]) -> [PredEvent] {
        var out: [PredEvent] = []
        for e in page["events"] as? [[String: Any]] ?? [] {
            var outcomes: [PredOutcome] = []
            var volume = 0.0
            for m in e["markets"] as? [[String: Any]] ?? [] {
                let type = m["market_type"] as? String ?? ""
                if !type.isEmpty && type != "binary" { continue }
                let status = m["status"] as? String ?? ""
                if !status.isEmpty && status != "active" && status != "open" { continue }
                let bid = price(m, "yes_bid")
                let ask = price(m, "yes_ask")
                let last = price(m, "last_price")
                // The middle of the buy and sell offers when they're close together; otherwise the last trade.
                let prob: Double
                if bid > 0 && ask > 0 && ask <= 1 && ask - bid <= 0.10 {
                    prob = (bid + ask) / 2
                } else if last > 0 && last < 1 {
                    prob = last
                } else {
                    continue
                }
                let prev = price(m, "previous_price")
                volume += count(m, "volume_24h")
                var label = m["yes_sub_title"] as? String ?? ""
                if label.isEmpty { label = m["subtitle"] as? String ?? "" }
                if label.isEmpty { label = "Yes" }
                if ["tie", "draw"].contains(label.lowercased()) { continue }   // rare; would hide the game
                outcomes.append(PredOutcome(label: label, prob: prob, change: prev > 0 && prev < 1 ? prob - prev : nil,
                                            ref: "k:" + (m["ticker"] as? String ?? "")))
            }
            if outcomes.isEmpty || volume < 100 { continue }
            outcomes.sort { $0.prob > $1.prob }
            let top = outcomes[0]
            // Nothing left to learn from a market that's all but settled.
            if top.prob >= 0.98 || (outcomes.count == 1 && top.prob <= 0.02) { continue }
            let move = outcomes.compactMap(\.change).max { abs($0) < abs($1) } ?? 0
            let eventTicker = e["event_ticker"] as? String ?? UUID().uuidString
            var series = e["series_ticker"] as? String ?? ""
            if series.isEmpty { series = eventTicker.components(separatedBy: "-").first ?? eventTicker }
            guard let title = e["title"] as? String, !title.isEmpty else { continue }
            let sub = e["sub_title"] as? String ?? ""
            out.append(PredEvent(
                id: eventTicker,
                source: "Kalshi",
                title: sub.isEmpty || outcomes.count > 1 ? title : "\(title) (\(sub))",
                category: category(e["category"] as? String ?? ""),
                url: "https://kalshi.com/markets/" + series.lowercased(),
                outcomes: Array(outcomes.prefix(2)),
                volume24: volume,
                move: move,
                count: outcomes.count))
        }
        return out
    }

    private static func category(_ raw: String) -> String {
        let c = raw.lowercased()
        if c.contains("sport") { return "Sports" }
        if c.contains("politic") || c.contains("election") { return "Politics" }
        if c.contains("econ") || c.contains("financ") || (c + " ").contains("fed ") { return "Economics" }
        return "Other"
    }

    /// Prices come as dollar strings ("0.5600"); older responses used whole cents. Returns 0-1, or 0 if missing.
    private static func price(_ m: [String: Any], _ name: String) -> Double {
        if let s = m["\(name)_dollars"] as? String, let v = Double(s) { return v }
        if let n = m["\(name)_dollars"] as? NSNumber { return n.doubleValue }
        if let n = m[name] as? NSNumber { return n.doubleValue / 100 }
        return 0
    }

    private static func count(_ m: [String: Any], _ name: String) -> Double {
        if let s = m["\(name)_fp"] as? String, let v = Double(s) { return v }
        if let n = m["\(name)_fp"] as? NSNumber { return n.doubleValue }
        return (m[name] as? NSNumber)?.doubleValue ?? 0
    }

    static func get(_ url: String) async throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: try await getData(url)) as? [String: Any] ?? [:]
    }

    static func getData(_ url: String) async throws -> Data {
        guard let u = URL(string: url) else { throw AppError("Bad URL") }
        var req = URLRequest(url: u)
        req.setValue("StockTracker-iOS/1.0 (personal app)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 40
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let site = url.contains("polymarket") ? "Polymarket" : "Kalshi"
        if code == 429 { throw AppError("\(site) is busy. Try again in a minute.") }
        guard (200..<300).contains(code) else { throw AppError("\(site) returned HTTP \(code)") }
        return data
    }
}
