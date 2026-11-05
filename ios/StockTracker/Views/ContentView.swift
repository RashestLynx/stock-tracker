import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            FeedView(goToSettings: { tab = 2 })
                .tabItem { Label("Feed", systemImage: "bell.fill") }
                .tag(0)
            PicksView()
                .tabItem { Label("Common picks", systemImage: "star.fill") }
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
