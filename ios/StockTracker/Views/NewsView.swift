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
                    Picker("View", selection: $view) {
                        Text("Market").tag(0)
                        Text("Your stocks").tag(1)
                        Text("Filings").tag(2)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } header: {
                    HStack(spacing: 6) {
                        Circle().fill(.red).frame(width: 8, height: 8)
                        Text("LIVE · refreshes every minute" + (updated.map { " · updated \(Format.ago($0))" } ?? ""))
                    }
                } footer: {
                    Text(view == 0 ? "Latest market headlines from Google News."
                         : view == 1 ? (yourTickers.isEmpty ? "Add tickers to your watchlist in Settings to follow them here."
                                        : "Headlines about \(yourTickers.joined(separator: ", ")).")
                         : "Official company announcements (SEC 8-K), posted the moment they're filed: earnings, CEO changes, deals and more.")
                }

                if let e = error { Section { Text(e).foregroundStyle(.red) } }
                if loading && items.isEmpty { Section { ProgressView().frame(maxWidth: .infinity) } }

                ForEach(items.prefix(60)) { n in
                    VStack(alignment: .leading, spacing: 6) {
                        Button {
                            if let u = URL(string: n.url) { openURL(u) }
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(n.source.isEmpty ? "News" : n.source).font(.caption.weight(.semibold))
                                        .foregroundStyle(Color.accentColor).lineLimit(1)
                                    Spacer()
                                    Text(Format.ago(n.time)).font(.caption2).foregroundStyle(.secondary)
                                }
                                Text(n.title).font(.subheadline.weight(.semibold))
                                if !n.detail.isEmpty { Text(n.detail).font(.caption).foregroundStyle(.secondary) }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.primary)
                        if !n.ticker.isEmpty {
                            Button("Summary of \(n.ticker)") {
                                model.openStock = StockTarget(ticker: n.ticker, company: n.isFiling ? n.title : "")
                            }
                            .buttonStyle(.borderless).font(.footnote.weight(.medium))
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Headlines from Google News; filings from SEC EDGAR. Tap a story to read it at the source.")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Live news")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load() }
            .task(id: view) {
                items = []
                while !Task.isCancelled {
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
