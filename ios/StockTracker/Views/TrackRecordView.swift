import SwiftUI

/// "Open in Robinhood", with a quick look first and the option to log the buy for Track record.
struct BuyButton: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    let ticker: String
    var prominent = false
    @State private var show = false
    @State private var reason = ""
    @State private var saving = false
    @State private var note = ""

    var body: some View {
        if let url = Broker.link(ticker) {
            Group {
                if prominent {
                    Button { show = true } label: {
                        Label("Open \(ticker) in \(Broker.name)", systemImage: "arrow.up.forward.app").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button("Open in \(Broker.name)") { show = true }.buttonStyle(.borderless).font(.caption)
                }
            }
            .sheet(isPresented: $show) {
                NavigationStack {
                    Form {
                        Section {
                            ForEach(Array(Journal.check(ticker, model: model).enumerated()), id: \.offset) { _, line in
                                HStack(alignment: .top, spacing: 8) {
                                    Image(systemName: line.good ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                                        .foregroundStyle(line.good ? .green : .orange)
                                    Text(line.text).font(.subheadline)
                                }
                            }
                        }
                        Section {
                            TextField("Why are you buying? (optional)", text: $reason, axis: .vertical)
                        } footer: {
                            Text(note.isEmpty ? "Logging it adds it to Track record, graded against the S&P 500." : note)
                                .foregroundStyle(note.isEmpty ? Color.secondary : Color.red)
                        }
                        Section {
                            Button(saving ? "Logging…" : "Log it & open \(Broker.name)") {
                                saving = true
                                Task {
                                    let ok = await Journal.logBuy(ticker, reason: reason)
                                    saving = false
                                    if ok { show = false; reason = ""; note = ""; openURL(url) }
                                    else { note = "Couldn't get today's price to log it. Try again, or just open." }
                                }
                            }
                            .disabled(saving)
                            Button("Just open") { show = false; openURL(url) }
                        }
                    }
                    .navigationTitle("Before you buy \(ticker)")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { show = false } } }
                }
                .presentationDetents([.medium, .large])
            }
        }
    }
}

/// Every logged buy, graded against the S&P 500 over the same days.
struct TrackRecordView: View {
    @EnvironmentObject var model: AppModel
    @State private var record: TrackRecord?
    @State private var loading = false

    private func pct(_ v: Double?) -> String { v.map { String(format: "%+.1f%%", $0 * 100) } ?? "–" }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let r = record, r.count > 0 {
                        HStack {
                            stat(pct(r.avg), "Your average")
                            stat(pct(r.spyAvg), "S&P 500")
                            stat("\(r.wins) of \(r.count)", "Beat it")
                        }
                        Text(r.count < 10 ? "Too early to judge. A fair test needs 10 or more picks held for several months."
                             : r.avg > r.spyAvg ? "So far you're ahead of simply buying an S&P 500 index fund. Keep logging to see if it lasts."
                             : "So far an S&P 500 index fund would have done better. That's true for most stock pickers, including professionals, which is why index funds are a strong default.")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else if loading {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        Text("Nothing logged yet. When you tap \"Open in \(Broker.name)\" on a stock, choose \"Log it & open\" and it shows up here, graded against the S&P 500 over the same days.")
                    }
                } header: {
                    Text("Your picks vs the S&P 500")
                }
                ForEach(record?.graded ?? []) { g in
                    Section { row(g) }
                }
                Section {
                    EmptyView()
                } footer: {
                    Text("Prices from Finnhub (live) or Stooq (last close). The S&P 500 is measured with the SPY fund. Dividends aren't included. Your log is kept in your iPhone's Keychain.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Track record")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { await load() }
            .task { await load() }
        }
    }

    private func stat(_ v: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(v).font(.title3.weight(.bold))
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func row(_ g: GradedPick) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { model.openStock = StockTarget(ticker: g.pick.ticker, company: "") } label: {
                HStack {
                    Text(g.pick.ticker).font(.headline)
                    Spacer()
                    Text(pct(g.ret)).font(.headline).foregroundStyle((g.ret ?? 0) >= 0 ? .green : .red)
                }
            }
            .buttonStyle(.plain)
            Text("Logged \(g.pick.id.formatted(date: .abbreviated, time: .omitted)) at $\(Format.number(g.pick.price, decimals: 2))"
                 + (g.pick.soldAt.map { " · sold \($0.formatted(date: .abbreviated, time: .omitted))" } ?? ""))
                .font(.caption).foregroundStyle(.secondary)
            Text("S&P 500 over the same time: \(pct(g.spyRet))" + (g.beat == true ? " · you beat it" : g.beat == false ? " · it beat you" : ""))
                .font(.caption)
            if !g.pick.reason.isEmpty { Text("Why: \(g.pick.reason)").font(.caption).foregroundStyle(.secondary) }
            HStack(spacing: 16) {
                if g.pick.soldAt == nil {
                    Button("I sold it") { Task { await Journal.markSold(g.pick.id); await load() } }.buttonStyle(.borderless)
                }
                Button("Remove", role: .destructive) { Journal.delete(g.pick.id); Task { await load() } }.buttonStyle(.borderless)
            }
            .font(.caption)
        }
    }

    private func load() async {
        loading = true
        record = await Journal.grade()
        loading = false
    }
}
