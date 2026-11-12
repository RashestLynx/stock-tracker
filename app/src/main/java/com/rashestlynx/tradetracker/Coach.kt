package com.rashestlynx.tradetracker

import java.util.Locale

/** A plain-English call on one game side for someone new to prediction markets. */
data class Verdict(val good: Boolean, val text: String)

object Coach {
    /** Edge needed before calling something value: smaller gaps are within the noise of the estimate itself. */
    private const val MIN_EDGE = 0.03

    /**
     * Best estimate = the average of every independent price we have for this side: each sportsbook's chance after
     * removing its cut (books were the most accurate source in testing), Kalshi's and Polymarket's. Then the cheapest
     * place to back it, after fees, is compared with that estimate.
     */
    fun verdict(side: SportSide, books: List<BookLine>): Verdict? {
        val ests = books.map { it.fair } + listOfNotNull(side.kalshiP, side.polyP)
        if (ests.size < 2) return null
        val est = ests.average()
        data class Option(val where: String, val ev: Double, val price: String)
        val opts = mutableListOf<Option>()
        side.kalshi?.prob?.takeIf { it in 0.01..0.99 }?.let { p ->
            val cost = p + Bets.feeRate("Kalshi", "Sports") * p * (1 - p)
            opts += Option("Kalshi", est / cost - 1, String.format(Locale.US, "%.0f¢", p * 100))
        }
        side.poly?.prob?.takeIf { it in 0.01..0.99 }?.let { p ->
            val cost = p + Bets.feeRate("Polymarket", "Sports") * p * (1 - p)
            opts += Option("Polymarket", est / cost - 1, String.format(Locale.US, "%.0f¢", p * 100))
        }
        books.forEach { b -> opts += Option(b.book, est * (1 + b.payout) - 1, b.oddsText) }
        val best = opts.maxByOrNull { it.ev } ?: return null
        return if (best.ev >= MIN_EDGE) Verdict(true, String.format(Locale.US,
            "Possible value: %s on %s at %s. Best estimate %.0f%%, expected return about %+.0f%% per bet. Keep it small.",
            side.label, best.where, best.price, est * 100, best.ev * 100))
        else Verdict(false, String.format(Locale.US,
            "%s: no edge. Every site's price matches the best estimate (%.0f%%). Skip, or treat it as entertainment money.",
            side.label, est * 100))
    }

    val GUIDE = listOf(
        "What a price means" to "A contract priced 62¢ pays \$1 if it happens and \$0 if not. The price is the crowd's chance: 62¢ ≈ 62%. Sportsbook odds say the same thing in another format: −150 ≈ 60%, +200 ≈ 33%.",
        "How you win long-term" to "Not by picking winners, but by paying less than the true chance. Backing a 70% favorite at 75¢ loses money over time; backing a 40% underdog at 33¢ makes money over time, even though it loses most bets.",
        "Why most bets have no edge" to "In this app's tests, the sportsbooks and Kalshi were more accurate than any rating model. So the price is usually right. The app only says 'possible value' when one site is clearly cheaper than the average of all of them, after fees.",
        "Where edges come from" to "1) Different sites disagreeing (shop for the best price; that's what 'pays most' shows). 2) News the price hasn't caught up to yet (injuries, lineups). 3) Kalshi longshots: research found contracts under about 15¢ win less often than their price, so cheap underdogs are usually a bad buy.",
        "How much to bet" to "Set a betting budget you can lose completely, and keep it separate. The 'How much?' screen suggests at most 5% of it per bet, using a quarter of the Kelly formula, and \$0 when there's no edge. Never chase losses.",
        "Fees and payouts" to "Kalshi charges up to about 1.75¢ per contract (most near 50¢). Polymarket's US fee is a few percent of the win. Sportsbooks build their cut into the odds (about 4-5%). The app subtracts all of these before calling anything value.",
        "Keep score" to "Log every pick under 'My picks'. After 50+ picks, your record tells you whether you actually have an edge. Most people don't, and that's useful to know.",
        "Getting help" to "If betting stops being fun or you're betting more than you planned, call or text 1-800-GAMBLER (free, 24/7).",
    )
}
