package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** One possible result of an event, with the market's odds (0-1) and how far they moved in a day. */
data class PredOutcome(val label: String, val prob: Double, val change: Double?)

data class PredEvent(
    val title: String,
    val category: String,        // "Sports", "Politics", "Economics" or "Other"
    val url: String,
    val outcomes: List<PredOutcome>, // most likely first, at most two
    val volume24: Double,        // contracts traded in the last 24 hours
    val move: Double,            // biggest one-day change in odds among its outcomes, signed
)

/**
 * Live odds from Kalshi, a federally regulated prediction market open to Illinois residents.
 *
 * A contract pays $1 if something happens, so its price is the crowd's estimate of the chance:
 * 62 cents means about 62%. Kalshi's market data is public and needs no key or account.
 * (Other apps available in Illinois don't publish open data, so they aren't included.)
 */
object Predictions {
    private val BASES = listOf(
        "https://external-api.kalshi.com/trade-api/v2",
        "https://api.elections.kalshi.com/trade-api/v2",
    )
    private const val CACHE_MS = 2 * 60 * 1000L
    private const val MAX_PAGES = 6
    private var cache: List<PredEvent> = emptyList()
    private var cachedAt = 0L

    /** Blocking: call from a background thread. */
    fun load(force: Boolean): List<PredEvent> {
        if (!force && cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_MS) return cache
        var lastError: Exception? = null
        for (base in BASES) {
            try {
                val out = ArrayList<PredEvent>()
                var cursor = ""
                for (page in 0 until MAX_PAGES) {
                    val json = JSONObject(get("$base/events?status=open&with_nested_markets=true&limit=200" +
                        if (cursor.isEmpty()) "" else "&cursor=$cursor"))
                    out.addAll(parse(json))
                    cursor = json.optString("cursor")
                    if (cursor.isEmpty()) break
                }
                if (out.isEmpty()) throw IOException("No open markets right now")
                cache = out
                cachedAt = System.currentTimeMillis()
                return out
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(lastError?.message ?: "Couldn't load prediction markets")
    }

    /** Turns one page of Kalshi events into rows, skipping dead or already-decided markets. */
    fun parse(page: JSONObject): List<PredEvent> {
        val events = page.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<PredEvent>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val markets = e.optJSONArray("markets") ?: continue
            val outcomes = ArrayList<PredOutcome>()
            var volume = 0.0
            for (k in 0 until markets.length()) {
                val m = markets.optJSONObject(k) ?: continue
                val type = m.optString("market_type")
                if (type.isNotEmpty() && type != "binary") continue
                val status = m.optString("status")
                if (status.isNotEmpty() && status != "active" && status != "open") continue
                val bid = price(m, "yes_bid")
                val ask = price(m, "yes_ask")
                val last = price(m, "last_price")
                // The middle of the buy and sell offers when they're close together; otherwise the last trade.
                val prob = when {
                    bid > 0 && ask > 0 && ask <= 1 && ask - bid <= 0.10 -> (bid + ask) / 2
                    last > 0 && last < 1 -> last
                    else -> continue
                }
                val prev = price(m, "previous_price")
                volume += count(m, "volume_24h")
                val label = m.optString("yes_sub_title").ifBlank { m.optString("subtitle") }.ifBlank { "Yes" }
                outcomes.add(PredOutcome(label, prob, if (prev > 0 && prev < 1) prob - prev else null))
            }
            if (outcomes.isEmpty() || volume < 100) continue
            outcomes.sortByDescending { it.prob }
            val top = outcomes.first()
            // Nothing left to learn from a market that's all but settled.
            if (top.prob >= 0.98 || (outcomes.size == 1 && top.prob <= 0.02)) continue
            val move = outcomes.mapNotNull { it.change }.maxByOrNull { abs(it) } ?: 0.0
            val series = e.optString("series_ticker").ifBlank { e.optString("event_ticker").substringBefore('-') }
            val title = e.optString("title")
            if (title.isBlank()) continue
            val sub = e.optString("sub_title")
            out.add(PredEvent(
                title = if (sub.isBlank() || outcomes.size > 1) title else "$title ($sub)",
                category = category(e.optString("category")),
                url = "https://kalshi.com/markets/" + series.lowercase(Locale.US),
                outcomes = outcomes.take(2),
                volume24 = volume,
                move = move,
            ))
        }
        return out
    }

    private fun category(raw: String): String {
        val c = raw.lowercase(Locale.US)
        return when {
            "sport" in c -> "Sports"
            "politic" in c || "election" in c -> "Politics"
            "econ" in c || "financ" in c -> "Economics"
            else -> "Other"
        }
    }

    /** Prices come as dollar strings ("0.5600"); older responses used whole cents. Returns 0-1, or 0 if missing. */
    private fun price(m: JSONObject, name: String): Double {
        m.optString("${name}_dollars").toDoubleOrNull()?.let { return it }
        val cents = m.optDouble(name, Double.NaN)
        return if (cents.isNaN()) 0.0 else cents / 100
    }

    private fun count(m: JSONObject, name: String): Double =
        m.optString("${name}_fp").toDoubleOrNull() ?: m.optDouble(name, 0.0).let { if (it.isNaN()) 0.0 else it }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0 (personal app)")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 20_000
        conn.readTimeout = 40_000
        try {
            val code = conn.responseCode
            if (code == 429) throw IOException("Kalshi is busy. Try again in a minute.")
            if (code !in 200..299) throw IOException("Kalshi returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
