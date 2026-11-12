import Foundation

/// Wall Street analysts' consensus for one stock: buy/hold/sell counts and 12-month price targets (from Nasdaq).
struct AnalystView {
    let buy: Int, hold: Int, sell: Int
    let low: Double?, avg: Double?, high: Double?
    var total: Int { buy + hold + sell }
    func pct(_ n: Int) -> Int { total == 0 ? 0 : Int((Double(n) * 100 / Double(total)).rounded()) }
}

enum Analysts {
    private static var memo: [String: (Date, AnalystView?)] = [:]

    /// nil when no analysts cover the stock. Cached for 6 hours.
    static func get(_ ticker: String) async -> AnalystView? {
        let t = ticker.uppercased()
        if let m = memo[t], Date().timeIntervalSince(m.0) < 6 * 3600 { return m.1 }
        guard let url = URL(string: "https://api.nasdaq.com/api/analyst/\(t.replacingOccurrences(of: ".", with: "-"))/targetprice") else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 20)
        // Nasdaq's API only answers requests that look like its own website.
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                     forHTTPHeaderField: "User-Agent")
        req.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        req.setValue("https://www.nasdaq.com", forHTTPHeaderField: "Origin")
        req.setValue("https://www.nasdaq.com/", forHTTPHeaderField: "Referer")
        guard let result = try? await URLSession.shared.data(for: req),
              (result.1 as? HTTPURLResponse)?.statusCode ?? 0 < 300 else { return nil }
        let v = parse(result.0)
        memo[t] = (Date(), v)
        return v
    }

    static func parse(_ d: Data) -> AnalystView? {
        guard let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let data = o["data"] as? [String: Any], let c = data["consensusOverview"] as? [String: Any] else { return nil }
        func int(_ k: String) -> Int { (c[k] as? NSNumber)?.intValue ?? 0 }
        func num(_ k: String) -> Double? { (c[k] as? NSNumber).map(\.doubleValue).flatMap { $0 > 0 ? $0 : nil } }
        let v = AnalystView(buy: int("buy"), hold: int("hold"), sell: int("sell"),
                            low: num("lowPriceTarget"), avg: num("priceTarget"), high: num("highPriceTarget"))
        return v.total == 0 && v.avg == nil ? nil : v
    }
}
