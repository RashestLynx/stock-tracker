import Foundation

struct Pt: Identifiable, Codable { let t: Date; let p: Double; var id: Date { t } }

enum ChartRange: String, CaseIterable, Identifiable { case d1 = "1D", w1 = "1W", m1 = "1M", m3 = "3M", y1 = "1Y", y5 = "5Y"; var id: String { rawValue } }

/// Price history for charts.
///  - 1W to 5Y: Massive aggregates (free plan, end-of-day data)
///  - 1D: built live from Finnhub quotes while the app is open (free plans don't include today's intraday history)
enum Charts {
    private static var cache: [String: (Date, [Pt])] = [:]

    static func history(_ ticker: String, _ range: ChartRange, key: String) async throws -> [Pt] {
        if range == .d1 { return LiveTicks.today(ticker) }
        let k = "\(ticker)|\(range.rawValue)"
        if let c = cache[k], Date().timeIntervalSince(c.0) < 1800 { return c.1 }
        guard !key.isEmpty else { throw AppError("Add a Massive key for longer charts") }
        let cal = Calendar(identifier: .gregorian)
        let now = Date()
        let (from, span): (Date, String) = {
            switch range {
            case .w1: return (cal.date(byAdding: .day, value: -7, to: now)!, "hour")
            case .m1: return (cal.date(byAdding: .month, value: -1, to: now)!, "day")
            case .m3: return (cal.date(byAdding: .month, value: -3, to: now)!, "day")
            case .y1: return (cal.date(byAdding: .year, value: -1, to: now)!, "day")
            default: return (cal.date(byAdding: .year, value: -5, to: now)!, "week")
            }
        }()
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"
        guard let url = URL(string: "https://api.massive.com/v2/aggs/ticker/\(ticker.uppercased())/range/1/\(span)/\(f.string(from: from))/\(f.string(from: now))?adjusted=true&sort=asc&limit=5000&apiKey=\(key)") else { return [] }
        let (data, resp) = try await URLSession.shared.data(from: url)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 429 { throw AppError("Chart data busy, try again in a minute") }
        guard (200..<300).contains(code) else { throw AppError("Chart data returned HTTP \(code)") }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        var pts = (json?["results"] as? [[String: Any]] ?? []).compactMap { o -> Pt? in
            guard let t = (o["t"] as? NSNumber)?.doubleValue, let c = (o["c"] as? NSNumber)?.doubleValue else { return nil }
            return Pt(t: Date(timeIntervalSince1970: t / 1000), p: c)
        }
        if let last = LiveTicks.today(ticker).last, last.t > (pts.last?.t ?? .distantPast) { pts.append(last) }
        cache[k] = (Date(), pts)
        return pts
    }
}

/// Today's live prices, saved as they come in so the 1D chart keeps building while the app runs.
enum LiveTicks {
    private struct Day: Codable { var d: String; var p: [Pt] }
    private static func key(_ t: String) -> String { "ticks_" + t.uppercased() }
    private static var today: String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: Date())
    }

    static func today(_ ticker: String) -> [Pt] {
        guard let data = UserDefaults.standard.data(forKey: key(ticker)),
              let day = try? JSONDecoder().decode(Day.self, from: data), day.d == today else { return [] }
        return day.p
    }

    /// Record a quote. Starts the day with the opening price at the market open (9:30 a.m. New York).
    static func add(_ ticker: String, _ q: LiveQuote) {
        var pts = today(ticker)
        let now = Date()
        if pts.isEmpty && q.open > 0 {
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = TimeZone(identifier: "America/New_York")!
            if let open = cal.date(bySettingHour: 9, minute: 30, second: 0, of: now), open < now { pts = [Pt(t: open, p: q.open)] }
        }
        if let last = pts.last, now.timeIntervalSince(last.t) < 10 { return }
        pts.append(Pt(t: now, p: q.price))
        if let data = try? JSONEncoder().encode(Day(d: today, p: Array(pts.suffix(2000)))) {
            UserDefaults.standard.set(data, forKey: key(ticker))
        }
    }
}
