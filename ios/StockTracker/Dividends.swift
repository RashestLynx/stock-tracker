import Foundation

/// A one-time "special" cash dividend a company announced.
struct SpecialDividend: Codable, Identifiable {
    var ticker: String
    var name: String
    var amount: Double     // dollars a share
    var price: Double      // latest close, 0 if unknown
    var declared: String   // yyyy-MM-dd, "" if not given
    var exDate: String
    var payDate: String
    var id: String { ticker + "|" + exDate }
}

/// A stock paying a high regular dividend.
struct HighYield: Codable, Identifiable {
    var ticker: String
    var name: String
    var yieldPct: Double   // yearly dividend / price, in percent
    var perYear: Double    // dollars a share each year
    var payment: Double    // latest payment, dollars a share
    var frequency: Int     // payments a year
    var price: Double
    var exDate: String
    var id: String { ticker }
}

struct SpecialDividendsResult: Codable { var priceDate: String; var items: [SpecialDividend]; var fetchedAt: Date }
struct HighYieldResult: Codable { var priceDate: String; var items: [HighYield]; var fetchedAt: Date }

/// Special dividends and high dividend yields for U.S. stocks, from Massive's (formerly Polygon.io)
/// dividends list plus one day of closing prices. Free plan: 5 requests a minute, so the high-yield
/// list (which reads every regular dividend paid in the last few months) takes a minute or two
/// and is kept for 12 hours.
enum Dividends {
    static let riskyYield = 10.0
    static let minYield = 4.0
    private static let base = "https://api.massive.com/v3/reference/dividends"
    private static var recent: [Date] = []

    static func specials(key: String, names: [String: String]) async throws -> SpecialDividendsResult {
        guard !key.isEmpty else { throw AppError("Add a free Massive key first") }
        let (priceDate, prices) = try await prices(key: key)
        let since = day(-120)
        let rows = try await pages("\(base)?dividend_type=SC&ex_dividend_date.gte=\(since)&order=desc&sort=ex_dividend_date&limit=1000",
                                   key: key, maxPages: 3)
        var seen = Set<String>()
        var list: [SpecialDividend] = []
        for o in rows {
            let t = o["ticker"] as? String ?? ""
            let amount = (o["cash_amount"] as? NSNumber)?.doubleValue ?? 0
            let ex = o["ex_dividend_date"] as? String ?? ""
            guard plain(t), amount > 0, !ex.isEmpty, usd(o), seen.insert(t + "|" + ex).inserted else { continue }
            guard let name = names.isEmpty ? Optional(t) : names[t] else { continue }
            list.append(SpecialDividend(ticker: t, name: name.capitalized, amount: amount, price: prices[t]?.close ?? 0,
                                        declared: o["declaration_date"] as? String ?? "", exDate: ex,
                                        payDate: o["pay_date"] as? String ?? ""))
        }
        return SpecialDividendsResult(priceDate: priceDate, items: list.sorted { $0.exDate > $1.exDate }, fetchedAt: Date())
    }

