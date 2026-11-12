import Foundation

/// One source's view: buy/hold/sell counts and 12-month price targets.
struct AnalystSource {
    let name: String, buy: Int, hold: Int, sell: Int
    let low: Double?, avg: Double?, high: Double?, targetAnalysts: Int
    var total: Int { buy + hold + sell }
    func pct(_ n: Int) -> Int { total == 0 ? 0 : Int((Double(n) * 100 / Double(total)).rounded()) }
}

/// Wall Street analysts' consensus for one stock, combined from every source that answers.
struct AnalystView {
    let ratings: AnalystSource?, targets: AnalystSource?, all: [AnalystSource]
    var buy: Int { ratings?.buy ?? 0 }
    var hold: Int { ratings?.hold ?? 0 }
    var sell: Int { ratings?.sell ?? 0 }
    var total: Int { ratings?.total ?? 0 }
    func pct(_ n: Int) -> Int { ratings?.pct(n) ?? 0 }
    var low: Double? { targets?.low }
    var avg: Double? { targets?.avg }
    var high: Double? { targets?.high }
}

enum Analysts {
    private static let browser = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private static var memo: [String: (Date, AnalystView?)] = [:]
    private static var crumb: String?

    /// Asks Nasdaq, Finnhub (with your key) and Yahoo Finance; uses whichever covers the most analysts. Cached 6 hours.
    static func get(_ ticker: String) async -> AnalystView? {
        let t = ticker.uppercased()
        if let m = memo[t], Date().timeIntervalSince(m.0) < 6 * 3600 { return m.1 }
        async let n = nasdaq(t)
        async let f = finnhub(t)
        async let y = yahoo(t)
        let all = [await n, await f, await y].compactMap { $0 }.filter { $0.total > 0 || $0.avg != nil }
        let v: AnalystView? = all.isEmpty ? nil : AnalystView(
            ratings: all.filter { $0.total > 0 }.max { $0.total < $1.total },
            targets: all.filter { $0.avg != nil && $0.low != nil && $0.high != nil }.max { $0.targetAnalysts < $1.targetAnalysts },
            all: all.sorted { $0.total > $1.total })
        memo[t] = (Date(), v)
        return v
    }

    private static func fetch(_ url: String, _ headers: [String: String]) async -> (Int, Data)? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u, timeoutInterval: 15)
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        guard let r = try? await URLSession.shared.data(for: req) else { return nil }
        return ((r.1 as? HTTPURLResponse)?.statusCode ?? 0, r.0)
    }

    private static func nasdaq(_ t: String) async -> AnalystSource? {
        guard let r = await fetch("https://api.nasdaq.com/api/analyst/\(t.replacingOccurrences(of: ".", with: "-"))/targetprice",
                                  ["User-Agent": browser, "Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.9",
                                   "Origin": "https://www.nasdaq.com", "Referer": "https://www.nasdaq.com/"]), r.0 < 300,
              let o = try? JSONSerialization.jsonObject(with: r.1) as? [String: Any],
              let data = o["data"] as? [String: Any], let c = data["consensusOverview"] as? [String: Any] else { return nil }
        func int(_ k: String) -> Int { (c[k] as? NSNumber)?.intValue ?? 0 }
        func num(_ k: String) -> Double? { (c[k] as? NSNumber).map(\.doubleValue).flatMap { $0 > 0 ? $0 : nil } }
        let b = int("buy"), h = int("hold"), s = int("sell")
        return AnalystSource(name: "Nasdaq", buy: b, hold: h, sell: s, low: num("lowPriceTarget"), avg: num("priceTarget"),
                             high: num("highPriceTarget"), targetAnalysts: b + h + s)
    }

    /// Finnhub's latest monthly count (free with a key; ratings only).
    private static func finnhub(_ t: String) async -> AnalystSource? {
        let key = Live.key
        guard !key.isEmpty, let sym = t.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let r = await fetch("https://finnhub.io/api/v1/stock/recommendation?symbol=\(sym)&token=\(key)", ["User-Agent": "StockTracker"]),
              r.0 < 300, let arr = try? JSONSerialization.jsonObject(with: r.1) as? [[String: Any]], let o = arr.first else { return nil }
        func int(_ k: String) -> Int { (o[k] as? NSNumber)?.intValue ?? 0 }
        return AnalystSource(name: "Finnhub", buy: int("strongBuy") + int("buy"), hold: int("hold"), sell: int("sell") + int("strongSell"),
                             low: nil, avg: nil, high: nil, targetAnalysts: 0)
    }

    /// Yahoo Finance ratings and price targets (needs a session cookie, which URLSession keeps, and a "crumb").
    private static func yahoo(_ t: String) async -> AnalystSource? {
        if crumb == nil {
            _ = await fetch("https://fc.yahoo.com", ["User-Agent": browser])
            guard let r = await fetch("https://query2.finance.yahoo.com/v1/test/getcrumb", ["User-Agent": browser]), r.0 < 300,
                  let c = String(data: r.1, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), !c.isEmpty, !c.contains("<")
            else { return nil }
            crumb = c
        }
        guard let c = crumb?.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let r = await fetch("https://query2.finance.yahoo.com/v10/finance/quoteSummary/\(t.replacingOccurrences(of: ".", with: "-"))?modules=financialData,recommendationTrend&crumb=\(c)",
                                  ["User-Agent": browser]) else { return nil }
        if r.0 == 401 || r.0 == 403 { crumb = nil; return nil }
        guard r.0 < 300, let o = try? JSONSerialization.jsonObject(with: r.1) as? [String: Any],
              let qs = o["quoteSummary"] as? [String: Any], let res = (qs["result"] as? [[String: Any]])?.first else { return nil }
        let fd = res["financialData"] as? [String: Any]
        func raw(_ k: String) -> Double? { ((fd?[k] as? [String: Any])?["raw"] as? NSNumber).map(\.doubleValue).flatMap { $0 > 0 ? $0 : nil } }
        var b = 0, h = 0, s = 0
        for p in ((res["recommendationTrend"] as? [String: Any])?["trend"] as? [[String: Any]]) ?? [] where (p["period"] as? String) == "0m" {
            func i(_ k: String) -> Int { (p[k] as? NSNumber)?.intValue ?? 0 }
            b = i("strongBuy") + i("buy"); h = i("hold"); s = i("sell") + i("strongSell")
        }
        let n = ((fd?["numberOfAnalystOpinions"] as? [String: Any])?["raw"] as? NSNumber)?.intValue ?? 0
        return AnalystSource(name: "Yahoo Finance", buy: b, hold: h, sell: s, low: raw("targetLowPrice"), avg: raw("targetMeanPrice"),
                             high: raw("targetHighPrice"), targetAnalysts: n)
    }
}
