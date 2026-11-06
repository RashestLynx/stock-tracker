import Foundation

struct NewsItem: Identifiable {
    let title: String
    let source: String
    let url: String
    let time: Date
    var ticker = ""
    var detail = ""
    var isFiling = false
    var id: String { url }
}

/// Near-live market news from free sources:
///  - Google News RSS: headlines usually appear within minutes of being published
///  - SEC EDGAR 8-K feed: official company announcements the moment they are filed
enum News {
    static func market() async throws -> [NewsItem] { try await google("stock market OR stocks OR Wall Street when:1d") }

    static func forTickers(_ tickers: [String]) async throws -> [NewsItem] {
        guard !tickers.isEmpty else { return [] }
        let q = tickers.prefix(8).map { "\"\($0)\"" }.joined(separator: " OR ") + " stock when:2d"
        return try await google(q).map { n in
            var n = n
            n.ticker = tickers.first { t in
                n.title.range(of: "\\b\(t)\\b", options: .regularExpression) != nil
            } ?? ""
            return n
        }
    }

    static func filings(ua: String, ciks: [String: Int]) async throws -> [NewsItem] {
        var byCik: [Int: String] = [:]
        for (t, c) in ciks where byCik[c] == nil { byCik[c] = t }
        let iso = ISO8601DateFormatter()
        return try await Edgar.feed("8-K", ua: ua).map { f in
            let cik = f.title.range(of: #"\((\d+)\)"#, options: .regularExpression)
                .flatMap { Int(f.title[$0].dropFirst().dropLast()) }
            let company = f.title.replacingOccurrences(of: "8-K - ", with: "")
                .replacingOccurrences(of: #"\s*\(\d+\)\s*\(Filer\)\s*$"#, with: "", options: .regularExpression)
            let summary = f.summary.replacingOccurrences(of: "<[^>]+>", with: "\n", options: .regularExpression)
                .replacingOccurrences(of: "&amp;", with: "&")
            var items: [String] = []
            for line in summary.components(separatedBy: "\n") {
                if let r = line.range(of: #"Item \d+\.\d+:\s*"#, options: .regularExpression) {
                    let d = line[r.upperBound...].trimmingCharacters(in: .whitespaces)
                    if !d.isEmpty && !d.hasPrefix("Financial Statements") { items.append(d) }
                }
            }
            return NewsItem(title: company.contains(where: { $0.isLowercase }) ? company : company.capitalized,
                            source: "SEC filing", url: f.link,
                            time: iso.date(from: f.updated) ?? Date(),
                            ticker: cik.flatMap { byCik[$0] } ?? "",
                            detail: items.isEmpty ? "Company announcement (8-K)" : items.joined(separator: " · "),
                            isFiling: true)
        }
    }

    static func search(_ query: String) async throws -> [NewsItem] { try await google(query + " when:14d") }

    private static func google(_ query: String) async throws -> [NewsItem] {
        let q = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)?
            .replacingOccurrences(of: "&", with: "%26") ?? ""
        guard let url = URL(string: "https://news.google.com/rss/search?hl=en-US&gl=US&ceid=US:en&q=\(q)") else { return [] }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (iPhone) StockTracker/1.0", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 20
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code), let root = XNode.parse(data) else { throw AppError("News returned HTTP \(code)") }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        let items = root.child("channel")?.all("item") ?? []
        return items.compactMap { it in
            let source = (it.child("source")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            var title = (it.child("title")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !source.isEmpty, title.hasSuffix(" - \(source)") { title = String(title.dropLast(source.count + 3)) }
            let link = (it.child("link")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty, !link.isEmpty else { return nil }
            let date = df.date(from: (it.child("pubDate")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)) ?? Date()
            return NewsItem(title: title, source: source, url: link, time: date)
        }.sorted { $0.time > $1.time }
    }
}
