package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** One trading day. */
data class Bar(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

/** mood: 1 = usually read as good for the price, -1 = bad, 0 = neutral. */
data class PatternHit(val name: String, val mood: Int, val what: String, val history: String = "")

data class PatternReport(val hits: List<PatternHit>, val support: Double?, val resistance: Double?, val days: Int)

/**
 * Chart-pattern recognition on daily prices (up to 2 years from Massive's free plan).
 *
 * For each signal found today, it also checks this stock's own past: every earlier time the same signal appeared,
 * what the price did over the next 20 trading days, compared with an ordinary 20-day stretch. That shows whether
 * the pattern has actually meant anything for this stock, instead of taking it on faith.
 */
object Patterns {
    private const val AHEAD = 20
    private val cache = HashMap<String, Pair<Long, PatternReport>>()

    /** Blocking. Cached for 30 minutes. */
    fun load(ticker: String, store: Store): PatternReport {
        val t = ticker.uppercase(Locale.US)
        synchronized(cache) { cache[t]?.let { if (System.currentTimeMillis() - it.first < 30 * 60_000) return it.second } }
        val r = analyze(bars(t, store))
        synchronized(cache) { cache[t] = System.currentTimeMillis() to r }
        return r
    }

    private fun bars(ticker: String, store: Store): List<Bar> {
        val key = store.massiveKey
        if (key.isBlank()) throw IOException("Add a Massive key in Settings to see patterns")
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val from = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -729) }
        val url = "https://api.massive.com/v2/aggs/ticker/$ticker/range/1/day/${day.format(from.time)}/" +
            "${day.format(Calendar.getInstance().time)}?adjusted=true&sort=asc&limit=5000&apiKey=$key"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        try {
            when (conn.responseCode) {
                in 200..299 -> {}
                429 -> throw IOException("Price data busy, try again in a minute")
                else -> throw IOException("Price data returned HTTP ${conn.responseCode}")
            }
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: JSONArray()
            return List(arr.length()) {
                val o = arr.getJSONObject(it)
                Bar(o.optLong("t"), o.optDouble("o"), o.optDouble("h"), o.optDouble("l"), o.optDouble("c"), o.optDouble("v"))
            }.filter { it.c > 0 }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- indicators ----------------
    fun sma(c: List<Double>, n: Int, i: Int): Double? = if (i + 1 < n) null else c.subList(i + 1 - n, i + 1).average()

    /** Wilder's 14-day RSI at every day (null until there's enough data). */
    fun rsi(c: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(c.size) { null }
        if (c.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) gain += d else loss -= d }
        gain /= n; loss /= n
        out[n] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in n + 1 until c.size) {
            val d = c[i] - c[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }

    // ---------------- signals (each one: did it fire on day i?) ----------------
    private class Signal(val name: String, val mood: Int, val fires: (Int) -> Boolean, val what: (Int) -> String)

    fun analyze(bars: List<Bar>): PatternReport {
        if (bars.size < 60) return PatternReport(listOf(PatternHit("Not enough history", 0,
            "Patterns need at least 60 trading days of prices; this stock has ${bars.size}.")), null, null, bars.size)
        val c = bars.map { it.c }
        val v = bars.map { it.v }
        val r = rsi(c)
        val s50 = c.indices.map { sma(c, 50, it) }
        val s200 = c.indices.map { sma(c, 200, it) }
        val v50 = v.indices.map { sma(v, 50, it) }
        val last = c.size - 1
        fun pct(x: Double) = String.format(Locale.US, "%+.1f%%", x * 100)
        fun hi(i: Int, n: Int) = (maxOf(0, i - n + 1)..i).maxOf { bars[it].h }
        fun lo(i: Int, n: Int) = (maxOf(0, i - n + 1)..i).minOf { bars[it].l }

        val signals = listOf(
            Signal("Golden cross", 1, { i -> i > 0 && s50[i] != null && s200[i] != null && s50[i - 1] != null && s200[i - 1] != null &&
                s50[i]!! > s200[i]!! && s50[i - 1]!! <= s200[i - 1]!! },
                { "The 50-day average just crossed above the 200-day average, a classic sign a downtrend may be turning up." }),
            Signal("Death cross", -1, { i -> i > 0 && s50[i] != null && s200[i] != null && s50[i - 1] != null && s200[i - 1] != null &&
                s50[i]!! < s200[i]!! && s50[i - 1]!! >= s200[i - 1]!! },
                { "The 50-day average just crossed below the 200-day average, a classic warning that the trend is weakening." }),
            Signal("Overbought (RSI)", -1, { i -> (r[i] ?: 50.0) >= 70 && (r[i - 1] ?: 50.0) < 70 },
                { i -> String.format(Locale.US, "RSI is %.0f. Above 70 means it has risen fast and often pauses or pulls back.", r[i]) }),
            Signal("Oversold (RSI)", 1, { i -> (r[i] ?: 50.0) <= 30 && (r[i - 1] ?: 50.0) > 30 },
                { i -> String.format(Locale.US, "RSI is %.0f. Below 30 means it has fallen fast and often bounces.", r[i]) }),
            Signal("New 52-week high", 1, { i -> i >= 250 && bars[i].c >= hi(i - 1, 250) && bars[i - 1].c < hi(i - 2, 250) },
                { "Closed at its highest price in a year. Stocks making new highs often keep going (momentum)." }),
            Signal("New 52-week low", -1, { i -> i >= 250 && bars[i].c <= lo(i - 1, 250) && bars[i - 1].c > lo(i - 2, 250) },
                { "Closed at its lowest price in a year. Stocks making new lows often keep falling." }),
            Signal("Volume spike", 0, { i -> v50[i - 1] != null && v[i] >= 2.5 * v50[i - 1]!! && (i == 0 || v[i - 1] < 2.5 * (v50[i - 2] ?: Double.MAX_VALUE)) },
                { i -> String.format(Locale.US, "Traded %.1f× its usual volume. Big volume often means news; the direction of the move matters.",
                    v[i] / v50[i - 1]!!) }),
            Signal("Gap up", 1, { i -> bars[i].o >= bars[i - 1].c * 1.03 },
                { i -> "Opened ${pct(bars[i].o / bars[i - 1].c - 1)} above yesterday's close." }),
            Signal("Gap down", -1, { i -> bars[i].o <= bars[i - 1].c * 0.97 },
                { i -> "Opened ${pct(bars[i].o / bars[i - 1].c - 1)} below yesterday's close." }),
            Signal("Breakout", 1, { i -> i >= 31 && (hi(i - 1, 30) / lo(i - 1, 30) - 1) < 0.10 && bars[i].c > hi(i - 1, 30) &&
                bars[i - 1].c <= hi(i - 2, 30) },
                { "Broke above a tight 30-day trading range. Breakouts on strong volume tend to be more reliable." }),
            Signal("Breakdown", -1, { i -> i >= 31 && (hi(i - 1, 30) / lo(i - 1, 30) - 1) < 0.10 && bars[i].c < lo(i - 1, 30) &&
                bars[i - 1].c >= lo(i - 2, 30) },
                { "Fell below a tight 30-day trading range." }),
        )

        // Ordinary 20-day result for this stock, to compare against.
        val base = (0 until c.size - AHEAD).map { c[it + AHEAD] / c[it] - 1 }
        val baseAvg = if (base.isEmpty()) 0.0 else base.average()
        val baseUp = if (base.isEmpty()) 0.0 else base.count { it > 0 }.toDouble() / base.size

        val hits = ArrayList<PatternHit>()
        // Signals that fired in the last 5 trading days.
        for (s in signals) {
            val recent = (maxOf(1, last - 4)..last).lastOrNull { runCatching { s.fires(it) }.getOrDefault(false) } ?: continue
            val past = (1 until c.size - AHEAD).filter { it < recent - 5 && runCatching { s.fires(it) }.getOrDefault(false) }
            val after = past.map { c[it + AHEAD] / c[it] - 1 }
            val history = if (after.size < 3) "Too rare in this stock's last 2 years (${after.size} time${if (after.size == 1) "" else "s"}) to judge."
            else String.format(Locale.US, "The %d earlier times in 2 years, it was up 20 trading days later %d times (avg %s), vs %.0f%% of the time normally (avg %s).",
                after.size, after.count { it > 0 }, pct(after.average()), baseUp * 100, pct(baseAvg))
            val ago = last - recent
            hits.add(PatternHit(s.name + if (ago == 0) " (today)" else " ($ago day${if (ago == 1) "" else "s"} ago)",
                s.mood, s.what(recent), history))
        }

        // Trend right now.
        val a50 = s50[last]
        val a200 = s200[last]
        if (a50 != null && a200 != null) {
            val p = c[last]
            hits.add(0, when {
                p > a50 && a50 > a200 -> PatternHit("Uptrend", 1, "Price is above its 50-day and 200-day averages, and the 50 is above the 200.")
                p < a50 && a50 < a200 -> PatternHit("Downtrend", -1, "Price is below its 50-day and 200-day averages, and the 50 is below the 200.")
                else -> PatternHit("No clear trend", 0, "Price and its 50-day and 200-day averages are mixed.")
            })
        } else if (a50 != null) {
            hits.add(0, PatternHit(if (c[last] > a50) "Above 50-day average" else "Below 50-day average",
                if (c[last] > a50) 1 else -1, "Not enough history yet for the 200-day average."))
        }
        r[last]?.let { val x = it; if (hits.none { h -> h.name.startsWith("Overbought") || h.name.startsWith("Oversold") }) {
            if (x >= 70) hits.add(PatternHit("Overbought (RSI)", -1, String.format(Locale.US, "RSI is %.0f, above 70.", x)))
            else if (x <= 30) hits.add(PatternHit("Oversold (RSI)", 1, String.format(Locale.US, "RSI is %.0f, below 30.", x)))
        } }

        doubleTopBottom(bars)?.let { hits.add(it) }
        val (sup, res) = levels(bars)
        return PatternReport(hits, sup, res, bars.size)
    }

    /** Swing highs/lows: a day higher (lower) than the 5 days on each side. */
    private fun swings(bars: List<Bar>, high: Boolean): List<Int> = (5 until bars.size - 5).filter { i ->
        val x = if (high) bars[i].h else bars[i].l
        (i - 5..i + 5).all { j -> j == i || if (high) bars[j].h < x else bars[j].l > x }
    }

    /** Two similar peaks (or troughs) in the last 6 months with a clear dip (or rise) between them. */
    private fun doubleTopBottom(bars: List<Bar>): PatternHit? {
        val start = maxOf(0, bars.size - 126)
        val lastClose = bars.last().c
        val highs = swings(bars, true).filter { it >= start }
        for (a in highs.indices.reversed()) for (b in (a - 1) downTo 0) {
            val i = highs[b]; val j = highs[a]
            if (j - i < 15) continue
            val p1 = bars[i].h; val p2 = bars[j].h
            if (abs(p1 - p2) / maxOf(p1, p2) > 0.03) continue
            val trough = (i..j).minOf { bars[it].l }
            if (trough > minOf(p1, p2) * 0.92) continue
            val confirmed = lastClose < trough
            return PatternHit("Double top" + if (confirmed) " (confirmed)" else " (forming)", -1,
                String.format(Locale.US, "Two peaks near $%.2f with a dip to $%.2f between. %s", maxOf(p1, p2), trough,
                    if (confirmed) "Price has fallen below the dip, which is when the pattern counts." else "It only counts if price falls below $%.2f.".format(trough)),
                "Chart patterns like this are well known but studies find they predict weakly; treat it as a hint.")
        }
        val lows = swings(bars, false).filter { it >= start }
        for (a in lows.indices.reversed()) for (b in (a - 1) downTo 0) {
            val i = lows[b]; val j = lows[a]
            if (j - i < 15) continue
            val p1 = bars[i].l; val p2 = bars[j].l
            if (abs(p1 - p2) / maxOf(p1, p2) > 0.03) continue
            val peak = (i..j).maxOf { bars[it].h }
            if (peak < maxOf(p1, p2) * 1.08) continue
            val confirmed = lastClose > peak
            return PatternHit("Double bottom" + if (confirmed) " (confirmed)" else " (forming)", 1,
                String.format(Locale.US, "Two lows near $%.2f with a rise to $%.2f between. %s", minOf(p1, p2), peak,
                    if (confirmed) "Price has risen above that peak, which is when the pattern counts." else "It only counts if price rises above $%.2f.".format(peak)),
                "Chart patterns like this are well known but studies find they predict weakly; treat it as a hint.")
        }
        return null
    }

    /** Nearest support below and resistance above, from swing lows/highs in the last year that were touched twice or more. */
    private fun levels(bars: List<Bar>): Pair<Double?, Double?> {
        val start = maxOf(0, bars.size - 252)
        val p = bars.last().c
        val pts = (swings(bars, true) + swings(bars, false)).filter { it >= start }
            .map { if (bars[it].h >= p) bars[it].h else bars[it].l }
        fun cluster(xs: List<Double>) = xs.filter { x -> xs.count { abs(it - x) / x < 0.015 } >= 2 }
        // At least 1% away, so it's a level to watch rather than today's price.
        val sup = cluster(pts.filter { it < p * 0.99 }).maxOrNull()
        val res = cluster(pts.filter { it > p * 1.01 }).minOrNull()
        return sup to res
    }
}