    /// Each company's latest regular dividend (ex-dividend day in the last ~3 months), times how many
    /// times a year it pays, divided by the latest closing price.
    static func highYield(key: String, names: [String: String], progress: @escaping (String) -> Void) async throws -> HighYieldResult {
        guard !key.isEmpty else { throw AppError("Add a free Massive key first") }
        progress("Getting today's prices…")
        let (priceDate, prices) = try await prices(key: key)
        let rows = try await pages("\(base)?dividend_type=CD&ex_dividend_date.gte=\(day(-100))&order=desc&sort=ex_dividend_date&limit=1000",
                                   key: key, maxPages: 15) { n in progress("Reading dividends (page \(n))…") }
        var seen = Set<String>()
        var list: [HighYield] = []
        for o in rows {   // newest first, so the first row for a ticker is its latest dividend
            let t = o["ticker"] as? String ?? ""
            guard seen.insert(t).inserted else { continue }
            let f = (o["frequency"] as? NSNumber)?.intValue ?? 0
            let amount = (o["cash_amount"] as? NSNumber)?.doubleValue ?? 0
            guard plain(t), [1, 2, 4, 12].contains(f), amount > 0, usd(o),
                  let p = prices[t], p.close >= 5, p.volume >= 100_000,
                  let name = names.isEmpty ? Optional(t) : names[t] else { continue }
            let y = amount * Double(f) / p.close * 100
            guard y >= minYield, y <= 30 else { continue }   // above 30% is almost always a data error
            list.append(HighYield(ticker: t, name: name.capitalized, yieldPct: y, perYear: amount * Double(f), payment: amount,
                                  frequency: f, price: p.close, exDate: o["ex_dividend_date"] as? String ?? ""))
        }
        return HighYieldResult(priceDate: priceDate, items: Array(list.sorted { $0.yieldPct > $1.yieldPct }.prefix(60)), fetchedAt: Date())
    }

    static func frequencyLabel(_ f: Int) -> String {
        switch f { case 12: return "monthly"; case 4: return "quarterly"; case 2: return "twice a year"; case 1: return "yearly"; default: return "" }
    }

    // ---------------- shared ----------------

    private static func day(_ offset: Int) -> String {
        Movers.dayFormatter.string(from: Calendar(identifier: .gregorian).date(byAdding: .day, value: offset, to: Date()) ?? Date())
    }

    private static func plain(_ t: String) -> Bool {
        (1...5).contains(t.count) && t.allSatisfy { $0.isUppercase && $0.isLetter }
    }

    private static func usd(_ o: [String: Any]) -> Bool {
        let c = o["currency"] as? String ?? ""
        return c.isEmpty || c == "USD"
    }

    private static func prices(key: String) async throws -> (String, [String: (close: Double, volume: Double)]) {
        Movers.resetCalls()
        try await waitTurn()
        let d = try await Movers.findDay(from: Date(), key: key, maxTries: 5)
        for _ in 0..<max(0, Movers.callCount - 1) { recent.append(Date()) }
        return (d?.date ?? "", d?.data ?? [:])
    }

    /// Follows next_url up to maxPages pages.
    private static func pages(_ first: String, key: String, maxPages: Int,
                              onPage: (Int) -> Void = { _ in }) async throws -> [[String: Any]] {
        var out: [[String: Any]] = []
        var url: String? = first + "&apiKey=" + key
        var n = 0
        while let u = url, n < maxPages {
            n += 1
            onPage(n)
            let data = try await get(u)
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            out += json?["results"] as? [[String: Any]] ?? []
            if let next = json?["next_url"] as? String, next.hasPrefix("https://") { url = next + "&apiKey=" + key } else { url = nil }
        }
        return out
    }

    /// Keeps to the free plan's 5 requests a minute.
    private static func waitTurn() async throws {
        let now = Date()
        recent.removeAll { now.timeIntervalSince($0) > 61 }
        if recent.count >= 4, let first = recent.first {
            let wait = max(0, 61 - now.timeIntervalSince(first))
            try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
        }
        recent.append(Date())
    }

    private static func get(_ s: String, retried: Bool = false) async throws -> Data {
        try await waitTurn()
        guard let url = URL(string: s) else { throw AppError("Bad dividend address") }
        var req = URLRequest(url: url)
        req.timeoutInterval = 60
        let (data, resp) = try await URLSession.shared.data(for: req)
        switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200..<300: return data
        case 401: throw AppError("Massive key not accepted. Check it in Settings.")
        case 429:
            guard !retried else { throw AppError("Massive is busy (too many requests). Try again in a minute.") }
            try await Task.sleep(nanoseconds: 20_000_000_000)
            return try await get(s, retried: true)
        case let code: throw AppError("Dividend data returned HTTP \(code)")
        }
    }
}
