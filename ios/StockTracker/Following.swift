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
    var insider: String? = nil   // SEC filer number, or EDGAR name like "Musk Elon", for company-insider filings
    var person: String? = nil    // profile id when followed from a senator, representative, insider or fund profile
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

    static let usGov = "United States"

    /// The U.S. government itself, which has taken stakes in companies since 2025.
    static let government: [Followed] = [
        Followed(name: usGov, youtube: "",
                 newsQuery: "\"equity stake\" (\"Commerce Department\" OR Pentagon OR \"U.S. government\" OR \"Energy Department\")",
                 businesses: ["Since 2025 the federal government has taken stakes in companies, mostly through the Commerce Department (CHIPS Act), the Pentagon and the Energy Department",
                              "There's no official list; these come from company announcements and news reports"],
                 links: [["Cato Institute: government equity stakes", "https://www.cato.org/blog/rare-earths-vulcan-government-equity-stakes"],
                         ["Commerce CHIPS announcements", "https://www.nist.gov/chips"]]),
    ]

    /// Other countries, followed through their government investment funds' quarterly 13F filings,
    /// which list the U.S. stocks they hold.
    static let countries: [Followed] = [
        Followed(name: "Norway", youtube: "", newsQuery: "\"Norges Bank Investment Management\" OR \"Norway wealth fund\" stake",
                 businesses: ["Norway's oil fund (Government Pension Fund Global), run by Norges Bank, one of the world's largest stock owners"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1374170&type=13F"]], cik: "1374170"),
        Followed(name: "Saudi Arabia", youtube: "", newsQuery: "\"Public Investment Fund\" Saudi stake",
                 businesses: ["Public Investment Fund (PIF), Saudi Arabia's sovereign wealth fund"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1767640&type=13F"]], cik: "1767640"),
        Followed(name: "United Arab Emirates", youtube: "", newsQuery: "Mubadala stake",
                 businesses: ["Mubadala Investment Company, a sovereign wealth fund of Abu Dhabi"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1704268&type=13F"]], cik: "1704268"),
        Followed(name: "Switzerland", youtube: "", newsQuery: "\"Swiss National Bank\" stocks",
                 businesses: ["Swiss National Bank, the central bank, which invests part of its reserves in U.S. stocks"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1582202&type=13F"]], cik: "1582202"),
        Followed(name: "South Korea", youtube: "", newsQuery: "\"Korea Investment Corporation\" stake",
                 businesses: ["Korea Investment Corporation, South Korea's sovereign wealth fund"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1441689&type=13F"]], cik: "1441689"),
        Followed(name: "Canada", youtube: "", newsQuery: "\"CPP Investments\" stake",
                 businesses: ["CPP Investments, which manages the national Canada Pension Plan"],
                 links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1283718&type=13F"]], cik: "1283718"),
    ]

    /// Flags shown as the avatar for countries.
    static let flags: [String: String] = [usGov: "🇺🇸", "Norway": "🇳🇴", "Saudi Arabia": "🇸🇦", "United Arab Emirates": "🇦🇪", "Switzerland": "🇨🇭", "South Korea": "🇰🇷", "Canada": "🇨🇦"]

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

    /// Famous rich people in the public eye.
    static let publicFigures: [Followed] = [
        Followed(name: "Elon Musk", youtube: "", newsQuery: "\"Elon Musk\" (Tesla OR SpaceX OR xAI) stock",
                 businesses: ["CEO of Tesla and SpaceX; founder of xAI"],
                 links: [["SEC insider filings", "https://www.sec.gov/cgi-bin/own-disp?action=getowner&CIK=1494730"]], insider: "1494730"),
        Followed(name: "Sam Altman", youtube: "", newsQuery: "\"Sam Altman\" (stake OR investment OR shares)",
                 businesses: ["CEO of OpenAI (holds no OpenAI equity)", "Investor in Helion, Stripe, Oklo and hundreds of startups"],
                 links: [], insider: "Altman Samuel H"),
        Followed(name: "Donald Trump", youtube: "", newsQuery: "\"Trump\" (\"278-T\" OR \"financial disclosure\" OR \"stock trades\")",
                 businesses: ["President of the United States", "Largest shareholder of Trump Media & Technology Group (DJT)"],
                 links: [["Office of Government Ethics filings", "https://www.oge.gov/web/OGE.nsf/Officials%20Individual%20Disclosures%20Search%20Collection?OpenForm"]],
                 insider: "Trump Donald J."),
        Followed(name: "Jeff Bezos", youtube: "", newsQuery: "\"Jeff Bezos\" (Amazon OR shares OR stake)",
                 businesses: ["Founder and executive chair of Amazon; founder of Blue Origin"], links: [], insider: "Bezos Jeffrey P"),
        Followed(name: "Mark Zuckerberg", youtube: "", newsQuery: "\"Zuckerberg\" (shares OR stake OR sold)",
                 businesses: ["Founder and CEO of Meta Platforms"], links: [], insider: "Zuckerberg Mark"),
        Followed(name: "Jensen Huang", youtube: "", newsQuery: "\"Jensen Huang\" (shares OR sold OR stake)",
                 businesses: ["Co-founder and CEO of Nvidia"], links: [], insider: "Huang Jen Hsun"),
        Followed(name: "Bill Gates", youtube: "", newsQuery: "\"Bill Gates\" (stake OR shares OR foundation)",
                 businesses: ["Co-founder of Microsoft; his foundation trust reports its stock holdings"],
                 links: [["Foundation trust 13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1166559&type=13F"]], cik: "1166559"),
    ]

    struct InsiderFiling: Identifiable {
        let date: String; let ticker: String; let company: String; let lines: [String]; let link: String
        var ownedAfter: Double = 0; var price: Double = 0
        var id: String { link }
    }

    /// Newest share count per company from their Form 4s.
    static func latestHoldings(_ filings: [InsiderFiling]) -> [InsiderFiling] {
        var seen = Set<String>()
        return filings.filter { $0.ownedAfter > 0 && !$0.ticker.isEmpty && seen.insert($0.ticker).inserted }
    }

    /// Resolve an SEC filer number from a number or an EDGAR name ("Musk Elon"). Cached.
    static func insiderCik(_ f: Followed, ua: String) async -> String? {
        guard let q = f.insider?.trimmingCharacters(in: .whitespaces), !q.isEmpty else { return nil }
        if q.allSatisfy(\.isNumber) { return q }
        let key = "insider_cik_" + q
        if let c = UserDefaults.standard.string(forKey: key) { return c }
        guard let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let data = try? await Edgar.get("https://efts.sec.gov/LATEST/search-index?keysTyped=\(enc)", ua: ua),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hits = (j["hits"] as? [String: Any])?["hits"] as? [[String: Any]] else { return nil }
        let first = q.lowercased().split(separator: " ").first.map(String.init) ?? q.lowercased()
        for h in hits {
            let ent = ((h["_source"] as? [String: Any])?["entity"] as? String ?? "").lowercased()
            if ent.hasPrefix(first), let id = h["_id"] as? String {
                let cik = String(id.drop(while: { $0 == "0" }))
                UserDefaults.standard.set(cik, forKey: key)
                return cik
            }
        }
        return nil
    }

    /// Their latest SEC Form 4 insider filings (any company).
    static func insiderFilings(_ f: Followed, ua: String, max: Int = 6) async -> [InsiderFiling] {
        guard let cik = await insiderCik(f, ua: ua), let n = Int(cik),
              let data = try? await Edgar.get(String(format: "https://data.sec.gov/submissions/CIK%010d.json", n), ua: ua),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let r = (j["filings"] as? [String: Any])?["recent"] as? [String: Any],
              let forms = r["form"] as? [String], let accs = r["accessionNumber"] as? [String],
              let dates = r["filingDate"] as? [String] else { return [] }
        var out: [InsiderFiling] = []
        for i in forms.indices where (forms[i] == "4" || forms[i] == "4/A") && i < accs.count {
            let acc = accs[i]
            let link = "https://www.sec.gov/Archives/edgar/data/\(n)/\(acc.replacingOccurrences(of: "-", with: ""))/\(acc)-index.htm"
            let filing = Filing(accession: acc, form: "4", title: f.name, link: link)
            guard let info = try? await Edgar.parseForm4(filing, ua: ua) else { continue }
            let lines = Edgar.summarize(info.trades)
            out.append(InsiderFiling(date: i < dates.count ? dates[i] : "", ticker: info.ticker, company: info.issuer,
                                     lines: lines.isEmpty ? ["Options/derivatives only"] : lines, link: link,
                                     ownedAfter: info.ownedAfter, price: info.lastPrice))
            if out.count >= max { break }
        }
        return out
    }

    /// A senator, representative, insider or fund to follow from their profile.
    static func fromProfile(_ t: PersonTarget, name: String, subtitle: String) -> Followed {
        let id = String(t.key.drop(while: { $0 != ":" }).dropFirst())
        if t.key.hasPrefix("ins:") {
            return Followed(name: name, youtube: "", newsQuery: "\"\(name)\" (shares OR stock)", businesses: [subtitle], links: [],
                            insider: id, person: t.key)
        }
        if t.key.hasPrefix("fund:") {
            return Followed(name: name, youtube: "", newsQuery: "\"\(name)\" holdings", businesses: [subtitle],
                            links: [["13F filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=\(id)&type=13F"]],
                            cik: id, person: t.key)
        }
        return Followed(name: name, youtube: "", newsQuery: "\"\(name)\" stock trades", businesses: [subtitle], links: [],
                        person: t.key.isEmpty ? nil : t.key)
    }

    static func isFollowing(_ t: PersonTarget) -> Bool {
        load().contains { (!t.key.isEmpty && $0.person == t.key) || $0.name.caseInsensitiveCompare(t.name) == .orderedSame }
    }

    static func load() -> [Followed] {
        let d = UserDefaults.standard
        var list: [Followed]
        if let data = d.data(forKey: "following"), let l = try? JSONDecoder().decode([Followed].self, from: data) {
            list = l
        } else {
            list = defaults + funds + publicFigures + government + countries
        }
        // One-time: add new built-in groups to existing lists too.
        let v = d.integer(forKey: "following_v")
        if v < 5 {
            let add = (v < 2 ? funds : []) + (v < 3 ? publicFigures : []) + (v < 4 ? government : []) + countries
            list += add.filter { f in !list.contains { $0.name == f.name } }
            save(list)
            d.set(5, forKey: "following_v")
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
        // YouTube's RSS feed has started returning 404; read the channel's Videos page instead when it does.
        guard let data = await getData(feed), let root = XNode.parse(data) else { return await videosFromPage(handle) }
        let iso = ISO8601DateFormatter()
        let fromFeed: [Video] = root.all("entry").compactMap { e in
            let title = (e.child("title")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let link = e.child("link")?.attrs["href"] ?? ""
            guard !title.isEmpty, !link.isEmpty else { return nil }
            let vid = (e.child("videoId")?.text ?? link).trimmingCharacters(in: .whitespacesAndNewlines)
            return Video(title: title, url: link, time: iso.date(from: e.child("published")?.text ?? "") ?? Date(),
                         id: "yt:" + vid,
                         description: e.child("group")?.child("description")?.text ?? "")
        }
        return fromFeed.isEmpty ? await videosFromPage(handle) : fromFeed
    }

    /// The latest uploads listed on the channel's Videos page (no descriptions; dates are approximate).
    private static func videosFromPage(_ handle: String) async -> [Video] {
        // YouTube only lists a channel's videos in the page data it sends to desktop browsers.
        guard let page = await getText("https://www.youtube.com/@\(handle)/videos",
            userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        else { return [] }
        let idRe = try! NSRegularExpression(pattern: #""contentId":"([\w-]{11})","contentType":"LOCKUP_CONTENT_TYPE_VIDEO""#)
        let titleRe = try! NSRegularExpression(pattern: #""lockupMetadataViewModel":\{"title":\{"content":"((?:[^"\\]|\\.)*)""#)
        let agoRe = try! NSRegularExpression(pattern: #""accessibilityLabel":"(?:Streamed )?(\d+) (second|minute|hour|day|week|month|year)s? ago""#)
        func first(_ re: NSRegularExpression, _ s: String) -> [String]? {
            guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
            return (1..<m.numberOfRanges).compactMap { Range(m.range(at: $0), in: s).map { String(s[$0]) } }
        }
        let units: [String: TimeInterval] = ["second": 1, "minute": 60, "hour": 3600, "day": 86_400,
                                             "week": 7 * 86_400, "month": 30 * 86_400, "year": 365 * 86_400]
        let now = Date()
        var out: [Video] = []
        for chunk in page.components(separatedBy: #""lockupViewModel":{"#).dropFirst() {
            guard let id = first(idRe, chunk)?.first, let raw = first(titleRe, chunk)?.first else { continue }
            // Undo the JSON escaping in the title.
            let title = (try? JSONSerialization.jsonObject(with: Data("\"\(raw)\"".utf8), options: .fragmentsAllowed) as? String) ?? raw
            var time = now.addingTimeInterval(-Double(out.count) * 60)   // keep page order when there's no date
            if let a = first(agoRe, chunk), a.count == 2, let n = Double(a[0]), let u = units[a[1]] {
                time = now.addingTimeInterval(-n * u)
            }
            guard !out.contains(where: { $0.id == "yt:" + id }) else { continue }
            out.append(Video(title: title, url: "https://www.youtube.com/watch?v=\(id)", time: time, id: "yt:" + id, description: ""))
        }
        return out
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

    private static func getData(_ url: String, userAgent: String = "Mozilla/5.0 (iPhone) StockTracker/1.0") async -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("en-US", forHTTPHeaderField: "Accept-Language")
        req.timeoutInterval = 20
        guard let (d, r) = try? await URLSession.shared.data(for: req),
              (r as? HTTPURLResponse)?.statusCode ?? 0 < 300 else { return nil }
        return d
    }

    private static func getText(_ url: String, userAgent: String = "Mozilla/5.0 (iPhone) StockTracker/1.0") async -> String? {
        await getData(url, userAgent: userAgent).flatMap { String(data: $0, encoding: .utf8) }
    }
}
