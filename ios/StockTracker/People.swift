import Foundation

/// Who was tapped. `key` is the person id stored on trades (see People.congressKey).
struct PersonTarget: Identifiable {
    let key: String
    let name: String
    let category: Category
    var role: String = ""
    var id: String { key.isEmpty ? "name:" + name : key }
}

struct RecordTrade: Identifiable {
    let ticker: String
    let type: String
    let amount: String
    let date: String
    let perfPct: Double?
    let id = UUID()
}

struct PersonProfile {
    var name: String
    var subtitle: String
    var facts: [(String, String)] = []
    var committees: [String] = []
    var record: [RecordTrade] = []
    var links: [(String, String)] = []
    var note = ""
}

/// Investor profiles built only from public information about each person's public role:
///  - Congress: official legislator data (unitedstates/congress-legislators, public domain) and
///    their disclosed trades with performance since (Bargo)
///  - Insiders and funds: their SEC filing record
/// Personal details such as home addresses that sometimes appear in filings are never shown.
enum People {
    private static let legis = "https://unitedstates.github.io/congress-legislators/"
    private static var cache: [String: PersonProfile] = [:]

    static func congressKey(slug: String, chamber: String, state: String, member: String) -> String {
        "cg:" + [slug, chamber, state, member].map { $0.replacingOccurrences(of: "|", with: " ") }.joined(separator: "|")
    }

    static func load(_ t: PersonTarget, ua: String, bargoKey: String) async -> PersonProfile {
        if let c = cache[t.id] { return c }
        let p: PersonProfile
        if t.key.hasPrefix("cg:") { p = await congress(t, bargoKey: bargoKey) }
        else if t.key.hasPrefix("ins:") { p = await insider(t, ua: ua) }
        else if t.key.hasPrefix("fund:") { p = await fund(t, ua: ua) }
        else {
            p = PersonProfile(name: t.name, subtitle: t.role.isEmpty ? t.category.label : t.role,
                              note: "This entry was saved before profiles were added, so only its activity below is available.")
        }
        cache[t.id] = p
        return p
    }

    // ---------------- Congress ----------------
    private static func congress(_ t: PersonTarget, bargoKey: String) async -> PersonProfile {
        let parts = String(t.key.dropFirst(3)).components(separatedBy: "|")
        let slug = parts.count > 0 ? parts[0] : ""
        let chamber = parts.count > 1 ? parts[1] : ""
        let state = parts.count > 2 ? String(parts[2].prefix(2)).uppercased() : ""
        let member = parts.count > 3 ? parts[3] : t.name
        var p = PersonProfile(name: member, subtitle: chamber == "senate" ? "U.S. Senator" : "U.S. Representative")

        if let leg = await findLegislator(member: member, chamber: chamber, state: state),
           let terms = leg["terms"] as? [[String: Any]], let cur = terms.last {
            let n = leg["name"] as? [String: Any] ?? [:]
            if let full = n["official_full"] as? String, !full.isEmpty { p.name = full }
            let party = cur["party"] as? String ?? ""
            let st = cur["state"] as? String ?? ""
            let isSen = cur["type"] as? String == "sen"
            var sub = [party, stateName(st)]
            if !isSen, let d = cur["district"] as? Int { sub[1] += " · District \(d)" }
            p.subtitle = (isSen ? "U.S. Senator" : "U.S. Representative") + " · " + sub.filter { !$0.isEmpty }.joined(separator: " · ")
            let years = terms.compactMap { Int(String(($0["start"] as? String ?? "").prefix(4))) }
            let sen = terms.filter { $0["type"] as? String == "sen" }.count
            let rep = terms.count - sen
            if !party.isEmpty { p.facts.append(("Party", party)) }
            p.facts.append(("State", stateName(st)))
            if let first = years.min() { p.facts.append(("In Congress since", String(first))) }
            p.facts.append(("Terms served", [sen > 0 ? "\(sen) in the Senate" : nil, rep > 0 ? "\(rep) in the House" : nil]
                .compactMap { $0 }.joined(separator: ", ")))
            if let end = cur["end"] as? String { p.facts.append(("Current term ends", String(end.prefix(4)))) }
            if let url = cur["url"] as? String, !url.isEmpty { p.links.append(("Official website", url)) }
            if let bio = (leg["id"] as? [String: Any])?["bioguide"] as? String {
                p.links.append(("Congressional biography", "https://bioguide.congress.gov/search/bio/\(bio)"))
                p.committees = await committees(bioguide: bio)
            }
        }

        p.record = await memberTrades(member: member, slug: slug, apiKey: bargoKey)
        let buys = p.record.filter {
            let ty = $0.type.lowercased()
            return $0.perfPct != nil && (ty.contains("buy") || ty.contains("purchase"))
        }
        if !buys.isEmpty {
            let up = buys.filter { ($0.perfPct ?? 0) > 0 }.count
            let avg = buys.compactMap(\.perfPct).reduce(0, +) / Double(buys.count)
            p.facts.append(("Recent buys up since", "\(up) of \(buys.count)"))
            p.facts.append(("Average move since buy", String(format: "%+.1f%%", avg)))
        }
        p.links.append(("Trades on Bargo", Congress.sourceURL))
        return p
    }

