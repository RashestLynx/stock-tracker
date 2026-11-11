import Foundation

/// One prediction you logged: you "bought" `side` of `label` at the market's price then. Mirrors PredJournal.kt.
struct PredPick: Codable, Identifiable {
    var id: Date
    var source: String
    var title: String
    var url: String
    var label: String
    var side: String        // "yes" or "no"
    var price: Double       // what one $1 contract cost then, 0-1 (the market's chance)
    var ref: String
}

/// Where a pick stands now. `won` is nil until the market settles.
struct PredStatus: Identifiable {
    let pick: PredPick
    let now: Double?
    let won: Bool?
    var id: Date { pick.id }
    /// Profit per $1 staked: each contract costs `pick.price` and pays $1 if right. Open picks use today's price.
    var profit: Double? {
        guard pick.price > 0 else { return nil }
        let value: Double
        switch won {
        case .some(true): value = 1
        case .some(false): value = 0
        case .none: guard let n = now else { return nil }; value = n
        }
        return value / pick.price - 1
    }
}

struct PredRecord {
    let picks: [PredStatus]
    var settled: [PredStatus] { picks.filter { $0.won != nil } }
    var wins: Int { settled.filter { $0.won == true }.count }
    /// How many of the settled picks the market's odds said you'd win.
    var expected: Double { settled.reduce(0) { $0 + $1.pick.price } }
    var profit: Double { picks.compactMap(\.profit).reduce(0, +) }
}

/// Your prediction-market track record, kept in the Keychain. Each pick is followed until its market settles.
enum PredJournal {
    static var all: [PredPick] {
        get {
            let s = Keychain.get("pred_journal")
            guard !s.isEmpty else { return [] }
            return (try? JSONDecoder().decode([PredPick].self, from: Data(s.utf8))) ?? []
        }
        set { Keychain.set("pred_journal", (try? JSONEncoder().encode(newValue)).flatMap { String(data: $0, encoding: .utf8) } ?? "") }
    }

    static func log(_ e: PredEvent, _ o: PredOutcome, side: String) {
        guard !o.ref.isEmpty else { return }
        all.append(PredPick(id: Date(), source: e.source, title: e.title, url: e.url, label: o.label, side: side,
                            price: side == "yes" ? o.prob : 1 - o.prob, ref: o.ref))
    }

    static func delete(_ id: Date) { all.removeAll { $0.id == id } }

    static func grade() async -> PredRecord {
        var out: [PredStatus] = []
        for p in all.sorted(by: { $0.id > $1.id }) { out.append(await status(p)) }
        return PredRecord(picks: out)
    }

    private static func status(_ p: PredPick) async -> PredStatus {
        let parts = p.ref.split(separator: ":").map(String.init)
        switch parts.first {
        case "k":
            guard parts.count > 1, let m = await kalshiMarket(parts[1]) else { return PredStatus(pick: p, now: nil, won: nil) }
            let result = m["result"] as? String ?? ""
            let now = Predictions.kalshiProb(m).map { p.side == "yes" ? $0 : 1 - $0 }
            let won: Bool? = (result == "yes" || result == "no") ? result == p.side : nil
            return PredStatus(pick: p, now: now, won: won)
        case "p":
            guard parts.count > 1,
                  let data = try? await Predictions.getData("https://gamma-api.polymarket.com/markets/\(parts[1])"),
                  let m = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return PredStatus(pick: p, now: nil, won: nil)
            }
            let idx = parts.count > 2 ? Int(parts[2]) ?? 0 : 0
            let chosen = p.side == "yes" ? idx : 1 - idx
            var prices: [Double] = []
            if let s = m["outcomePrices"] as? String, let d = s.data(using: .utf8),
               let a = try? JSONSerialization.jsonObject(with: d) as? [Any] {
                prices = a.map { Double("\($0)") ?? .nan }
            } else if let a = m["outcomePrices"] as? [Any] {
                prices = a.map { Double("\($0)") ?? .nan }
            }
            let now = chosen >= 0 && chosen < prices.count && prices[chosen].isFinite ? prices[chosen] : nil
            var won: Bool?
            if m["closed"] as? Bool == true, let n = now {
                if n >= 0.99 { won = true } else if n <= 0.01 { won = false }
            }
            return PredStatus(pick: p, now: now, won: won)
        default:
            return PredStatus(pick: p, now: nil, won: nil)
        }
    }

    private static func kalshiMarket(_ ticker: String) async -> [String: Any]? {
        for base in ["https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2"] {
            if let j = try? await Predictions.get("\(base)/markets/\(ticker)"), let m = j["market"] as? [String: Any] { return m }
        }
        return nil
    }
}
