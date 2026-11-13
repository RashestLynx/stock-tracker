import SwiftUI

/// "Watchlist" and "My accounts" in the Watchlist tab.
struct WatchlistTabView: View {
    @State private var view = 0
    var body: some View {
        VStack(spacing: 0) {
            PillTabs(options: [(0, "Watchlist"), (1, "My accounts"), (2, "Covered calls"), (3, "Track record")], selection: $view)
                .padding(.horizontal).padding(.vertical, 8)
            switch view {
            case 0: WatchlistView()
            case 1: MyAccountsView()
            case 2: CoveredCallsView()
            default: TrackRecordView()
            }
        }
    }
}

/// Everything you own: Robinhood (linked read-only through SnapTrade) plus funds you type in, with a checkup.
struct MyAccountsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var hasKey = Accounts.hasKey
    @State private var portfolio: MyMoney?
    @State private var loading = false
    @State private var clientInput = ""
    @State private var keyInput = ""
    @State private var manual = Accounts.manual
    @State private var mAccount = "T. Rowe Price"
    @State private var mName = ""
    @State private var mTicker = ""
    @State private var mValue = ""

    private func money(_ v: Double) -> String { "$" + Format.number(v, decimals: 2) }

    var body: some View {
        NavigationStack {
            List {
                if let p = portfolio, !p.holdings.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Total").font(.caption).foregroundStyle(.secondary)
                            Text(money(p.total)).font(.largeTitle.weight(.bold))
                            ForEach(Dictionary(grouping: p.holdings, by: \.account).sorted(by: { $0.key < $1.key }), id: \.key) { entry in
                                Text("\(entry.key): \(money(entry.value.reduce(0) { $0 + $1.value }))").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if let e = portfolio?.error {
                    Section { Text(e).foregroundStyle(.red) }
                }
                if let p = portfolio {
                    let notes = Accounts.checkup(p, moves: model.moves, scores: model.scores)
                    if !notes.isEmpty {
                        Section {
                            ForEach(notes) { n in
                                Button {
                                    if !n.ticker.isEmpty { model.openStock = StockTarget(ticker: n.ticker, company: "") }
                                } label: {
                                    HStack(alignment: .top, spacing: 8) {
                                        Image(systemName: n.good ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                                            .foregroundStyle(n.good ? .green : .orange)
                                        Text(n.text).font(.subheadline)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        } header: {
                            Text("Checkup")
                        } footer: {
                            Text("Not advice: things worth knowing about what you own.")
                        }
                    }
                    if !p.holdings.isEmpty {
                        Section("Holdings") {
                            ForEach(p.holdings.sorted { $0.value > $1.value }) { h in
                                Button {
                                    if !h.ticker.isEmpty { model.openStock = StockTarget(ticker: h.ticker, company: h.name) }
                                } label: { holdingRow(h, total: p.total) }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                Section {
                    if hasKey {
                        Text("Linked, read-only. Pull down to refresh.")
                        Button("Unlink", role: .destructive) { Accounts.forgetKey(); hasKey = false; Task { await load(force: true) } }
                    } else {
                        Text("1. Make a free SnapTrade Personal account and link Robinhood there.\n2. In SnapTrade's dashboard, create a Personal API key.\n3. Paste the Client ID and Consumer Key below. They're kept in your iPhone's Keychain.")
                            .font(.footnote)
                        Link("Open SnapTrade", destination: URL(string: Accounts.signup)!)
                        TextField("Client ID", text: $clientInput).textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("Consumer Key", text: $keyInput)
                        Button("Link") {
                            Accounts.saveKey(clientId: clientInput, consumerKey: keyInput)
                            clientInput = ""; keyInput = ""
                            hasKey = true
                            Task { await load(force: true) }
                        }
                        .disabled(clientInput.trimmingCharacters(in: .whitespaces).isEmpty || keyInput.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } header: {
                    Text("Robinhood")
                } footer: {
                    Text("Linked read-only through SnapTrade Personal, which is free for personal use. The app can see what you own but can't trade or move money.")
                }
                Section {
                    ForEach(manual) { m in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.name.isEmpty ? m.ticker : m.name).font(.subheadline.weight(.semibold))
                            Text("\(m.account)\(m.ticker.isEmpty ? "" : " · \(m.ticker)") · \(money(m.value))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .onDelete { idx in
                        manual.remove(atOffsets: idx)
                        Accounts.manual = manual
                        Task { await load(force: false) }
                    }
                    TextField("Account", text: $mAccount)
                    TextField("Fund name, e.g. Retirement 2055", text: $mName)
                    TextField("Ticker, if it has one", text: $mTicker).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    TextField("Current value in dollars", text: $mValue).keyboardType(.decimalPad)
                    Button("Add") {
                        guard let v = parsedValue else { return }
                        manual.append(ManualHolding(account: mAccount.isEmpty ? "Other" : mAccount, name: mName.trimmingCharacters(in: .whitespaces),
                                                    ticker: mTicker.trimmingCharacters(in: .whitespaces).uppercased(), value: v, updated: Date()))
                        Accounts.manual = manual
                        mName = ""; mTicker = ""; mValue = ""
                        Task { await load(force: false) }
                    }
                    .disabled((mName.trimmingCharacters(in: .whitespaces).isEmpty && mTicker.trimmingCharacters(in: .whitespaces).isEmpty) || parsedValue == nil)
                } header: {
                    Text("T. Rowe Price and other accounts")
                } footer: {
                    Text("T. Rowe Price can't be linked automatically, so type in each fund and its current value. Update the values now and then from your statement. Swipe left to remove one.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("My accounts")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private var parsedValue: Double? {
        let v = Double(mValue.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "$", with: ""))
        return (v ?? 0) > 0 ? v : nil
    }

    @ViewBuilder
    private func holdingRow(_ h: OwnedHolding, total: Double) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(h.ticker.isEmpty ? h.name : h.ticker).font(.headline)
                Text([h.ticker.isEmpty ? nil : h.name, h.account, h.units.map { "\(WatchlistView.shareText($0)) shares" }]
                        .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(money(h.value)).font(.subheadline.weight(.bold))
                Text(String(format: "%.1f%% of total", h.value / total * 100)).font(.caption2).foregroundStyle(.secondary)
                if let c = h.cost, let u = h.units, c > 0 {
                    let gain = h.value - c * u
                    Text(String(format: "%@%@ (%+.0f%%)", gain >= 0 ? "+" : "-", money(abs(gain)), gain / (c * u) * 100))
                        .font(.caption2).foregroundStyle(gain >= 0 ? .green : .red)
                }
            }
        }
        .contentShape(Rectangle())
    }

    private func load(force: Bool) async {
        if loading { return }
        loading = true
        portfolio = await Accounts.load(force: force)
        loading = false
    }
}
