import Foundation

/// Your track record: each stock you log as a buy, graded against the S&P 500 over the same days.
/// Kept in the Keychain like your account data. Mirrors Journal.kt.
struct JournalPick: Codable, Identifiable {
    var id: Date              // when it was logged
    var ticker: String
    var price: Double         // stock price when logged
    var spy: Double           // S&P 500 fund (SPY) price when logged
    var reason: String
    var soldPrice: Double?
    var soldSpy: Double?
    var soldAt: Date?
}

struct GradedPick: Identifiable {
    let pick: JournalPick
    let now: Double?
    let spyNow: Double?
    var id: Date { pick.id }
    var ret: Double? { (pick.soldPrice ?? now).map { $0 / pick.price - 1 } }
    var spyRet: Double? { (pick.soldSpy ?? spyNow).map { $0 / pick.spy - 1 } }
    var beat: Bool? { guard let r = ret, let s = spyRet else { return nil }; return r > s }
}

struct TrackRecord {
    let graded: [GradedPick]
    private var scored: [GradedPick] { graded.filter { $0.ret != nil && $0.spyRet != nil } }
    var count: Int { scored.count }
    var avg: Double { count == 0 ? 0 : scored.reduce(0) { $0 + ($1.ret ?? 0) } / Double(count) }
    var spyAvg: Double { count == 0 ? 0 : scored.reduce(0) { $0 + ($1.spyRet ?? 0) } / Double(count) }
    var wins: Int { scored.filter { $0.beat == true }.count }
}

enum Journal {
    static var all: [JournalPick] {
        get {
            let s = Keychain.get("journal")
            guard !s.isEmpty else { return [] }
            return (try? JSONDecoder().decode([JournalPick].self, from: Data(s.utf8))) ?? []
        }
        set { Keychain.set("journal", (try? JSONEncoder().encode(newValue)).flatMap { String(data: $0, encoding: .utf8) } ?? "") }
    }

    /// Latest price: live (Finnhub) when you have a key, otherwise the last close.
    static func price(_ ticker: String) async -> Double? {
        if let q = await Live.quote(ticker), q.price > 0 { return q.price }
        if let p = await StockInfo.price(ticker), p > 0 { return p }
        return nil
    }

    /// Logs a buy at today's price. Returns false when a price couldn't be found.
    static func logBuy(_ ticker: String, reason: String) async -> Bool {
        guard let p = await price(ticker), let s = await price("SPY") else { return false }
        all.append(JournalPick(id: Date(), ticker: ticker.uppercased(), price: p, spy: s,
                               reason: reason.trimmingCharacters(in: .whitespacesAndNewlines)))
        return true
    }

    /// Marks a pick as sold at today's price, which freezes its result.
    static func markSold(_ id: Date) async {
        guard let pick = all.first(where: { $0.id == id }),
              let p = await price(pick.ticker), let s = await price("SPY") else { return }
        all = all.map { var x = $0; if x.id == id { x.soldPrice = p; x.soldSpy = s; x.soldAt = Date() }; return x }
    }

    static func delete(_ id: Date) { all.removeAll { $0.id == id } }

    static func grade() async -> TrackRecord {
        let picks = all.sorted { $0.id > $1.id }
        var prices: [String: Double] = [:]
        for t in Set(picks.filter { $0.soldPrice == nil }.map(\.ticker)) {
            if let p = await price(t) { prices[t] = p }
        }
        let spy = picks.contains { $0.soldPrice == nil } ? await price("SPY") : nil
        return TrackRecord(graded: picks.map { GradedPick(pick: $0, now: prices[$0.ticker], spyNow: spy) })
    }

    /// Quick facts to look at before buying. Uses saved data only, so it's instant.
    @MainActor
    static func check(_ ticker: String, model: AppModel) -> [(good: Bool, text: String)] {
        let t = ticker.uppercased()
        var lines: [(good: Bool, text: String)] = []
        let owned = Accounts.owned()
        let total = owned.values.reduce(0) { $0 + $1.value }
        if let o = owned[t] {
            let share = total > 0 ? o.value / total : 0
            lines.append((share <= 0.2, String(format: "You already own $%@ of %@ (%.0f%% of your money).",
                                               Format.number(o.value), t, share * 100)))
        }
        if let s = model.scores {
            for (name, list) in [("growth", s.growth), ("income", s.income), ("wealth", s.wealth)] {
                if let hit = list.first(where: { $0.ticker == t }) {
                    lines.append((true, "On the Top 30 for \(name) (score \(hit.score)).")); break
                }
            }
        }
        let since = Date().addingTimeInterval(-90 * 86_400)
        let recent = model.moves.filter { $0.ticker == t && $0.time >= since }
        let sold = recent.filter { $0.side == "sell" }, bought = recent.filter { $0.side == "buy" }
        if !sold.isEmpty || !bought.isEmpty {
            let good = bought.reduce(0) { $0 + $1.value } >= sold.reduce(0) { $0 + $1.value }
            lines.append((good, "Last 90 days, people the app tracks: \(bought.count) buy\(bought.count == 1 ? "" : "s"), \(sold.count) sale\(sold.count == 1 ? "" : "s")."))
        }
        if all.count >= 5 {
            lines.append((true, "You've logged \(all.count) buys. Check Track record to see how they're doing against the S&P 500."))
        }
        lines.append((true, "Rule of thumb: keep any one stock under about 5–10% of your money."))
        return lines
    }
}
