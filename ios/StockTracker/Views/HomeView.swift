import SwiftUI

/// One post in the Home feed.
struct Post: Identifiable {
    let who: String
    let about: String
    let category: Category?     // nil = someone you follow
    let time: Date
    let text: String
    let ticker: String
    let company: String
    let link: String
    var person: PersonTarget?
    var followed: Followed?
    var id: String { who + "|" + String(time.timeIntervalSince1970) + "|" + String(text.hashValue) }
}

/// Funds show a ticker (or "13F") instead of initials.
func fundBadge(_ name: String) -> String {
    let n = name.lowercased()
    if n.contains("berkshire") { return "BRK.B" }
    if n.contains("pershing square") { return "PSH" }
    return "13F"
}

func isFund(_ f: Followed) -> Bool { Following.funds.contains { $0.name == f.name } }

struct Avatar: View {
    let name: String
    var size: CGFloat = 44
    var fund = false
    private static let palette: [Color] = [.blue, .green, .purple, .orange, .teal, .indigo, .pink, .brown]
    private var initials: String {
        let w = name.split(separator: " ").filter { $0.first?.isLetter == true }
        let s = (w.first.map { String($0.prefix(1)) } ?? "") + (w.count > 1 ? String(w.last!.prefix(1)) : "")
        return s.isEmpty ? "?" : s.uppercased()
    }
    var body: some View {
        if fund {
            Text(fundBadge(name))
                .font(size >= 50 ? .caption.weight(.heavy) : .caption2.weight(.heavy))
                .minimumScaleFactor(0.6).lineLimit(1)
                .foregroundStyle(.orange)
                .frame(width: size, height: size)
                .background(Color.orange.opacity(0.18), in: Circle())
        } else {
            initialsView
        }
    }

    private var initialsView: some View {
        let idx = abs(name.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }) % Self.palette.count
        return Text(initials)
            .font(size >= 44 ? .headline : .caption.weight(.bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Self.palette[idx], in: Circle())
    }
}

