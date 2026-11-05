import Foundation

/// What was tapped: a stock, plus (optionally) the trade or filing it came from.
struct StockTarget: Identifiable {
    let ticker: String
    let company: String
    var heading: String = ""     // "This trade" / "This filing", or "" for none
    var who: String = ""
    var lines: [String] = []
    var link: String = ""
    var tradePrice: Double? = nil
    var id: String { ticker + "|" + heading + "|" + link }
}

/// Everything shown in a stock's summary sheet. Blank fields are hidden.
struct StockSummary {
    var name = ""
    var industry = ""
    var exchange = ""
    var location = ""
    var about = ""
    var aboutURL = ""
    var price: Double?
    var priceDate = ""
}

/// Builds a short company profile from free public sources:
///  - SEC company profile (name, industry, exchange, headquarters)
///  - Wikipedia summary (what the company does)
///  - Stooq delayed quote (latest price)
/// Each part is optional; if one source fails the rest still show.
enum StockInfo {
    private static var cache: [String: StockSummary] = [:]

    @MainActor
    static func load(ticker: String, company: String, model: AppModel) async -> StockSummary {
        if let c = cache[ticker] { return c }
        var s = StockSummary(name: company)
        let ua = model.userAgent

        if let cik = await model.cik(for: ticker),
           let data = try? await Edgar.get(String(format: "https://data.sec.gov/submissions/CIK%010d.json", cik), ua: ua),
           let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let n = j["name"] as? String, !n.isEmpty { s.name = n }
            s.industry = j["sicDescription"] as? String ?? ""
            s.exchange = (j["exchanges"] as? [String])?.first ?? ""
            if let b = (j["addresses"] as? [String: Any])?["business"] as? [String: Any] {
                s.location = [b["city"] as? String, b["stateOrCountryDescription"] as? String]
                    .compactMap { $0 }.filter { !$0.isEmpty }.map(titleCase).joined(separator: ", ")
            }
        }
        s.name = titleCase(s.name)
        s.industry = titleCase(s.industry)

        if let w = await wikipedia(s.name) { s.about = w.0; s.aboutURL = w.1 }
        if let q = await quote(ticker) { s.price = q.0; s.priceDate = q.1 }

        cache[ticker] = s
        return s
    }

    // ---------------- Wikipedia ----------------
    private static let companyWords = [
        "company", "corporation", "conglomerate", "manufacturer", "bank", "firm", "retailer",
        "provider", "developer", "producer", "operator", "trust", "holding", "airline", "insurer",
        "maker", "business", "brand", "publicly traded", "multinational", "chain", "utility",
    ]

    private static func wikipedia(_ companyName: String) async -> (String, String)? {
        let clean = Edgar.normalizeName(companyName)
        guard let first = clean.split(separator: " ").first?.lowercased() else { return nil }
        let q = "\(clean) company".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        guard let data = await get("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=3&srsearch=\(q)"),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = (json["query"] as? [String: Any])?["search"] as? [[String: Any]] else { return nil }
        for r in results {
            guard let title = r["title"] as? String else { continue }
            let path = title.replacingOccurrences(of: " ", with: "_")
                .addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? ""
            guard let pd = await get("https://en.wikipedia.org/api/rest_v1/page/summary/\(path)"),
                  let page = try? JSONSerialization.jsonObject(with: pd) as? [String: Any] else { continue }
            if page["type"] as? String == "disambiguation" { continue }
            let extract = page["extract"] as? String ?? ""
            let text = ((page["description"] as? String ?? "") + " " + extract).lowercased()
            // Only accept pages that are clearly about this company, not a person or place.
            if text.contains(first) && companyWords.contains(where: { text.contains($0) }) {
                let url = ((page["content_urls"] as? [String: Any])?["mobile"] as? [String: Any])?["page"] as? String ?? ""
                return (firstSentences(extract, 3), url)
            }
        }
        return nil
    }

    private static func firstSentences(_ text: String, _ n: Int) -> String {
        var out: [String] = []
        text.enumerateSubstrings(in: text.startIndex..., options: .bySentences) { sub, _, _, stop in
            if let sub = sub { out.append(sub.trimmingCharacters(in: .whitespaces)) }
            if out.count >= n { stop = true }
        }
        return out.joined(separator: " ")
    }

    // ---------------- Price ----------------
    /// Latest delayed close from Stooq, e.g. "AAPL.US,2026-11-05,22:00:00,230.10".
    private static func quote(_ ticker: String) async -> (Double, String)? {
        let sym = ticker.lowercased().replacingOccurrences(of: ".", with: "-") + ".us"
        guard let data = await get("https://stooq.com/q/l/?s=\(sym)&f=sd2t2c&h&e=csv"),
              let csv = String(data: data, encoding: .utf8) else { return nil }
        let lines = csv.split(whereSeparator: \.isNewline)
        guard lines.count > 1 else { return nil }
        let row = lines[1].split(separator: ",").map(String.init)
        guard row.count > 3, let price = Double(row[3]) else { return nil }
        return (price, row[1] == "N/D" ? "" : row[1])
    }

    // ---------------- helpers ----------------
    private static func get(_ url: String) async -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.setValue("StockTracker-iOS/1.0 (personal app)", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 20
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let code = (resp as? HTTPURLResponse)?.statusCode, (200..<300).contains(code) else { return nil }
        return data
    }

    private static func titleCase(_ s: String) -> String {
        s.contains(where: { $0.isLowercase }) ? s : s.capitalized
    }
}
