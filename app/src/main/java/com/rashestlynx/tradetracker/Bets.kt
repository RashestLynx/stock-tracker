package com.rashestlynx.tradetracker

import kotlin.math.ceil
import kotlin.math.floor

/** What a bet would cost and pay. All amounts in dollars. */
data class BetPlan(
    val contracts: Int,
    val fee: Double,
    val cost: Double,       // price × contracts + fee: what leaves your account
    val payout: Double,     // what comes back if it wins ($1 per contract)
) {
    val profit get() = payout - cost
}

/** How much the app suggests, and why. amount is 0 when there's no edge after fees. */
data class BetSuggestion(val amount: Double, val edgePoints: Double, val why: String)

/**
 * Bet sizing for prediction-market contracts (each pays $1 if it happens).
 *
 * Fees: Kalshi charges 0.07 × contracts × price × (1 − price), rounded up to the cent. Polymarket US uses the same
 * shape; since Nov 6, 2026 its rate is the same for every category and set by your 30-day trading volume:
 * 0.095 under $10,000, 0.085 to $50,000, 0.075 to $250,000, 0.0695 above (and 0.0695 for a new account's first week).
 * The app uses 0.095, the rate most people pay, so it never understates the fee.
 *
 * Size: a quarter of the "Kelly" amount (the standard formula for sizing a bet you have an edge on), never more
 * than 5% of your budget. With no edge after fees, the suggestion is $0.
 */
object Bets {
    const val MAX_SHARE = 0.05
    const val KELLY_PART = 0.25

    fun feeRate(source: String, category: String): Double = when {
        source == "Kalshi" -> 0.07
        else -> POLY_US_RATE
    }

    /** Polymarket US taker rate for accounts under $10,000 of 30-day volume (their highest tier, from Nov 6, 2026). */
    const val POLY_US_RATE = 0.095

    /** Fee for [contracts] at [price], rounded up to the cent. */
    fun fee(source: String, category: String, contracts: Int, price: Double): Double =
        ceil(feeRate(source, category) * contracts * price * (1 - price) * 100 - 1e-9) / 100

    /** The most contracts [stake] buys at [price] including the fee. */
    fun plan(stake: Double, price: Double, source: String, category: String): BetPlan {
        if (price <= 0 || price >= 1 || stake <= 0) return BetPlan(0, 0.0, 0.0, 0.0)
        var c = floor(stake / price).toInt()
        while (c > 0 && c * price + fee(source, category, c, price) > stake + 1e-9) c--
        val f = fee(source, category, c, price)
        return BetPlan(c, f, c * price + f, c.toDouble())
    }

    /**
     * Suggested stake when you think the chance is [estimate] and the contract costs [price].
     * [budget] is the money you've set aside for betting.
     */
    fun suggest(budget: Double, estimate: Double, price: Double, source: String, category: String): BetSuggestion {
        if (price <= 0 || price >= 1) return BetSuggestion(0.0, 0.0, "No price to bet at.")
        val feePer = feeRate(source, category) * price * (1 - price)
        val cost = price + feePer
        val edge = estimate - cost
        val pts = edge * 100
        if (edge <= 0) {
            return BetSuggestion(0.0, pts, String.format(java.util.Locale.US,
                "No edge: after the fee it costs %.0f¢ and the best estimate is %.0f%%. Suggested: skip it.",
                cost * 100, estimate * 100))
        }
        if (budget <= 0) return BetSuggestion(0.0, pts, "Set your betting budget to get a suggested amount.")
        val kelly = edge / (1 - cost)
        val share = minOf(kelly * KELLY_PART, MAX_SHARE)
        val amount = floor(budget * share)
        return BetSuggestion(amount, pts, String.format(java.util.Locale.US,
            "Edge of %.1f points after the fee (best estimate %.0f%% vs %.0f¢). Suggested: %.1f%% of your budget%s.",
            pts, estimate * 100, cost * 100, share * 100, if (share >= MAX_SHARE) ", the most it ever suggests" else ""))
    }
}
