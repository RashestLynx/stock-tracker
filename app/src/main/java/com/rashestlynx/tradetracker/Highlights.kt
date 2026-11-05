package com.rashestlynx.tradetracker

import java.util.Calendar
import java.util.Locale
import kotlin.math.log10

/** The top buy or sale of the day, with the reasons it was picked. */
data class Highlight(val move: Move, val score: Double, val reasons: List<String>, val othersBuying: Int)

data class DailyHighlights(val label: String, val buy: Highlight?, val sale: Highlight?)

/**
 * Picks the day's most notable buy and sale.
 *
 * Nobody can know which trade will pay off, so this ranks by signals investors commonly watch:
 *  - size: bigger dollar amounts score higher (on a log scale, so $10M isn't 1,000x $10K)
 *  - who: CEOs, CFOs and presidents score highest, then directors and big owners; members of Congress get a bump
 *  - agreement (buys only): other tracked people buying or holding the same stock in the last 90 days
 */
object Highlights {

    fun compute(moves: List<Move>, picks: List<Pick>): DailyHighlights {
        val today = dayStart(0)
        var label = "Today"
        var pool = moves.filter { it.time >= today }
        if (pool.isEmpty()) {
            // Nothing yet today (weekends, early morning): fall back to the last 3 days.
            pool = moves.filter { it.time >= dayStart(-3) }
            label = "Last 3 days"
        }
        val buy = pool.filter { it.side == "buy" }.map { score(it, picks) }.maxByOrNull { it.score }
        val sale = pool.filter { it.side == "sell" }.map { score(it, picks) }.maxByOrNull { it.score }
        return DailyHighlights(label, buy, sale)
    }

    private fun score(m: Move, picks: List<Pick>): Highlight {
        val reasons = mutableListOf<String>()
        val size = log10(m.value.coerceAtLeast(1.0))

        val (roleWeight, roleReason) = roleWeight(m)
        roleReason?.let { reasons.add(it) }

        var others = 0
        var agreement = 1.0
        if (m.side == "buy") {
            others = picks.filter { it.ticker == m.ticker && it.who != m.who }.map { it.who }.distinct().size
            if (others > 0) {
                agreement = 1.0 + 0.2 * others.coerceAtMost(5)
                reasons.add("$others other tracked ${if (others == 1) "person" else "people"} also bought or hold it")
            }
        }

        reasons.add(0, when {
            m.category == Category.SENATE || m.category == Category.HOUSE -> "Disclosed range ${m.valueLabel}"
            m.side == "buy" -> "${m.valueLabel} open-market purchase"
            else -> "${m.valueLabel} open-market sale"
        })
        return Highlight(m, size * roleWeight * agreement, reasons, others)
    }

    private fun roleWeight(m: Move): Pair<Double, String?> {
        if (m.category == Category.SENATE || m.category == Category.HOUSE) {
            return 1.3 to "Trade by a member of Congress"
        }
        val r = m.role.lowercase(Locale.US)
        return when {
            listOf("chief executive", "ceo", "president", "chairman").any { it in r } -> 1.5 to "Made by the company's top boss"
            listOf("chief financial", "cfo").any { it in r } -> 1.5 to "Made by the CFO, who knows the books best"
            "chief" in r || "officer" in r || "evp" in r || "vp" in r -> 1.3 to "Made by a senior executive"
            "director" in r -> 1.2 to "Made by a board director"
            "10%" in r -> 1.1 to "Made by a 10%+ owner"
            else -> 1.0 to null
        }
    }

    private fun dayStart(offsetDays: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }
}
