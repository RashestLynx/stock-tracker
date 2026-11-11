import SwiftUI

/// Your logged prediction-market picks, followed until they settle, compared with what the market expected.
struct MyPredPicksView: View {
    @Environment(\.openURL) private var openURL
    @State private var record: PredRecord?
    @State private var loading = false

    private func money(_ v: Double) -> String { (v < 0 ? "-" : "+") + String(format: "$%.2f", abs(v)) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let r = record, !r.picks.isEmpty {
                        let n = r.settled.count
                        HStack {
                            stat("\(r.wins) of \(n)", "You won")
                            stat(String(format: "%.1f", r.expected), "Market expected")
                            stat(money(r.profit * 10), "$10 on each")
                        }
                        Text(n < 20 ? "Too early to judge: \(n) settled. You need about 20 or more before luck stops dominating."
                             : Double(r.wins) > r.expected + 2 ? "You're winning more than the market's odds predicted. That's what an edge looks like. Keep logging to see if it holds."
                             : Double(r.wins) < r.expected - 2 ? "You're winning fewer than the market's odds predicted, so the market has been the better forecaster so far."
                             : "You're winning about as often as the market's odds predicted, which means no edge yet. Most people land here.")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else if loading {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        Text("Nothing logged yet. Tap \"Log a pick\" on any market under All markets.")
                    }
                } header: {
                    Text("Your picks vs the market")
                }
                ForEach(record?.picks ?? []) { s in
                    Section { row(s) }
                }
                Section {
                    EmptyView()
                } footer: {
                    Text("\"$10 on each\" is what you'd have made or lost betting $10 on every pick at the logged price, before fees. Open picks are valued at today's price. Nothing is ever bought for you.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("My picks")
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
    private func row(_ s: PredStatus) -> some View {
        let p = s.pick
        VStack(alignment: .leading, spacing: 4) {
            Button { if let u = URL(string: p.url) { openURL(u) } } label: {
                Text(p.title).font(.headline).lineLimit(2).multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
            Text("\(p.label) – \(p.side == "yes" ? "Yes" : "No") at \(String(format: "%.0f¢", p.price * 100)) · \(p.source) · \(p.id.formatted(date: .abbreviated, time: .omitted))")
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Text(s.won == true ? "Won" : s.won == false ? "Lost" : s.now.map { String(format: "Open · now %.0f¢", $0 * 100) } ?? "Open")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(s.won == true ? .green : s.won == false ? .red : .primary)
                Spacer()
                if let pr = s.profit {
                    let st = p.stake ?? 10
                    Text(money(pr * st) + " on $" + Format.number(st)).font(.caption.weight(.semibold)).foregroundStyle(pr >= 0 ? .green : .red)
                }
            }
            Button("Remove", role: .destructive) { PredJournal.delete(p.id); Task { await load() } }
                .buttonStyle(.borderless).font(.caption)
        }
    }

    private func load() async {
        loading = true
        record = await PredJournal.grade()
        loading = false
    }
}
