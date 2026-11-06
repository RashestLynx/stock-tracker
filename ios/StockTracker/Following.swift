import Foundation

/// A public figure the user follows, such as a finance YouTuber. Private investors don't have to
/// report their trades, so this tracks what they say publicly: their YouTube videos and news.
struct Followed: Codable, Identifiable, Hashable {
    var name: String
    var youtube: String          // YouTube @handle, without the @
    var newsQuery: String
    var businesses: [String]
    var links: [[String]]        // [label, url]
    var cik: String? = nil       // SEC filer number for investment firms (13F holdings)
    var id: String { name }
}

struct Video: Identifiable {
    let title: String
    let url: String
    let time: Date
    let id: String
    var description = ""
}

/// A stock someone talked about publicly. stance = "buying", "selling" or "mentioned".
struct Mention: Identifiable {
    let ticker: String
    let company: String
    let stance: String
    let count: Int
    let latest: Date
    var id: String { ticker }
}

enum Following {
    static let defaults: [Followed] = [
        Followed(name: "Jaspreet Singh", youtube: "minoritymindset",
                 newsQuery: "\"Jaspreet Singh\" (\"Minority Mindset\" OR \"Market Briefs\")",
                 businesses: ["Founder of Minority Mindset, a financial education media company and YouTube channel",
                              "CEO of Briefs Media, publisher of the free daily newsletter Market Briefs"],
                 links: [["Minority Mindset", "https://theminoritymindset.com"], ["Market Briefs", "https://www.briefs.co"]]),
        Followed(name: "Mark Tilbury", youtube: "marktilbury", newsQuery: "\"Mark Tilbury\"",
                 businesses: ["Model World Ltd, a UK company selling remote-controlled toys",
                              "Century UK Ltd",
                              "Mark Tilbury Coaching Ltd, business and investing coaching"],
                 links: [["Website", "https://www.marktilbury.com"],
                         ["UK company register", "https://find-and-update.company-information.service.gov.uk/search?q=Mark+Tilbury"]]),
    ]

