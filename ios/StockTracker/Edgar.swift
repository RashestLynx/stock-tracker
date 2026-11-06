import Foundation

struct Filing {
    let accession: String
    let form: String
    let title: String
    let link: String
    var updated: String = ""
    var summary: String = ""
}

struct Trade {
    let code: String
    let shares: Double
    let price: Double
    var value: Double { shares * price }
}

struct Form4 {
    let ticker: String
    let issuer: String
    let owners: [String]
    var ownerCiks: [String] = []
    let roles: [String]
    let trades: [Trade]
}

/// SEC EDGAR access: insider trades (Form 4) and fund holdings (13F).
enum Edgar {
    static let txCodes: [String: String] = [
        "P": "Bought (open market)", "S": "Sold (open market)",
        "A": "Award/grant", "M": "Option exercise", "F": "Tax withholding",
        "G": "Gift", "D": "Disposed to issuer", "C": "Conversion",
        "X": "Option exercise", "J": "Other",
    ]

    private static var lastRequest = Date.distantPast

    /// GET with the SEC-required User-Agent, kept under the SEC's 10 requests/second limit.
    static func get(_ url: String, ua: String) async throws -> Data {
        let wait = 0.15 - Date().timeIntervalSince(lastRequest)
        if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        lastRequest = Date()
        guard let u = URL(string: url) else { throw AppError("Bad URL") }
        var req = URLRequest(url: u)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 30
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw AppError("SEC returned HTTP \(code)") }
        return data
    }

    /// Latest filings of one form type ("4" or "13F-HR"), newest first, one per accession.
    static func feed(_ form: String, ua: String) async throws -> [Filing] {
        let encoded = form.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? form
        let url = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=\(encoded)"
            + "&company=&dateb=&owner=include&start=0&count=100&output=atom"
        guard let root = XNode.parse(try await get(url, ua: ua)) else { throw AppError("Couldn't read SEC feed") }
        var seen = Set<String>()
        var out: [Filing] = []
        for e in root.all("entry") {
            let id = e.child("id")?.text ?? ""
            guard let r = id.range(of: "accession-number=") else { continue }
            let acc = String(id[r.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
            let term = e.child("category")?.attrs["term"] ?? ""
            // Each Form 4 is listed twice (reporting person + company); keep the first.
            guard term == form, !seen.contains(acc) else { continue }
            seen.insert(acc)
            out.append(Filing(
                accession: acc, form: term,
                title: (e.child("title")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                link: e.child("link")?.attrs["href"] ?? "",
                updated: e.child("updated")?.text ?? "",
                summary: e.child("summary")?.text ?? ""
            ))
        }
        return out
    }

    private static func folder(_ f: Filing) -> String {
        guard let slash = f.link.lastIndex(of: "/") else { return f.link }
        return String(f.link[...slash])
    }

    /// File names in a filing's folder.
    private static func files(_ f: Filing, ua: String) async throws -> [String] {
        let data = try await get(folder(f) + "index.json", ua: ua)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let dir = json?["directory"] as? [String: Any]
        let items = dir?["item"] as? [[String: Any]] ?? []
        return items.compactMap { $0["name"] as? String }
    }

    /// Downloads a Form 4's XML and pulls out who traded what.
    static func parseForm4(_ f: Filing, ua: String) async throws -> Form4? {
        guard let xmlName = try await files(f, ua: ua).first(where: { $0.lowercased().hasSuffix(".xml") }),
              let root = XNode.parse(try await get(folder(f) + xmlName, ua: ua)) else { return nil }

        var owners: [String] = []
        var ownerCiks: [String] = []
        var roles: [String] = []
        for o in root.all("reportingOwner") {
            if let n = o.text("reportingOwnerId/rptOwnerName") { owners.append(n) }
            if let c = o.text("reportingOwnerId/rptOwnerCik") {
                ownerCiks.append(String(c.drop(while: { $0 == "0" })))
            }
            guard let rel = o.child("reportingOwnerRelationship") else { continue }
            let flag: (String) -> Bool = { ["1", "true"].contains((rel.text($0) ?? "").lowercased()) }
            if let t = rel.text("officerTitle"), !t.isEmpty { roles.append(t) }
            else if flag("isDirector") { roles.append("Director") }
            else if flag("isTenPercentOwner") { roles.append("10% owner") }
        }

        let trades: [Trade] = (root.child("nonDerivativeTable")?.all("nonDerivativeTransaction") ?? []).map { t in
            Trade(
                code: t.text("transactionCoding/transactionCode") ?? "",
                shares: Double(t.text("transactionAmounts/transactionShares/value") ?? "") ?? 0,
                price: Double(t.text("transactionAmounts/transactionPricePerShare/value") ?? "") ?? 0
            )
        }
        return Form4(
            ticker: (root.text("issuer/issuerTradingSymbol") ?? "").uppercased(),
            issuer: root.text("issuer/issuerName") ?? "",
            owners: owners, ownerCiks: ownerCiks, roles: roles, trades: trades
        )
    }

    /// One readable line per transaction type.
    static func summarize(_ trades: [Trade]) -> [String] {
        var order: [String] = []
        var agg: [String: (shares: Double, value: Double)] = [:]
        for t in trades {
            if agg[t.code] == nil { order.append(t.code); agg[t.code] = (0, 0) }
            agg[t.code]!.shares += t.shares
            agg[t.code]!.value += t.value
        }
        return order.map { code in
            let a = agg[code]!
            let label = txCodes[code] ?? "Code \(code)"
            let shares = Format.number(a.shares)
            if a.shares > 0 && a.value > 0 {
                return "\(label): \(shares) shares @ ~$\(Format.number(a.value / a.shares, decimals: 2)) ($\(Format.number(a.value)))"
            }
            return "\(label): \(shares) shares"
        }
    }

    /// A 13F's largest stock positions: (issuer name, value in dollars), biggest first.
    struct Row13F { let name: String; let value: Double; let shares: Double; let option: Bool }

    /// Every row of a 13F holdings table.
    static func rows13F(_ f: Filing, ua: String) async throws -> [Row13F] {
        guard let table = try await files(f, ua: ua).first(where: {
            let l = $0.lowercased(); return l.hasSuffix(".xml") && l != "primary_doc.xml"
        }), let root = XNode.parse(try await get(folder(f) + table, ua: ua)) else { return [] }
        return root.all("infoTable").compactMap { row in
            guard let name = row.text("nameOfIssuer") else { return nil }
            return Row13F(name: name, value: Double(row.text("value") ?? "") ?? 0,
                          shares: Double(row.text("shrsOrPrnAmt/sshPrnamt") ?? "") ?? 0,
                          option: row.child("putCall") != nil)
        }
    }

    static func top13F(_ rows: [Row13F], top: Int = 20) -> [(String, Double)] {
        var totals: [String: Double] = [:]
        for r in rows where !r.option { totals[r.name, default: 0] += r.value }
        return totals.sorted { $0.value > $1.value }.prefix(top).map { ($0.key, $0.value) }
    }

    /// The newest 13F-HR filings for a filer, newest first, with their report period.
    static func latest13Fs(cik: String, ua: String, count: Int = 2) async throws -> [(Filing, String)] {
        guard let n = Int(cik) else { return [] }
        let data = try await get(String(format: "https://data.sec.gov/submissions/CIK%010d.json", n), ua: ua)
        guard let j = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let r = (j["filings"] as? [String: Any])?["recent"] as? [String: Any],
              let forms = r["form"] as? [String], let accs = r["accessionNumber"] as? [String] else { return [] }
        let periods = r["reportDate"] as? [String] ?? []
        let name = j["name"] as? String ?? ""
        var out: [(Filing, String)] = []
        for i in forms.indices where forms[i] == "13F-HR" && i < accs.count {
            let acc = accs[i]
            let folder = "https://www.sec.gov/Archives/edgar/data/\(n)/\(acc.replacingOccurrences(of: "-", with: ""))/"
            out.append((Filing(accession: acc, form: "13F-HR", title: name, link: folder + "\(acc)-index.htm"),
                        i < periods.count ? periods[i] : ""))
            if out.count >= count { break }
        }
        return out
    }

    static func parse13FHoldings(_ f: Filing, ua: String, top: Int = 20) async throws -> [(String, Double)] {
        guard let table = try await files(f, ua: ua).first(where: {
            let l = $0.lowercased(); return l.hasSuffix(".xml") && l != "primary_doc.xml"
        }), let root = XNode.parse(try await get(folder(f) + table, ua: ua)) else { return [] }
        var totals: [String: Double] = [:]
        for row in root.all("infoTable") where row.child("putCall") == nil {
            guard let name = row.text("nameOfIssuer") else { continue }
            totals[name, default: 0] += Double(row.text("value") ?? "") ?? 0
        }
        return totals.sorted { $0.value > $1.value }.prefix(top).map { ($0.key, $0.value) }
    }

    /// SEC's official list of company names -> tickers, keyed by normalizeName.
    static func companyTickers(ua: String) async throws -> [String: String] {
        let data = try await get("https://www.sec.gov/files/company_tickers.json", ua: ua)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] ?? [:]
        // Keys are "0", "1", ... ordered by company size; keep the first ticker per name.
        let rows = json.sorted { (Int($0.key) ?? 0) < (Int($1.key) ?? 0) }
        var map: [String: String] = [:]
        for (_, o) in rows {
            let norm = normalizeName(o["title"] as? String ?? "")
            if !norm.isEmpty && map[norm] == nil { map[norm] = (o["ticker"] as? String ?? "").uppercased() }
        }
        return map
    }

    /// Ticker -> company name, from the SEC's official company list.
    static func tickerNames(ua: String) async throws -> [String: String] {
        let data = try await get("https://www.sec.gov/files/company_tickers.json", ua: ua)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] ?? [:]
        var map: [String: String] = [:]
        for o in json.values {
            let t = (o["ticker"] as? String ?? "").uppercased()
            if !t.isEmpty, map[t] == nil { map[t] = o["title"] as? String ?? "" }
        }
        return map
    }

    /// Ticker -> SEC CIK number, from the SEC's official company list.
    static func tickerCiks(ua: String) async throws -> [String: Int] {
        let data = try await get("https://www.sec.gov/files/company_tickers.json", ua: ua)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] ?? [:]
        var map: [String: Int] = [:]
        for o in json.values {
            let t = (o["ticker"] as? String ?? "").uppercased()
            if !t.isEmpty, map[t] == nil, let cik = (o["cik_str"] as? NSNumber)?.intValue { map[t] = cik }
        }
        return map
    }

    private static let noise: Set<String> = [
        "INC", "INCORPORATED", "CORP", "CORPORATION", "CO", "COMPANY", "LTD", "LIMITED",
        "PLC", "LLC", "LP", "NV", "SA", "AG", "SE", "THE", "CL", "CLASS", "NEW", "DEL", "COM",
        "SHS", "ORD", "ADR", "SPONSORED", "REIT",
    ]

    /// "Meta Platforms, Inc." and "META PLATFORMS INC CL A" both become "META PLATFORMS".
    static func normalizeName(_ name: String) -> String {
        let cleaned = name.uppercased().map { $0.isLetter || $0.isNumber ? $0 : " " }
        var words = String(cleaned).split(separator: " ").map(String.init).filter { !noise.contains($0) }
        while words.count > 1, let last = words.last, last.count == 1 { words.removeLast() }
        return words.joined(separator: " ")
    }
}
