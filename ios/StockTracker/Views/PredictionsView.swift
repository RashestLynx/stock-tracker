import SwiftUI

/// Live prediction-market odds from Kalshi, sorted by today's biggest moves or by trading volume.
struct PredictionsView: View {
    @Environment(\.openURL) private var openURL
    @State private var events: [PredEvent] = []
    @State private var loading = false
    @State private var error: String?
    @State private var category = 0   // 0 all, 1 sports, 2 politics, 3 economics
    @State private var sort = 0       // 0 biggest moves, 1 most traded
    @State private var logging: PredEvent?
    @State private var logged = ""

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
                    Text("Live odds from Kalshi and Polymarket, both open in Illinois. A price of 62¢ means traders put the chance at about 62%. Big moves often mean news just broke. FanDuel and DraftKings don't share their odds, so they can't be shown here.")
                }

                if let e = error {
                    Section { Text(e).foregroundStyle(.red) }
                }
                if !logged.isEmpty {
                    Section { Text("\(logged). See it under My picks.").foregroundStyle(.green) }
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
                                Text("\(ev.source) · \(ev.category) · " + (ev.source == "Polymarket"
                                     ? "$\(Int(ev.volume24).formatted()) traded today"
                                     : "\(Int(ev.volume24).formatted()) contracts traded today"))
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if ev.outcomes.contains(where: { !$0.ref.isEmpty }) {
                            Button("Log a pick") { logging = ev }.font(.caption)
                        }
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
            .sheet(item: $logging) { ev in
                NavigationStack {
                    Form {
                        Section {
                            Text(ev.title).font(.headline)
                        } footer: {
                            Text("Pick what you think will happen. It's logged at today's price, and My picks follows it until it settles. Nothing is bought.")
                        }
                        ForEach(ev.outcomes.filter { !$0.ref.isEmpty }) { o in
                            Section(o.label) {
                                ForEach([("yes", o.prob), ("no", 1 - o.prob)], id: \.0) { side in
                                    NavigationLink(String(format: "%@  %.0f¢", side.0 == "yes" ? "Yes" : "No", side.1 * 100)) {
                                        BetSheetView(target: BetTarget(event: ev, outcome: o, side: side.0, estimate: side.1,
                                                                       estimateNote: "This is the market's own chance. Change it if you think differently.")) {
                                            logged = $0; logging = nil
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .navigationTitle("Log a pick")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { logging = nil } } }
                }
                .presentationDetents([.medium, .large])
            }
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
