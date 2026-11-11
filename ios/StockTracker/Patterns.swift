import Foundation

/// One trading day.
struct Bar { let t: Double; let o: Double; let h: Double; let l: Double; let c: Double; let v: Double }

/// mood: 1 = usually read as good for the price, -1 = bad, 0 = neutral.
struct PatternHit: Identifiable {
    let name: String
    let mood: Int
    let what: String
    var history = ""
    var id: String { name }
}

struct PatternReport {
    let hits: [PatternHit]
    let support: Double?
    let resistance: Double?
}

/// Chart-pattern recognition on daily prices (up to 2 years from Massive). For each signal found now, it checks every
/// earlier time it appeared in this stock and what the price did over the next 20 trading days. Mirrors Patterns.kt.
enum Patterns {
    private static let ahead = 20
    private static var cache: [String: (Date, PatternReport)] = [:]

    static func load(_ ticker: String, key: String) async throws -> PatternReport {
        let t = ticker.uppercased()
        if let c = cache[t], Date().timeIntervalSince(c.0) < 1800 { return c.1 }
        guard !key.isEmpty else { throw AppError("Add a Massive key in Settings to see patterns") }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        let from = Date().addingTimeInterval(-729 * 86_400)
        guard let url = URL(string: "https://api.massive.com/v2/aggs/ticker/\(t)/range/1/day/\(f.string(from: from))/\(f.string(from: Date()))?adjusted=true&sort=asc&limit=5000&apiKey=\(key)") else {
            throw AppError("Bad ticker")
        }
        let (data, resp) = try await URLSession.shared.data(from: url)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 429 { throw AppError("Price data busy, try again in a minute") }
        guard code / 100 == 2 else { throw AppError("Price data returned HTTP \(code)") }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let bars: [Bar] = (json?["results"] as? [[String: Any]] ?? []).compactMap { o in
            func d(_ k: String) -> Double { (o[k] as? NSNumber)?.doubleValue ?? 0 }
            return d("c") > 0 ? Bar(t: d("t"), o: d("o"), h: d("h"), l: d("l"), c: d("c"), v: d("v")) : nil
        }
        let r = analyze(bars)
        cache[t] = (Date(), r)
        return r
    }

    static func sma(_ c: [Double], _ n: Int, _ i: Int) -> Double? {
        i + 1 < n ? nil : c[(i + 1 - n)...i].reduce(0, +) / Double(n)
    }

