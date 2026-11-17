import Foundation

/// One IPO on the calendar. Prices are dollars a share; value is the whole deal in dollars.
struct Ipo: Codable, Identifiable {
    var key: String          // normalized company name, used to match sources and remember stages
    var name: String
    var symbol: String
    var date: String         // yyyy-MM-dd, "" if not set yet
    var exchange: String
    var low: Double          // price range (low == high once priced)
    var high: Double
    var shares: Double
    var value: Double
    var status: String       // filed, expected, priced, withdrawn
    var cornerstone = ""     // link to the filing that names investors lined up to buy, "" if none found
    var mentions = 0         // Reddit mentions in the last 24 hours
    var open = 0.0           // first trading price seen (priced IPOs only)
    var now = 0.0
    var id: String { key }
    var big: Bool { value >= Ipos.bigDeal }
    var ipoPrice: Double { high > 0 ? high : low }
}

/// A company that just filed with the SEC to go public (form S-1 or F-1), before any price is set.
struct IpoFiling: Codable, Identifiable {
    var key: String
    var name: String
    var cik: String
    var form: String
    var date: String
    var link: String
    var cornerstone = ""
    var id: String { cik }
}

struct IpoEvent: Codable, Identifiable {
    var id: String
    var title: String
    var body: String
    var link: String
    var time: Date
}

struct IpoResult: Codable {
    var upcoming: [Ipo]
    var priced: [Ipo]
    var filings: [IpoFiling]
    var fetchedAt: Date
}

/// What the last check saw for one IPO, so the next one can tell what changed.
private struct IpoMark: Codable {
    var s = ""; var l = 0.0; var h = 0.0; var sh = 0.0
    var big = false; var c = false; var o = false; var b = false
    var t = Date()
}

/// IPOs from three free sources:
///  - Finnhub's IPO calendar (with your Finnhub key): date, price range, shares, deal size, priced or not.
///  - SEC EDGAR: brand-new S-1 / F-1 filings, the earliest public sign a company plans to go public, and a
///    full-text search for filings that say investors "indicated an interest in purchasing" shares
///    (cornerstone investors, named in the prospectus).
///  - ApeWisdom: how much Reddit is talking about each one.
///
/// Each check is compared with the last one so the app can alert at every stage: filed, range set,
/// range raised or cut, deal upsized, priced, first trade.
enum Ipos {
    static let bigDeal = 100_000_000.0
    private static let phrase = "indicated an interest in purchasing"
    private static let suffix: Set<String> = ["inc", "incorporated", "corp", "corporation", "co", "company", "ltd", "limited", "plc",
        "holdings", "holding", "group", "the", "sa", "nv", "ag", "llc", "lp", "se", "class", "a", "ordinary", "shares"]
    // S-1s that aren't company IPOs: crypto and other trusts, ETFs, funds, blank-check SPACs.
    private static let notIpo = try! NSRegularExpression(pattern: "\\b(trust|etf|fund|acquisition|blank check|spac|bitcoin|ether|solana|xrp)\\b",
                                                         options: .caseInsensitive)
    private static let d = UserDefaults.standard

    static var alertsOn: Bool {
        get { d.object(forKey: "ipoAlerts") as? Bool ?? true }
        set { d.set(newValue, forKey: "ipoAlerts") }
    }
    static var filingAlertsOn: Bool {
        get { d.object(forKey: "ipoFilingAlerts") as? Bool ?? true }
        set { d.set(newValue, forKey: "ipoFilingAlerts") }
    }

    static func norm(_ name: String) -> String {
        let cleaned = name.lowercased().map { ($0.isLetter && $0.isASCII) || $0.isNumber ? $0 : " " }
        return String(cleaned).split(separator: " ").map(String.init).filter { !suffix.contains($0) }.prefix(3).joined(separator: " ")
    }

