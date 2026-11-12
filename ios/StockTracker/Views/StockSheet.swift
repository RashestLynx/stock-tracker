import SwiftUI

/// Summary of a stock: what the company does, price, the trade that was tapped, and who else is in it.
struct StockSheet: View {
    let target: StockTarget
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss
    @State private var summary: StockSummary?
    @State private var live: LiveQuote?

    private var bought: [Pick] { unique(model.picks.filter { $0.ticker == target.ticker && $0.action == "buy" }) }
    private var held: [Pick] { unique(model.picks.filter { $0.ticker == target.ticker && $0.action == "hold" }) }
    private var sold: [Move] {
        var seen = Set<String>()
        return model.moves.filter { $0.ticker == target.ticker && $0.side == "sell" && seen.insert($0.who).inserted }
    }

    var body: some View {
        NavigationStack {
            List {
                // ---- header + price ----
                Section {
                    HStack(spacing: 14) {
                        TickerBadge(text: target.ticker, color: .green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(name).font(.title3.weight(.bold))
                            Text([target.ticker, summary?.exchange ?? "", summary?.industry ?? ""]
                                .filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    BuyButton(ticker: target.ticker, prominent: true)
                    let watching = model.watchlist.contains(target.ticker.uppercased())
                    Button {
                        if watching { model.watchlist.removeAll { $0 == target.ticker.uppercased() } }
                        else { model.watchlist.append(target.ticker.uppercased()) }
                    } label: {
                        Label(watching ? "On your watchlist (tap to remove)" : "Add to watchlist",
                              systemImage: watching ? "star.fill" : "star")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    PriceChartView(ticker: target.ticker, live: live)
                    AnalystCard(ticker: target.ticker, price: live?.price)
                    GradeCard(ticker: target.ticker)
                    NightlyCard(ticker: target.ticker)
                    PatternsCard(ticker: target.ticker)
                    if summary == nil {
                        ProgressView().frame(maxWidth: .infinity)
                    } else if let price = live?.price ?? summary?.price {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(String(format: "$%.2f", price)).font(.largeTitle.weight(.bold))
                                Text(live != nil ? "live" : summary?.priceDate.isEmpty == false ? "close \(summary!.priceDate)" : "latest")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if let q = live {
                                HStack(spacing: 4) {
                                    Circle().fill(.red).frame(width: 7, height: 7)
                                    Text("LIVE · " + changeText(q) + " today").font(.subheadline.weight(.semibold))
                                        .foregroundStyle(q.change >= 0 ? .green : .red)
                                }
                            }
                            if let tp = target.tradePrice, tp > 0 {
                                let pct = (price - tp) / tp * 100
                                Text(String(format: "%@ %.1f%% since this trade at $%.2f", pct >= 0 ? "▲" : "▼", abs(pct), tp))
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(pct >= 0 ? .green : .red)
                            }
                        }
                    }
                }

                // ---- about ----
                if let s = summary {
                    Section("About") {
                        Text(s.about.isEmpty ? "No company description found." : s.about)
                        if !s.location.isEmpty {
                            Label("Headquarters: \(s.location)", systemImage: "building.2")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if let url = URL(string: s.aboutURL), !s.aboutURL.isEmpty {
                            Link("Read more on Wikipedia", destination: url)
                        }
                    }
                }

                // ---- the trade that was tapped ----
                if !target.heading.isEmpty {
                    Section(target.heading) {
                        if !target.who.isEmpty { Text(target.who).font(.headline) }
                        ForEach(target.lines, id: \.self) { TradeLine(line: $0) }
                        if let url = URL(string: target.link), !target.link.isEmpty {
                            Button("View official filing") { openURL(url) }
                        }
                    }
                }

                // ---- others tracking it ----
                Section("Who else is in it") {
                    if bought.isEmpty && held.isEmpty && sold.isEmpty {
                        Text("No one else you track has bought, sold or held it recently.")
                            .foregroundStyle(.secondary)
                    }
                    peopleGroup("Bought (90 days)", bought.map { ("\($0.who) (\($0.category.label))", personOf($0)) }, .green)
                    peopleGroup("Funds holding it", held.map { ($0.who, personOf($0)) }, .primary)
                    peopleGroup("Sold (7 days)", sold.map { ("\($0.who) (\($0.category.label))", personOf($0)) }, .red)
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Company info from the SEC and Wikipedia; price is delayed. This is a summary of public information, not financial advice.")
                }
            }
            .navigationTitle(target.ticker)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .task(id: target.ticker) {
                summary = await StockInfo.load(ticker: target.ticker, company: target.company, model: model)
            }
            .task(id: target.ticker + "live") {
                while !Task.isCancelled && !Live.key.isEmpty {
                    live = await Live.quote(target.ticker)
                    try? await Task.sleep(nanoseconds: 15_000_000_000)
                }
            }
        }
        .presentationDragIndicator(.visible)
    }

    private var name: String {
        if let n = summary?.name, !n.isEmpty { return n }
        return target.company.isEmpty ? target.ticker : target.company
    }

    private func unique(_ ps: [Pick]) -> [Pick] {
        var seen = Set<String>()
        return ps.filter { seen.insert($0.who).inserted }
    }

    @ViewBuilder
    private func peopleGroup(_ label: String, _ people: [(String, PersonTarget)], _ color: Color) -> some View {
        if !people.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(label) · \(people.count)").font(.subheadline.weight(.semibold)).foregroundStyle(color)
                ForEach(Array(people.prefix(8).enumerated()), id: \.offset) { _, p in
                    NavigationLink {
                        PersonView(target: p.1)
                    } label: {
                        Text("• \(p.0)").font(.footnote)
                    }
                }
                if people.count > 8 {
                    Text("• and \(people.count - 8) more").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }
}
