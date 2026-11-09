import SwiftUI

/// One place the app gets information from. `usedFor` says what it backs in the app.
struct Source: Identifiable, Hashable {
    let name: String
    let usedFor: String
    let url: String
    var id: String { url }
}

/// Every source the app uses: live data feeds, plus where each person's reported holdings and links come from.
enum Sources {
    /// Live data the app downloads.
    static let feeds: [Source] = [
        Source(name: "SEC EDGAR: latest filings", usedFor: "Insiders (Form 4) and Funds (13F) sections, company announcements (8-K)",
               url: "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent"),
        Source(name: "SEC EDGAR: company submissions", usedFor: "Each company's and filer's filing history, insider filings of people you follow",
               url: "https://data.sec.gov/submissions/"),
        Source(name: "SEC EDGAR: full-text search", usedFor: "Finding the SEC filer number for people you follow", url: "https://efts.sec.gov/LATEST/search-index"),
        Source(name: "SEC company ticker list", usedFor: "Matching company names and 13F holdings to stock tickers", url: "https://www.sec.gov/files/company_tickers.json"),
        Source(name: "SEC litigation releases", usedFor: "SEC charges section", url: "https://www.sec.gov/enforcement-litigation/litigation-releases/rss"),
        Source(name: "SEC press releases", usedFor: "SEC charges section", url: "https://www.sec.gov/news/pressreleases.rss"),
        Source(name: "Bargo Congress Trades API", usedFor: "Senate and House trades (from House Clerk and Senate eFD filings)", url: "https://www.bargo.ai/free-apis/congress"),
        Source(name: "congress-legislators (unitedstates.github.io)", usedFor: "Members of Congress: party, state, photo", url: "https://unitedstates.github.io/congress-legislators/"),
        Source(name: "Congressional Bioguide", usedFor: "Members' official biographies", url: "https://bioguide.congress.gov"),
        Source(name: "ApeWisdom", usedFor: "WSB section: most-discussed stocks on Reddit", url: "https://apewisdom.io"),
        Source(name: "Finnhub", usedFor: "Live stock prices", url: "https://finnhub.io"),
        Source(name: "Massive (formerly Polygon.io)", usedFor: "Movers and price charts", url: "https://massive.com"),
        Source(name: "Stooq", usedFor: "Delayed latest price in stock summaries", url: "https://stooq.com"),
        Source(name: "Wikipedia", usedFor: "Company descriptions in stock summaries", url: "https://en.wikipedia.org"),
        Source(name: "CoinGecko", usedFor: "Bitcoin and Ethereum prices, companies holding crypto", url: "https://www.coingecko.com"),
        Source(name: "Google News", usedFor: "News tab and news about people you follow", url: "https://news.google.com"),
        Source(name: "YouTube", usedFor: "Newest videos from people you follow", url: "https://www.youtube.com"),
    ]

    /// A person or group, and the sources behind what the app says about them.
    struct Group: Identifiable {
        let who: String
        var sources: [Source]
        var id: String { who }
    }

    /// Sources for every built-in person plus anyone the user added.
    static func people(_ followed: [Followed]) -> [Group] {
        var seenNames = Set<String>()
        let all = (Following.defaults + Following.funds + Following.publicFigures + Following.government + followed)
            .filter { seenNames.insert($0.name).inserted }
        return all.compactMap { f in
            var out: [Source] = []
            var seen = Set<String>()
            func add(_ s: Source) { if !s.url.isEmpty && seen.insert(s.url).inserted { out.append(s) } }
            if let k = Known.people[f.name] {
                var order: [String] = []
                var byURL: [String: [Holding]] = [:]
                for h in k.owns where !h.sourceURL.isEmpty {
                    if byURL[h.sourceURL] == nil { order.append(h.sourceURL) }
                    byURL[h.sourceURL, default: []].append(h)
                }
                for url in order {
                    let hs = byURL[url]!
                    add(Source(name: hs[0].source, usedFor: hs.map { $0.ticker.isEmpty ? $0.name : $0.ticker }.joined(separator: ", "), url: url))
                }
                add(Source(name: "Recommendations", usedFor: k.recommends.joined(separator: ", "), url: k.recommendsSource))
                add(Source(name: "Briefs articles feed", usedFor: "New holdings they disclose", url: k.disclosureFeed))
            }
            for l in f.links where l.count == 2 { add(Source(name: l[0], usedFor: "Link", url: l[1])) }
            if !f.youtube.isEmpty {
                let handle = f.youtube.hasPrefix("@") ? String(f.youtube.dropFirst()) : f.youtube
                add(Source(name: "YouTube channel", usedFor: "Their newest videos", url: "https://www.youtube.com/@\(handle)"))
            }
            if let cik = f.cik, !cik.isEmpty {
                add(Source(name: "SEC 13F filings", usedFor: "Their quarterly stock holdings",
                           url: "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=\(cik)&type=13F"))
            }
            if let ins = f.insider, !ins.isEmpty {
                if ins.allSatisfy(\.isNumber) {
                    add(Source(name: "SEC insider filings", usedFor: "Their Form 4 buys and sells",
                               url: "https://www.sec.gov/cgi-bin/own-disp?action=getowner&CIK=\(ins)"))
                } else {
                    let enc = ins.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ins
                    add(Source(name: "SEC insider filings", usedFor: "Their Form 4 buys and sells (found by name: \(ins))",
                               url: "https://efts.sec.gov/LATEST/search-index?keysTyped=\(enc)"))
                }
            }
            return out.isEmpty ? nil : Group(who: f.name, sources: out)
        }
    }
}

/// Lists every source the app uses. Tap one to open it.
struct SourcesView: View {
    @Environment(\.openURL) private var openURL
    @State private var groups: [Sources.Group] = Sources.people(Following.load())
    @State private var query = ""

    private func match(_ s: Source, _ who: String = "") -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty || [s.name, s.usedFor, s.url, who].contains { $0.lowercased().contains(q) }
    }

    var body: some View {
        let feeds = Sources.feeds.filter { match($0) }
        let people = groups.map { g in Sources.Group(who: g.who, sources: g.sources.filter { match($0, g.who) }) }
            .filter { !$0.sources.isEmpty }
        let total = Sources.feeds.count + groups.reduce(0) { $0 + $1.sources.count }
        NavigationStack {
            List {
                Section {
                    Text("\(total) sources. Tap any one to open it.").font(.subheadline).foregroundStyle(.secondary)
                }
                if !feeds.isEmpty {
                    Section("Live data feeds") { ForEach(feeds) { row($0) } }
                }
                ForEach(people) { g in
                    Section(g.who) { ForEach(g.sources) { row($0) } }
                }
                if feeds.isEmpty && people.isEmpty {
                    Section { Text("No sources match \"\(query)\".") }
                }
            }
            .searchable(text: $query, prompt: "Search sources, people or tickers")
            .navigationTitle("Sources")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .onAppear { groups = Sources.people(Following.load()) }
        }
    }

    private func row(_ s: Source) -> some View {
        Button {
            if let u = URL(string: s.url) { openURL(u) }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(s.name).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Text(s.usedFor).font(.caption).foregroundStyle(.secondary)
                Text(s.url).font(.caption2).foregroundStyle(.tint).lineLimit(1)
            }
        }
    }
}