    private static func isNotIpo(_ s: String) -> Bool {
        notIpo.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    // ---------------- loading ----------------

    static func cached() -> IpoResult? {
        guard let data = d.data(forKey: "ipoCache") else { return nil }
        return try? decoder.decode(IpoResult.self, from: data)
    }

    /// Loads all three sources; works without a Finnhub key (filings only).
    static func load(ua: String, publicCiks: Set<Int>) async throws -> IpoResult {
        let today = Movers.dayFormatter.string(from: Date())
        var errors: [String] = []
        var cal: [Ipo] = []
        do { cal = try await calendar() } catch { errors.append(error.localizedDescription) }
        var filings: [IpoFiling] = []
        do { filings = try await newFilings(ua: ua, publicCiks: publicCiks) } catch { errors.append(error.localizedDescription) }
        let corner = (try? await cornerstones(ua: ua)) ?? [:]
        var buzz: [String: Int] = [:]
        for w in (try? await Wsb.trending(filter: "all-stocks")) ?? [] { buzz[w.ticker] = w.mentions }
        if cal.isEmpty && filings.isEmpty, let e = errors.first { throw AppError(e) }

        let extras = cal.map { i -> Ipo in var x = i; x.cornerstone = corner[i.key] ?? ""; x.mentions = buzz[i.symbol] ?? 0; return x }
        let upcoming = extras.filter { $0.status != "priced" && $0.status != "withdrawn" && ($0.date.isEmpty || $0.date >= today) }
            .sorted { ($0.date.isEmpty ? "9999" : $0.date, -$0.value) < ($1.date.isEmpty ? "9999" : $1.date, -$1.value) }
        var priced: [Ipo] = []
        for (n, i) in extras.filter({ $0.status == "priced" }).sorted(by: { $0.date > $1.date }).prefix(25).enumerated() {
            var x = i
            if n < 12, !i.symbol.isEmpty, i.date <= today, let q = await Live.quote(i.symbol) {
                // The first day's open is the IPO's first trade; after that only a saved one counts.
                let open = savedOpen(i.symbol) ?? (i.date == today && q.open > 0 ? q.open : 0)
                rememberOpen(i.symbol, open)
                x.open = open
                x.now = q.price
            }
            priced.append(x)
        }
        let filed = filings.map { f -> IpoFiling in var x = f; x.cornerstone = corner[f.key] ?? ""; return x }
        let r = IpoResult(upcoming: upcoming, priced: priced, filings: filed, fetchedAt: Date())
        d.set(try? encoder.encode(r), forKey: "ipoCache")
        return r
    }

    private static func savedOpen(_ symbol: String) -> Double? {
        (d.dictionary(forKey: "ipoOpens")?[symbol] as? Double).flatMap { $0 > 0 ? $0 : nil }
    }

    private static func rememberOpen(_ symbol: String, _ open: Double) {
        var m = d.dictionary(forKey: "ipoOpens") ?? [:]
        guard open > 0, m[symbol] == nil else { return }
        m[symbol] = open
        d.set(m, forKey: "ipoOpens")
    }

    private static func calendar() async throws -> [Ipo] {
        let k = Live.key
        guard !k.isEmpty else { return [] }
        let cal = Calendar(identifier: .gregorian)
        let from = Movers.dayFormatter.string(from: cal.date(byAdding: .day, value: -21, to: Date()) ?? Date())
        let to = Movers.dayFormatter.string(from: cal.date(byAdding: .day, value: 90, to: Date()) ?? Date())
        guard let ke = k.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let url = URL(string: "https://finnhub.io/api/v1/calendar/ipo?from=\(from)&to=\(to)&token=\(ke)") else { return [] }
        var req = URLRequest(url: url)
        req.timeoutInterval = 30
        let (data, resp) = try await URLSession.shared.data(for: req)
        switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200..<300: break
        case 401, 403: throw AppError("Finnhub key not accepted for the IPO calendar")
        case let code: throw AppError("IPO calendar returned HTTP \(code)")
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        var seen = Set<String>()
        var out: [Ipo] = []
        for o in json?["ipoCalendar"] as? [[String: Any]] ?? [] {
            let name = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty, !isNotIpo(name) else { continue }
            let price = o["price"] as? String ?? ""
            let nums = price.split(whereSeparator: { !($0.isNumber || $0 == ".") }).compactMap { Double($0) }
            let low = nums.min() ?? 0, high = nums.max() ?? 0
            let shares = (o["numberOfShares"] as? NSNumber)?.doubleValue ?? 0
            let total = (o["totalSharesValue"] as? NSNumber)?.doubleValue ?? 0
            let i = Ipo(key: norm(name), name: name, symbol: (o["symbol"] as? String ?? "").uppercased(), date: o["date"] as? String ?? "",
                        exchange: o["exchange"] as? String ?? "", low: low, high: high, shares: shares,
                        value: total > 0 ? total : shares * high, status: (o["status"] as? String ?? "").lowercased())
            if seen.insert(i.key).inserted { out.append(i) }
        }
        return out
    }

    /// New S-1 and F-1 filings from companies that aren't public yet.
    private static func newFilings(ua: String, publicCiks: Set<Int>) async throws -> [IpoFiling] {
        let s1 = try await Edgar.feed("S-1", ua: ua, count: 60)
        let f1 = try await Edgar.feed("F-1", ua: ua, count: 40)
        let list = s1 + f1
        let re = try NSRegularExpression(pattern: "^\\S+ - (.+?) \\((\\d{10})\\)")
        var seen = Set<String>()
        var out: [IpoFiling] = []
        for f in list {
            guard let m = re.firstMatch(in: f.title, range: NSRange(f.title.startIndex..., in: f.title)),
                  let r1 = Range(m.range(at: 1), in: f.title), let r2 = Range(m.range(at: 2), in: f.title) else { continue }
            let name = String(f.title[r1]).trimmingCharacters(in: .whitespaces)
            let cik = String(f.title[r2])
            guard let n = Int(cik), !publicCiks.contains(n), !isNotIpo(name), seen.insert(cik).inserted else { continue }
            out.append(IpoFiling(key: norm(name), name: name.capitalized, cik: cik, form: f.form, date: String(f.updated.prefix(10)), link: f.link))
        }
        return out.sorted { $0.date > $1.date }
    }

    /// Filings from the last 4 months that name investors lined up to buy shares: normalized name -> document link.
    private static func cornerstones(ua: String) async throws -> [String: String] {
        let cal = Calendar(identifier: .gregorian)
        let start = Movers.dayFormatter.string(from: cal.date(byAdding: .day, value: -120, to: Date()) ?? Date())
        let end = Movers.dayFormatter.string(from: Date())
        let q = "%22indicated%20an%20interest%20in%20purchasing%22"
        let data = try await Edgar.get("https://efts.sec.gov/LATEST/search-index?q=\(q)&forms=S-1,S-1/A,F-1,F-1/A,424B4&dateRange=custom&startdt=\(start)&enddt=\(end)", ua: ua)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let hits = (json?["hits"] as? [String: Any])?["hits"] as? [[String: Any]] ?? []
        var out: [String: String] = [:]
        for h in hits {
            guard let src = h["_source"] as? [String: Any],
                  let display = (src["display_names"] as? [String])?.first,
                  let cikRaw = (src["ciks"] as? [String])?.first, let cik = Int(cikRaw),
                  let id = h["_id"] as? String, let colon = id.firstIndex(of: ":") else { continue }
            let adsh = id[..<colon].replacingOccurrences(of: "-", with: "")
            let file = id[id.index(after: colon)...]
            guard !adsh.isEmpty, !file.isEmpty else { continue }
            let name = display.components(separatedBy: "(").first?.trimmingCharacters(in: .whitespaces) ?? display
            let k = norm(name)
            if out[k] == nil { out[k] = "https://www.sec.gov/Archives/edgar/data/\(cik)/\(adsh)/\(file)" }
        }
        return out
    }

    /// Reads a filing and returns the sentences naming investors lined up to buy, or nil.
    static func whoIsBuying(_ link: String, ua: String) async -> String? {
        guard let data = try? await Edgar.get(link, ua: ua) else { return nil }
        var text = String(decoding: data, as: UTF8.self)
        let swaps: [(String, String)] = [
            ("(?is)<(script|style)[^>]*>.*?</\\1>", " "), ("<[^>]+>", " "), ("&nbsp;|&#160;|&#xa0;", " "), ("&amp;", "&"),
            ("&#8217;|&rsquo;", "'"), ("&#822[01];|&[lr]dquo;", "\""), ("&#[0-9]+;|&[a-z]+;", " "), ("\\s+", " "),
        ]
        for (p, r) in swaps { text = text.replacingOccurrences(of: p, with: r, options: .regularExpression) }
        var out: [String] = []
        var from = text.startIndex
        while out.count < 3, let hit = text.range(of: phrase, options: .caseInsensitive, range: from..<text.endIndex) {
            let back = text.index(hit.lowerBound, offsetBy: -600, limitedBy: text.startIndex) ?? text.startIndex
            let fwd = text.index(hit.upperBound, offsetBy: 600, limitedBy: text.endIndex) ?? text.endIndex
            let start = text.range(of: ". ", options: .backwards, range: back..<hit.lowerBound)?.upperBound ?? back
            let end = text.range(of: ". ", range: hit.upperBound..<fwd).map { text.index(after: $0.lowerBound) } ?? fwd
            let s = text[start..<end].trimmingCharacters(in: .whitespaces)
            if !out.contains(s) { out.append(s) }
            from = end
        }
        return out.isEmpty ? nil : out.joined(separator: "\n\n")
    }

    // ---------------- alerts ----------------

    /// For the background check. Loads at most every 20 minutes, compares with what it saw last time and returns
    /// what's new. The first time it only remembers, so you don't get a flood of old IPOs.
    static func check(ua: String, publicCiks: Set<Int>) async -> [IpoEvent] {
        guard alertsOn else { return [] }
        let last = d.object(forKey: "ipoLastCheck") as? Date ?? .distantPast
        guard Date().timeIntervalSince(last) >= 20 * 60 else { return [] }
        d.set(Date(), forKey: "ipoLastCheck")
        guard let r = try? await load(ua: ua, publicCiks: publicCiks) else { return [] }
        let stored = d.data(forKey: "ipoState").flatMap { try? decoder.decode([String: IpoMark].self, from: $0) }
        let first = stored == nil
        var state = stored ?? [:]
        var events: [IpoEvent] = []
        let now = Date()
        func ev(_ id: String, _ title: String, _ body: String, _ link: String) {
            events.append(IpoEvent(id: id, title: title, body: body, link: link, time: now))
        }

        for i in r.upcoming + r.priced {
            let s = state[i.key]
            let link = i.cornerstone.isEmpty ? "https://finnhub.io" : i.cornerstone
            let label = i.symbol.isEmpty ? i.name : "\(i.name) (\(i.symbol))"
            let range = rangeText(i.low, i.high)
            let when = i.date.isEmpty ? "soon" : i.date
            if let s {
                if i.status == "priced" && s.s != "priced" && (i.big || s.big) {
                    let p = i.ipoPrice
                    let vs: String
                    if s.h <= 0 { vs = "" }
                    else if p > s.h { vs = " Above its \(rangeText(s.l, s.h)) range: strong demand." }
                    else if p == s.h { vs = " At the top of its range." }
                    else if p < s.l { vs = " Below its \(rangeText(s.l, s.h)) range: weak demand." }
                    else { vs = " Inside its range." }
                    ev("ipo:\(i.key):priced", "\(label) priced its IPO at $\(fmt(p))",
                       "Starts trading \(when).\(vs) Shares usually open well above the IPO price; use a limit order.", link)
                } else if i.status != "priced" && s.h > 0 && i.high > 0 {
                    if i.high > s.h || i.low > s.l {
                        ev("ipo:\(i.key):up:\(i.high)", "\(label) raised its IPO price range",
                           "Now \(range) (was \(rangeText(s.l, s.h))). A raised range usually means strong demand.", link)
                    } else if i.high < s.h || i.low < s.l {
                        ev("ipo:\(i.key):down:\(i.high)", "\(label) cut its IPO price range",
                           "Now \(range) (was \(rangeText(s.l, s.h))). A cut range usually means weak demand.", link)
                    }
                    if s.sh > 0 && i.shares > s.sh * 1.05 {
                        ev("ipo:\(i.key):upsized:\(Int(i.shares))", "\(label) is selling more shares",
                           "Deal upsized to \(Int(i.shares).formatted()) shares (was \(Int(s.sh).formatted())), about \(money(i.value)). Usually a sign of strong demand.", link)
                    }
                } else if s.h <= 0 && i.high > 0 && i.big && i.status != "priced" {
                    ev("ipo:\(i.key):range", "\(label) set its IPO price range",
                       "\(range) · about \(money(i.value)) deal\(i.date.isEmpty ? "" : " · expected \(i.date)"). This is usually 1–2 weeks before it trades.", link)
                }
                if i.status == "withdrawn" && s.s != "withdrawn" && s.big {
                    ev("ipo:\(i.key):pulled", "\(label) pulled its IPO", "The company withdrew or postponed its offering.", link)
                }
                if !i.cornerstone.isEmpty && !s.c && (i.big || i.value == 0) {
                    ev("ipo:\(i.key):corner", "Big investors lined up for \(label)",
                       "Its filing says investors have indicated an interest in buying shares in the IPO. Open the IPOs view to see who.", i.cornerstone)
                }
                if i.open > 0 && !s.o && i.ipoPrice > 0 && (i.big || s.big) {
                    ev("ipo:\(i.key):open", "\(i.symbol.isEmpty ? i.name : i.symbol) opened at $\(fmt(i.open))",
                       String(format: "%+.0f%% vs its $%@ IPO price. Now $%@.", (i.open / i.ipoPrice - 1) * 100, fmt(i.ipoPrice), fmt(i.now)), link)
                }
                if i.mentions >= 50 && !s.b {
                    ev("ipo:\(i.key):buzz", "\(i.symbol) is trending on Reddit",
                       "\(i.mentions) mentions in the last 24 hours across stock subreddits.", "https://apewisdom.io/stocks/\(i.symbol)/")
                }
            } else if i.big && i.status == "priced" {
                ev("ipo:\(i.key):priced", "\(label) priced its IPO at $\(fmt(i.ipoPrice))",
                   "About \(money(i.value)) deal. Trades \(when). Shares usually open well above the IPO price; use a limit order.", link)
            } else if i.big && i.status != "withdrawn" {
                let parts = [range.isEmpty ? nil : range, "about \(money(i.value)) deal", i.date.isEmpty ? nil : "expected \(i.date)",
                             i.exchange.isEmpty ? nil : i.exchange].compactMap { $0 }
                ev("ipo:\(i.key):new", "Big IPO coming: \(label)",
                   parts.joined(separator: " · ") + ". Request shares now if your broker has IPO access.", link)
            }
            let prev = s ?? IpoMark()
            state[i.key] = IpoMark(s: i.status, l: i.low, h: i.high, sh: i.shares, big: i.big || prev.big,
                                   c: !i.cornerstone.isEmpty || prev.c, o: i.open > 0 || prev.o, b: i.mentions >= 50 || prev.b, t: now)
        }

        let newFiled = r.filings.filter { state["f:" + $0.cik] == nil }
        for f in newFiled { state["f:" + f.cik] = IpoMark(t: now) }
        if filingAlertsOn && !first {
            if newFiled.count > 2 {
                ev("ipo:filed:\(Int(now.timeIntervalSince1970))", "\(newFiled.count) companies filed to go public",
                   newFiled.prefix(5).map(\.name).joined(separator: ", ") + ". Open the IPOs view to see them.",
                   "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=S-1")
            } else {
                for f in newFiled {
                    ev("ipo:filed:\(f.cik)", "\(f.name) filed to go public",
                       "\(f.form) filed with the SEC \(f.date). No price or date yet; this is the earliest public sign.", f.link)
                }
            }
        }

        // Forget IPOs not seen for 60 days.
        state = state.filter { now.timeIntervalSince($0.value.t) < 60 * 86_400 }
        d.set(try? encoder.encode(state), forKey: "ipoState")
        if first { return [] }
        saveLog(events)
        return events
    }

    static func log() -> [IpoEvent] {
        guard let data = d.data(forKey: "ipoLog") else { return [] }
        return (try? decoder.decode([IpoEvent].self, from: data)) ?? []
    }

    private static func saveLog(_ new: [IpoEvent]) {
        guard !new.isEmpty else { return }
        var seen = Set<String>()
        let all = (new + log()).filter { seen.insert($0.id).inserted }.prefix(40)
        d.set(try? encoder.encode(Array(all)), forKey: "ipoLog")
    }

    // ---------------- helpers ----------------

    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    static func fmt(_ v: Double) -> String { v == v.rounded() ? String(format: "%.0f", v) : String(format: "%.2f", v) }

    static func rangeText(_ low: Double, _ high: Double) -> String {
        if high <= 0 { return "" }
        if low <= 0 || low == high { return "$" + fmt(high) }
        return "$\(fmt(low))–$\(fmt(high))"
    }

    static func money(_ v: Double) -> String {
        if v >= 1e9 { return String(format: "$%.1fB", v / 1e9) }
        if v >= 1e6 { return String(format: "$%.0fM", v / 1e6) }
        if v > 0 { return "$" + Int(v).formatted() }
        return "size not set"
    }
}
