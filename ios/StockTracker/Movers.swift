import Foundation

struct Mover: Codable, Identifiable {
    var ticker: String
    var name: String
    var pct: Double
    var price: Double
    var id: String { ticker }
}

struct MoversResult: Codable {
    var fromDate: String
    var toDate: String
    var marketGainers: [Mover]
    var marketLosers: [Mover]
    var trackedGainers: [Mover]
    var trackedLosers: [Mover]
    var fetchedAt: Date
}

/// Best and worst performing U.S. stocks over the past week.
///
/// Uses Massive's (formerly Polygon.io) free "grouped daily" data: one request returns the
/// closing price of every U.S. stock for a day, so two requests (latest trading day and one
/// week earlier) cover the whole market. Free plan: 5 requests/minute, end-of-day.
enum Movers {
    static let signupURL = "https://massive.com/pricing"
    private static var calls = 0

    static func load(key: String, names: [String: String], tracked: Set<String>) async throws -> MoversResult {
        guard !key.isEmpty else { throw AppError("Add a free Massive key first") }
        calls = 0
        let cal = Calendar(identifier: .gregorian)

        // Latest trading day with data (the free plan is end-of-day, so today may not be ready).
        guard let latest = try await findDay(from: Date(), key: key, maxTries: 7) else {
            throw AppError("No recent market data available")
        }
        let weekBefore = cal.date(byAdding: .day, value: -7, to: dayFormatter.date(from: latest.date) ?? Date()) ?? Date()
        guard let earlier = try await findDay(from: weekBefore, key: key, maxTries: 4) else {
            throw AppError("No market data for last week")
        }

        var market: [Mover] = []
        var mine: [Mover] = []
        for (ticker, now) in latest.data {
            // Only real companies from the SEC list; skip warrants, units and penny stocks.
            guard let before = earlier.data[ticker], before.close > 0, let name = names[ticker],
                  ticker.count <= 5, ticker.allSatisfy({ $0.isUppercase && $0.isLetter }) else { continue }
            let m = Mover(ticker: ticker, name: name.capitalized, pct: (now.close - before.close) / before.close * 100,
                          price: now.close)
            if now.close >= 5 && now.volume >= 300_000 { market.append(m) }
            if tracked.contains(ticker) && now.close >= 1 { mine.append(m) }
        }
        return MoversResult(
            fromDate: earlier.date, toDate: latest.date,
            marketGainers: Array(market.sorted { $0.pct > $1.pct }.prefix(10)),
            marketLosers: Array(market.sorted { $0.pct < $1.pct }.prefix(10)),
            trackedGainers: Array(mine.sorted { $0.pct > $1.pct }.prefix(10)),
            trackedLosers: Array(mine.sorted { $0.pct < $1.pct }.prefix(10)),
            fetchedAt: Date())
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "America/New_York")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private struct Day { let date: String; let data: [String: (close: Double, volume: Double)] }

    /// Walks back from a date to the first weekday that has data.
    private static func findDay(from start: Date, key: String, maxTries: Int) async throws -> Day? {
        let cal = Calendar(identifier: .gregorian)
        var day = start
        var tries = 0
        while tries < maxTries {
            let wd = cal.component(.weekday, from: day)
            if wd != 1 && wd != 7 {
                tries += 1
                let date = dayFormatter.string(from: day)
                let data = try await grouped(date: date, key: key)
                if !data.isEmpty { return Day(date: date, data: data) }
            }
            day = cal.date(byAdding: .day, value: -1, to: day) ?? day
        }
        return nil
    }

    private static func grouped(date: String, key: String) async throws -> [String: (close: Double, volume: Double)] {
        // Free plan allows 5 requests a minute.
        if calls >= 4 { try await Task.sleep(nanoseconds: 13_000_000_000) }
        calls += 1
        guard let url = URL(string: "https://api.massive.com/v2/aggs/grouped/locale/us/market/stocks/\(date)?adjusted=true&apiKey=\(key)") else { return [:] }
        var req = URLRequest(url: url)
        req.timeoutInterval = 60
        let (data, resp) = try await URLSession.shared.data(for: req)
        switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200..<300: break
        case 401: throw AppError("Massive key not accepted. Check it in Settings.")
        case 403: return [:]   // e.g. today's data not available on the free plan yet
        case 429:
            try await Task.sleep(nanoseconds: 15_000_000_000)
            return try await grouped(date: date, key: key)
        case let code: throw AppError("Market data returned HTTP \(code)")
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let results = json?["results"] as? [[String: Any]] ?? []
        var map: [String: (close: Double, volume: Double)] = [:]
        for r in results {
            guard let t = r["T"] as? String else { continue }
            map[t] = ((r["c"] as? NSNumber)?.doubleValue ?? 0, (r["v"] as? NSNumber)?.doubleValue ?? 0)
        }
        return map
    }
}
