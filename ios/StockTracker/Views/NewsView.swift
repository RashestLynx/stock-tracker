import SwiftUI

/// Near-live news: market headlines, your stocks, and SEC company announcements. Refreshes every minute.
struct NewsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var view = 0   // 0 market, 1 your stocks, 2 filings
    @State private var items: [NewsItem] = []
    @State private var loading = false
    @State private var error: String?
    @State private var updated: Date?

    private var yourTickers: [String] {
        var counts: [String: Int] = [:]
        model.picks.forEach { counts[$0.ticker, default: 0] += 1 }
        let popular = counts.sorted { $0.value > $1.value }.map(\.key)
        var seen = Set<String>()
        return (model.tickers + popular).filter { seen.insert($0).inserted }.prefix(8).map { $0 }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    PillTabs(options: [(0, "Market"), (1, "Your stocks"), (2, "Filings"), (3, "People")], selection: $view)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } header: {
                    HStack(spacing: 6) {
                        Circle().fill(.red).frame(width: 8, height: 8)
                        Text("LIVE · refreshes every minute" + (updated.map { " · updated \(Format.ago($0))" } ?? ""))
                    }
                } footer: {
                    Text(view == 3 ? "Private investors like finance YouTubers don't have to report their trades, so this follows what they share publicly: their newest videos (where they often say what they're buying or selling), news about them, and their businesses. You'll get a notification when they post a new video."
                         : view == 0 ? "Latest market headlines from Google News."
                         : view == 1 ? (yourTickers.isEmpty ? "Add tickers to your watchlist in Settings to follow them here."
                                        : "Headlines about \(yourTickers.joined(separator: ", ")).")
                         : "Official company announcements (SEC 8-K), posted the moment they're filed: earnings, CEO changes, deals and more.")
                }

                if view == 3 {
                    FollowingSections()
                }
                if let e = error, view != 3 { Section { Text(e).foregroundStyle(.red) } }
                if view != 3 && loading && items.isEmpty { Section { ProgressView().frame(maxWidth: .infinity) } }

                ForEach(view == 3 ? [] : Array(items.prefix(60))) { n in
                    // Each headline as a post: the source is the author.
                    HStack(alignment: .top, spacing: 12) {
                        Avatar(name: n.source.isEmpty ? "News" : n.source, size: 40)
                        VStack(alignment: .leading, spacing: 4) {
                            Button {
                                if let u = URL(string: n.url) { openURL(u) }
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 4) {
                                        Text(n.source.isEmpty ? "News" : n.source).font(.subheadline.weight(.bold)).lineLimit(1)
                                        Text("· " + Format.ago(n.time)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    Text(n.title).font(.body.weight(.medium)).fixedSize(horizontal: false, vertical: true)
                                    if !n.detail.isEmpty { Text(n.detail).font(.caption).foregroundStyle(.secondary) }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.primary)
                            if !n.ticker.isEmpty {
                                Button("$\(n.ticker)  ›") {
                                    model.openStock = StockTarget(ticker: n.ticker, company: n.isFiling ? n.title : "")
                                }
                                .font(.subheadline.weight(.bold))
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(Color(.secondarySystemFill), in: Capsule())
                                .buttonStyle(.plain)
                                .foregroundStyle(Brand.accent)
                                .padding(.top, 2)
                            }
                        }
                    }
                    .padding(.vertical, 6)
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Headlines from Google News; filings from SEC EDGAR. Tap a story to read it at the source.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Live news")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load() }
            .task(id: view) {
                items = []
                while !Task.isCancelled && view != 3 {
                    await load()
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                }
            }
        }
    }

    private func load() async {
        loading = true
        error = nil
        do {
            switch view {
            case 0: items = try await News.market()
            case 1: items = try await News.forTickers(yourTickers)
            default: items = try await News.filings(ua: model.userAgent, ciks: await model.cikMap())
            }
            updated = Date()
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}
