import Foundation

/// Something a person has publicly said they own, with where they said it.
struct Holding: Identifiable {
    let name: String
    let ticker: String
    let note: String
    let source: String
    let sourceURL: String
    var id: String { ticker.isEmpty ? name : ticker }
}

/// Public statements gathered from interviews, articles and their own company's write-ups.
struct KnownInfo {
    let owns: [Holding]
    let recommends: [String]
    let recommendsSource: String
    var disclosureFeed = ""
}

enum Known {
    private static let briefsETF = "https://www.briefs.co/best-etfs-to-buy-three-etf-portfolios/"
    private static let markTranscript = "https://singjupost.com/transcript-of-how-to-invest-for-beginners-in-2025-by-mark-tilbury/"

    static let people: [String: KnownInfo] = [
        "Jaspreet Singh": KnownInfo(
            owns: [
                Holding(name: "Vanguard S&P 500 ETF", ticker: "VOO", note: "Briefs says he personally invests in VOO", source: "Briefs", sourceURL: briefsETF),
                Holding(name: "Vanguard international high-dividend ETF", ticker: "", note: "Briefs says he personally invests in it (ticker not named)", source: "Briefs", sourceURL: briefsETF),
                Holding(name: "Bitcoin", ticker: "", note: "Bought since 2017; later sold a large portion to reinvest in real estate", source: "Yahoo Finance",
                        sourceURL: "https://finance.yahoo.com/news/jaspreet-singh-cuts-bitcoin-why-120039231.html"),
                Holding(name: "Physical gold", ticker: "", note: "Buys a little every month, about 2% of his portfolio", source: "Yahoo Finance",
                        sourceURL: "https://finance.yahoo.com/news/jaspreet-singh-says-rich-people-172918130.html"),
                Holding(name: "Fundrise (real estate)", ticker: "", note: "Has discussed his position in Fundrise", source: "People & Media",
                        sourceURL: "https://www.peopleandmedia.com/jaspreet-singh-net-worth/"),
            ],
            recommends: ["SCHD", "VUG", "QQQ", "VIG", "GLD", "SMH", "VTI", "SPY"],
            recommendsSource: briefsETF,
            disclosureFeed: "https://www.briefs.co/feed/"),
        "Mark Tilbury": KnownInfo(
            owns: [
                Holding(name: "S&P 500 index funds", ticker: "", note: "Says index funds hold the majority of his money; the S&P 500 is \"part of my portfolio\"",
                        source: "Mark Tilbury on X", sourceURL: "https://x.com/marktilbury/status/1890400994409734435"),
                Holding(name: "Nvidia", ticker: "NVDA", note: "Named with Apple and Microsoft: \"luckily I have some of those in my personal portfolio\" (didn't say which)",
                        source: "Video transcript", sourceURL: markTranscript),
                Holding(name: "Apple", ticker: "AAPL", note: "Same statement as Nvidia above, so he owns at least some of these three", source: "Video transcript", sourceURL: markTranscript),
                Holding(name: "Microsoft", ticker: "MSFT", note: "Same statement as Nvidia above", source: "Video transcript", sourceURL: markTranscript),
                Holding(name: "Bitcoin and Ethereum", ticker: "", note: "Keeps a small percentage of his portfolio in crypto", source: "Mark Tilbury on X",
                        sourceURL: "https://x.com/marktilbury/status/1966849360534401375"),
            ],
            recommends: ["AAPL"],
            recommendsSource: "https://x.com/marktilbury/status/1959964001850560980"),
    ]

    private static let stop: Set<String> = ["ETF", "ETFS", "AI", "US", "USA", "CEO", "IPO", "SPDR", "GDP", "FED", "IRA", "VC", "OK"]

    /// New "he personally invests in X" lines from the person's own company articles (Briefs for Jaspreet).
    static func disclosures(feed: String, name: String) async -> [Holding] {
        guard !feed.isEmpty, let url = URL(string: feed) else { return [] }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (iPhone) StockTracker/1.0", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: req), let root = XNode.parse(data) else { return [] }
        let first = (name.split(separator: " ").first.map(String.init) ?? name).lowercased()
        let re = try! NSRegularExpression(pattern: #"\b([A-Z]{2,5})\b"#)
        func tickers(_ s: String) -> [String] {
            re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { Range($0.range(at: 1), in: s).map { String(s[$0]) } }
                .filter { !stop.contains($0) }
        }
        var out: [Holding] = []
        var seen = Set<String>()
        for item in root.child("channel")?.all("item") ?? [] {
            let title = (item.child("title")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let link = (item.child("link")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let raw = (item.child("encoded")?.text ?? "") + " " + (item.child("description")?.text ?? "")
            let text = raw.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
                .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&#8217;", with: "'")
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            var sentences: [String] = []
            text.enumerateSubstrings(in: text.startIndex..., options: .bySentences) { s, _, _, _ in if let s { sentences.append(s) } }
            for (i, sent) in sentences.enumerated() {
                let low = sent.lowercased()
                guard low.contains(first),
                      ["personally invest", "personally own", "is invested in", "he owns", "he holds"].contains(where: { low.contains($0) })
                else { continue }
                var ts = tickers(sent)
                if ts.isEmpty && i > 0 { ts = tickers(sentences[i - 1]) }
                if ts.isEmpty {
                    let short = String(sent.prefix(80)).trimmingCharacters(in: .whitespaces)
                    if seen.insert(short).inserted {
                        out.append(Holding(name: short, ticker: "", note: "Disclosed in \"\(title)\"", source: "Briefs", sourceURL: link))
                    }
                } else {
                    for t in ts where seen.insert(t).inserted {
                        out.append(Holding(name: t, ticker: t, note: "Disclosed in \"\(title)\"", source: "Briefs", sourceURL: link))
                    }
                }
            }
        }
        return out
    }
}
