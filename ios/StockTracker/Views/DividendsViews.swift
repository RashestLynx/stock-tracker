import SwiftUI

/// The "paste your Massive key" section, for views that need one.
struct MassiveKeySection: View {
    @EnvironmentObject var model: AppModel
    let what: String
    var onSaved: () -> Void
    @State private var keyInput = ""

    var body: some View {
        Section {
            Text("\(what) come from Massive (formerly Polygon.io). Their free plan works: sign up, copy your API key, and paste it here.")
            Link("Get a free key", destination: URL(string: Movers.signupURL)!)
            SecureField("Paste API key", text: $keyInput)
            Button("Save key") {
                model.massiveKey = keyInput.trimmingCharacters(in: .whitespaces)
                onSaved()
            }
            .disabled(keyInput.trimmingCharacters(in: .whitespaces).isEmpty)
        } header: {
            Text("One-time setup")
        }
    }
}

private func prettyDay(_ d: String) -> String {
    guard let date = Movers.dayFormatter.date(from: d) else { return d }
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = Movers.dayFormatter.timeZone
    f.dateFormat = "MMM d, yyyy"
    return f.string(from: date)
}

/// One-time cash payouts companies announced, coming up and recently paid.
struct SpecialDividendsView: View {
    @EnvironmentObject var model: AppModel
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if model.massiveKey.isEmpty {
                    MassiveKeySection(what: "Dividend announcements") { Task { await load(force: true) } }
                } else {
                    Section {
                        EmptyView()
                    } footer: {
                        Text("One-time cash payouts on top of a company's usual dividend, often from a big sale, a strong year or extra cash. To get one you must own the stock before its ex-dividend day.")
                    }
                    if let e = error { Section { Text(e).foregroundStyle(.red) } }
                    if let r = model.specialDividends {
                        let today = Movers.dayFormatter.string(from: Date())
                        let coming = r.items.filter { $0.exDate >= today }.sorted { $0.exDate < $1.exDate }
                        let past = r.items.filter { $0.exDate < today }
                        section("Coming up", "Buy before the ex-dividend day to get it.", coming, upcoming: true)
                        section("Recently paid", "Ex-dividend day in the last 4 months.", past, upcoming: false)
                    } else if loading {
                        Section { ProgressView().frame(maxWidth: .infinity) }
                    }
                    Section {
                        EmptyView()
                    } footer: {
                        Text("Data from Massive. A special dividend is usually one-time and doesn't mean the company will pay it again. The share price normally drops by about the dividend on the ex-dividend day. Not financial advice.")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Special dividends")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private func load(force: Bool) async {
        guard !model.massiveKey.isEmpty else { return }
        loading = true
        error = nil
        do { try await model.loadSpecialDividends(force: force) } catch { self.error = error.localizedDescription }
        loading = false
    }

    @ViewBuilder
    private func section(_ title: String, _ sub: String, _ list: [SpecialDividend], upcoming: Bool) -> some View {
        Section {
            if list.isEmpty {
                Text(upcoming ? "No special dividends announced right now." : "Nothing in the last 4 months.")
                    .foregroundStyle(.secondary)
            }
            ForEach(list) { d in
                Button {
                    model.openStock = StockTarget(ticker: d.ticker, company: d.name)
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.ticker).font(.headline)
                            Text(d.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            Text("Ex-dividend \(prettyDay(d.exDate))" + (d.payDate.isEmpty ? "" : " · paid \(prettyDay(d.payDate))"))
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(String(format: "$%.2f", d.amount))
                                .font(.subheadline.weight(.bold)).foregroundStyle(.green)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(Color.green.opacity(0.14), in: Capsule())
                            if d.price > 0 {
                                Text(String(format: "%.1f%% of the $%.2f price", d.amount / d.price * 100, d.price))
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text(title).foregroundStyle(upcoming ? Color.green : Color.primary)
        } footer: {
            Text(sub)
        }
    }
}

/// U.S. stocks with the highest regular dividend yields.
struct HighDividendsView: View {
    @EnvironmentObject var model: AppModel
    @State private var loading = false
    @State private var error: String?
    @State private var scope = 0   // 0 = up to 10%, 1 = all

    var body: some View {
        NavigationStack {
            List {
                if model.massiveKey.isEmpty {
                    MassiveKeySection(what: "Dividends and prices for every U.S. stock") { Task { await load(force: true) } }
                } else {
                    Section {
                        PillTabs(options: [(0, "Up to \(Int(Dividends.riskyYield))%"), (1, "All high yields")], selection: $scope)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    } footer: {
                        Text("Yield is a year of the latest regular dividend divided by the share price: how much cash you'd get each year for every $100 invested. U.S. stocks over $5 paying at least \(Int(Dividends.minYield))%."
                             + (model.highYield.map { $0.priceDate.isEmpty ? "" : " Prices from \($0.priceDate)." } ?? ""))
                    }
                    if let e = error { Section { Text(e).foregroundStyle(.red) } }
                    if let r = model.highYield {
                        let list = Array((scope == 0 ? r.items.filter { $0.yieldPct <= Dividends.riskyYield } : r.items).prefix(30))
                        Section {
                            if list.isEmpty { Text("Nothing to show yet.").foregroundStyle(.secondary) }
                            ForEach(Array(list.enumerated()), id: \.element.id) { i, h in row(i, h) }
                        } header: {
                            Text("Highest dividend yields").foregroundStyle(.green)
                        }
                    } else if loading {
                        Section {
                            VStack(spacing: 8) {
                                ProgressView()
                                Text((model.highYieldProgress.isEmpty ? "Loading…" : model.highYieldProgress)
                                     + " This takes a minute or two the first time (free plan limits).")
                                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                        }
                    }
                    Section {
                        EmptyView()
                    } footer: {
                        Text("Data from Massive. A very high yield (over \(Int(Dividends.riskyYield))%) often means the price fell because investors expect the dividend to be cut. For dividends that are covered by cash and growing, see Top 30 → Income. Not financial advice.")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("High dividends")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private func load(force: Bool) async {
        guard !model.massiveKey.isEmpty else { return }
        loading = true
        error = nil
        do { try await model.loadHighYield(force: force) } catch { self.error = error.localizedDescription }
        loading = false
    }

    private func row(_ i: Int, _ h: HighYield) -> some View {
        let risky = h.yieldPct > Dividends.riskyYield
        let accent: Color = risky ? .red : .green
        return Button {
            model.openStock = StockTarget(ticker: h.ticker, company: h.name)
        } label: {
            HStack(spacing: 12) {
                Text("\(i + 1)").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    .frame(width: 26, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(h.ticker).font(.headline)
                    Text(h.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(String(format: "$%.2f a year, paid ", h.perYear) + Dividends.frequencyLabel(h.frequency) + (risky ? " · may be cut" : ""))
                        .font(.caption2).foregroundStyle(risky ? Color.red : Color.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(String(format: "%.1f%%", h.yieldPct))
                        .font(.subheadline.weight(.bold)).foregroundStyle(accent)
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(accent.opacity(0.14), in: Capsule())
                    Text(String(format: "$%.2f", h.price)).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
