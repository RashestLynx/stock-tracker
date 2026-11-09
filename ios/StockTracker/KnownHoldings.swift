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
    var ownsTitle = ""
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
        "Elon Musk": KnownInfo(
            owns: [
                Holding(name: "Tesla", ticker: "TSLA", note: "Largest individual shareholder. In June 2026 he exercised 303,960,630 Tesla options from his 2018 pay package; no shares were sold",
                        source: "Teslarati", sourceURL: "https://www.teslarati.com/?p=302443"),
                Holding(name: "SpaceX", ticker: "", note: "CEO and largest shareholder; SpaceX went public in June 2026", source: "Teslarati", sourceURL: "https://www.teslarati.com/?p=302443"),
            ], recommends: [], recommendsSource: ""),
        "Sam Altman": KnownInfo(
            owns: [
                Holding(name: "OpenAI", ticker: "", note: "Holds no equity in OpenAI", source: "Reuters via Layer3", sourceURL: "https://www.layer3labs.io/guides/sam-altman-net-worth"),
                Holding(name: "Helion Energy (private)", ticker: "", note: "Stake valued around $1.7 billion in a May 2026 court document", source: "Layer3", sourceURL: "https://www.layer3labs.io/guides/sam-altman-net-worth"),
                Holding(name: "Stripe (private)", ticker: "", note: "Stake valued around $633 million in the same court document", source: "Layer3", sourceURL: "https://www.layer3labs.io/guides/sam-altman-net-worth"),
                Holding(name: "Oklo", ticker: "OKLO", note: "Public nuclear company he chaired until April 2025; holds shares", source: "BingX", sourceURL: "https://bingx.com/en/learn/article/what-is-sam-altman-s-net-worth"),
                Holding(name: "Reddit", ticker: "RDDT", note: "Was one of his biggest holdings; a court document says he sold out by the end of 2025", source: "Layer3", sourceURL: "https://www.layer3labs.io/guides/sam-altman-net-worth"),
            ], recommends: [], recommendsSource: ""),
        "Donald Trump": KnownInfo(
            owns: [
                Holding(name: "Stock trades on his behalf", ticker: "", note: "Ethics filings show 3,642 trades in Q1 2026 worth $220M-$750M; they don't say who directed them",
                        source: "Moneywise", sourceURL: "https://moneywise.com/news/top-stories/trump-eric-trump-nvidia-palantir-stock-trades-insider-trading-accusations"),
                Holding(name: "Nvidia", ticker: "NVDA", note: "15 transactions in Q1 2026 (9 purchases, 6 sales)", source: "CBS News", sourceURL: "https://cbsnews.com/news/trump-stock-trades-2026"),
                Holding(name: "Trump Media & Technology Group", ticker: "DJT", note: "His media company, listed on Nasdaq", source: "SEC", sourceURL: "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&company=trump+media"),
            ], recommends: [], recommendsSource: ""),
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
        Following.usGov: KnownInfo(
            owns: [
                Holding(name: "Intel", ticker: "INTC", note: "Commerce Department bought about 9.9% (433.3 million shares at $20.47) in August 2025, the government's biggest stake", source: "CNN", sourceURL: "https://www.cnn.com/2025/11/17/investing/equity-stakes-government-stock-market"),
                Holding(name: "MP Materials", ticker: "MP", note: "Pentagon holds preferred stock and warrants for about 15%, making it the largest shareholder (July 2025)", source: "Columbia CGEP", sourceURL: "https://www.energypolicy.columbia.edu/qa-how-the-us-is-using-equity-stakes-to-support-domestic-critical-minerals-development/"),
                Holding(name: "Lithium Americas", ticker: "LAC", note: "Energy Department holds 5% of the company and 5% of its Thacker Pass lithium project with GM (October 2025)", source: "Columbia CGEP", sourceURL: "https://www.energypolicy.columbia.edu/qa-how-the-us-is-using-equity-stakes-to-support-domestic-critical-minerals-development/"),
                Holding(name: "Trilogy Metals", ticker: "TMQ", note: "Government holds 10% plus warrants for 7.5% more (October 2025)", source: "Columbia CGEP", sourceURL: "https://www.energypolicy.columbia.edu/qa-how-the-us-is-using-equity-stakes-to-support-domestic-critical-minerals-development/"),
                Holding(name: "USA Rare Earth", ticker: "USAR", note: "Commerce agreed to invest $1.6 billion for about a 10% stake (January 2026); 8% to 16% depending on warrants", source: "Yahoo Finance", sourceURL: "https://finance.yahoo.com/news/trump-admin-invest-1-6bn-112926262.html"),
                Holding(name: "GlobalFoundries", ticker: "GFS", note: "Commerce stakes tied to CHIPS awards: quantum foundry (May 2026) and up to $300 million for co-packaged optics (July 2026)", source: "NIST", sourceURL: "https://www.nist.gov/system/files/documents/2026/07/29/CHIPS%20Compute%20Supply%20Chain%20Press%20Release%202026-07-29_Final.pdf"),
                Holding(name: "IBM", ticker: "IBM", note: "Quantum foundry CHIPS award with a minority, non-controlling government stake (May 2026)", source: "DCD", sourceURL: "https://www.datacenterdynamics.com/en/news/us-dept-of-commerce-awards-nine-quantum-computing-companies-2bn-in-exchange-for-non-controlling-equity-stakes/"),
                Holding(name: "D-Wave Quantum", ticker: "QBTS", note: "$100 million CHIPS award for a minority, non-controlling government stake (finalized 2026)", source: "DCD", sourceURL: "https://www.datacenterdynamics.com/en/news/us-dept-of-commerce-awards-nine-quantum-computing-companies-2bn-in-exchange-for-non-controlling-equity-stakes/"),
                Holding(name: "Rigetti Computing", ticker: "RGTI", note: "CHIPS award for a minority, non-controlling government stake (finalized 2026)", source: "DCD", sourceURL: "https://www.datacenterdynamics.com/en/news/us-dept-of-commerce-awards-nine-quantum-computing-companies-2bn-in-exchange-for-non-controlling-equity-stakes/"),
                Holding(name: "Aeluma", ticker: "ALMU", note: "Letter of intent for up to $30 million in CHIPS funding for a minority stake (July 2026)", source: "NIST", sourceURL: "https://www.nist.gov/system/files/documents/2026/07/29/CHIPS%20Compute%20Supply%20Chain%20Press%20Release%202026-07-29_Final.pdf"),
                Holding(name: "U.S. Steel (owned by Nippon Steel)", ticker: "", note: "Government holds a \"golden share\" with veto rights from the Nippon Steel deal (June 2025)", source: "CNBC", sourceURL: "https://www.cnbc.com/2026/02/07/trump-administration-equity-portfolio-grows-these-are-investments-so-far.html"),
                Holding(name: "Westinghouse (private)", ticker: "", note: "Participation interest from the $80 billion nuclear deal (October 2025); can require an IPO", source: "CNBC", sourceURL: "https://www.cnbc.com/2026/02/07/trump-administration-equity-portfolio-grows-these-are-investments-so-far.html"),
                Holding(name: "Vulcan Elements (private)", ticker: "", note: "Commerce $50 million equity stake plus Pentagon warrants (November 2025)", source: "Cato Institute", sourceURL: "https://www.cato.org/blog/rare-earths-vulcan-government-equity-stakes"),
                Holding(name: "xLight (private)", ticker: "", note: "Commerce $150 million equity stake (December 2025)", source: "CNBC", sourceURL: "https://www.cnbc.com/2026/02/07/trump-administration-equity-portfolio-grows-these-are-investments-so-far.html"),
                Holding(name: "Korea Zinc (Tennessee smelter)", ticker: "", note: "Pentagon-backed smelter project with a conditional stake (December 2025)", source: "CNBC", sourceURL: "https://www.cnbc.com/2026/02/07/trump-administration-equity-portfolio-grows-these-are-investments-so-far.html"),
                Holding(name: "Quantinuum, PsiQuantum, Atom Computing, Infleqtion, Diraq", ticker: "", note: "Quantum computing companies in the May 2026 CHIPS awards, each with a government stake", source: "DCD", sourceURL: "https://www.datacenterdynamics.com/en/news/us-dept-of-commerce-awards-nine-quantum-computing-companies-2bn-in-exchange-for-non-controlling-equity-stakes/"),
                Holding(name: "Kepler, Multibeam, Extropic, Thintronics, OBSIDIA", ticker: "", note: "Chip startups in the July 2026 CHIPS letters of intent, each with a minority stake", source: "NIST", sourceURL: "https://www.nist.gov/system/files/documents/2026/07/29/CHIPS%20Compute%20Supply%20Chain%20Press%20Release%202026-07-29_Final.pdf"),
            ],
            recommends: [], recommendsSource: "",
            ownsTitle: "Companies the U.S. government owns a stake in"),
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
