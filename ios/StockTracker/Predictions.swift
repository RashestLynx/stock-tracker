import Foundation

/// One possible result of an event, with the market's odds (0-1) and how far they moved in a day.
struct PredOutcome: Identifiable {
    let label: String
    let prob: Double
    let change: Double?
    var id: String { label }
}

struct PredEvent: Identifiable {
    let id: String
    let title: String
    let category: String        // "Sports", "Politics", "Economics" or "Other"
    let url: String
    let outcomes: [PredOutcome] // most likely first, at most two
    let volume24: Double        // contracts traded in the last 24 hours
    let move: Double            // biggest one-day change in odds among its outcomes, signed
}

/// Live odds from Kalshi, a federally regulated prediction market open to Illinois residents.
///
/// A contract pays $1 if something happens, so its price is the crowd's estimate of the chance:
/// 62 cents means about 62%. Kalshi's market data is public and needs no key or account.
/// Mirrors Predictions.kt in the Android app.
enum Predictions {
    private static let bases = [
        "https://external-api.kalshi.com/trade-api/v2",
        "https://api.elections.kalshi.com/trade-api/v2",
    ]
    private static let maxPages = 6
    private static var cache: [PredEvent] = []
    private static var cachedAt = Date.distantPast

    static func load(force: Bool) async throws -> [PredEvent] {
        if !force, !cache.isEmpty, Date().timeIntervalSince(cachedAt) < 120 { return cache }
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
                cache = out
                cachedAt = Date()
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
                outcomes.append(PredOutcome(label: label, prob: prob, change: prev > 0 && prev < 1 ? prob - prev : nil))
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
                title: sub.isEmpty || outcomes.count > 1 ? title : "\(title) (\(sub))",
                category: category(e["category"] as? String ?? ""),
                url: "https://kalshi.com/markets/" + series.lowercased(),
                outcomes: Array(outcomes.prefix(2)),
                volume24: volume,
                move: move))
        }
        return out
    }

    private static func category(_ raw: String) -> String {
        let c = raw.lowercased()
        if c.contains("sport") { return "Sports" }
        if c.contains("politic") || c.contains("election") { return "Politics" }
        if c.contains("econ") || c.contains("financ") { return "Economics" }
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

    private static func get(_ url: String) async throws -> [String: Any] {
        guard let u = URL(string: url) else { throw AppError("Bad URL") }
        var req = URLRequest(url: u)
        req.setValue("StockTracker-iOS/1.0 (personal app)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 40
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 429 { throw AppError("Kalshi is busy. Try again in a minute.") }
        guard (200..<300).contains(code) else { throw AppError("Kalshi returned HTTP \(code)") }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }
}
