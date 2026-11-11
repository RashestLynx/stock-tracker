package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A live quote: price, change today in dollars and percent. */
data class LiveQuote(val price: Double, val change: Double, val changePct: Double, val time: Long,
                     val open: Double = 0.0, val prevClose: Double = 0.0)

/** Live stock prices from Finnhub's free API (needs a free key; 60 requests a minute). */
object Live {
    const val SIGNUP = "https://finnhub.io/register"

    /** Your own key, saved on this phone only (Settings → Live prices). */
    fun key(store: Store): String = store.prefs.getString("finnhub_key", "") ?: ""
    fun setKey(store: Store, k: String) = store.prefs.edit().putString("finnhub_key", k.trim()).apply()

    /**
     * How the market is doing today, for the green/red background. Uses Finnhub when you have a key, otherwise
     * Stooq's free delayed prices (no key needed). Blocking.
     */
    fun market(store: Store): LiveQuote? =
        runCatching { quote("SPY", store) }.getOrNull() ?: runCatching { stooqDay("SPY") }.getOrNull()

    /** Today's change from Stooq: tries the quote with previous close, then the daily history, then open vs now. */
    fun stooqDay(ticker: String): LiveQuote? {
        val sym = ticker.lowercase() + ".us"
        fun rows(url: String): List<Map<String, String>> {
            val lines = plain(url).lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) return emptyList()
            val head = lines[0].split(',').map { it.trim().lowercase() }
            return lines.drop(1).map { l -> head.zip(l.split(',').map { it.trim() }).toMap() }
        }
        fun num(m: Map<String, String>, vararg names: String): Double? =
            m.entries.firstOrNull { (k, _) -> names.any { k.startsWith(it) } }?.value?.toDoubleOrNull()?.takeIf { it > 0 }
        fun quote(now: Double, prev: Double, open: Double = 0.0) =
            LiveQuote(now, now - prev, (now / prev - 1) * 100, System.currentTimeMillis(), open, prev)

        runCatching {
            val r = rows("https://stooq.com/q/l/?s=$sym&f=sd2t2ohlcp&h&e=csv").firstOrNull()
            val c = r?.let { num(it, "close") }
            val p = r?.let { num(it, "prev") }
            if (c != null && p != null) return quote(c, p, r.let { num(it, "open") } ?: 0.0)
        }
        runCatching {
            val closes = rows("https://stooq.com/q/d/l/?s=$sym&i=d").mapNotNull { num(it, "close") }
            if (closes.size >= 2) return quote(closes.last(), closes[closes.size - 2])
        }
        runCatching {
            val r = rows("https://stooq.com/q/l/?s=$sym&f=sd2t2oc&h&e=csv").firstOrNull()
            val o = r?.let { num(it, "open") }
            val c = r?.let { num(it, "close") }
            if (o != null && c != null) return quote(c, o, o)
        }
        return null
    }

    private fun plain(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking. Returns null without a key or when the symbol isn't found. */
    fun quote(ticker: String, store: Store): LiveQuote? {
        val k = key(store)
        if (k.isBlank()) return null
        val conn = URL("https://finnhub.io/api/v1/quote?symbol=" + URLEncoder.encode(ticker.uppercase(), "UTF-8") +
            "&token=" + URLEncoder.encode(k, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 15_000
        try {
            if (conn.responseCode == 401) throw IOException("Finnhub key not accepted")
            if (conn.responseCode !in 200..299) return null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val c = o.optDouble("c", 0.0)
            if (c <= 0) return null
            val q = LiveQuote(c, o.optDouble("d", 0.0), o.optDouble("dp", 0.0), o.optLong("t", 0L) * 1000,
                o.optDouble("o", 0.0), o.optDouble("pc", 0.0))
            LiveTicks.add(ticker, q, q.open, store)
            return q
        } finally {
            conn.disconnect()
        }
    }
}
