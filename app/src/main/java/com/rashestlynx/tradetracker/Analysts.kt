package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Wall Street analysts' consensus for one stock: buy/hold/sell counts and 12-month price targets (from Nasdaq). */
data class AnalystView(val buy: Int, val hold: Int, val sell: Int, val low: Double?, val avg: Double?, val high: Double?) {
    val total: Int get() = buy + hold + sell
    fun pct(n: Int): Int = if (total == 0) 0 else Math.round(n * 100.0 / total).toInt()
}

object Analysts {
    private val memo = ConcurrentHashMap<String, Pair<Long, AnalystView?>>()

    /** null when no analysts cover the stock. Cached for 6 hours. */
    fun get(ticker: String): AnalystView? {
        val t = ticker.uppercase().trim()
        memo[t]?.let { if (System.currentTimeMillis() - it.first < 6 * 3600_000L) return it.second }
        val body = fetch("https://api.nasdaq.com/api/analyst/${t.replace(".", "-")}/targetprice")
        val v = parse(body)
        memo[t] = System.currentTimeMillis() to v
        return v
    }

    fun parse(body: String): AnalystView? {
        val c = JSONObject(body).optJSONObject("data")?.optJSONObject("consensusOverview") ?: return null
        fun num(k: String) = if (c.isNull(k)) null else c.optDouble(k).takeIf { !it.isNaN() && it > 0 }
        val v = AnalystView(c.optInt("buy"), c.optInt("hold"), c.optInt("sell"), num("lowPriceTarget"), num("priceTarget"), num("highPriceTarget"))
        return if (v.total == 0 && v.avg == null) null else v
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        // Nasdaq's API only answers requests that look like its own website.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Origin", "https://www.nasdaq.com")
        c.setRequestProperty("Referer", "https://www.nasdaq.com/")
        try {
            if (c.responseCode !in 200..299) throw Exception("Nasdaq answered HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
