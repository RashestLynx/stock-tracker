import Foundation

/// A live quote: price, change today in dollars and percent.
struct LiveQuote { let price: Double; let change: Double; let changePct: Double; var open: Double = 0; var prevClose: Double = 0 }

/// Live stock prices from Finnhub's free API (needs a free key; 60 requests a minute).
enum Live {
    static let signup = "https://finnhub.io/register"

    static var key: String {
        // Your own key, saved on this phone only (Settings → Live prices).
        get { UserDefaults.standard.string(forKey: "finnhub_key") ?? "" }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "finnhub_key") }
    }

    /// How the market is doing today, for the green/red background. Uses Finnhub when you have a key,
    /// otherwise Stooq's free delayed prices (no key needed). Mirrors Live.market on Android.
    static func market() async -> LiveQuote? {
        if let q = await quote("SPY") { return q }
        if let q = await cboeDay("SPY") { return q }
        if let q = await yahooDay("SPY") { return q }
        return await stooqDay("SPY")
    }

    private static func json(_ url: String) async -> [String: Any]? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.setValue("StockTracker-iOS/1.0", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              ((resp as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2 else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func dbl(_ v: Any?) -> Double { (v as? NSNumber)?.doubleValue ?? Double(v as? String ?? "") ?? 0 }

    /// Cboe's free 15-minute-delayed quote (no key).
    static func cboeDay(_ ticker: String) async -> LiveQuote? {
        guard let d = await json("https://cdn.cboe.com/api/global/delayed_quotes/quotes/\(ticker.uppercased()).json")?["data"] as? [String: Any] else { return nil }
        let now = dbl(d["current_price"]), prev = dbl(d["prev_day_close"])
        guard now > 0, prev > 0 else { return nil }
        return LiveQuote(price: now, change: now - prev, changePct: (now / prev - 1) * 100, open: dbl(d["open"]), prevClose: prev)
    }

    /// Yahoo Finance's public chart data (no key).
    static func yahooDay(_ ticker: String) async -> LiveQuote? {
        guard let chart = await json("https://query1.finance.yahoo.com/v8/finance/chart/\(ticker.uppercased())?range=1d&interval=1d")?["chart"] as? [String: Any],
              let meta = (chart["result"] as? [[String: Any]])?.first?["meta"] as? [String: Any] else { return nil }
        let now = dbl(meta["regularMarketPrice"])
        var prev = dbl(meta["chartPreviousClose"])
        if prev <= 0 { prev = dbl(meta["previousClose"]) }
        guard now > 0, prev > 0 else { return nil }
        return LiveQuote(price: now, change: now - prev, changePct: (now / prev - 1) * 100, prevClose: prev)
    }

    /// Today's change from Stooq: tries the quote with previous close, then the daily history, then open vs now.
    static func stooqDay(_ ticker: String) async -> LiveQuote? {
        let sym = ticker.lowercased() + ".us"
        func rows(_ url: String) async -> [[String: String]] {
            guard let u = URL(string: url), let (data, resp) = try? await URLSession.shared.data(from: u),
                  ((resp as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2,
                  let text = String(data: data, encoding: .utf8) else { return [] }
            let lines = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            guard lines.count >= 2 else { return [] }
            let head = lines[0].split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            return lines.dropFirst().map { l in
                let vals = l.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
                var m: [String: String] = [:]
                for (i, h) in head.enumerated() where i < vals.count { m[h] = vals[i] }
                return m
            }
        }
        func num(_ m: [String: String], _ names: String...) -> Double? {
            for (k, v) in m where names.contains(where: { k.hasPrefix($0) }) {
                if let d = Double(v), d > 0 { return d }
            }
            return nil
        }
        func make(_ now: Double, _ prev: Double, _ open: Double = 0) -> LiveQuote {
            LiveQuote(price: now, change: now - prev, changePct: (now / prev - 1) * 100, open: open, prevClose: prev)
        }
        if let r = await rows("https://stooq.com/q/l/?s=\(sym)&f=sd2t2ohlcp&h&e=csv").first,
           let c = num(r, "close"), let p = num(r, "prev") {
            return make(c, p, num(r, "open") ?? 0)
        }
        let closes = await rows("https://stooq.com/q/d/l/?s=\(sym)&i=d").compactMap { num($0, "close") }
        if closes.count >= 2 { return make(closes[closes.count - 1], closes[closes.count - 2]) }
        if let r = await rows("https://stooq.com/q/l/?s=\(sym)&f=sd2t2oc&h&e=csv").first,
           let o = num(r, "open"), let c = num(r, "close") {
            return make(c, o, o)
        }
        return nil
    }

    static func quote(_ ticker: String) async -> LiveQuote? {
        guard !key.isEmpty,
              let t = ticker.uppercased().addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let k = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://finnhub.io/api/v1/quote?symbol=\(t)&token=\(k)"),
              let (d, r) = try? await URLSession.shared.data(from: url),
              (r as? HTTPURLResponse)?.statusCode ?? 0 < 300,
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let c = (o["c"] as? NSNumber)?.doubleValue, c > 0 else { return nil }
        let q = LiveQuote(price: c, change: (o["d"] as? NSNumber)?.doubleValue ?? 0, changePct: (o["dp"] as? NSNumber)?.doubleValue ?? 0,
                          open: (o["o"] as? NSNumber)?.doubleValue ?? 0, prevClose: (o["pc"] as? NSNumber)?.doubleValue ?? 0)
        LiveTicks.add(ticker, q)
        return q
    }
}

/// "▲ +1.23 (+0.45%)"
func changeText(_ q: LiveQuote) -> String {
    String(format: "%@%.2f (%+.2f%%)", q.change >= 0 ? "▲ +" : "▼ ", q.change, q.changePct)
}