    /// Famous investment firms, followed through their quarterly 13F holdings.
    static let funds: [Followed] = [
        Followed(name: "Berkshire Hathaway", youtube: "", newsQuery: "\"Berkshire Hathaway\" stocks",
                 businesses: ["Warren Buffett's company, run by Greg Abel since 2026"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1067983&type=13F"]], cik: "1067983"),
        Followed(name: "Pershing Square (Bill Ackman)", youtube: "", newsQuery: "\"Bill Ackman\" OR \"Pershing Square\"",
                 businesses: ["Bill Ackman's concentrated hedge fund"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1336528&type=13F"]], cik: "1336528"),
        Followed(name: "Duquesne (Stanley Druckenmiller)", youtube: "", newsQuery: "\"Druckenmiller\"",
                 businesses: ["Stanley Druckenmiller's family office"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1536411&type=13F"]], cik: "1536411"),
        Followed(name: "Bridgewater (Ray Dalio)", youtube: "", newsQuery: "\"Bridgewater Associates\" OR \"Ray Dalio\"",
                 businesses: ["The world's largest hedge fund, founded by Ray Dalio"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1350694&type=13F"]], cik: "1350694"),
    ]

    static func load() -> [Followed] {
        let d = UserDefaults.standard
        var list: [Followed]
        if let data = d.data(forKey: "following"), let l = try? JSONDecoder().decode([Followed].self, from: data) {
            list = l
        } else {
            list = defaults + funds
        }
        // One-time: add the famous funds to existing lists too.
        if d.integer(forKey: "following_v") < 2 {
            list += funds.filter { f in !list.contains { $0.name == f.name } }
            save(list)
            d.set(2, forKey: "following_v")
        }
        return list
    }

    struct FundHolding: Identifiable { let name: String; let value: Double; let change: String; var id: String { name } }
    struct FundSnapshot {
        let period: String
        let total: Double
        let top: [FundHolding]
        let newBuys: [String]
        let soldOut: [String]
        let crypto: [FundHolding]
    }

    /// Latest 13F with changes vs the one before.
    static func fundSnapshot(cik: String, ua: String) async -> FundSnapshot? {
        guard let filings = try? await Edgar.latest13Fs(cik: cik, ua: ua), let first = filings.first,
              let now = try? await Edgar.rows13F(first.0, ua: ua) else { return nil }
        let before = filings.count > 1 ? ((try? await Edgar.rows13F(filings[1].0, ua: ua)) ?? []) : []
        func byName(_ rows: [Edgar.Row13F]) -> [String: (Double, Double)] {
            var m: [String: (Double, Double)] = [:]
            for r in rows where !r.option { let o = m[r.name] ?? (0, 0); m[r.name] = (o.0 + r.value, o.1 + r.shares) }
            return m
        }
        let a = byName(now), b = byName(before)
        func change(_ n: String) -> String {
            let sh = a[n]?.1 ?? 0
            guard let old = b[n]?.1 else { return before.isEmpty ? "" : "New" }
            guard old > 0 else { return "" }
            let pct = (sh - old) / old * 100
            return pct > 1 ? String(format: "Added %.0f%%", pct) : pct < -1 ? String(format: "Cut %.0f%%", -pct) : "No change"
        }
        let top = a.sorted { $0.value.0 > $1.value.0 }.prefix(10).map { FundHolding(name: $0.key, value: $0.value.0, change: change($0.key)) }
        let newBuys = before.isEmpty ? [] : a.keys.filter { b[$0] == nil }.sorted { (a[$0]?.0 ?? 0) > (a[$1]?.0 ?? 0) }.prefix(6).map { $0 }
        let sold = b.keys.filter { a[$0] == nil }.sorted { (b[$0]?.0 ?? 0) > (b[$1]?.0 ?? 0) }.prefix(6).map { $0 }
        let crypto = a.filter { Crypto.assetOf($0.key) != nil }.map { FundHolding(name: $0.key, value: $0.value.0, change: change($0.key)) }
        CryptoStore.add(crypto.map { CryptoPos(fund: first.0.title, asset: Crypto.assetOf($0.name)!, product: $0.name, value: $0.value, time: Date()) })
        return FundSnapshot(period: first.1, total: a.values.reduce(0) { $0 + $1.0 }, top: Array(top),
                            newBuys: newBuys, soldOut: sold, crypto: crypto)
    }

    static func save(_ list: [Followed]) {
        if let d = try? JSONEncoder().encode(list) { UserDefaults.standard.set(d, forKey: "following") }
    }

    /// Latest uploads from their YouTube channel (public RSS feed).
    static func videos(_ f: Followed) async -> [Video] {
        let handle = f.youtube.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "@", with: "")
        guard !handle.isEmpty else { return [] }
        let key = "yt_channel_" + handle
        var channel = UserDefaults.standard.string(forKey: key)
        if channel == nil, let page = await getText("https://www.youtube.com/@\(handle)") {
            for pattern in [#""(?:channelId|externalId)":"(UC[\w-]{22})""#, #"youtube\.com/channel/(UC[\w-]{22})"#] {
                if let r = page.range(of: pattern, options: .regularExpression) {
                    let m = String(page[r])
                    if let u = m.range(of: #"UC[\w-]{22}"#, options: .regularExpression) { channel = String(m[u]); break }
                }
            }
            if let c = channel { UserDefaults.standard.set(c, forKey: key) }
        }
        let feed = channel.map { "https://www.youtube.com/feeds/videos.xml?channel_id=\($0)" }
            ?? "https://www.youtube.com/feeds/videos.xml?user=\(handle)"
        guard let data = await getData(feed), let root = XNode.parse(data) else { return [] }
        let iso = ISO8601DateFormatter()
        return root.all("entry").compactMap { e in
            let title = (e.child("title")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let link = e.child("link")?.attrs["href"] ?? ""
            guard !title.isEmpty, !link.isEmpty else { return nil }
            let vid = (e.child("videoId")?.text ?? link).trimmingCharacters(in: .whitespacesAndNewlines)
            return Video(title: title, url: link, time: iso.date(from: e.child("published")?.text ?? "") ?? Date(),
                         id: "yt:" + vid,
                         description: e.child("group")?.child("description")?.text ?? "")
        }
    }

    // ---------------- stocks they talk about ----------------
    private static var companies: [(ticker: String, name: String, norm: String)]?
    private static let stop: Set<String> = ["ALL", "NOW", "ARE", "ONE", "CAN", "BIG", "FAST", "GOOD", "BEST", "REAL",
        "SAFE", "LOW", "HIGH", "FREE", "CASH", "GAIN", "LOVE", "PLAY", "RUN", "TRUE", "OPEN", "HOME", "LIFE", "WELL",
        "KEY", "CEO", "IPO", "ETF", "USA", "YOU", "NEW", "BUY", "MAN", "HAS", "OUT", "SEE", "WAY", "HOW", "WHY", "NOT",
        "AND", "THE", "FOR", "TOP", "FBI", "IRS"]

    /// The ~600 largest U.S. companies (the SEC list is ordered by size).
    private static func bigCompanies(ua: String) async -> [(ticker: String, name: String, norm: String)] {
        if let c = companies { return c }
        guard let data = try? await Edgar.get("https://www.sec.gov/files/company_tickers.json", ua: ua),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else { return [] }
        var seen = Set<String>()
        let list = json.sorted { (Int($0.key) ?? 0) < (Int($1.key) ?? 0) }.prefix(600).compactMap { _, o -> (ticker: String, name: String, norm: String)? in
            let t = (o["ticker"] as? String ?? "").uppercased()
            let title = o["title"] as? String ?? ""
            guard !t.isEmpty, seen.insert(t).inserted else { return nil }
            return (t, title.contains(where: { $0.isLowercase }) ? title : title.capitalized, Edgar.normalizeName(title))
        }
        companies = list
        return list
    }

    /// Stocks named in their video titles/descriptions and news headlines. Public statements only:
    /// private investors don't report actual trades.
    static func mentions(videos: [Video], news: [NewsItem], ua: String) async -> [Mention] {
        let cos = await bigCompanies(ua: ua)
        guard !cos.isEmpty else { return [] }
        var byTicker: [String: String] = [:]
        for c in cos { byTicker[c.ticker] = c.name }
        let sources: [(text: String, title: String, time: Date)] =
            videos.map { ($0.title + "\n" + String($0.description.prefix(1500)), $0.title, $0.time) } +
            news.map { ($0.title, $0.title, $0.time) }
        let buy = #"(?i)\b(buying|bought|buy|adding|added|loading up|i own|my portfolio|investing in)\b"#
        let sell = #"(?i)\b(selling|sold|sell|dumping|dumped|trimming|getting out)\b"#
        var hits: [String: [(Date, String)]] = [:]
        for src in sources {
            var found = Set<String>()
            for pattern in [#"\$([A-Z]{1,5})\b"#, #"\b([A-Z]{3,5})\b"#] {
                let re = try! NSRegularExpression(pattern: pattern)
                for m in re.matches(in: src.text, range: NSRange(src.text.startIndex..., in: src.text)) {
                    guard let r = Range(m.range(at: 1), in: src.text) else { continue }
                    let t = String(src.text[r])
                    if pattern.hasPrefix(#"\$"#) || (byTicker[t] != nil && !stop.contains(t)) { found.insert(t) }
                }
            }
            let norm = " " + Edgar.normalizeName(src.text) + " "
            for c in cos where c.norm.count >= 4 && !stop.contains(c.norm) && norm.contains(" \(c.norm) ") {
                found.insert(c.ticker)
            }
            let isBuy = src.title.range(of: buy, options: .regularExpression) != nil
            let isSell = src.title.range(of: sell, options: .regularExpression) != nil
            let stance = isSell && !isBuy ? "selling" : isBuy ? "buying" : "mentioned"
            for t in found { hits[t, default: []].append((src.time, stance)) }
        }
        return hits.map { t, h in
            Mention(ticker: t, company: byTicker[t] ?? t,
                    stance: h.first(where: { $0.1 != "mentioned" })?.1 ?? "mentioned",
                    count: h.count, latest: h.map(\.0).max() ?? Date())
        }.sorted { a, b in
            if (a.stance != "mentioned") != (b.stance != "mentioned") { return a.stance != "mentioned" }
            return a.latest > b.latest
        }.prefix(15).map { $0 }
    }

    private static func getData(_ url: String) async -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.setValue("Mozilla/5.0 (iPhone) StockTracker/1.0", forHTTPHeaderField: "User-Agent")
        req.setValue("en-US", forHTTPHeaderField: "Accept-Language")
        req.timeoutInterval = 20
        guard let (d, r) = try? await URLSession.shared.data(for: req),
              (r as? HTTPURLResponse)?.statusCode ?? 0 < 300 else { return nil }
        return d
    }

    private static func getText(_ url: String) async -> String? {
        await getData(url).flatMap { String(data: $0, encoding: .utf8) }
    }
}
