import SwiftUI

/// Chart patterns for one stock, under its price chart.
struct PatternsCard: View {
    @EnvironmentObject var model: AppModel
    let ticker: String
    @State private var report: PatternReport?
    @State private var error: String?
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Patterns").font(.headline)
                Spacer()
                if let r = report {
                    Text("\(r.hits.filter { $0.mood > 0 }.count) good · \(r.hits.filter { $0.mood < 0 }.count) bad")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if let e = error {
                Text(e).font(.footnote).foregroundStyle(.secondary)
            } else if let r = report {
                ForEach(open ? r.hits : Array(r.hits.prefix(3))) { h in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 4) {
                            Text(h.mood > 0 ? "▲" : h.mood < 0 ? "▼" : "•").foregroundStyle(h.mood > 0 ? .green : h.mood < 0 ? .red : .secondary)
                            Text(h.name).fontWeight(.semibold)
                        }
                        Text(h.what).font(.footnote)
                        if !h.history.isEmpty { Text(h.history).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                if r.support != nil || r.resistance != nil {
                    Text([r.support.map { String(format: "Support near $%.2f", $0) }, r.resistance.map { String(format: "resistance near $%.2f", $0) }]
                            .compactMap { $0 }.joined(separator: " · ") + ": prices it has bounced off more than once in the past year.")
                        .font(.footnote)
                }
                if r.hits.count > 3 {
                    Button(open ? "Show less" : "Show all \(r.hits.count)") { open.toggle() }.font(.footnote)
                }
                Text("Patterns are hints, not predictions. Research finds trend and momentum signals hold up best; classic chart shapes are weaker. The history line shows how each one has actually worked for this stock.")
                    .font(.caption2).foregroundStyle(.secondary)
            } else {
                Text("Checking the last 2 years of prices…").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .task(id: ticker) {
            do { report = try await Patterns.load(ticker, key: model.massiveKey) } catch { self.error = error.localizedDescription }
        }
    }
}
