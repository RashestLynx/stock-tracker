package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** One possible result of an event, with the market's odds (0-1) and how far they moved in a day. */
data class PredOutcome(val label: String, val prob: Double, val change: Double?, val ref: String = "")
// ref identifies the exact market, so a logged pick can be followed until it settles:
// "k:<Kalshi market ticker>" or "p:<Polymarket market id>:<outcome index>". For Kalshi, "No" is the other side of
// the same market; for Polymarket, "No" is the other outcome (index 1 - i).

data class PredEvent(
    val source: String,          // "Kalshi" or "Polymarket"
    val title: String,
    val category: String,        // "Sports", "Politics", "Economics" or "Other"
    val url: String,
    val outcomes: List<PredOutcome>, // most likely first, at most two
    val volume24: Double,        // traded in the last 24 hours (Kalshi: contracts, Polymarket: dollars)
    val move: Double,            // biggest one-day change in odds among its outcomes, signed
    val count: Int = outcomes.size, // how many outcomes the event has in total (only the top two are kept)
    val all: List<PredOutcome> = outcomes, // every outcome, including a tie/draw, most likely first
    val ticker: String = "",     // Kalshi event ticker (holds the game date), or Polymarket slug
)

/**
 * Live odds from Kalshi and Polymarket, both open to Illinois residents.
 *
 * A contract pays $1 if something happens, so its price is the crowd's estimate of the chance:
 * 62 cents means about 62%. Both publish their market data openly, with no key or account.
 * FanDuel Predicts and DraftKings Predictions don't publish their odds, so they aren't included.
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

    /** Blocking: call from a background thread. Shows whichever sources load; fails only if both fail. */
    fun load(force: Boolean): List<PredEvent> {
        if (!force && cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_MS) return cache
        val kalshi = runCatching { loadKalshi() }
        val poly = runCatching { loadPolymarket() }
        val out = kalshi.getOrDefault(emptyList()) + poly.getOrDefault(emptyList())
        if (out.isEmpty()) {
            throw IOException(kalshi.exceptionOrNull()?.message ?: poly.exceptionOrNull()?.message
                ?: "No open markets right now")
        }
        cache = out
        cachedAt = System.currentTimeMillis()
        return out
    }

    private fun loadPolymarket(): List<PredEvent> {
        val out = ArrayList<PredEvent>()
        for (page in 0 until 3) {
            val arr = JSONArray(get("https://gamma-api.polymarket.com/events?closed=false&active=true" +
                "&order=volume24hr&ascending=false&limit=100&offset=${page * 100}"))
            out.addAll(parsePolymarket(arr))
            if (arr.length() < 100) break
        }
        return out
    }

    /** Polymarket events: one market per outcome ("Will X win?"), or one market with named outcomes. */
    fun parsePolymarket(events: JSONArray, minVolume: Double = 100.0): List<PredEvent> {
        val out = ArrayList<PredEvent>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val markets = e.optJSONArray("markets") ?: continue
            val outcomes = ArrayList<PredOutcome>()
            for (k in 0 until markets.length()) {
                val m = markets.optJSONObject(k) ?: continue
                if (m.optBoolean("closed", false) || !m.optBoolean("active", true)) continue
                val names = strings(m.opt("outcomes"))
                val prices = strings(m.opt("outcomePrices")).map { it.toDoubleOrNull() ?: Double.NaN }
                if (names.size != prices.size || names.isEmpty()) continue
                val change = m.optDouble("oneDayPriceChange", Double.NaN).takeIf { !it.isNaN() }
                val yes = names.indexOfFirst { it.equals("Yes", ignoreCase = true) }
                val mkind = m.optString("sportsMarketType").lowercase(Locale.US)
                if (yes >= 0 && mkind.isNotEmpty() && mkind != "moneyline") continue // spreads and totals
                if (yes >= 0) {
                    val p = prices[yes]
                    if (p.isNaN() || p <= 0 || p >= 1) continue
                    val label = m.optString("groupItemTitle").ifBlank {
                        if (markets.length() == 1) "Yes" else m.optString("question").ifBlank { "Yes" }
                    }
                    outcomes.add(PredOutcome(label, p, change, "p:" + m.optString("id") + ":" + yes))
                } else {
                    // Game events also hold point-spread and over/under markets; keep only who wins.
                    val kind = m.optString("sportsMarketType").lowercase(Locale.US)
                    val q = m.optString("question").lowercase(Locale.US)
                    if (kind.isNotEmpty() && kind != "moneyline") continue
                    if (kind.isEmpty() && (Regex("\\d").containsMatchIn(q.replace("49ers", "")) ||
                            "o/u" in q || "spread" in q || "total" in q)) continue
                    // e.g. a game with outcomes ["Bears", "Lions"]; the daily change is for the first one.
                    names.forEachIndexed { j, n ->
                        val p = prices[j]
                        if (!p.isNaN() && p > 0 && p < 1) {
                            outcomes.add(PredOutcome(n, p, change?.let { if (j == 0) it else -it }, "p:" + m.optString("id") + ":" + j))
                        }
                    }
                }
            }
            val volume = e.optDouble("volume24hr", 0.0).let { if (it.isNaN()) 0.0 else it }
            if (outcomes.isEmpty() || volume < minVolume) continue
            outcomes.sortByDescending { it.prob }
            val top = outcomes.first()
            if (top.prob >= 0.98 || (outcomes.size == 1 && top.prob <= 0.02)) continue
            val title = e.optString("title")
            if (title.isBlank()) continue
            val tags = e.optJSONArray("tags")
            val labels = StringBuilder(e.optString("category"))
            if (tags != null) for (t in 0 until tags.length()) {
                labels.append(' ').append(tags.optJSONObject(t)?.optString("label") ?: tags.optString(t))
            }
            out.add(PredEvent(
                source = "Polymarket",
                title = title,
                category = category(labels.toString()),
                url = "https://polymarket.com/event/" + e.optString("slug"),
                outcomes = outcomes.take(2),
                volume24 = volume,
                move = outcomes.mapNotNull { it.change }.maxByOrNull { abs(it) } ?: 0.0,
                count = outcomes.size,
                all = outcomes.toList(),
                ticker = e.optString("slug"),
            ))
        }
        return out
    }

    /** Polymarket sends some lists as JSON text ("[\"Yes\",\"No\"]") and some as real arrays. */
    private fun strings(v: Any?): List<String> {
        val arr = when (v) {
            is JSONArray -> v
            is String -> try { JSONArray(v) } catch (_: Exception) { return emptyList() }
            else -> return emptyList()
        }
        return List(arr.length()) { arr.optString(it) }
    }

    private fun loadKalshi(): List<PredEvent> {
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
                return out
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(lastError?.message ?: "Couldn't load Kalshi")
    }

    /** Turns one page of Kalshi events into rows, skipping dead or already-decided markets. */
    fun parse(page: JSONObject, minVolume: Double = 100.0): List<PredEvent> {
        val events = page.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<PredEvent>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val markets = e.optJSONArray("markets") ?: continue
            val outcomes = ArrayList<PredOutcome>()
            val ties = ArrayList<PredOutcome>()
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
                if (label.equals("Tie", true) || label.equals("Draw", true)) {
                    // Kept apart so two-team matching still works; soccer uses it via PredEvent.all.
                    ties.add(PredOutcome(label, prob, null, "k:" + m.optString("ticker")))
                    continue
                }
                outcomes.add(PredOutcome(label, prob, if (prev > 0 && prev < 1) prob - prev else null, "k:" + m.optString("ticker")))
            }
            if (outcomes.isEmpty() || volume < minVolume) continue
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
                source = "Kalshi",
                title = if (sub.isBlank() || outcomes.size > 1) title else "$title ($sub)",
                category = category(e.optString("category")),
                url = "https://kalshi.com/markets/" + series.lowercase(Locale.US),
                outcomes = outcomes.take(2),
                volume24 = volume,
                move = move,
                count = outcomes.size,
                all = (outcomes + ties).sortedByDescending { it.prob },
                ticker = e.optString("event_ticker"),
            ))
        }
        return out
    }

    private fun category(raw: String): String {
        val c = raw.lowercase(Locale.US)
        return when {
            "sport" in c -> "Sports"
            "politic" in c || "election" in c -> "Politics"
            "econ" in c || "financ" in c || "fed " in "$c " -> "Economics"
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

    /** Current chance (0-1) for one market from its parts, as used in [parse]. Null when there's no usable price. */
    fun kalshiProb(m: JSONObject): Double? {
        val bid = price(m, "yes_bid")
        val ask = price(m, "yes_ask")
        val last = price(m, "last_price")
        return when {
            bid > 0 && ask > 0 && ask <= 1 && ask - bid <= 0.10 -> (bid + ask) / 2
            last > 0 && last < 1 -> last
            else -> null
        }
    }

    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0 (personal app)")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 20_000
        conn.readTimeout = 40_000
        try {
            val code = conn.responseCode
            val site = if ("polymarket" in url) "Polymarket" else "Kalshi"
            if (code == 429) throw IOException("$site is busy. Try again in a minute.")
            if (code !in 200..299) throw IOException("$site returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
