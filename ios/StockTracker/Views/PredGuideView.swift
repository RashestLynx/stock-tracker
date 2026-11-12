import SwiftUI

/// Predictions → Start here: how prediction markets work, for a beginner.
struct PredGuideView: View {
    var body: some View {
        List {
            Section("The simple routine") {
                Text("1. Open Sports and pick a league.")
                Text("2. Read the green or gray line on each game. Gray means no edge: skip it.")
                Text("3. Only on a green 'possible value' line, tap the side and use 'How much?'.")
                Text("4. Log it under My picks, then check your record after 50 picks.")
            }
            ForEach(Coach.sports, id: \.0) { item in
                Section(item.0) { Text(item.1) }
            }
            ForEach(Coach.guide, id: \.0) { item in
                Section(item.0) { Text(item.1) }
            }
        }
        .listStyle(.insetGrouped)
    }
}
