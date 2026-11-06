import SwiftUI

/// Bitcoin & Ethereum: prices, big funds holding crypto funds, insider/Congress crypto trades, holders, news.
struct CryptoView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var coin = 0   // 0 BTC, 1 ETH
    @State private var prices: [String: CoinPrice] = [:]
    @State private var treasuries: [TreasuryCompany] = []
    @State private var news: [NewsItem] = []

    private var asset: String { coin == 0 ? "BTC" : "ETH" }
    private var coinName: String { coin == 0 ? "Bitcoin" : "Ethereum" }
    private var funds: [CryptoPos] { CryptoStore.all().filter { $0.asset == asset }.sorted { $0.value > $1.value } }
    private var trades: [Move] {
        model.moves.filter { let a = Crypto.tickerAsset($0.ticker); return a == asset || (a == "STOCK" && coin == 0) }
            .sorted { $0.time > $1.time }
    }
    private var charges: [Alert] {
        model.alerts.filter { a in a.category == .charges &&
            ["crypto", "bitcoin", "ether", "token", "digital asset", "coin"].contains { (a.headline + " " + a.lines.joined(separator: " ")).localizedCaseInsensitiveContains($0) } }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        priceCard("BTC", "Bitcoin", 0)
                        priceCard("ETH", "Ethereum", 1)
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } footer: {
                    Text("Prices refresh every minute (CoinGecko). Tap a coin to switch.")
                }

                Section {
                    if funds.isEmpty { Text("None captured yet.").foregroundStyle(.secondary) }
                    ForEach(funds.prefix(25)) { p in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.fund).font(.subheadline.weight(.medium)).lineLimit(1)
                                Text(p.product).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            Text(Format.money(p.value)).font(.subheadline.weight(.semibold))
                        }
                    }
                } header: {
                    Text("Big funds holding \(coinName) funds")
                } footer: {
                    Text("From their quarterly SEC filings (13F). Fills in as new filings arrive; the next wave is due mid-November.")
                }

                Section {
                    if trades.isEmpty { Text("None captured in the last 7 days.").foregroundStyle(.secondary) }
                    ForEach(Array(trades.prefix(15).enumerated()), id: \.offset) { _, m in
                        Button {
                            model.openStock = StockTarget(ticker: m.ticker, company: m.company, tradePrice: m.price)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                TradeLine(line: "\(m.side == "buy" ? "Bought" : "Sold") \(m.ticker) · \(m.valueLabel)")
                                Text("\(m.who) · \(m.category.label) · \(Format.ago(m.time))").font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.borderless)
                    }
                    if !charges.isEmpty {
                        Text("SEC crypto insider trading cases").font(.subheadline.weight(.semibold))
                        ForEach(charges.prefix(5)) { a in
                            if let u = URL(string: a.link) { Link("• \(a.headline)", destination: u).font(.footnote) }
                        }
                    }
                } header: {
                    Text("Insider & Congress crypto trades")
                } footer: {
                    Text("Coins have no company insiders who must report trades, so this shows trades in crypto funds (like IBIT) by members of Congress and insider trades at crypto companies" + (coin == 0 ? " (Strategy, Coinbase, miners)." : "."))
                }

                if !treasuries.isEmpty {
                    Section("Public companies holding \(coinName)") {
                        ForEach(treasuries.prefix(15)) { t in
                            Button {
                                let sym = t.symbol.components(separatedBy: CharacterSet(charactersIn: ".:")).first ?? t.symbol
                                if !sym.isEmpty { model.openStock = StockTarget(ticker: sym, company: t.name) }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(t.name).font(.subheadline.weight(.medium)).lineLimit(1)
                                        Text([t.symbol, t.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text("\(Format.number(t.coins)) \(asset)").font(.subheadline.weight(.semibold))
                                        Text(Format.money(t.valueUSD)).font(.caption2).foregroundStyle(.secondary)
                                    }
                                }
                                .foregroundStyle(.primary)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Section("Big-money \(coinName) news") {
                    if news.isEmpty { ProgressView() }
                    ForEach(news.prefix(8)) { n in
                        if let u = URL(string: n.url) {
                            Link(destination: u) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(n.title).font(.subheadline).foregroundStyle(.primary)
                                    Text("\(n.source) · \(Format.ago(n.time))").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Prices and company holdings from CoinGecko; fund positions from SEC filings; news from Google News. Not financial advice.")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Bitcoin & Ethereum")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .refreshable { prices = await Crypto.prices() }
            .task {
                while !Task.isCancelled {
                    prices = await Crypto.prices()
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                }
            }
            .task(id: coin) {
                treasuries = await Crypto.treasuries(coin == 0 ? "bitcoin" : "ethereum")
                news = (try? await News.search("\(coinName.lowercased()) (ETF OR institutional OR fund OR whale)")) ?? []
            }
        }
    }

    private func priceCard(_ sym: String, _ label: String, _ idx: Int) -> some View {
        Button { coin = idx } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.subheadline.weight(.semibold))
                Text(prices[sym].map { "$" + Format.number($0.usd) } ?? "—").font(.title2.weight(.bold))
                if let p = prices[sym] {
                    Text(String(format: "%+.1f%% today", p.change24h)).font(.caption.weight(.semibold))
                        .foregroundStyle(p.change24h >= 0 ? .green : .red)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(coin == idx ? Color.accentColor.opacity(0.18) : Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 18))
            .foregroundStyle(.primary)
        }
        .buttonStyle(.plain)
    }
}
