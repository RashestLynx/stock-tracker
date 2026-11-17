import SwiftUI

/// Upcoming, just-priced and just-filed IPOs, with alerts at every stage.
struct IposView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var result: IpoResult? = Ipos.cached()
    @State private var loading = false
    @State private var error: String?
    @State private var alertsOn = Ipos.alertsOn
    @State private var filingAlerts = Ipos.filingAlertsOn
    @State private var hasKey = !Live.key.isEmpty
    @State private var keyInput = ""
    @State private var log = Ipos.log()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle(isOn: $alertsOn) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Alert me about big IPOs")
                            Text("Deals over \(Ipos.money(Ipos.bigDeal)): price range set, raised or cut, more shares, priced, first trade, and big investors lined up.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .onChange(of: alertsOn) { _, v in Ipos.alertsOn = v }
                    Toggle(isOn: $filingAlerts) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Every new IPO filing")
                            Text("The earliest sign (S-1 or F-1 with the SEC), weeks before a price. Includes small companies.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .disabled(!alertsOn)
                    .onChange(of: filingAlerts) { _, v in Ipos.filingAlertsOn = v }
                } footer: {
                    Text("Checked in the background with your other alerts. iPhone decides how often the app gets to check.")
                }

                if !hasKey {
                    Section {
                        Text("The IPO calendar (dates, price ranges, deal sizes, priced or not) comes from Finnhub. Without it you only see new SEC filings.")
                        Link("Get a free key", destination: URL(string: Live.signup)!)
                        SecureField("Paste Finnhub key", text: $keyInput)
                        Button("Save key") {
                            Live.key = keyInput
                            hasKey = !Live.key.isEmpty
                            Task { await load() }
                        }
                        .disabled(keyInput.trimmingCharacters(in: .whitespaces).isEmpty)
                    } header: { Text("Add a free Finnhub key") }
                }

                if let e = error { Section { Text(e).foregroundStyle(.red) } }
                if result == nil && loading { Section { ProgressView().frame(maxWidth: .infinity) } }

                if !log.isEmpty {
                    Section {
                        ForEach(log.prefix(6)) { e in
                            Button { if let u = URL(string: e.link) { openURL(u) } } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(e.title).font(.subheadline.weight(.semibold))
                                    Text(e.body).font(.caption).foregroundStyle(.secondary)
                                    Text(e.time.formatted(date: .abbreviated, time: .shortened)).font(.caption2).foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    } header: { Text("Latest IPO alerts") }
                }

                if let r = result {
                    Section {
                        if r.upcoming.isEmpty {
                            Text(hasKey ? "No IPOs on the calendar right now." : "Needs the Finnhub key.").foregroundStyle(.secondary)
                        }
                        ForEach(r.upcoming.prefix(40)) { IpoRowView(ipo: $0) }
                    } header: { Text("Coming up") } footer: { Text("Biggest first within each day. Request shares before it prices.") }

                    Section {
                        if r.priced.isEmpty { Text("Nothing priced in the last 3 weeks.").foregroundStyle(.secondary) }
                        ForEach(r.priced) { IpoRowView(ipo: $0) }
                    } header: { Text("Just priced") } footer: { Text("IPO price, first trade and where it is now.") }

                    Section {
                        if r.filings.isEmpty { Text("No new filings in the SEC's latest list.").foregroundStyle(.secondary) }
                        ForEach(r.filings.prefix(25)) { f in
                            Button { if let u = URL(string: f.link) { openURL(u) } } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.name).font(.subheadline.weight(.semibold))
                                    Text("\(f.form) · \(f.date)" + (f.cornerstone.isEmpty ? "" : " · big investors lined up"))
                                        .font(.caption2).foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    } header: { Text("Just filed to go public") } footer: { Text("New S-1 / F-1 filings with the SEC. No price or date yet.") }
                }

                Section {
                    ForEach([
                        "The IPO price mostly goes to big funds. Everyone else buys when it opens, often far above that price, so the first-day \"pop\" usually isn't yours.",
                        "To get the IPO price, request shares through your broker's IPO access (Robinhood IPO Access, SoFi, Public) after the price range is set and before it prices. Hot deals get cut back or you get none.",
                        "Robinhood may limit your future IPO access if you sell IPO shares within 30 days.",
                        "A raised range or more shares means strong demand; a cut range means weak demand.",
                        "If you buy at the open, use a limit order. New IPOs can swing 20–50% in a day.",
                        "On average, buying at the opening price and flipping doesn't make money. Not financial advice.",
                    ], id: \.self) { Text("• " + $0).font(.caption).foregroundStyle(.secondary) }
                } header: { Text("How buying an IPO really works") } footer: {
                    Text("Calendar from Finnhub, filings and investor lists from SEC EDGAR, Reddit mentions from ApeWisdom.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("IPOs")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load() }
            .task { await load() }
        }
    }

    private func load() async {
        loading = true
        error = nil
        do { result = try await Ipos.load(ua: model.userAgent, publicCiks: Set(await model.cikMap().values)) }
        catch { self.error = error.localizedDescription }
        log = Ipos.log()
        loading = false
    }
}

private struct IpoRowView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    let ipo: Ipo
    @State private var expanded = false
    @State private var who: String?
    @State private var whoLoading = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { withAnimation(.snappy) { expanded.toggle() } } label: {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ipo.symbol.isEmpty ? ipo.name : ipo.symbol).font(.headline)
                        if !ipo.symbol.isEmpty { Text(ipo.name).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                        Text([ipo.date, ipo.exchange].filter { !$0.isEmpty }.joined(separator: " · ").ifEmpty("Date not set"))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(Ipos.rangeText(ipo.low, ipo.high).ifEmpty("No price yet")).font(.subheadline.weight(.bold))
                        Text(Ipos.money(ipo.value)).font(.caption2).foregroundStyle(.secondary)
                        if ipo.open > 0 && ipo.ipoPrice > 0 {
                            Text(String(format: "Opened $%@ (%+.0f%%)", Ipos.fmt(ipo.open), (ipo.open / ipo.ipoPrice - 1) * 100))
                                .font(.caption2).foregroundStyle(ipo.open >= ipo.ipoPrice ? Color.green : Color.red)
                        }
                        if ipo.now > 0 && ipo.ipoPrice > 0 {
                            Text(String(format: "Now $%@ (%+.0f%%)", Ipos.fmt(ipo.now), (ipo.now / ipo.ipoPrice - 1) * 100))
                                .font(.caption2).foregroundStyle(ipo.now >= ipo.ipoPrice ? Color.green : Color.red)
                        }
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            let candidates: [(String, Bool)?] = [
                ipo.big ? ("Big deal", true) : nil,
                ipo.cornerstone.isEmpty ? nil : ("Big investors lined up", true),
                ipo.mentions > 0 ? ("Reddit: \(ipo.mentions) mentions", ipo.mentions >= 50) : nil,
            ]
            let badges = candidates.compactMap { $0 }
            if !badges.isEmpty {
                HStack(spacing: 6) {
                    ForEach(Array(badges.enumerated()), id: \.offset) { _, b in
                        Text(b.0).font(.caption2.weight(.semibold))
                            .foregroundStyle(b.1 ? Color.green : Color.primary)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background((b.1 ? Color.green : Color.secondary).opacity(0.14), in: Capsule())
                    }
                }
            }

            if expanded {
                if ipo.cornerstone.isEmpty {
                    Text("No investors named in its filings yet. Big funds lined up before an IPO are listed in the prospectus when there are any.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Who's lined up to buy").font(.caption.weight(.semibold))
                    Text(whoLoading ? "Reading the filing…" : (who ?? "")).font(.caption)
                    Button("Open the filing") { if let u = URL(string: ipo.cornerstone) { openURL(u) } }.font(.caption)
                }
                if !ipo.symbol.isEmpty {
                    Button("Open \(ipo.symbol)") { model.openStock = StockTarget(ticker: ipo.symbol, company: ipo.name) }.font(.caption)
                }
            }
        }
        .task(id: expanded) {
            guard expanded, who == nil, !ipo.cornerstone.isEmpty else { return }
            whoLoading = true
            who = await Ipos.whoIsBuying(ipo.cornerstone, ua: model.userAgent)
                ?? "Couldn't read the investor list. Tap Open the filing to read it."
            whoLoading = false
        }
    }
}

private extension String {
    func ifEmpty(_ s: String) -> String { isEmpty ? s : self }
}
