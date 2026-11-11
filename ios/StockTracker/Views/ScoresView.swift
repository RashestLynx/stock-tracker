import SwiftUI

/// Top 30: the 10 best-scoring stocks for growth, for income and for wealth.
struct ScoresView: View {
    @EnvironmentObject var model: AppModel
    @State private var view = 0   // 0 growth, 1 income, 2 wealth
    @State private var loading = false
    @State private var error: String?
    @State private var keyInput = ""

    private var blurb: String {
        switch view {
        case 0: return "Growth: sales and profits rising fast, with the share price trending up."
        case 1: return "Income: a solid dividend paid for 10 years, covered by cash, and still growing."
        default: return "Wealth: steady compounders. Very profitable, low debt, reliable growth year after year."
        }
    }

    private var title: String {
        switch view {
        case 0: return "Top 10 for growth"
        case 1: return "Top 10 for income"
        default: return "Top 10 for wealth"
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if model.massiveKey.isEmpty {
                    Section {
                        Text("Top 30 scores every U.S. stock using SEC filings and daily prices from Massive (formerly Polygon.io). Their free plan works: sign up, copy your API key and paste it here. It's saved on this iPhone only.")
                        Link("Get a free key", destination: URL(string: Movers.signupURL)!)
                        SecureField("Paste API key", text: $keyInput)
                        Button("Save and score stocks") {
                            model.massiveKey = keyInput.trimmingCharacters(in: .whitespaces)
                            Task { await load(force: true) }
                        }
                        .disabled(keyInput.trimmingCharacters(in: .whitespaces).isEmpty)
                    } header: {
                        Text("One-time setup")
                    }
                }
                Section {
                    PillTabs(options: [(0, "Growth"), (1, "Income"), (2, "Wealth")], selection: $view)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } footer: {
                    Text(blurb)
                }

                if loading {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text(model.scores == nil
                                 ? "Scoring every U.S. stock. The first time takes a minute or two. \(model.scoresProgress)"
                                 : "Updating… \(model.scoresProgress)")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }

                if let e = error {
                    Section { Text(e).foregroundStyle(.red) }
                }

                if let r = model.scores {
                    let list = view == 0 ? r.growth : view == 1 ? r.income : r.wealth
                    Section {
                        if list.isEmpty {
                            Text("No stocks passed every check this time.").foregroundStyle(.secondary)
                        }
                        ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                            Button {
                                model.openStock = StockTarget(ticker: s.ticker, company: s.name)
                            } label: {
                                row(i + 1, s)
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text(title)
                    } footer: {
                        Text("Scored \(r.screened) U.S. companies using their \(String(r.fiscalYear)) annual reports (SEC) and prices from \(r.priceDate) (Massive). Each score is 0 to 100. A stock appears on one list only, with at most 3 per industry. Pull down to refresh.")
                    }
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Scores are made automatically from public filings and can contain errors or be out of date. They are a starting point for research, not financial advice.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Top 30")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private func row(_ rank: Int, _ s: ScoredStock) -> some View {
        HStack(spacing: 12) {
            Text("\(rank)").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                .frame(width: 22, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(s.ticker).font(.headline)
                Text(s.sector.isEmpty ? s.name : "\(s.name) · \(s.sector)")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text(s.facts.joined(separator: " · ")).font(.caption).lineLimit(2)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(s.score)")
                    .font(.subheadline.weight(.bold)).foregroundStyle(.green)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Color.green.opacity(0.14), in: Capsule())
                Text(String(format: "$%.2f", s.price)).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }

    private func load(force: Bool) async {
        if loading || model.massiveKey.isEmpty { return }
        loading = true
        error = nil
        do { try await model.loadScores(force: force) } catch { self.error = error.localizedDescription }
        loading = false
    }
}
