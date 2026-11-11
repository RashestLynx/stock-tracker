package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Mover(val ticker: String, val name: String, val pct: Double, val price: Double)

data class MoversResult(
    val fromDate: String,
    val toDate: String,
    val marketGainers: List<Mover>,
    val marketLosers: List<Mover>,
    val trackedGainers: List<Mover>,
    val trackedLosers: List<Mover>,
    val fetchedAt: Long,
)

/**
 * Best and worst performing U.S. stocks over the past week.
 *
 * Uses Massive's (formerly Polygon.io) free "grouped daily" data: one request returns
 * the closing price of every U.S. stock for a day, so two requests (latest trading day
 * and one week earlier) cover the whole market. Free plan: 5 requests/minute, end-of-day.
 */
object Movers {
    private const val CACHE_MS = 6 * 60 * 60 * 1000L
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var calls = 0

    /** Starts a fresh count for the 5-requests-a-minute limit. */
    fun resetCalls() { calls = 0 }

    fun parseDay(date: String): java.util.Date = dayFmt.parse(date)!!

    /** Blocking: call from a background thread. */
    fun load(store: Store, force: Boolean): MoversResult {
        if (!force) cached(store)?.let { if (System.currentTimeMillis() - it.fetchedAt < CACHE_MS) return it }
        val key = store.massiveKey
        if (key.isBlank()) throw IOException("Add a free Massive key first")
        calls = 0

        // Latest trading day with data (the free plan is end-of-day, so today may not be ready).
        val latest = findDay(Calendar.getInstance(), key, 7)
            ?: throw IOException("No recent market data available")
        val start = Calendar.getInstance().apply { time = dayFmt.parse(latest.first)!!; add(Calendar.DAY_OF_YEAR, -7) }
        val earlier = findDay(start, key, 4) ?: throw IOException("No market data for last week")

        val names = store.cachedNameMap()
            ?: Edgar.tickerNames(store.userAgent).also { store.saveNameMap(it) }
        val tracked = (store.picks().map { it.ticker } + store.moves().map { it.ticker }).toSet()

        val all = latest.second.mapNotNull { (ticker, now) ->
            val before = earlier.second[ticker] ?: return@mapNotNull null
            // Only real companies from the SEC list; skip warrants, units and penny stocks.
            val name = names[ticker] ?: return@mapNotNull null
            if (!Regex("^[A-Z]{1,5}$").matches(ticker) || before.first <= 0) return@mapNotNull null
            Triple(Mover(ticker, name, (now.first - before.first) / before.first * 100, now.first), now.second, ticker in tracked)
        }
        val market = all.filter { it.first.price >= 5 && it.second >= 300_000 }.map { it.first }
        val mine = all.filter { it.third && it.first.price >= 1 }.map { it.first }

        val result = MoversResult(
            fromDate = earlier.first, toDate = latest.first,
            marketGainers = market.sortedByDescending { it.pct }.take(10),
            marketLosers = market.sortedBy { it.pct }.take(10),
            trackedGainers = mine.sortedByDescending { it.pct }.take(10),
            trackedLosers = mine.sortedBy { it.pct }.take(10),
            fetchedAt = System.currentTimeMillis(),
        )
        store.moversCache = toJson(result)
        return result
    }

    fun cached(store: Store): MoversResult? = try {
        if (store.moversCache.isBlank()) null else fromJson(JSONObject(store.moversCache))
    } catch (e: Exception) {
        null
    }

    /** Walks back from [from] to the first weekday that has data. Returns (date, ticker -> (close, volume)). */
    fun findDay(from: Calendar, key: String, maxTries: Int): Pair<String, Map<String, Pair<Double, Double>>>? {
        val c = from.clone() as Calendar
        var tries = 0
        while (tries < maxTries) {
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                tries++
                val date = dayFmt.format(c.time)
                val data = grouped(date, key)
                if (data.isNotEmpty()) return date to data
            }
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return null
    }

    private fun grouped(date: String, key: String): Map<String, Pair<Double, Double>> {
        // Free plan allows 5 requests a minute.
        if (calls >= 4) Thread.sleep(13_000)
        calls++
        val url = "https://api.massive.com/v2/aggs/grouped/locale/us/market/stocks/$date?adjusted=true&apiKey=$key"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401 -> throw IOException("Massive key not accepted. Check it in Settings.")
                403 -> return emptyMap() // e.g. today's data not available on the free plan yet
                429 -> { Thread.sleep(15_000); return grouped(date, key) }
                else -> throw IOException("Market data returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return emptyMap()
            val map = HashMap<String, Pair<Double, Double>>(results.length())
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                map[o.optString("T")] = o.optDouble("c", 0.0) to o.optDouble("v", 0.0)
            }
            return map
        } finally {
            conn.disconnect()
        }
    }

    // ---- cache (de)serialization ----
    private fun moversJson(list: List<Mover>) = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("t", it.ticker).put("n", it.name).put("p", it.pct).put("c", it.price)) }
    }

    private fun moversFrom(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) {
        val o = a.getJSONObject(it)
        Mover(o.optString("t"), o.optString("n"), o.optDouble("p"), o.optDouble("c"))
    }

    private fun toJson(r: MoversResult) = JSONObject()
        .put("from", r.fromDate).put("to", r.toDate).put("at", r.fetchedAt)
        .put("mg", moversJson(r.marketGainers)).put("ml", moversJson(r.marketLosers))
        .put("tg", moversJson(r.trackedGainers)).put("tl", moversJson(r.trackedLosers))
        .toString()

    private fun fromJson(o: JSONObject) = MoversResult(
        o.optString("from"), o.optString("to"),
        moversFrom(o.optJSONArray("mg")), moversFrom(o.optJSONArray("ml")),
        moversFrom(o.optJSONArray("tg")), moversFrom(o.optJSONArray("tl")),
        o.optLong("at"),
    )
}
