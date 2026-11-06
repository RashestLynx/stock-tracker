package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Pt(val t: Long, val p: Double)

enum class ChartRange(val label: String) { D1("1D"), W1("1W"), M1("1M"), M3("3M"), Y1("1Y"), Y5("5Y") }

/**
 * Price history for charts.
 *  - 1W to 5Y: Massive aggregates (free plan, end-of-day data)
 *  - 1D: built live from Finnhub quotes while the app is open (free plans don't include today's intraday history)
 */
object Charts {
    private val cache = HashMap<String, Pair<Long, List<Pt>>>()
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Blocking. */
    fun history(ticker: String, range: ChartRange, store: Store): List<Pt> {
        if (range == ChartRange.D1) return LiveTicks.today(ticker, store)
        val k = "$ticker|$range"
        synchronized(cache) { cache[k]?.let { if (System.currentTimeMillis() - it.first < 30 * 60_000) return it.second } }
        val key = store.massiveKey
        if (key.isBlank()) throw IOException("Add a Massive key for longer charts")
        val to = Calendar.getInstance()
        val from = Calendar.getInstance()
        val (mult, span) = when (range) {
            ChartRange.W1 -> { from.add(Calendar.DAY_OF_YEAR, -7); 1 to "hour" }
            ChartRange.M1 -> { from.add(Calendar.MONTH, -1); 1 to "day" }
            ChartRange.M3 -> { from.add(Calendar.MONTH, -3); 1 to "day" }
            ChartRange.Y1 -> { from.add(Calendar.YEAR, -1); 1 to "day" }
            else -> { from.add(Calendar.YEAR, -5); 1 to "week" }
        }
        val url = "https://api.massive.com/v2/aggs/ticker/${ticker.uppercase(Locale.US)}/range/$mult/$span/" +
            "${day.format(from.time)}/${day.format(to.time)}?adjusted=true&sort=asc&limit=5000&apiKey=$key"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        try {
            when (conn.responseCode) {
                in 200..299 -> {}
                429 -> throw IOException("Chart data busy, try again in a minute")
                else -> throw IOException("Chart data returned HTTP ${conn.responseCode}")
            }
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: JSONArray()
            var pts = List(arr.length()) { val o = arr.getJSONObject(it); Pt(o.optLong("t"), o.optDouble("c")) }
            // Add today's live price at the end when we have one.
            LiveTicks.today(ticker, store).lastOrNull()?.let { if (pts.isEmpty() || it.t > pts.last().t) pts = pts + it }
            synchronized(cache) { cache[k] = System.currentTimeMillis() to pts }
            return pts
        } finally {
            conn.disconnect()
        }
    }
}

/** Today's live prices, saved as they come in so the 1D chart keeps building while the app runs. */
object LiveTicks {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun keyFor(ticker: String) = "ticks_" + ticker.uppercase(Locale.US)

    fun today(ticker: String, store: Store): List<Pt> {
        val raw = store.prefs.getString(keyFor(ticker), null) ?: return emptyList()
        return try {
            val o = JSONObject(raw)
            if (o.optString("d") != day.format(System.currentTimeMillis())) return emptyList()
            val a = o.getJSONArray("p")
            List(a.length()) { val x = a.getJSONArray(it); Pt(x.getLong(0), x.getDouble(1)) }
        } catch (_: Exception) { emptyList() }
    }

    /** Record a quote. Starts the day with the opening price at the market open (9:30 a.m. New York). */
    fun add(ticker: String, q: LiveQuote, open: Double, store: Store) {
        val now = System.currentTimeMillis()
        var pts = today(ticker, store)
        if (pts.isEmpty() && open > 0) {
            val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
            c.set(Calendar.HOUR_OF_DAY, 9); c.set(Calendar.MINUTE, 30); c.set(Calendar.SECOND, 0)
            if (c.timeInMillis < now) pts = listOf(Pt(c.timeInMillis, open))
        }
        if (pts.isNotEmpty() && now - pts.last().t < 10_000) return
        pts = (pts + Pt(now, q.price)).takeLast(2000)
        val a = JSONArray()
        pts.forEach { a.put(JSONArray().put(it.t).put(it.p)) }
        store.prefs.edit().putString(keyFor(ticker), JSONObject().put("d", day.format(now)).put("p", a).toString()).apply()
    }
}
