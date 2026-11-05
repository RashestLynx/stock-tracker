import SwiftUI

extension Category {
    var color: Color {
        switch self {
        case .senate: return .indigo
        case .house: return .teal
        case .insider: return .green
        case .fund: return .orange
        }
    }
}

struct TickerBadge: View {
    let text: String
    let color: Color

    var body: some View {
        Text(String(text.prefix(5)))
            .font(.caption.weight(.bold))
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .foregroundStyle(color)
            .frame(width: 48, height: 48)
            .background(color.opacity(0.16), in: Circle())
    }
}

struct CategoryPill: View {
    let category: Category
    var count: Int? = nil

    var body: some View {
        Text(count.map { "\(category.label) \($0)" } ?? category.label)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .foregroundStyle(category.color)
            .background(category.color.opacity(0.15), in: Capsule())
    }
}

struct TradeLine: View {
    let line: String

    var color: Color {
        if line.hasPrefix("Bought") { return .green }
        if line.hasPrefix("Sold") { return .red }
        return .secondary
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(line).font(.subheadline).foregroundStyle(color)
        }
    }
}

struct EmptyStateView: View {
    let title: String
    let message: String
    var systemImage: String = "bell"

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(message)
        }
    }
}

struct AlertRow: View {
    let alert: Alert
    @Environment(\.openURL) private var openURL

    private var badgeColor: Color {
        if alert.category == .fund { return Category.fund.color }
        if alert.buy && !alert.sell { return .green }
        if alert.sell && !alert.buy { return .red }
        return .gray
    }

    var body: some View {
        Button {
            if let url = URL(string: alert.link) { openURL(url) }
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    TickerBadge(text: alert.category == .fund ? "13F" : (alert.ticker.isEmpty ? "—" : alert.ticker),
                                color: badgeColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(alert.category == .fund ? alert.headline : (alert.ticker.isEmpty ? alert.company : alert.ticker))
                            .font(.headline).lineLimit(1)
                        Text(alert.company).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text(Format.ago(alert.time)).font(.caption2).foregroundStyle(.secondary)
                }
                if alert.category != .fund {
                    Text(alert.headline).font(.subheadline.weight(.medium))
                }
                ForEach(alert.lines, id: \.self) { TradeLine(line: $0) }
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
