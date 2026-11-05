import SwiftUI

/// "Today's buy & sale": the day's most notable open-market buy and sale.
struct TodayCard: View {
    let highlights: DailyHighlights
    @State private var showInfo = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Today's buy & sale").font(.title2.weight(.bold))
                    Text(highlights.isToday ? "Biggest signals so far today" : "Nothing yet today · showing the last 3 days")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button(showInfo ? "Hide" : "How?") { withAnimation { showInfo.toggle() } }
                    .font(.subheadline)
            }
            if showInfo {
                Text("Ranked by trade size, who made it (CEOs and CFOs count most, then other executives, directors and members of Congress), and for buys, how many other tracked people also bought or hold the stock. Only open-market trades count. This shows notable activity, not a prediction, and isn't financial advice.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HighlightTile(title: "Top buy", highlight: highlights.buy, accent: .green,
                          emptyText: "No open-market buys yet. Insider and Congress buys will appear here.")
            HighlightTile(title: "Top sale", highlight: highlights.sale, accent: .red,
                          emptyText: "No open-market sales yet.")
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
    }
}

struct HighlightTile: View {
    let title: String
    let highlight: Highlight?
    let accent: Color
    let emptyText: String
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
        Button {
            guard let h = highlight else { return }
            let m = h.move
            model.openStock = StockTarget(
                ticker: m.ticker, company: m.company, heading: "This trade",
                who: m.who + (m.category == .insider && !m.role.isEmpty ? " · \(m.role)" : ""),
                lines: h.reasons, link: m.link, tradePrice: m.price)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                Text(title.uppercased()).font(.caption.weight(.bold)).foregroundStyle(accent)
                if let h = highlight {
                    let m = h.move
                    HStack(spacing: 10) {
                        Text(m.ticker).font(.title2.weight(.bold)).foregroundStyle(accent)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(m.company).font(.subheadline.weight(.medium)).lineLimit(1)
                            Text(m.who + (m.category == .insider && !m.role.isEmpty ? " · \(m.role)" : ""))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 4)
                        CategoryPill(category: m.category)
                    }
                    ForEach(h.reasons, id: \.self) { r in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Circle().fill(accent).frame(width: 6, height: 6)
                            Text(r).font(.caption).foregroundStyle(.primary)
                        }
                    }
                    Text("Tap for a summary of \(m.ticker)").font(.caption2.weight(.medium)).foregroundStyle(accent)
                } else {
                    Text(emptyText).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.primary)
        .disabled(highlight == nil)
        if let h = highlight {
            Button("Investor profile") { model.openPerson = personOf(h.move) }
                .buttonStyle(.borderless)
                .font(.footnote.weight(.medium))
                .padding(.horizontal, 14).padding(.bottom, 12)
        }
        }
        .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
    }
}
