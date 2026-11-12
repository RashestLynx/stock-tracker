import SwiftUI

/// Headlines for one stock.
struct NewsCard: View {
    let ticker: String
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var list: [Headline]?

    var body: some View {
        Group {
            if let l = list {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Headlines").font(.headline)
                    if l.isEmpty {
                        Text(model.massiveKey.isEmpty && Live.key.isEmpty ? "Add a Massive or Finnhub key in Settings to see headlines." : "No recent headlines.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    ForEach(l) { h in
                        Button { if let u = URL(string: h.url) { openURL(u) } } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(h.title).font(.subheadline).lineLimit(3).multilineTextAlignment(.leading)
                                HStack {
                                    Text(h.source).font(.caption2).foregroundStyle(.secondary)
                                    if let t = h.time { Text(t, style: .relative).font(.caption2).foregroundStyle(.secondary) }
                                    Spacer()
                                    if !h.sentiment.isEmpty {
                                        Text(h.sentiment.capitalized).font(.caption2.bold())
                                            .foregroundStyle(h.sentiment == "positive" ? .green : h.sentiment == "negative" ? .red : .secondary)
                                    }
                                }
                            }
                        }.buttonStyle(.plain)
                    }
                    if l.contains(where: { !$0.sentiment.isEmpty }) {
                        Text("Positive/negative labels are Massive's automatic read of each article, not advice.").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .task(id: ticker) { list = await StockNews.get(ticker, massiveKey: model.massiveKey) }
    }
}
