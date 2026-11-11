import Foundation

/// What a bet would cost and pay. All amounts in dollars. Mirrors Bets.kt.
struct BetPlan {
    let contracts: Int
    let fee: Double
    let cost: Double      // price × contracts + fee: what leaves your account
    let payout: Double    // what comes back if it wins ($1 per contract)
    var profit: Double { payout - cost }
}

/// How much the app suggests, and why. amount is 0 when there's no edge after fees.
struct BetSuggestion {
    let amount: Double
    let edgePoints: Double
    let why: String
}

/// Bet sizing for prediction-market contracts (each pays $1 if it happens).
/// Fees: Kalshi 0.07 × contracts × price × (1 − price), rounded up to the cent; Polymarket US the same shape at
/// 4% (politics), 5% (sports, economics, other) or 7% (crypto). Size: a quarter of the Kelly amount, at most 5% of
/// your budget, and $0 with no edge after fees.
enum Bets {
    static let maxShare = 0.05
    static let kellyPart = 0.25

    static func feeRate(_ source: String, _ category: String) -> Double {
        if source == "Kalshi" { return 0.07 }
        if category == "Politics" { return 0.04 }
        if category == "Crypto" { return 0.07 }
        return 0.05
    }

    static func fee(_ source: String, _ category: String, contracts: Int, price: Double) -> Double {
        (feeRate(source, category) * Double(contracts) * price * (1 - price) * 100 - 1e-9).rounded(.up) / 100
    }

    static func plan(stake: Double, price: Double, source: String, category: String) -> BetPlan {
        guard price > 0, price < 1, stake > 0 else { return BetPlan(contracts: 0, fee: 0, cost: 0, payout: 0) }
        var c = Int((stake / price).rounded(.down))
        while c > 0 && Double(c) * price + fee(source, category, contracts: c, price: price) > stake + 1e-9 { c -= 1 }
        let f = fee(source, category, contracts: c, price: price)
        return BetPlan(contracts: c, fee: f, cost: Double(c) * price + f, payout: Double(c))
    }

    static func suggest(budget: Double, estimate: Double, price: Double, source: String, category: String) -> BetSuggestion {
        guard price > 0, price < 1 else { return BetSuggestion(amount: 0, edgePoints: 0, why: "No price to bet at.") }
        let cost = price + feeRate(source, category) * price * (1 - price)
        let edge = estimate - cost
        if edge <= 0 {
            return BetSuggestion(amount: 0, edgePoints: edge * 100, why: String(format:
                "No edge: after the fee it costs %.0f¢ and the best estimate is %.0f%%. Suggested: skip it.", cost * 100, estimate * 100))
        }
        guard budget > 0 else {
            return BetSuggestion(amount: 0, edgePoints: edge * 100, why: "Set your betting budget to get a suggested amount.")
        }
        let share = min(edge / (1 - cost) * kellyPart, maxShare)
        return BetSuggestion(amount: (budget * share).rounded(.down), edgePoints: edge * 100, why: String(format:
            "Edge of %.1f points after the fee (best estimate %.0f%% vs %.0f¢). Suggested: %.1f%% of your budget%@.",
            edge * 100, estimate * 100, cost * 100, share * 100, share >= maxShare ? ", the most it ever suggests" : ""))
    }
}