    private static func findLegislator(member: String, chamber: String, state: String) async -> [String: Any]? {
        guard let data = await cachedGet(legis + "legislators-current.json", file: "legislators.json"),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        let words = fold(member).split(separator: " ").map(String.init).filter { $0.count > 1 }
        let type = chamber == "senate" ? "sen" : "rep"
        var best: [String: Any]?
        var bestScore = 0
        for o in arr {
            guard let terms = o["terms"] as? [[String: Any]], let cur = terms.last else { continue }
            if !chamber.isEmpty && cur["type"] as? String != type { continue }
            if !state.isEmpty && cur["state"] as? String != state { continue }
            let n = o["name"] as? [String: Any] ?? [:]
            let last = fold(n["last"] as? String ?? "")
            guard !last.isEmpty, words.contains(where: { $0 == last || last.hasSuffix($0) }) else { continue }
            var score = 2
            if words.contains(fold(n["first"] as? String ?? "")) || words.contains(fold(n["nickname"] as? String ?? "")) { score += 2 }
            if score > bestScore { best = o; bestScore = score }
        }
        return best
    }

    private static func committees(bioguide: String) async -> [String] {
        guard let cd = await cachedGet(legis + "committees-current.json", file: "committees.json"),
              let list = try? JSONSerialization.jsonObject(with: cd) as? [[String: Any]],
              let md = await cachedGet(legis + "committee-membership-current.json", file: "committee_membership.json"),
              let membership = try? JSONSerialization.jsonObject(with: md) as? [String: [[String: Any]]] else { return [] }
        var names: [String: String] = [:]
        for c in list { if let id = c["thomas_id"] as? String { names[id] = c["name"] as? String ?? id } }
        var out: [String] = []
        for (id, members) in membership {
            guard let name = names[id] else { continue }   // top-level committees only
            for m in members where m["bioguide"] as? String == bioguide {
                let title = m["title"] as? String ?? ""
                out.append(name + (title.isEmpty ? "" : " (\(title))"))
            }
        }
        return out.sorted()
    }

    private static func memberTrades(member: String, slug: String, apiKey: String) async -> [RecordTrade] {
        guard let last = member.split(separator: " ").last,
              let q = String(last).addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://www.bargo.ai/free-apis/congress/v1/trades?limit=25&member=\(q)") else { return [] }
        var req = URLRequest(url: url)
        req.setValue("StockTracker-iOS/1.0", forHTTPHeaderField: "User-Agent")
        if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-Api-Key") }
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode ?? 0 < 300,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = json["trades"] as? [[String: Any]] else { return [] }
        return rows.compactMap { o in
            if !slug.isEmpty, o["member_slug"] as? String != slug { return nil }
            return RecordTrade(ticker: (o["ticker"] as? String ?? "").uppercased(), type: o["type"] as? String ?? "",
                               amount: o["amount_range"] as? String ?? "", date: o["transaction_date"] as? String ?? "",
                               perfPct: (o["perf_pct"] as? NSNumber)?.doubleValue)
        }
    }

