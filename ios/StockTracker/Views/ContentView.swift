import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            FeedView(goToSettings: { tab = 2 })
                .tabItem { Label("Feed", systemImage: "bell.fill") }
                .tag(0)
            MoversView()
                .tabItem { Label("Movers", systemImage: "chart.line.uptrend.xyaxis") }
                .tag(3)
            PicksView()
                .tabItem { Label("Picks", systemImage: "star.fill") }
                .tag(1)
            SettingsView(onReady: {
                tab = 0
                Task { await model.check() }
            })
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(2)
        }
        .onAppear { if !model.isSetUp { tab = 2 } }
        .sheet(item: $model.openStock) { target in
            StockSheet(target: target).environmentObject(model)
        }
    }
}
