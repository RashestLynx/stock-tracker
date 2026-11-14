import SwiftUI

extension Category {
    var color: Color {
        switch self {
        case .senate: return .indigo
        case .house: return .teal
        case .insider: return .green
        case .fund: return .orange
        case .charges: return .red
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

/// Pill-shaped tabs, like the feed switchers on social apps. Scrolls sideways when they don't fit.
struct PillTabs<T: Hashable>: View {
    let options: [(T, String)]
    @Binding var selection: T

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { o in
                    Button { withAnimation(.snappy) { selection = o.0 } } label: {
                        Text(o.1).font(.subheadline.weight(.semibold)).lineLimit(1)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .foregroundStyle(selection == o.0 ? Color(.systemBackground) : .primary)
                            .background(selection == o.0 ? Color.primary : Color(.secondarySystemFill), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("pill-" + o.1)
                }
            }
        }
    }
}

/// A filing as a social-style post (shared by Home and Sections).
func postFrom(_ a: Alert) -> Post {
    let after = a.headline.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
    let name: String
    let about: String
    var text = a.lines.joined(separator: " · ")
    switch a.category {
    case .senate:
        name = cleanName(a.headline).components(separatedBy: ", ").first ?? a.headline
        about = "U.S. Senator" + (after.isEmpty ? "" : " · \(after)")
    case .house:
        name = cleanName(a.headline).components(separatedBy: ", ").first ?? a.headline
        about = "U.S. Representative" + (after.isEmpty ? "" : " · \(after)")
    case .insider:
        name = cleanName(a.headline).components(separatedBy: ", ").first ?? a.headline
        about = [after, a.company].filter { !$0.isEmpty }.joined(separator: " at ")
    case .fund:
        name = a.headline
        about = "Investment fund"
        text = "Filed new quarterly holdings. " + text
    case .charges:
        name = "SEC Enforcement"
        about = a.company
        text = ([a.headline] + a.lines).joined(separator: "\n")
    }
    return Post(who: name, about: about, category: a.category, time: a.time, text: text,
                ticker: a.ticker, company: a.company, link: a.link,
                person: a.category == .charges ? nil : personOf(a))
}

/// One filing in Sections, laid out like a social post: avatar, name and time, text, stock card, actions.
struct AlertRow: View {
    let alert: Alert
    @Environment(\.openURL) private var openURL
    @EnvironmentObject var model: AppModel
    @State private var liked = false

    var body: some View {
        let p = postFrom(alert)
        let watching = !p.ticker.isEmpty && model.watchlist.contains(p.ticker.uppercased())
        HStack(alignment: .top, spacing: 12) {
            Button { if let t = p.person { model.openPerson = t } } label: {
                Avatar(name: p.who, size: 44, fund: alert.category == .fund)
            }
            .buttonStyle(.plain)
            VStack(alignment: .leading, spacing: 6) {
                Button { if let t = p.person { model.openPerson = t } } label: {
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 4) {
                            Text(p.who).font(.subheadline.weight(.bold)).lineLimit(1)
                            Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(alert.category.color)
                            Text("· " + Format.ago(p.time)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Text([alert.category.label, p.about].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .foregroundStyle(.primary)
                }
                .buttonStyle(.plain)

                Text(p.text).font(.body)
                    .foregroundStyle(p.text.hasPrefix("Bought") ? .green : p.text.hasPrefix("Sold") ? .red : .primary)
                    .fixedSize(horizontal: false, vertical: true)

                if !p.ticker.isEmpty && alert.category != .fund {
                    Button {
                        let price = model.moves.first { $0.key == alert.key || $0.key.hasPrefix(alert.key + "|") }?.price
                        model.openStock = StockTarget(
                            ticker: alert.ticker, company: alert.company, heading: "This filing",
                            who: alert.headline, lines: alert.lines, link: alert.link, tradePrice: price)
                    } label: {
                        HStack(spacing: 12) {
                            Text(String(p.ticker.prefix(4))).font(.caption.weight(.heavy)).foregroundStyle(.white)
                                .frame(width: 40, height: 40)
                                .background(LinearGradient(colors: Brand.story, startPoint: .bottomLeading, endPoint: .topTrailing),
                                            in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            VStack(alignment: .leading, spacing: 1) {
                                Text("$" + p.ticker).font(.subheadline.weight(.bold)).foregroundStyle(.primary)
                                if !p.company.isEmpty { Text(p.company).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                            }
                            Spacer()
                            Text("Chart").font(.caption.weight(.semibold)).foregroundStyle(Brand.accent)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(12)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }

                HStack {
                    Button {
                        var set = Set(UserDefaults.standard.stringArray(forKey: "likedPosts") ?? [])
                        if set.contains(p.key) { set.remove(p.key) } else { set.insert(p.key) }
                        UserDefaults.standard.set(Array(set), forKey: "likedPosts")
                        withAnimation(.snappy) { liked = set.contains(p.key) }
                    } label: {
                        Image(systemName: liked ? "heart.fill" : "heart").foregroundStyle(liked ? Brand.like : .secondary)
                            .symbolEffect(.bounce, value: liked)
                    }
                    Spacer()
                    if !p.ticker.isEmpty {
                        Button {
                            let t = p.ticker.uppercased()
                            if let i = model.watchlist.firstIndex(of: t) { model.watchlist.remove(at: i) } else { model.watchlist.append(t) }
                        } label: {
                            Image(systemName: watching ? "star.fill" : "star").foregroundStyle(watching ? Brand.accent : .secondary)
                        }
                        Spacer()
                    }
                    ShareLink(item: "\(p.who): \(p.text)" + (p.ticker.isEmpty ? "" : " $\(p.ticker)") + (p.link.isEmpty ? "" : "\n\(p.link)")) {
                        Image(systemName: "square.and.arrow.up").foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let u = URL(string: p.link), !p.link.isEmpty {
                        Button { openURL(u) } label: {
                            Label("Source", systemImage: "link").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        }
                    }
                }
                .font(.title3)
                .buttonStyle(.plain)
                .padding(.top, 4)
                .padding(.trailing, 8)
            }
        }
        .padding(.vertical, 8)
        .onAppear { liked = (UserDefaults.standard.stringArray(forKey: "likedPosts") ?? []).contains(p.key) }
    }
}

// ---------------- investor profile helpers ----------------
func cleanName(_ who: String) -> String {
    var s = who
    for p in ["Sen. ", "Rep. "] where s.hasPrefix(p) { s = String(s.dropFirst(p.count)) }
    return s.components(separatedBy: " · ").first ?? s
}

func personOf(_ m: Move) -> PersonTarget {
    PersonTarget(key: m.person ?? "", name: cleanName(m.who), category: m.category, role: m.role)
}

func personOf(_ p: Pick) -> PersonTarget {
    PersonTarget(key: p.person ?? "", name: cleanName(p.who), category: p.category)
}

func personOf(_ a: Alert) -> PersonTarget {
    let role = a.category == .insider ? (a.headline.components(separatedBy: " · ").dropFirst().first ?? "") : ""
    return PersonTarget(key: a.person ?? "", name: cleanName(a.headline).components(separatedBy: ", ").first ?? a.headline,
                        category: a.category, role: role)
}