    // ---------------- Insiders & funds ----------------
    private static func submissions(_ cik: String, ua: String) async -> [String: Any]? {
        guard let n = Int(cik),
              let data = try? await Edgar.get(String(format: "https://data.sec.gov/submissions/CIK%010d.json", n), ua: ua)
        else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func recent(_ j: [String: Any]) -> (forms: [String], dates: [String]) {
        let r = (j["filings"] as? [String: Any])?["recent"] as? [String: Any]
        return (r?["form"] as? [String] ?? [], r?["filingDate"] as? [String] ?? [])
    }

    private static func insider(_ t: PersonTarget, ua: String) async -> PersonProfile {
        let cik = String(t.key.dropFirst(4))
        var p = PersonProfile(name: t.name, subtitle: t.role.isEmpty ? "Company insider" : t.role)
        if let j = await submissions(cik, ua: ua) {
            if let n = j["name"] as? String, !n.isEmpty { p.name = titleCase(n) }
            let (forms, dates) = recent(j)
            p.facts.append(("Insider trade reports (recent)", String(forms.filter { $0 == "4" || $0 == "4/A" }.count)))
            if let oldest = dates.last { p.facts.append(("Filings on record since", String(oldest.prefix(4)))) }
        }
        p.links = [("Every company they're an insider at", "https://www.sec.gov/cgi-bin/own-disp?action=getowner&CIK=\(cik)"),
                   ("All SEC filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=\(cik)")]
        return p
    }

    private static func fund(_ t: PersonTarget, ua: String) async -> PersonProfile {
        let cik = String(t.key.dropFirst(5))
        var p = PersonProfile(name: t.name, subtitle: "Investment manager")
        if let j = await submissions(cik, ua: ua) {
            if let n = j["name"] as? String, !n.isEmpty { p.name = titleCase(n) }
            // A firm's business address is its office, which is public.
            if let b = (j["addresses"] as? [String: Any])?["business"] as? [String: Any] {
                let loc = [b["city"] as? String, b["stateOrCountryDescription"] as? String]
                    .compactMap { $0 }.filter { !$0.isEmpty }.map(titleCase).joined(separator: ", ")
                if !loc.isEmpty { p.facts.append(("Headquarters", loc)) }
            }
            let (forms, dates) = recent(j)
            let idx = forms.indices.filter { forms[$0].hasPrefix("13F-HR") }
            if !idx.isEmpty { p.facts.append(("Quarterly 13F reports on record", String(idx.count))) }
            if let i = idx.last, i < dates.count { p.facts.append(("Reporting holdings since", String(dates[i].prefix(4)))) }
        }
        p.links = [("13F filings on SEC", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=\(cik)&type=13F")]
        return p
    }

    // ---------------- helpers ----------------
    private static func cachedGet(_ url: String, file: String) async -> Data? {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let path = dir.appendingPathComponent(file)
        if let attrs = try? FileManager.default.attributesOfItem(atPath: path.path),
           let m = attrs[.modificationDate] as? Date, Date().timeIntervalSince(m) < 7 * 86_400,
           let d = try? Data(contentsOf: path) { return d }
        guard let u = URL(string: url), let (d, r) = try? await URLSession.shared.data(from: u),
              (r as? HTTPURLResponse)?.statusCode ?? 0 < 300 else { return nil }
        try? d.write(to: path, options: .atomic)
        return d
    }

    private static func fold(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .init(identifier: "en_US"))
            .lowercased().map { $0.isLetter || $0 == " " ? $0 : " " }
            .reduce(into: "") { $0.append($1) }
            .split(separator: " ").joined(separator: " ")
    }

    private static func titleCase(_ s: String) -> String { s.contains(where: { $0.isLowercase }) ? s : s.capitalized }

    private static let states: [String: String] = [
        "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas", "CA": "California", "CO": "Colorado",
        "CT": "Connecticut", "DE": "Delaware", "FL": "Florida", "GA": "Georgia", "HI": "Hawaii", "ID": "Idaho",
        "IL": "Illinois", "IN": "Indiana", "IA": "Iowa", "KS": "Kansas", "KY": "Kentucky", "LA": "Louisiana",
        "ME": "Maine", "MD": "Maryland", "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota",
        "MS": "Mississippi", "MO": "Missouri", "MT": "Montana", "NE": "Nebraska", "NV": "Nevada",
        "NH": "New Hampshire", "NJ": "New Jersey", "NM": "New Mexico", "NY": "New York", "NC": "North Carolina",
        "ND": "North Dakota", "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon", "PA": "Pennsylvania",
        "RI": "Rhode Island", "SC": "South Carolina", "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas",
        "UT": "Utah", "VT": "Vermont", "VA": "Virginia", "WA": "Washington", "WV": "West Virginia",
        "WI": "Wisconsin", "WY": "Wyoming", "DC": "District of Columbia", "PR": "Puerto Rico",
    ]
    private static func stateName(_ c: String) -> String { states[c] ?? c }
}
