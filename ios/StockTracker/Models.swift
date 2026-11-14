import Foundation

/// The sections of the feed.
enum Category: String, CaseIterable, Codable, Identifiable {
    case senate, house, insider, fund, charges

    var id: String { rawValue }

    var label: String {
        switch self {
        case .senate: return "Senate"
        case .house: return "House"
        case .insider: return "Insiders"
        case .fund: return "Funds"
        case .charges: return "SEC charges"
        }
    }

    var subtitle: String {
        switch self {
        case .senate: return "U.S. senators' stock trades (STOCK Act)"
        case .house: return "U.S. representatives' stock trades (STOCK Act)"
        case .insider: return "Executives, directors and 10% owners (SEC Form 4)"
        case .fund: return "Big investment funds' quarterly holdings (SEC 13F)"
        case .charges: return "SEC enforcement cases for illegal insider trading"
        }
    }
}

/// One item in the feed and one notification.
struct Alert: Codable, Identifiable, Hashable {
    var time: Date
    var category: Category
    var ticker: String
    var company: String
    var headline: String      // who traded, or the fund's name
    var lines: [String]       // "Bought…"/"Sold…" lines are colored
    var link: String
    var key: String
    var person: String? = nil

    var id: String { key }
    var buy: Bool { lines.contains { $0.hasPrefix("Bought") } }
    var sell: Bool { lines.contains { $0.hasPrefix("Sold") } }

    var notificationTitle: String {
        if category == .fund { return "New 13F: \(headline)" }
        if category == .charges { return "SEC insider trading case" }
        let who: String
        switch category {
        case .senate: who = "Senator"
        case .house: who = "Representative"
        default: who = "Insider"
        }
        let action = buy && sell ? "bought and sold" : buy ? "bought" : sell ? "sold" : "filed"
        return "\(ticker.isEmpty ? company : ticker): \(who) \(action)"
    }

    var notificationBody: String {
        ([headline, company] + lines).filter { !$0.isEmpty }.joined(separator: "\n")
    }
}

/// A record that one person bought ("buy") or holds ("hold") one stock. Feeds Common picks.
struct Pick: Codable, Hashable {
    var time: Date
    var ticker: String
    var company: String
    var who: String
    var category: Category
    var action: String
    var person: String? = nil
}

/// A stock that two or more tracked people bought or hold.
struct Overlap: Identifiable {
    var ticker: String
    var company: String
    var people: [Pick]                 // one per person
    var perCategory: [Category: Int]
    var latest: Date
    var id: String { ticker }
    var buyers: Int { people.filter { $0.action == "buy" }.count }
}

func computeOverlaps(_ picks: [Pick]) -> [Overlap] {
    let byTicker = Dictionary(grouping: picks, by: { $0.ticker })
    var result: [Overlap] = []
    for (ticker, list) in byTicker {
        let byPerson = Dictionary(grouping: list, by: { $0.who })
        let perPerson: [Pick] = byPerson.values.compactMap { ps in
            // Prefer a buy over a holding for the same person, then the newest.
            ps.sorted { a, b in
                if (a.action == "buy") != (b.action == "buy") { return a.action == "buy" }
                return a.time > b.time
            }.first
        }
        guard perPerson.count >= 2 else { continue }
        var counts: [Category: Int] = [:]
        perPerson.forEach { counts[$0.category, default: 0] += 1 }
        let sortedPeople = perPerson.sorted {
            let ia = Category.allCases.firstIndex(of: $0.category) ?? 0
            let ib = Category.allCases.firstIndex(of: $1.category) ?? 0
            return ia != ib ? ia < ib : $0.who < $1.who
        }
        result.append(Overlap(
            ticker: ticker,
            company: list.first(where: { !$0.company.isEmpty })?.company ?? "",
            people: sortedPeople,
            perCategory: counts,
            latest: list.map(\.time).max() ?? Date()
        ))
    }
    return result.sorted { a, b in
        if a.perCategory.count != b.perCategory.count { return a.perCategory.count > b.perCategory.count }
        if a.people.count != b.people.count { return a.people.count > b.people.count }
        return a.latest > b.latest
    }
}

struct AppError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

enum Format {
    static func number(_ v: Double, decimals: Int = 0) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = decimals
        f.maximumFractionDigits = decimals
        f.locale = Locale(identifier: "en_US")
        return f.string(from: NSNumber(value: v)) ?? "\(v)"
    }

    static func money(_ v: Double) -> String {
        if v >= 1e12 { return String(format: "$%.1fT", v / 1e12) }
        if v >= 1e9 { return String(format: "$%.1fB", v / 1e9) }
        if v >= 1e6 { return String(format: "$%.1fM", v / 1e6) }
        return "$" + number(v)
    }

    static func day(_ s: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.date(from: s) ?? Date()
    }

    static func ago(_ d: Date) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        return f.localizedString(for: d, relativeTo: Date())
    }
}


/// Int(x) crashes the app when x is not a number or infinite (e.g. 0/0 from missing data). This returns 0 instead.
func safeInt(_ x: Double) -> Int {
    guard x.isFinite else { return 0 }
    return x >= Double(Int.max) ? Int.max : x <= Double(Int.min) ? Int.min : Int(x)
}
