import Foundation
import UserNotifications

// MARK: - Nightly scan of every U.S. stock (mirrors Nightly.kt on Android)

struct NightlyPattern: Identifiable {
    let id: String, name: String, kind: String, mood: Int, desc: String
    let n: Int, beat: Double, avg: Double
    /// Average result in the pattern's own direction: positive means it has worked.
    var edge: Double { n > 0 ? avg * Double(mood == 0 ? 1 : mood) : 0 }
    var record: String {
        n == 0 ? "" : String(format: "Across all stocks: beat the S&P 500 %.0f%% of %@ times, average %+.1f%% over 20 trading days",
                             beat * 100, n.formatted(), avg * 100)
    }
}

struct NightlyHit {
    let pattern: NightlyPattern, ago: Int, note: String
    let ownN: Int, ownBeat: Double, ownAvg: Double
    var whenText: String { ago == 0 ? "Today" : ago == 1 ? "Yesterday" : "\(ago) trading days ago" }
    var ownRecord: String {
        ownN < 3 ? "" : String(format: "This stock: beat the S&P 500 %.0f%% of %d times, average %+.1f%%", ownBeat * 100, ownN, ownAvg * 100)
    }
}

struct NightlyStock {
    let ticker: String, price: Double, chg: Double, hi20: Double, lo20: Double, hi52: Double, lo52: Double
    let hits: [NightlyHit]
}

struct NightlySignal: Identifiable {
    let stock: NightlyStock, hit: NightlyHit
    var id: String { stock.ticker + hit.pattern.id }
}

struct NightlyResult {
    let date: String, scanned: Int, patterns: [NightlyPattern], stocks: [String: NightlyStock]

    /// Every signal, best track record first; smart-money signals (no price history) after the strongest ones.
    func signals() -> [NightlySignal] {
        func score(_ s: NightlySignal) -> Double { s.hit.pattern.kind == "Smart money" ? 0.02 : s.hit.pattern.edge }
        return stocks.values.flatMap { s in s.hits.map { NightlySignal(stock: s, hit: $0) } }
            .sorted { a, b in score(a) != score(b) ? score(a) > score(b) : a.hit.ago < b.hit.ago }
    }
}

enum Nightly {
    static let url = URL(string: "https://github.com/RashestLynx/stock-Tracker-patterns/releases/download/latest/patterns.json.gz")!
    private static var memo: (Date, NightlyResult)?

    private static var file: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("nightly.json.gz")
    }

    private static var savedAt: Date? {
        (try? FileManager.default.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date
    }

    /// The saved copy only, never downloads.
    static func cached() -> NightlyResult? {
        guard let at = savedAt else { return nil }
        if let m = memo, m.0 == at { return m.1 }
        guard let d = try? Data(contentsOf: file), let r = parse(d) else { return nil }
        memo = (at, r)
        return r
    }

    /// The latest scan: the saved copy if it's under 3 hours old, else downloaded (falls back to the saved copy offline).
    static func load(force: Bool = false) async throws -> NightlyResult {
        if !force, let at = savedAt, Date().timeIntervalSince(at) < 3 * 3600, let r = cached() { return r }
        do {
            var req = URLRequest(url: url, timeoutInterval: 60)
            req.setValue("StockTracker", forHTTPHeaderField: "User-Agent")
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode ?? 0 < 300, parse(data) != nil else { throw URLError(.badServerResponse) }
            try data.write(to: file, options: .atomic)
            memo = nil
        } catch {
            if let r = cached() { return r }
            throw NSError(domain: "Nightly", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "The nightly scan isn't available yet. Results appear after the first scan finishes."])
        }
        guard let r = cached() else { throw URLError(.cannotDecodeContentData) }
        return r
    }

    /// Unpacks a .gz file: skips the gzip header, then inflates the raw DEFLATE data with the system's zlib.
    static func gunzip(_ d: Data) -> Data? {
        let b = [UInt8](d)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { return nil }
        let flags = b[3]
        var i = 10
        if flags & 4 != 0 { guard i + 2 <= b.count else { return nil }; i += 2 + Int(b[i]) + Int(b[i + 1]) << 8 }
        if flags & 8 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 16 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 2 != 0 { i += 2 }
        guard i < b.count - 8 else { return nil }
        let raw = Data(b[i..<(b.count - 8)]) as NSData
        return try? raw.decompressed(using: .zlib) as Data
    }

    static func parse(_ gz: Data) -> NightlyResult? {
        guard let text = gunzip(gz) ?? (gz.first == UInt8(ascii: "{") ? gz : nil),
              let o = try? JSONSerialization.jsonObject(with: text) as? [String: Any],
              let pj = o["patterns"] as? [String: [String: Any]],
              let sj = o["stocks"] as? [String: [String: Any]] else { return nil }
        func num(_ x: Any?) -> Double { (x as? NSNumber)?.doubleValue ?? 0 }
        let patterns = pj.map { id, p in
            NightlyPattern(id: id, name: p["name"] as? String ?? id, kind: p["kind"] as? String ?? "",
                           mood: Int(num(p["mood"])), desc: p["desc"] as? String ?? "",
                           n: Int(num(p["n"])), beat: num(p["beat"]), avg: num(p["avg"]))
        }
        let byId = Dictionary(uniqueKeysWithValues: patterns.map { ($0.id, $0) })
        var stocks: [String: NightlyStock] = [:]
        for (t, s) in sj {
            let hits: [NightlyHit] = (s["hits"] as? [[String: Any]] ?? []).compactMap { h in
                guard let p = byId[h["p"] as? String ?? ""] else { return nil }
                let own = h["own"] as? [String: Any]
                return NightlyHit(pattern: p, ago: Int(num(h["ago"])), note: h["note"] as? String ?? "",
                                  ownN: Int(num(own?["n"])), ownBeat: num(own?["beat"]), ownAvg: num(own?["avg"]))
            }
            stocks[t] = NightlyStock(ticker: t, price: num(s["price"]), chg: num(s["chg"]), hi20: num(s["hi20"]), lo20: num(s["lo20"]),
                                     hi52: num(s["hi52"]), lo52: num(s["lo52"]), hits: hits)
        }
        return NightlyResult(date: o["date"] as? String ?? "", scanned: Int(num(o["stocks_scanned"])), patterns: patterns, stocks: stocks)
    }
}

