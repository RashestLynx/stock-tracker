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
        let who = Array(Set(model.picks.filter { $0.ticker == t }.map { "\($0.who) (\($0.action == "hold" ? "holds" : "bought"))" }
            + model.moves.filter { $0.ticker == t && $0.side == "sell" }.map { "\($0.who) (sold)" })).sorted()
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
            Text(who.isEmpty ? "No one you track has bought, sold or held it recently."
                 : "\(who.count) tracked \(who.count == 1 ? "person" : "people"): " + who.prefix(4).joined(separator: ", ")
                    + (who.count > 4 ? " and \(who.count - 4) more" : ""))
                .font(.caption).foregroundStyle(who.isEmpty ? .secondary : .primary)
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