    /// Wilder's 14-day RSI at every day.
    static func rsi(_ c: [Double], _ n: Int = 14) -> [Double?] {
        var out = [Double?](repeating: nil, count: c.count)
        guard c.count > n else { return out }
        var gain = 0.0, loss = 0.0
        for i in 1...n { let d = c[i] - c[i - 1]; if d > 0 { gain += d } else { loss -= d } }
        gain /= Double(n); loss /= Double(n)
        out[n] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss)
        if c.count > n + 1 {
            for i in (n + 1)..<c.count {
                let d = c[i] - c[i - 1]
                gain = (gain * Double(n - 1) + max(d, 0)) / Double(n)
                loss = (loss * Double(n - 1) + max(-d, 0)) / Double(n)
                out[i] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss)
            }
        }
        return out
    }

    private struct Signal {
        let name: String
        let mood: Int
        let fires: (Int) -> Bool
        let what: (Int) -> String
    }

    static func analyze(_ bars: [Bar]) -> PatternReport {
        guard bars.count >= 60 else {
            return PatternReport(hits: [PatternHit(name: "Not enough history", mood: 0,
                what: "Patterns need at least 60 trading days of prices; this stock has \(bars.count).")], support: nil, resistance: nil)
        }
        let c = bars.map(\.c), v = bars.map(\.v)
        let r = rsi(c)
        let s50 = c.indices.map { sma(c, 50, $0) }, s200 = c.indices.map { sma(c, 200, $0) }, v50 = v.indices.map { sma(v, 50, $0) }
        let last = c.count - 1
        func pct(_ x: Double) -> String { String(format: "%+.1f%%", x * 100) }
        func hi(_ i: Int, _ n: Int) -> Double { guard i >= 0 else { return .infinity }; return bars[max(0, i - n + 1)...i].map(\.h).max() ?? 0 }
        func lo(_ i: Int, _ n: Int) -> Double { guard i >= 0 else { return -.infinity }; return bars[max(0, i - n + 1)...i].map(\.l).min() ?? 0 }
        func cross(_ i: Int, up: Bool) -> Bool {
            guard i > 0, let a = s50[i], let b = s200[i], let pa = s50[i - 1], let pb = s200[i - 1] else { return false }
            return up ? (a > b && pa <= pb) : (a < b && pa >= pb)
        }
        let signals: [Signal] = [
            Signal(name: "Golden cross", mood: 1, fires: { cross($0, up: true) },
                   what: { _ in "The 50-day average just crossed above the 200-day average, a classic sign a downtrend may be turning up." }),
            Signal(name: "Death cross", mood: -1, fires: { cross($0, up: false) },
                   what: { _ in "The 50-day average just crossed below the 200-day average, a classic warning that the trend is weakening." }),
            Signal(name: "Overbought (RSI)", mood: -1, fires: { i in i > 0 && (r[i] ?? 50) >= 70 && (r[i - 1] ?? 50) < 70 },
                   what: { i in String(format: "RSI is %.0f. Above 70 means it has risen fast and often pauses or pulls back.", r[i] ?? 0) }),
            Signal(name: "Oversold (RSI)", mood: 1, fires: { i in i > 0 && (r[i] ?? 50) <= 30 && (r[i - 1] ?? 50) > 30 },
                   what: { i in String(format: "RSI is %.0f. Below 30 means it has fallen fast and often bounces.", r[i] ?? 0) }),
            Signal(name: "New 52-week high", mood: 1, fires: { i in i >= 250 && bars[i].c >= hi(i - 1, 250) && bars[i - 1].c < hi(i - 2, 250) },
                   what: { _ in "Closed at its highest price in a year. Stocks making new highs often keep going (momentum)." }),
            Signal(name: "New 52-week low", mood: -1, fires: { i in i >= 250 && bars[i].c <= lo(i - 1, 250) && bars[i - 1].c > lo(i - 2, 250) },
                   what: { _ in "Closed at its lowest price in a year. Stocks making new lows often keep falling." }),
            Signal(name: "Volume spike", mood: 0, fires: { i in
                guard i > 1, let a = v50[i - 1] else { return false }
                return v[i] >= 2.5 * a && v[i - 1] < 2.5 * (v50[i - 2] ?? .greatestFiniteMagnitude)
            }, what: { i in String(format: "Traded %.1f× its usual volume. Big volume often means news; the direction of the move matters.", v[i] / (v50[i - 1] ?? 1)) }),
            Signal(name: "Gap up", mood: 1, fires: { i in i > 0 && bars[i].o >= bars[i - 1].c * 1.03 },
                   what: { i in "Opened \(pct(bars[i].o / bars[i - 1].c - 1)) above yesterday's close." }),
            Signal(name: "Gap down", mood: -1, fires: { i in i > 0 && bars[i].o <= bars[i - 1].c * 0.97 },
                   what: { i in "Opened \(pct(bars[i].o / bars[i - 1].c - 1)) below yesterday's close." }),
            Signal(name: "Breakout", mood: 1, fires: { i in i >= 31 && hi(i - 1, 30) / lo(i - 1, 30) - 1 < 0.10 && bars[i].c > hi(i - 1, 30) && bars[i - 1].c <= hi(i - 2, 30) },
                   what: { _ in "Broke above a tight 30-day trading range. Breakouts on strong volume tend to be more reliable." }),
            Signal(name: "Breakdown", mood: -1, fires: { i in i >= 31 && hi(i - 1, 30) / lo(i - 1, 30) - 1 < 0.10 && bars[i].c < lo(i - 1, 30) && bars[i - 1].c >= lo(i - 2, 30) },
                   what: { _ in "Fell below a tight 30-day trading range." }),
        ]
        let base = (0..<max(0, c.count - ahead)).map { c[$0 + ahead] / c[$0] - 1 }
        let baseAvg = base.isEmpty ? 0 : base.reduce(0, +) / Double(base.count)
        let baseUp = base.isEmpty ? 0 : Double(base.filter { $0 > 0 }.count) / Double(base.count)

        var hits: [PatternHit] = []
        for s in signals {
            guard let recent = (max(1, last - 4)...last).last(where: { s.fires($0) }) else { continue }
            let past = (1..<max(1, c.count - ahead)).filter { $0 < recent - 5 && s.fires($0) }
            let after = past.map { c[$0 + ahead] / c[$0] - 1 }
            let history = after.count < 3
                ? "Too rare in this stock's last 2 years (\(after.count) time\(after.count == 1 ? "" : "s")) to judge."
                : String(format: "The %d earlier times in 2 years, it was up 20 trading days later %d times (avg %@), vs %.0f%% of the time normally (avg %@).",
                         after.count, after.filter { $0 > 0 }.count, pct(after.reduce(0, +) / Double(after.count)), baseUp * 100, pct(baseAvg))
            let ago = last - recent
            hits.append(PatternHit(name: s.name + (ago == 0 ? " (today)" : " (\(ago) day\(ago == 1 ? "" : "s") ago)"),
                                   mood: s.mood, what: s.what(recent), history: history))
        }
        if let a50 = s50[last], let a200 = s200[last] {
            let p = c[last]
            let trend: PatternHit
            if p > a50 && a50 > a200 { trend = PatternHit(name: "Uptrend", mood: 1, what: "Price is above its 50-day and 200-day averages, and the 50 is above the 200.") }
            else if p < a50 && a50 < a200 { trend = PatternHit(name: "Downtrend", mood: -1, what: "Price is below its 50-day and 200-day averages, and the 50 is below the 200.") }
            else { trend = PatternHit(name: "No clear trend", mood: 0, what: "Price and its 50-day and 200-day averages are mixed.") }
            hits.insert(trend, at: 0)
        } else if let a50 = s50[last] {
            hits.insert(PatternHit(name: c[last] > a50 ? "Above 50-day average" : "Below 50-day average", mood: c[last] > a50 ? 1 : -1,
                                   what: "Not enough history yet for the 200-day average."), at: 0)
        }
        if let x = r[last], !hits.contains(where: { $0.name.hasPrefix("Overbought") || $0.name.hasPrefix("Oversold") }) {
            if x >= 70 { hits.append(PatternHit(name: "Overbought (RSI)", mood: -1, what: String(format: "RSI is %.0f, above 70.", x))) }
            else if x <= 30 { hits.append(PatternHit(name: "Oversold (RSI)", mood: 1, what: String(format: "RSI is %.0f, below 30.", x))) }
        }
        if let d = doubleTopBottom(bars) { hits.append(d) }
        let lv = levels(bars)
        return PatternReport(hits: hits, support: lv.0, resistance: lv.1)
    }

    private static func swings(_ bars: [Bar], high: Bool) -> [Int] {
        guard bars.count > 10 else { return [] }
        return (5..<(bars.count - 5)).filter { i in
            let x = high ? bars[i].h : bars[i].l
            return ((i - 5)...(i + 5)).allSatisfy { j in j == i || (high ? bars[j].h < x : bars[j].l > x) }
        }
    }

    private static func doubleTopBottom(_ bars: [Bar]) -> PatternHit? {
        let start = max(0, bars.count - 126)
        let lastClose = bars[bars.count - 1].c
        let note = "Chart patterns like this are well known but studies find they predict weakly; treat it as a hint."
        let highs = swings(bars, high: true).filter { $0 >= start }
        for a in stride(from: highs.count - 1, through: 0, by: -1) {
            for b in stride(from: a - 1, through: 0, by: -1) {
                let i = highs[b], j = highs[a]
                guard j - i >= 15 else { continue }
                let p1 = bars[i].h, p2 = bars[j].h
                guard abs(p1 - p2) / max(p1, p2) <= 0.03 else { continue }
                let trough = bars[i...j].map(\.l).min() ?? 0
                guard trough <= min(p1, p2) * 0.92 else { continue }
                let confirmed = lastClose < trough
                return PatternHit(name: "Double top" + (confirmed ? " (confirmed)" : " (forming)"), mood: -1,
                    what: String(format: "Two peaks near $%.2f with a dip to $%.2f between. ", max(p1, p2), trough) +
                        (confirmed ? "Price has fallen below the dip, which is when the pattern counts." : String(format: "It only counts if price falls below $%.2f.", trough)),
                    history: note)
            }
        }
        let lows = swings(bars, high: false).filter { $0 >= start }
        for a in stride(from: lows.count - 1, through: 0, by: -1) {
            for b in stride(from: a - 1, through: 0, by: -1) {
                let i = lows[b], j = lows[a]
                guard j - i >= 15 else { continue }
                let p1 = bars[i].l, p2 = bars[j].l
                guard abs(p1 - p2) / max(p1, p2) <= 0.03 else { continue }
                let peak = bars[i...j].map(\.h).max() ?? 0
                guard peak >= max(p1, p2) * 1.08 else { continue }
                let confirmed = lastClose > peak
                return PatternHit(name: "Double bottom" + (confirmed ? " (confirmed)" : " (forming)"), mood: 1,
                    what: String(format: "Two lows near $%.2f with a rise to $%.2f between. ", min(p1, p2), peak) +
                        (confirmed ? "Price has risen above that peak, which is when the pattern counts." : String(format: "It only counts if price rises above $%.2f.", peak)),
                    history: note)
            }
        }
        return nil
    }

    private static func levels(_ bars: [Bar]) -> (Double?, Double?) {
        let start = max(0, bars.count - 252)
        let p = bars[bars.count - 1].c
        let pts = (swings(bars, high: true) + swings(bars, high: false)).filter { $0 >= start }.map { bars[$0].h >= p ? bars[$0].h : bars[$0].l }
        func cluster(_ xs: [Double]) -> [Double] { xs.filter { x in xs.filter { abs($0 - x) / x < 0.015 }.count >= 2 } }
        // At least 1% away, so it's a level to watch rather than today's price.
        return (cluster(pts.filter { $0 < p * 0.99 }).max(), cluster(pts.filter { $0 > p * 1.01 }).min())
    }
}
