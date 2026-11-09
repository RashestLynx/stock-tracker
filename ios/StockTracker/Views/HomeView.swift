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
    var id: String { who + "|" + String(time.timeIntervalSince1970) + "|" + text }
    /// Stable across launches, for remembering likes.
    var key: String { who + "|" + String(Int(time.timeIntervalSince1970)) + "|" + String(text.prefix(80)) }
}

/// Accent colors shared across the app: the story-ring gradient and the like heart.
enum Brand {
    static let accent = Color(red: 0.36, green: 0.24, blue: 0.96)
    static let story: [Color] = [Color(red: 1, green: 0.54, blue: 0.24), Color(red: 0.91, green: 0.21, blue: 0.56),
                                 Color(red: 0.48, green: 0.25, blue: 0.95), Color(red: 0.24, green: 0.48, blue: 1)]
    static let like = Color(red: 0.94, green: 0.22, blue: 0.42)
}

let feedFilters = ["For you", "Following", "Countries", "Congress", "Insiders", "Funds", "Liked"]

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
        if let flag = Following.flags[name] {
            Text(flag).font(.system(size: size * 0.55))
                .frame(width: size, height: size)
                .background(Color(.tertiarySystemFill), in: Circle())
        } else if fund {
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
    @State private var filter = "For you"
    @State private var liked: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "likedPosts") ?? [])

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

    private var posts: [Post] {
        (followedPosts + alertPosts).sorted { $0.time > $1.time }.filter { p in
            switch filter {
            case "Following": return p.followed != nil
            case "Countries": return Following.flags[p.who] != nil
            case "Congress": return p.category == .senate || p.category == .house
            case "Insiders": return p.category == .insider || !(p.followed?.insider ?? "").isEmpty
            case "Funds": return p.category == .fund || (p.followed.map(isFund) ?? false)
            case "Liked": return liked.contains(p.key)
            default: return true
            }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if let q = model.market {
                        MarketPill(quote: q).padding(.horizontal, 12).padding(.vertical, 4)
                    }
                    if model.isSetUp {
                        TodayCard(highlights: Highlights.compute(moves: model.moves, picks: model.picks))
                            .padding(.horizontal, 12).padding(.top, 4)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(follows) { f in
                                Button { showFollowed = f } label: {
                                    VStack(spacing: 5) {
                                        Avatar(name: f.name, size: 58, fund: isFund(f))
                                            .padding(4)
                                            .overlay(Circle().stroke(LinearGradient(colors: Brand.story, startPoint: .bottomLeading,
                                                                                    endPoint: .topTrailing), lineWidth: 3))
                                        Text(f.name.components(separatedBy: " (").first ?? f.name)
                                            .font(.caption2).lineLimit(1).frame(width: 72)
                                            .foregroundStyle(.primary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 10)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(feedFilters, id: \.self) { f in
                                Button { withAnimation(.snappy) { filter = f } } label: {
                                    Text(f).font(.subheadline.weight(.semibold))
                                        .padding(.horizontal, 14).padding(.vertical, 7)
                                        .foregroundStyle(filter == f ? Color(.systemBackground) : .primary)
                                        .background(filter == f ? Color.primary : Color(.secondarySystemFill), in: Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 12)
                    }
                    Divider().padding(.top, 10)

                    if posts.isEmpty {
                        Group {
                            if filter == "Liked" {
                                EmptyStateView(title: "No liked posts yet", message: "Tap the heart on a post to keep it here.", systemImage: "heart")
                            } else {
                                EmptyStateView(title: "Your feed is warming up", message: "Pull down to check for new filings.", systemImage: "person.2")
                            }
                        }
                        .frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    ForEach(posts.prefix(200)) { p in
                        postView(p)
                        Divider()
                    }
                }
            }
            .marketBackground()
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

    private func toggleLike(_ p: Post) {
        if liked.contains(p.key) { liked.remove(p.key) } else { liked.insert(p.key) }
        UserDefaults.standard.set(Array(liked), forKey: "likedPosts")
    }

    private func toggleWatch(_ ticker: String) {
        let t = ticker.uppercased()
        if let i = model.watchlist.firstIndex(of: t) { model.watchlist.remove(at: i) } else { model.watchlist.append(t) }
    }

    private func openAuthor(_ p: Post) {
        if let f = p.followed { showFollowed = f } else if let t = p.person { model.openPerson = t }
    }

    /// A post laid out like a social app: avatar, name and time, text, stock card, actions.
    @ViewBuilder
    private func postView(_ p: Post) -> some View {
        let isLiked = liked.contains(p.key)
        let watching = !p.ticker.isEmpty && model.watchlist.contains(p.ticker.uppercased())
        HStack(alignment: .top, spacing: 12) {
            Button { openAuthor(p) } label: {
                Avatar(name: p.who, size: 46, fund: p.category == .fund || (p.followed.map(isFund) ?? false))
            }
            .buttonStyle(.plain)
            VStack(alignment: .leading, spacing: 6) {
                Button { openAuthor(p) } label: {
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 4) {
                            Text(p.who).font(.subheadline.weight(.bold)).lineLimit(1)
                            Image(systemName: "checkmark.seal.fill").font(.caption)
                                .foregroundStyle(p.category?.color ?? Brand.accent)
                            Text("· " + Format.ago(p.time)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Text([p.category?.label ?? "Following", p.about].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .foregroundStyle(.primary)
                }
                .buttonStyle(.plain)

                Text(p.text).font(.body)
                    .foregroundStyle(p.text.hasPrefix("Bought") ? .green : p.text.hasPrefix("Sold") ? .red : .primary)
                    .fixedSize(horizontal: false, vertical: true)

                if !p.ticker.isEmpty {
                    Button { model.openStock = StockTarget(ticker: p.ticker, company: p.company) } label: {
                        HStack(spacing: 12) {
                            Text(String(p.ticker.prefix(4))).font(.caption.weight(.heavy)).foregroundStyle(.white)
                                .frame(width: 40, height: 40)
                                .background(LinearGradient(colors: Brand.story, startPoint: .bottomLeading, endPoint: .topTrailing),
                                            in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            VStack(alignment: .leading, spacing: 1) {
                                Text("$" + p.ticker).font(.subheadline.weight(.bold)).foregroundStyle(.primary)
                                if !p.company.isEmpty {
                                    Text(p.company).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            Spacer()
                            Text("Chart").font(.caption.weight(.semibold)).foregroundStyle(Brand.accent)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(12)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }

                HStack {
                    Button { withAnimation(.snappy) { toggleLike(p) } } label: {
                        Image(systemName: isLiked ? "heart.fill" : "heart")
                            .foregroundStyle(isLiked ? Brand.like : .secondary)
                            .symbolEffect(.bounce, value: isLiked)
                    }
                    Spacer()
                    if !p.ticker.isEmpty {
                        Button { toggleWatch(p.ticker) } label: {
                            Image(systemName: watching ? "star.fill" : "star")
                                .foregroundStyle(watching ? Brand.accent : .secondary)
                        }
                        .accessibilityLabel(watching ? "On your watchlist" : "Add to watchlist")
                        Spacer()
                    }
                    ShareLink(item: "\(p.who): \(p.text)" + (p.ticker.isEmpty ? "" : " $\(p.ticker)") + (p.link.isEmpty ? "" : "\n\(p.link)")) {
                        Image(systemName: "square.and.arrow.up").foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let u = URL(string: p.link), !p.link.isEmpty {
                        Button { openURL(u) } label: {
                            Label("Source", systemImage: "link").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        }
                    } else {
                        Color.clear.frame(width: 60, height: 1)
                    }
                }
                .font(.title3)
                .buttonStyle(.plain)
                .padding(.top, 4)
                .padding(.trailing, 8)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
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
                Text("Movers").tag(0)
                Text("Common picks").tag(1)
                Text("WSB").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal).padding(.vertical, 6)
            switch view {
            case 0: MoversView()
            case 1: PicksView()
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
    @State private var live: [String: LiveQuote] = [:]
    @State private var loaded = false
    @State private var sel = ""            // "" = All
    @State private var followed: [Followed] = Following.load()
    @State private var whoMap: [String: [WhoEntry]]? = WhoTraded.cached(Following.load())
    @State private var newName = ""
    @State private var showNew = false
    @State private var showEdit = false
    private var hasLive: Bool { !Live.key.isEmpty }
    private var group: WatchGroup? { model.watchGroups.first { $0.name == sel } }
    private var shown: [String] { group.map { g in g.tickers.filter { model.watchlist.contains($0) } } ?? model.watchlist }
    private func updateGroup(_ name: String, _ f: (inout WatchGroup) -> Void) {
        if let i = model.watchGroups.firstIndex(where: { $0.name == name }) { f(&model.watchGroups[i]) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("Add a stock or fund, e.g. NVDA or VOO", text: $input)
                            .textInputAutocapitalization(.characters).autocorrectionDisabled().onSubmit(add)
                        Button(action: add) { Image(systemName: "plus.circle.fill") }.disabled(input.isEmpty)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            chip("All (\(model.watchlist.count))", on: sel.isEmpty) { sel = "" }
                            ForEach(model.watchGroups) { g in
                                chip("\(g.name) (\(g.tickers.filter { model.watchlist.contains($0) }.count))", on: sel == g.name) {
                                    if sel == g.name { newName = g.name; showEdit = true } else { sel = g.name }
                                }
                            }
                            Button { newName = ""; showNew = true } label: { Label("New group", systemImage: "plus") }
                                .buttonStyle(.bordered).font(.caption.weight(.semibold))
                        }
                    }
                } footer: {
                    Text((group != nil ? "Tap the selected group again to rename or delete it. New tickers go into this group. " : "")
                         + (hasLive ? "Live prices refresh every 15 seconds. You'll also get news and SEC filing alerts for these."
                         : "Showing latest closing prices. Add a free Finnhub key in Settings → Live prices for live prices."))
                }
                if !shown.isEmpty {
                    Section {
                        GroupCheckupView(title: group?.name ?? "Whole watchlist", tickers: shown, live: live,
                                         notes: group.map { g in Binding(get: { g.notes }, set: { n in updateGroup(g.name) { $0.notes = n } }) })
                    }
                } else if !model.watchlist.isEmpty {
                    EmptyStateView(title: "\"\(sel)\" is empty",
                                   message: "Add tickers above, or use the folder button on a stock in All to put it in this group.", systemImage: "folder")
                        .listRowBackground(Color.clear)
                }
                if model.watchlist.isEmpty {
                    EmptyStateView(title: "Your watchlist is empty",
                                   message: "Add tickers above, or tap \"Add to watchlist\" on any stock summary.", systemImage: "star")
                        .listRowBackground(Color.clear)
                }
                ForEach(shown, id: \.self) { t in
                    Section { row(t) }
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Watchlist")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .alert("New group", isPresented: $showNew) {
                TextField("e.g. Long term, Tech, Dividends", text: $newName)
                Button("Create") {
                    let n = newName.trimmingCharacters(in: .whitespaces)
                    if !n.isEmpty && !model.watchGroups.contains(where: { $0.name == n }) { model.watchGroups.append(WatchGroup(name: n)); sel = n }
                }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Rename or delete \"\(sel)\"", isPresented: $showEdit) {
                TextField("Name", text: $newName)
                Button("Rename") {
                    let n = newName.trimmingCharacters(in: .whitespaces)
                    if !n.isEmpty { let old = sel; updateGroup(old) { $0.name = n }; sel = n }
                }
                Button("Delete group", role: .destructive) { model.watchGroups.removeAll { $0.name == sel }; sel = "" }
                Button("Cancel", role: .cancel) {}
            }
            .task {
                followed = Following.load()
                if whoMap == nil { whoMap = await WhoTraded.build(followed, model: model) }
            }
            .task(id: shown) {
                while !Task.isCancelled {
                    if hasLive {
                        // Live: every 15 seconds, staying inside Finnhub's free 60 requests a minute.
                        var got: [String: LiveQuote] = [:]
                        for t in shown { if let q = await Live.quote(t) { got[t] = q } }
                        live.merge(got) { $1 }
                        loaded = true
                        let wait = max(15.0, Double(shown.count) * 1.1)
                        try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                    } else {
                        var got: [String: Double] = [:]
                        for t in shown { if let p = await StockInfo.price(t) { got[t] = p } }
                        prices.merge(got) { $1 }
                        loaded = true
                        try? await Task.sleep(nanoseconds: 300_000_000_000)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ t: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { model.openStock = StockTarget(ticker: t, company: "") } label: {
                HStack(spacing: 12) {
                    TickerBadge(text: t, color: .green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t).font(.headline)
                        if let q = live[t] {
                            HStack(spacing: 6) {
                                Text("$" + Format.number(q.price, decimals: 2)).font(.subheadline.weight(.bold))
                                Text(changeText(q)).font(.caption.weight(.semibold)).foregroundStyle(q.change >= 0 ? .green : .red)
                                Circle().fill(.red).frame(width: 6, height: 6)
                                Text("LIVE").font(.caption2.weight(.bold))
                            }
                        } else {
                            Text(prices[t].map { "$" + Format.number($0, decimals: 2) + " latest close" }
                                 ?? (loaded ? "Price unavailable" : "Price loading…"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                }
                .foregroundStyle(.primary)
            }
            .buttonStyle(.borderless)
            WatchInfo(ticker: t)
            PriceChartView(ticker: t, live: live[t])
            WhoBlock(ticker: t, following: whoMap?[t] ?? [], tracked: WhoTraded.others(model.moves, ticker: t, followed: Set(followed.map(\.name))),
                     followedNames: Set(followed.map(\.name)), loading: whoMap == nil)
            HStack {
                if let url = Broker.link(t) {
                    Button("Open in \(Broker.name)") { openURL(url) }.buttonStyle(.borderless).font(.caption)
                }
                Spacer()
                if !model.watchGroups.isEmpty {
                    Menu {
                        ForEach(model.watchGroups) { g in
                            let inIt = g.tickers.contains(t)
                            Button { updateGroup(g.name) { if inIt { $0.tickers.removeAll { $0 == t } } else { $0.tickers.append(t) } } } label: {
                                Label(g.name, systemImage: inIt ? "checkmark.circle.fill" : "circle")
                            }
                        }
                    } label: { Label("Groups", systemImage: "folder") }
                    .font(.caption)
                }
                Button(group != nil ? "Remove from group" : "Remove", role: .destructive) {
                    if let g = group { updateGroup(g.name) { $0.tickers.removeAll { $0 == t } } }
                    else {
                        model.watchlist.removeAll { $0 == t }
                        for i in model.watchGroups.indices { model.watchGroups[i].tickers.removeAll { $0 == t } }
                    }
                }
                .buttonStyle(.borderless).font(.caption)
            }
        }
    }

    private func add() {
        let t = input.uppercased().filter { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }
        if !t.isEmpty && !model.watchlist.contains(t) { model.watchlist.append(t) }
        if !t.isEmpty, let g = group, !g.tickers.contains(t) { updateGroup(g.name) { $0.tickers.append(t) } }
        input = ""
    }

    private func chip(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.caption.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(on ? Color.green.opacity(0.25) : Color.secondary.opacity(0.12), in: Capsule())
                .foregroundStyle(on ? Color.green : Color.primary)
        }
        .buttonStyle(.plain)
    }
}


/// Company line and short description for a watchlist card.
struct WatchInfo: View {
    let ticker: String
    @EnvironmentObject var model: AppModel
    @State private var info: StockSummary?
    var body: some View {
        Group {
            if let i = info {
                let line = [i.name, i.industry, i.exchange].filter { !$0.isEmpty }.joined(separator: " · ")
                if !line.isEmpty { Text(line).font(.caption.weight(.medium)).lineLimit(1) }
                if !i.about.isEmpty { Text(i.about).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
            }
        }
        .task(id: ticker) { info = await StockInfo.load(ticker: ticker, company: "", model: model) }
    }
}


/// Summary at the top of each watchlist group: what's in it, mix by area, diversification notes, ideas and your notes.
struct GroupCheckupView: View {
    let title: String
    let tickers: [String]
    let live: [String: LiveQuote]
    var notes: Binding<String>?
    @EnvironmentObject var model: AppModel
    @State private var info: [String: StockSummary] = [:]
    @State private var expanded = true

    var body: some View {
        let c = Portfolio.checkup(tickers, info: info, live: live, picks: model.picks)
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation { expanded.toggle() } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(title) checkup").font(.headline)
                        Text("\(tickers.count) \(tickers.count == 1 ? "holding" : "holdings") · \(c.sectors.count) \(c.sectors.count == 1 ? "area" : "areas")"
                             + (c.dayMove.map { String(format: " · today %+.2f%% avg", $0) } ?? ""))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").foregroundStyle(.secondary)
                }
                .foregroundStyle(.primary)
            }
            .buttonStyle(.borderless)
            if expanded {
                Text("What's in it").font(.subheadline.weight(.bold))
                ForEach(tickers, id: \.self) { t in
                    Button { model.openStock = StockTarget(ticker: t, company: "") } label: {
                        HStack(alignment: .firstTextBaseline) {
                            Text(t).font(.caption.weight(.bold)).frame(width: 56, alignment: .leading)
                            Text(Portfolio.describe(t, info[t])).font(.caption).lineLimit(1)
                        }
                        .foregroundStyle(.primary)
                    }
                    .buttonStyle(.borderless)
                }
                if !c.sectors.isEmpty {
                    Text("Mix by area").font(.subheadline.weight(.bold))
                    ForEach(c.sectors, id: \.0) { s in
                        let frac = Double(s.1) / Double(tickers.count)
                        HStack {
                            Text(s.0).font(.caption).lineLimit(1).frame(width: 140, alignment: .leading)
                            ProgressView(value: frac).tint(.green)
                            Text("\(Int(frac * 100))%").font(.caption2).frame(width: 36, alignment: .trailing)
                        }
                    }
                    Text("Counted by number of stocks, not dollars.").font(.caption2).foregroundStyle(.secondary)
                }
                if !c.notes.isEmpty {
                    Text("Diversification notes").font(.subheadline.weight(.bold))
                    ForEach(c.notes, id: \.self) { Text($0).font(.caption) }
                }
                if !c.ideas.isEmpty {
                    Text("Ideas to research (areas this group doesn't cover)").font(.subheadline.weight(.bold))
                    ForEach(c.ideas, id: \.self) { i in
                        Button { model.openStock = StockTarget(ticker: i.ticker, company: "") } label: {
                            HStack(alignment: .firstTextBaseline) {
                                Text(i.ticker).font(.caption.weight(.bold)).frame(width: 56, alignment: .leading)
                                Text("\(i.sector) · \(i.why)").font(.caption).lineLimit(1)
                            }
                            .foregroundStyle(.primary)
                        }
                        .buttonStyle(.borderless)
                    }
                }
                if let notes {
                    Text("Your notes").font(.subheadline.weight(.bold))
                    TextField("Why you're watching these, price targets, reminders…", text: notes, axis: .vertical)
                        .lineLimit(2...6).textFieldStyle(.roundedBorder)
                }
                Text("General info from SEC industry codes and the people you track, not personal financial advice.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .task(id: tickers) {
            for t in tickers + Portfolio.ideaCandidates(tickers, picks: model.picks) where info[t] == nil {
                info[t] = await StockInfo.load(ticker: t, company: "", model: model)
            }
        }
    }
}


/// "Who's in it": people you follow, then others the app tracks (Congress, insiders), with what they did.
struct WhoBlock: View {
    let ticker: String
    let following: [WhoEntry]
    let tracked: [WhoEntry]
    let followedNames: Set<String>
    let loading: Bool
    @EnvironmentObject var model: AppModel
    @State private var all = false
    @State private var looked: [WhoEntry]?
    @Environment(\.openURL) private var openURL

    private var others: [WhoEntry] {
        var seen = Set<String>()
        return (tracked + (looked ?? []).filter { !followedNames.contains($0.who) })
            .filter { seen.insert($0.who.lowercased() + $0.action + $0.date).inserted }
            .sorted { $0.date > $1.date }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("People you follow").font(.subheadline.weight(.bold))
            if !following.isEmpty {
                ForEach(all ? following : Array(following.prefix(4))) { row($0) }
            } else {
                Text(loading ? "Checking SEC filings for everyone you follow… (first time takes a minute)"
                     : "No one you follow has bought, sold or reported holding it.").font(.caption).foregroundStyle(.secondary)
            }
            Text("Others who bought or sold (Congress, company insiders)").font(.subheadline.weight(.bold))
            if !others.isEmpty {
                ForEach(all ? others : Array(others.prefix(3))) { row($0) }
            } else {
                Text(looked == nil ? "Looking up Congress trades and SEC insider filings…" : "No Congress trades or insider buys/sells reported recently.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if following.count > 4 || others.count > 3 {
                Button(all ? "Show less" : "Show all \(following.count + others.count)") { all.toggle() }
                    .buttonStyle(.borderless).font(.caption)
            }
        }
        .task(id: ticker) { looked = await WhoTraded.lookup(ticker, model: model) }
    }

    private func row(_ e: WhoEntry) -> some View {
        Button { if let u = URL(string: e.link), !e.link.isEmpty { openURL(u) } } label: {
            HStack(alignment: .top, spacing: 8) {
                Avatar(name: e.who, size: 28, fund: Following.funds.contains { $0.name == e.who })
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Text(e.who).font(.caption.weight(.bold)).lineLimit(1)
                        Text(e.action).font(.caption.weight(.bold))
                            .foregroundStyle(e.side == "buy" ? .green : e.side == "sell" ? .red : .blue)
                        if !e.date.isEmpty { Text("· \(e.date)").font(.caption2).foregroundStyle(.secondary) }
                    }
                    if !e.detail.isEmpty { Text(e.detail).font(.caption2).foregroundStyle(.secondary).lineLimit(2) }
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(.primary)
        }
        .buttonStyle(.borderless)
    }
}
