import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
                .tag(0)
            WatchlistView()
                .tabItem { Label("Watchlist", systemImage: "star.fill") }
                .tag(5)
            FeedView(goToSettings: { model.showSettings = true })
                .tabItem { Label("Sections", systemImage: "bell.fill") }
                .tag(1)
            NewsView()
                .tabItem { Label("News", systemImage: "newspaper.fill") }
                .tag(2)
            CryptoView()
                .tabItem { Label("Crypto", systemImage: "bitcoinsign.circle.fill") }
                .tag(3)
            MarketsView()
                .tabItem { Label("Markets", systemImage: "chart.line.uptrend.xyaxis") }
                .tag(4)
            SourcesView()
                .tabItem { Label("Sources", systemImage: "list.bullet.rectangle") }
                .tag(6)
        }
        .tint(Brand.accent)
        .preferredColorScheme(model.appearance == "dark" ? .dark : model.appearance == "light" ? .light : nil)
        .task {
            // Check the market, and for new filings, every 5 minutes while the app is open.
            var first = true
            while !Task.isCancelled {
                await model.refreshMarket()
                if !first { await model.check() }
                first = false
                try? await Task.sleep(for: .seconds(300))
            }
        }
        .onAppear { if !model.isSetUp { model.showSettings = true } }
        .sheet(item: $model.openStock) { target in
            StockSheet(target: target).environmentObject(model)
        }
        .background(
            EmptyView().sheet(item: $model.openPerson) { target in
                NavigationStack { PersonView(target: target) }.environmentObject(model)
            }
        )
        .background(
            EmptyView().sheet(isPresented: $model.showSettings) {
                SettingsView(onReady: {
                    model.showSettings = false
                    tab = 0
                    Task { await model.check() }
                }).environmentObject(model)
            }
        )
    }
}

/// Green background when the S&P 500 is up today, red when it's down (if turned on in Settings).
struct MarketBackground: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Environment(\.colorScheme) private var scheme

    @ViewBuilder
    func body(content: Content) -> some View {
        if model.marketBackground, let q = model.market {
            let up = q.changePct >= 0
            let c: Color = scheme == .dark
                ? (up ? Color(red: 0.02, green: 0.10, blue: 0.05) : Color(red: 0.12, green: 0.03, blue: 0.03))
                : (up ? Color(red: 0.90, green: 0.965, blue: 0.92) : Color(red: 0.99, green: 0.91, blue: 0.91))
            content.scrollContentBackground(.hidden).background(c.ignoresSafeArea())
        } else {
            content
        }
    }
}

extension View {
    func marketBackground() -> some View { modifier(MarketBackground()) }
}

/// "S&P 500 ▲ +0.84% today", explaining the market-color background.
struct MarketPill: View {
    let quote: LiveQuote
    var body: some View {
        let up = quote.changePct >= 0
        Text(String(format: "S&P 500 %@ %+.2f%% today", up ? "▲" : "▼", quote.changePct))
            .font(.subheadline.weight(.bold))
            .foregroundStyle(up ? .green : .red)
            .padding(.horizontal, 14).padding(.vertical, 6)
            .background((up ? Color.green : Color.red).opacity(0.15), in: Capsule())
    }
}

/// Settings button shown in each tab's top bar.
struct SettingsButton: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        Button { model.showSettings = true } label: { Image(systemName: "gearshape") }
    }
}
