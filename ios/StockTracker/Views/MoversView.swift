import SwiftUI

/// The week's 10 best and 10 worst performing stocks.
struct MoversView: View {
    @EnvironmentObject var model: AppModel
    @State private var scope = 0   // 0 = whole market, 1 = people you track
    @State private var loading = false
    @State private var error: String?
    @State private var keyInput = ""

    var body: some View {
        NavigationStack {
            List {
                if model.massiveKey.isEmpty {
                    Section {
                        Text("Weekly prices for every U.S. stock come from Massive (formerly Polygon.io). Their free plan works: sign up, copy your API key, and paste it here.")
                        Link("Get a free key", destination: URL(string: Movers.signupURL)!)
                        SecureField("Paste API key", text: $keyInput)
                        Button("Save and load movers") {
                            model.massiveKey = keyInput.trimmingCharacters(in: .whitespaces)
                            Task { await load(force: true) }
                        }
                        .disabled(keyInput.trimmingCharacters(in: .whitespaces).isEmpty)
                    } header: {
                        Text("One-time setup")
                    }
                } else {
                    Section {
                        Picker("Show", selection: $scope) {
                            Text("Whole market").tag(0)
                            Text("People you track").tag(1)
                        }
                        .pickerStyle(.segmented)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    } footer: {
                        if let r = model.movers {
                            Text("Price change from \(r.fromDate) to \(r.toDate) (closing prices). " +
                                 (scope == 0 ? "U.S. stocks over $5 with real trading volume."
                                             : "Only stocks the people you track bought, sold or hold."))
                        } else if loading {
                            Text("Loading this week's prices…")
                        }
                    }

                    if let e = error {
                        Section { Text(e).foregroundStyle(.red) }
                    }

                    if let r = model.movers {
                        moverSection("Top 10 this week", scope == 0 ? r.marketGainers : r.trackedGainers, up: true)
                        moverSection("Bottom 10 this week", scope == 0 ? r.marketLosers : r.trackedLosers, up: false)
                    } else if loading {
                        Section { ProgressView().frame(maxWidth: .infinity) }
                    }

                    Section {
                        EmptyView()
                    } footer: {
                        Text("Data from Massive. Past performance isn't a prediction or financial advice.")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("This week's movers")
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private func load(force: Bool) async {
        guard !model.massiveKey.isEmpty else { return }
        loading = true
        error = nil
        do { try await model.loadMovers(force: force) } catch { self.error = error.localizedDescription }
        loading = false
    }

    @ViewBuilder
    private func moverSection(_ title: String, _ list: [Mover], up: Bool) -> some View {
        let accent: Color = up ? .green : .red
        Section {
            if list.isEmpty {
                Text("Nothing to show yet.").foregroundStyle(.secondary)
            }
            ForEach(Array(list.enumerated()), id: \.element.id) { i, m in
                Button {
                    model.openStock = StockTarget(ticker: m.ticker, company: m.name)
                } label: {
                    HStack(spacing: 12) {
                        Text("\(i + 1)").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                            .frame(width: 22, alignment: .leading)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.ticker).font(.headline)
                            Text(m.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(String(format: "%+.1f%%", m.pct))
                                .font(.subheadline.weight(.bold)).foregroundStyle(accent)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(accent.opacity(0.14), in: Capsule())
                            Text(String(format: "$%.2f", m.price)).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text(title).foregroundStyle(accent)
        }
    }
}