/// Social-style Home: Today's buy & sale, a stories row of people you follow, and a feed of posts.
struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var followedPosts: [Post] = []
    @State private var follows: [Followed] = Following.load()
    @State private var showFollowed: Followed?

    private static let dayFmt: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f
    }()

    private var alertPosts: [Post] {
        model.alerts.filter { $0.category != .charges }.prefix(150).map { a in
            let isFund = a.category == .fund
            let name = isFund ? a.headline : (cleanName(a.headline).components(separatedBy: ", ").first ?? a.headline)
            let after = a.headline.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
            let about: String
            switch a.category {
            case .senate: about = "U.S. Senator" + (after.isEmpty ? "" : " · \(after)")
            case .house: about = "U.S. Representative" + (after.isEmpty ? "" : " · \(after)")
            case .insider: about = [after, a.company].filter { !$0.isEmpty }.joined(separator: " at ")
            default: about = "Investment fund"
            }
            return Post(who: name, about: about, category: a.category, time: a.time,
                        text: (isFund ? "Filed new quarterly holdings. " : "") + a.lines.joined(separator: " · "),
                        ticker: a.ticker, company: a.company, link: a.link, person: personOf(a))
        }
    }

    private var posts: [Post] { (followedPosts + alertPosts).sorted { $0.time > $1.time } }

    var body: some View {
        NavigationStack {
            List {
                if model.isSetUp {
                    Section {
                        TodayCard(highlights: Highlights.compute(moves: model.moves, picks: model.picks))
                            .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                    }
                }
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(follows) { f in
                                Button { showFollowed = f } label: {
                                    VStack(spacing: 4) {
                                        Avatar(name: f.name, size: 56, fund: isFund(f))
                                            .padding(3).overlay(Circle().stroke(Color.green, lineWidth: 3))
                                        Text(f.name.components(separatedBy: " (").first ?? f.name)
                                            .font(.caption2).lineLimit(1).frame(width: 70)
                                            .foregroundStyle(.primary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
                }

                if posts.isEmpty {
                    EmptyStateView(title: "Your feed is warming up", message: "Pull down to check for new filings.", systemImage: "person.2")
                        .listRowBackground(Color.clear)
                }
                ForEach(posts.prefix(200)) { p in
                    Section { postView(p) }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Stock Tracker")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { SettingsButton() }
                ToolbarItem(placement: .topBarTrailing) {
                    if model.isChecking { ProgressView() } else {
                        Button { Task { await model.check() } } label: { Image(systemName: "arrow.clockwise") }
                    }
                }
            }
            .refreshable {
                await model.check()
                await loadFollowed()
            }
            .task { await loadFollowed() }
            .sheet(item: $showFollowed) { f in
                NavigationStack {
                    List {
                        FollowedSection(person: f) {
                            var l = Following.load(); l.removeAll { $0.id == f.id }; Following.save(l)
                            follows = l
                            showFollowed = nil
                        }
                    }
                    .navigationTitle(f.name).navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showFollowed = nil } } }
                }
                .environmentObject(model)
            }
        }
    }

    @ViewBuilder
    private func postView(_ p: Post) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                if let f = p.followed { showFollowed = f } else if let t = p.person { model.openPerson = t }
            } label: {
                HStack(spacing: 10) {
                    Avatar(name: p.who, fund: p.category == .fund || (p.followed.map(isFund) ?? false))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(p.who).font(.subheadline.weight(.bold)).lineLimit(1)
                        Text([p.about, Format.ago(p.time)].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    if let c = p.category { CategoryPill(category: c) }
                }
                .foregroundStyle(.primary)
            }
            .buttonStyle(.borderless)
            Text(p.text).font(.subheadline)
                .foregroundStyle(p.text.hasPrefix("Bought") ? .green : p.text.hasPrefix("Sold") ? .red : .primary)
            HStack(spacing: 12) {
                if !p.ticker.isEmpty {
                    Button("$\(p.ticker)") { model.openStock = StockTarget(ticker: p.ticker, company: p.company) }
                        .buttonStyle(.bordered).controlSize(.small)
                }
                if let u = URL(string: p.link), !p.link.isEmpty {
                    Button("Source") { openURL(u) }.buttonStyle(.borderless).font(.caption)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func loadFollowed() async {
        follows = Following.load()
        let ua = model.userAgent
        let list = follows
        let names = list.contains(where: { ($0.cik ?? "").isEmpty == false }) ? await model.tickerMap(ua: ua) : [:]
        let result = await withTaskGroup(of: [Post].self) { group -> [Post] in
            for f in list {
                group.addTask {
                    var out: [Post] = []
                    let about = f.businesses.first ?? ""
                    if let ins = f.insider, !ins.isEmpty {
                        for x in await Following.insiderFilings(f, ua: ua, max: 3) {
                            out.append(Post(who: f.name, about: about, category: nil,
                                            time: Self.dayFmt.date(from: x.date) ?? Date.distantPast,
                                            text: x.lines.joined(separator: " · ") + (x.company.isEmpty ? "" : " · \(x.company)"),
                                            ticker: x.ticker, company: x.company, link: x.link, followed: f))
                        }
                    }
                    if let cik = f.cik, !cik.isEmpty, let s = await Following.fundSnapshot(cik: cik, ua: ua) {
                        var text = "Filed holdings as of \(s.period)."
                        if !s.newBuys.isEmpty { text += " New: " + s.newBuys.prefix(3).joined(separator: ", ") + "." }
                        if !s.soldOut.isEmpty { text += " Sold out of: " + s.soldOut.prefix(3).joined(separator: ", ") + "." }
                        if let t = s.top.first { text += " Biggest holding: \(t.name)." }
                        let filed = (Self.dayFmt.date(from: s.period) ?? Date.distantPast).addingTimeInterval(45 * 86_400)
                        let pick = (s.newBuys + s.top.map(\.name)).lazy.compactMap { n in names[Edgar.normalizeName(n)].map { ($0, n) } }.first
                        out.append(Post(who: f.name, about: about, category: nil, time: filed, text: text,
                                        ticker: pick?.0 ?? "", company: pick?.1 ?? "", link: "", followed: f))
                    }
                    if !f.youtube.isEmpty {
                        for v in await Following.videos(f).prefix(2) {
                            out.append(Post(who: f.name, about: about, category: nil, time: v.time,
                                            text: "Posted a new video: \"\(v.title)\"", ticker: "", company: "", link: v.url, followed: f))
                        }
                    }
                    return out
                }
            }
            var all: [Post] = []
            for await r in group { all += r }
            return all
        }
        followedPosts = result
    }
}

/// Watchlist, Movers, Common picks and WSB in one tab.
struct MarketsView: View {
    @State private var view = 0
    var body: some View {
        VStack(spacing: 0) {
            Picker("View", selection: $view) {
                Text("Watchlist").tag(0)
                Text("Movers").tag(1)
                Text("Picks").tag(2)
                Text("WSB").tag(3)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal).padding(.vertical, 6)
            switch view {
            case 0: WatchlistView()
            case 1: MoversView()
            case 2: PicksView()
            default: WsbView()
            }
        }
    }
}

/// Stocks and funds the user keeps an eye on.
struct WatchlistView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var input = ""
    @State private var prices: [String: Double] = [:]
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("Add a stock or fund, e.g. NVDA or VOO", text: $input)
                            .textInputAutocapitalization(.characters).autocorrectionDisabled().onSubmit(add)
                        Button(action: add) { Image(systemName: "plus.circle.fill") }.disabled(input.isEmpty)
                    }
                } footer: {
                    Text("You'll also get news and SEC filing alerts for these (Settings → News alerts).")
                }
                if model.watchlist.isEmpty {
                    EmptyStateView(title: "Your watchlist is empty",
                                   message: "Add tickers above, or tap \"Add to watchlist\" on any stock summary.", systemImage: "star")
                        .listRowBackground(Color.clear)
                }
                ForEach(model.watchlist, id: \.self) { t in
                    Section { row(t) }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Watchlist")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .task(id: model.watchlist) {
                while !Task.isCancelled {
                    var got: [String: Double] = [:]
                    for t in model.watchlist { if let p = await StockInfo.price(t) { got[t] = p } }
                    prices = got
                    loaded = true
                    try? await Task.sleep(nanoseconds: 300_000_000_000)
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ t: String) -> some View {
        let who = Array(Set(model.picks.filter { $0.ticker == t }.map { "\($0.who) (\($0.action == "hold" ? "holds" : "bought"))" }
            + model.moves.filter { $0.ticker == t && $0.side == "sell" }.map { "\($0.who) (sold)" })).sorted()
        VStack(alignment: .leading, spacing: 6) {
            Button { model.openStock = StockTarget(ticker: t, company: "") } label: {
                HStack(spacing: 12) {
                    TickerBadge(text: t, color: .green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t).font(.headline)
                        Text(prices[t].map { "$" + Format.number($0, decimals: 2) + " latest close" }
                             ?? (loaded ? "Price unavailable" : "Price loading…"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .foregroundStyle(.primary)
            }
            .buttonStyle(.borderless)
            Text(who.isEmpty ? "No one you track has bought, sold or held it recently."
                 : "\(who.count) tracked \(who.count == 1 ? "person" : "people"): " + who.prefix(4).joined(separator: ", ")
                    + (who.count > 4 ? " and \(who.count - 4) more" : ""))
                .font(.caption).foregroundStyle(who.isEmpty ? .secondary : .primary)
            HStack {
                if let url = Broker.link(t) {
                    Button("Open in \(Broker.name)") { openURL(url) }.buttonStyle(.borderless).font(.caption)
                }
                Spacer()
                Button("Remove", role: .destructive) { model.watchlist.removeAll { $0 == t } }
                    .buttonStyle(.borderless).font(.caption)
            }
        }
    }

    private func add() {
        let t = input.uppercased().filter { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }
        if !t.isEmpty && !model.watchlist.contains(t) { model.watchlist.append(t) }
        input = ""
    }
}
