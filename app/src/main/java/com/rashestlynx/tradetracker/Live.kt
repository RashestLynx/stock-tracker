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

    /** The user's own key, built in so live prices work out of the box. A key saved in Settings replaces it. */
    private const val DEFAULT_KEY = "dbmm4e1r01qn1o61utegdbmm4e1r01qn1o61utf0"

    fun key(store: Store): String = (store.prefs.getString("finnhub_key", "") ?: "").ifBlank { DEFAULT_KEY }
    fun setKey(store: Store, k: String) = store.prefs.edit().putString("finnhub_key", k.trim()).apply()

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
