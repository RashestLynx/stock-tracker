import Foundation

/// One buy or sale with a dollar size, used for "Today's buy & sale". side = "buy" or "sell".
struct Move: Codable, Hashable {
    var time: Date
    var category: Category
    var ticker: String
    var company: String
    var who: String
    var role: String
    var side: String
    var value: Double
    var valueLabel: String
    var link: String
    var key: String
}

struct Highlight {
    let move: Move
    let score: Double
    let reasons: [String]
}

struct DailyHighlights {
    let isToday: Bool
    let buy: Highlight?
    let sale: Highlight?
}

/// Picks the day's most notable buy and sale.
///
/// Nobody can know which trade will pay off, so this ranks by signals investors commonly watch:
///  - size: bigger dollar amounts score higher (on a log scale, so $10M isn't 1,000x $10K)
///  - who: CEOs, CFOs and presidents score highest, then other executives, directors and big owners;
///    members of Congress get a bump
///  - agreement (buys only): other tracked people buying or holding the same stock in the last 90 days
enum Highlights {
    static func compute(moves: [Move], picks: [Pick]) -> DailyHighlights {
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        var pool = moves.filter { $0.time >= today }
        var isToday = true
        if pool.isEmpty {
            // Nothing yet today (weekends, early morning): fall back to the last 3 days.
            let start = cal.date(byAdding: .day, value: -3, to: today) ?? today
            pool = moves.filter { $0.time >= start }
            isToday = false
        }
        let buy = pool.filter { $0.side == "buy" }.map { score($0, picks) }.max { $0.score < $1.score }
        let sale = pool.filter { $0.side == "sell" }.map { score($0, picks) }.max { $0.score < $1.score }
        return DailyHighlights(isToday: isToday, buy: buy, sale: sale)
    }

    private static func score(_ m: Move, _ picks: [Pick]) -> Highlight {
        var reasons: [String] = []
        let isCongress = m.category == .senate || m.category == .house
        reasons.append(isCongress ? "Disclosed range \(m.valueLabel)"
                       : m.side == "buy" ? "\(m.valueLabel) open-market purchase" : "\(m.valueLabel) open-market sale")

        let (weight, roleReason) = roleWeight(m)
        if let r = roleReason { reasons.append(r) }

        var agreement = 1.0
        if m.side == "buy" {
            let others = Set(picks.filter { $0.ticker == m.ticker && $0.who != m.who }.map(\.who)).count
            if others > 0 {
                agreement = 1.0 + 0.2 * Double(min(others, 5))
                reasons.append("\(others) other tracked \(others == 1 ? "person" : "people") also bought or hold it")
            }
        }
        return Highlight(move: m, score: log10(max(m.value, 1)) * weight * agreement, reasons: reasons)
    }

    private static func roleWeight(_ m: Move) -> (Double, String?) {
        if m.category == .senate || m.category == .house { return (1.3, "Trade by a member of Congress") }
        let r = m.role.lowercased()
        if ["chief executive", "ceo", "president", "chairman"].contains(where: { r.contains($0) }) {
            return (1.5, "Made by the company's top boss")
        }
        if ["chief financial", "cfo"].contains(where: { r.contains($0) }) {
            return (1.5, "Made by the CFO, who knows the books best")
        }
        if ["chief", "officer", "evp", "vp"].contains(where: { r.contains($0) }) { return (1.3, "Made by a senior executive") }
        if r.contains("director") { return (1.2, "Made by a board director") }
        if r.contains("10%") { return (1.1, "Made by a 10%+ owner") }
        return (1.0, nil)
    }
}
