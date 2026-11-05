import SwiftUI

/// Most-discussed and fastest-spiking stocks on Reddit.
struct WsbView: View {
    @EnvironmentObject var model: AppModel
    @State private var source = 0   // 0 = r/wallstreetbets, 1 = all stock subs
    @State private var sort = 0     // 0 = most talked about, 1 = spiking
    @State private var list: [WsbStock] = []
    @State private var loading = false
    @State private var error: String?

    private var shown: [WsbStock] {
        if sort == 0 { return Array(list.prefix(25)) }
        return Array(list.filter { $0.mentions >= 10 && $0.mentionChange != nil }
            .sorted { ($0.mentionChange ?? 0) > ($1.mentionChange ?? 0) }.prefix(25))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Source", selection: $source) {
                        Text("r/wallstreetbets").tag(0)
                        Text("All stock subs").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    Picker("Sort", selection: $sort) {
                        Text("Most talked about").tag(0)
                        Text("Spiking").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 4, trailing: 0))
                } footer: {
                    Text(sort == 0 ? "Most-mentioned stocks on Reddit in the last 24 hours."
                                   : "Stocks whose mentions jumped the most vs the day before.")
                }

                if let e = error { Section { Text(e).foregroundStyle(.red) } }

                if loading && list.isEmpty {
                    Section { ProgressView().frame(maxWidth: .infinity) }
                } else if shown.isEmpty && error == nil {
                    EmptyStateView(title: "Nothing trending right now", message: "Pull down to refresh.",
                                   systemImage: "flame")
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(shown) { w in
                        WsbRow(stock: w, trackedBy: trackedCategories(w.ticker))
                    }
                }

                Section {
                    Link("Reddit data from ApeWisdom", destination: URL(string: Wsb.sourceURL)!).font(.caption)
                } footer: {
                    Text("Hype isn't a recommendation; trending stocks often swing hard both ways.")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("WallStreetBets")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load() }
            .task(id: source) { await load() }
        }
    }

    private func trackedCategories(_ ticker: String) -> [Category] {
        let cats = Set(model.picks.filter { $0.ticker == ticker }.map(\.category))
        return Category.allCases.filter { cats.contains($0) }
    }

    private func load() async {
        loading = true
        error = nil
        do { list = try await Wsb.trending(filter: source == 0 ? "wallstreetbets" : "all-stocks") }
        catch { self.error = error.localizedDescription }
        loading = false
    }
}

struct WsbRow: View {
    let stock: WsbStock
    let trackedBy: [Category]
    @EnvironmentObject var model: AppModel

    var body: some View {
        Button {
            model.openStock = StockTarget(ticker: stock.ticker, company: stock.name)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    Text("#\(stock.rank)").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                        .frame(width: 36, alignment: .leading)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(stock.ticker).font(.headline)
                        Text(stock.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    if let ch = stock.mentionChange {
                        let up = ch >= 0
                        Text(String(format: "%+.0f%%", ch))
                            .font(.subheadline.weight(.bold)).foregroundStyle(up ? .green : .red)
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background((up ? Color.green : Color.red).opacity(0.14), in: Capsule())
                    } else {
                        Text("New").font(.subheadline.weight(.bold)).foregroundStyle(.purple)
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Color.purple.opacity(0.14), in: Capsule())
                    }
                }
                Text(details).font(.caption)
                if !trackedBy.isEmpty {
                    HStack(spacing: 6) {
                        Text("Also in:").font(.caption).foregroundStyle(.secondary)
                        ForEach(trackedBy) { CategoryPill(category: $0) }
                    }
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var details: String {
        var s = "\(Format.number(Double(stock.mentions))) mentions · \(Format.number(Double(stock.upvotes))) upvotes"
        if stock.rankChange > 0 { s += " · ▲\(stock.rankChange) spots" }
        if stock.rankChange < 0 { s += " · ▼\(-stock.rankChange) spots" }
        return s
    }
}