// MARK: - Live price alerts while the market is open

struct PriceAlert: Codable, Identifiable {
    let key: String, ticker: String, title: String, text: String, time: Date, up: Bool
    var id: String { key }
}

enum PriceWatch {
    static let maxStocks = 45   // Finnhub's free plan allows 60 quotes a minute
    private static let eastern = TimeZone(identifier: "America/New_York")!

    static func marketOpen(_ now: Date = Date()) -> Bool {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = eastern
        let c = cal.dateComponents([.weekday, .hour, .minute], from: now)
        guard let wd = c.weekday, wd != 1, wd != 7 else { return false }
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        return m >= 9 * 60 + 30 && m < 16 * 60
    }

    static func today() -> String {
        let f = DateFormatter(); f.timeZone = eastern; f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date())
    }

    /// Watchlist first, then the strongest price patterns from last night's scan.
    static func targets(watchlist: [String], nightly: NightlyResult?) -> [String] {
        var list: [String] = []
        for t in watchlist where !list.contains(t) { list.append(t) }
        for s in nightly?.signals() ?? [] where list.count < maxStocks {
            let p = s.hit.pattern
            if p.mood != 0 && p.kind != "Smart money" && s.hit.ago <= 1 && p.edge > 0 && !list.contains(s.stock.ticker) {
                list.append(s.stock.ticker)
            }
        }
        return Array(list.prefix(maxStocks))
    }

    /// What's worth an alert for one stock right now.
    static func rules(_ t: String, price: Double, changePct: Double, stock s: NightlyStock?, day: String) -> [PriceAlert] {
        var out: [PriceAlert] = []
        func money(_ x: Double) -> String { String(format: "$%.2f", x) }
        if let s, price > 0 {
            let bull = s.hits.first { $0.pattern.mood > 0 && $0.pattern.kind != "Smart money" }
            let bear = s.hits.first { $0.pattern.mood < 0 && $0.pattern.kind != "Smart money" }
            if s.hi52 > 0 && price > s.hi52 {
                out.append(PriceAlert(key: "px:\(t):hi52:\(day)", ticker: t, title: "\(t) hit a new 52-week high",
                    text: "\(money(price)), above last year's high of \(money(s.hi52))." + (bull.map { " Last night's scan found a \($0.pattern.name.lowercased())." } ?? ""), time: Date(), up: true))
            } else if s.hi20 > 0 && price > s.hi20 {
                out.append(PriceAlert(key: "px:\(t):hi20:\(day)", ticker: t, title: "\(t) broke above its 20-day high",
                    text: "\(money(price)), above \(money(s.hi20))." + (bull.map { " That confirms last night's \($0.pattern.name.lowercased())." } ?? ""), time: Date(), up: true))
            }
            if s.lo52 > 0 && price < s.lo52 {
                out.append(PriceAlert(key: "px:\(t):lo52:\(day)", ticker: t, title: "\(t) hit a new 52-week low",
                    text: "\(money(price)), below last year's low of \(money(s.lo52))." + (bear.map { " Last night's scan found a \($0.pattern.name.lowercased())." } ?? ""), time: Date(), up: false))
            } else if s.lo20 > 0 && price < s.lo20 {
                out.append(PriceAlert(key: "px:\(t):lo20:\(day)", ticker: t, title: "\(t) broke below its 20-day low",
                    text: "\(money(price)), below \(money(s.lo20))." + (bear.map { " That confirms last night's \($0.pattern.name.lowercased())." } ?? ""), time: Date(), up: false))
            }
        }
        if abs(changePct) >= 5 {
            out.append(PriceAlert(key: "px:\(t):move:\(day)", ticker: t,
                title: "\(t) is \(changePct > 0 ? "up" : "down") \(String(format: "%.1f", abs(changePct)))% today",
                text: price > 0 ? "Now \(money(price))." : "", time: Date(), up: changePct > 0))
        }
        return out
    }

    static func post(_ alerts: [PriceAlert]) async {
        let center = UNUserNotificationCenter.current()
        if alerts.count > 3 {
            let c = UNMutableNotificationContent()
            c.title = "\(alerts.count) price alerts"
            c.body = alerts.map(\.title).joined(separator: "\n")
            c.sound = .default
            try? await center.add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
            return
        }
        for a in alerts {
            let c = UNMutableNotificationContent()
            c.title = a.title; c.body = a.text; c.sound = .default
            try? await center.add(UNNotificationRequest(identifier: a.key, content: c, trigger: nil))
        }
    }
}
