import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            FeedView(goToSettings: { model.showSettings = true })
                .tabItem { Label("Feed", systemImage: "bell.fill") }
                .tag(0)
            NewsView()
                .tabItem { Label("News", systemImage: "newspaper.fill") }
                .tag(5)
            WsbView()
                .tabItem { Label("WSB", systemImage: "flame.fill") }
                .tag(4)
            MoversView()
                .tabItem { Label("Movers", systemImage: "chart.line.uptrend.xyaxis") }
                .tag(3)
            PicksView()
                .tabItem { Label("Picks", systemImage: "star.fill") }
                .tag(1)
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

/// Settings button shown in each tab's top bar.
struct SettingsButton: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        Button { model.showSettings = true } label: { Image(systemName: "gearshape") }
    }
}
