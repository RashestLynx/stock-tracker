import SwiftUI

/// Live prediction-market odds from Kalshi, sorted by today's biggest moves or by trading volume.
struct PredictionsView: View {
    @Environment(\.openURL) private var openURL
    @State private var events: [PredEvent] = []
    @State private var loading = false
    @State private var error: String?
    @State private var category = 0   // 0 all, 1 sports, 2 politics, 3 economics
    @State private var sort = 0       // 0 biggest moves, 1 most traded

    private let cats = ["All", "Sports", "Politics", "Economics"]

    private var shown: [PredEvent] {
        let filtered = events.filter { category == 0 || $0.category == cats[category] }
        let sorted = filtered.sorted { sort == 0 ? abs($0.move) > abs($1.move) : $0.volume24 > $1.volume24 }
        return Array(sorted.prefix(25))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    PillTabs(options: cats.enumerated().map { ($0.offset, $0.element) }, selection: $category)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    PillTabs(options: [(0, "Biggest moves today"), (1, "Most traded")], selection: $sort)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } footer: {
                    Text("Live odds from Kalshi, a federally regulated prediction market open in Illinois. A price of 62¢ means traders put the chance at about 62%. Big moves often mean news just broke.")
                }

                if let e = error {
                    Section { Text(e).foregroundStyle(.red) }
                }
                if loading && events.isEmpty {
                    Section { ProgressView().frame(maxWidth: .infinity) }
                } else if !loading && error == nil && shown.isEmpty {
                    Section { Text("Nothing to show yet.").foregroundStyle(.secondary) }
                }

                ForEach(shown) { ev in
                    Section {
                        Button {
                            if let u = URL(string: ev.url) { openURL(u) }
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(ev.title).font(.headline).lineLimit(2)
                                ForEach(ev.outcomes) { o in
                                    HStack {
                                        Text(o.label).lineLimit(1)
                                        Spacer()
                                        if let c = o.change, abs(c) >= 0.01 {
                                            Text(String(format: "%+.0f today", c * 100))
                                                .font(.caption2).foregroundStyle(c > 0 ? .green : .red)
                                        }
                                        Text(String(format: "%.0f%%", o.prob * 100)).font(.subheadline.weight(.bold))
                                    }
                                }
                                Text("\(ev.category) · \(Int(ev.volume24).formatted()) contracts traded today")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Odds are the crowd's estimate, not a sure thing. Prediction markets are risky; only trade money you can afford to lose. Not financial advice.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Predictions")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private func load(force: Bool) async {
        if loading { return }
        loading = true
        error = nil
        do { events = try await Predictions.load(force: force) } catch { self.error = error.localizedDescription }
        loading = false
    }
}
