package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class WsbStock(
    val rank: Int,
    val ticker: String,
    val name: String,
    val mentions: Int,
    val mentions24hAgo: Int,
    val upvotes: Int,
    val rank24hAgo: Int,
) {
    /** % change in mentions vs the previous 24 hours, or null if it's new. */
    val mentionChange: Double? get() =
        if (mentions24hAgo > 0) (mentions - mentions24hAgo).toDouble() / mentions24hAgo * 100 else null
    /** Positive = climbed the rankings. */
    val rankChange: Int get() = if (rank24hAgo > 0) rank24hAgo - rank else 0
}

/**
 * Most-discussed stocks on Reddit (r/wallstreetbets and other stock subreddits), from the free
 * ApeWisdom API. Counts are mentions over the last 24 hours, updated about hourly.
 */
object Wsb {
    const val SOURCE_URL = "https://apewisdom.io"
    private val cache = HashMap<String, Pair<Long, List<WsbStock>>>()

    /** filter: "wallstreetbets" or "all-stocks". Blocking: call from a background thread. */
    fun trending(filter: String, force: Boolean): List<WsbStock> {
        if (!force) synchronized(cache) {
            cache[filter]?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000) return it.second }
        }
        val conn = URL("https://apewisdom.io/api/v1.0/filter/$filter/page/1").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0 (personal app)")
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("Reddit data returned HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val list = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                WsbStock(
                    rank = o.optInt("rank", i + 1),
                    ticker = o.optString("ticker").uppercase(),
                    name = Html2Text(o.optString("name")),
                    mentions = o.optInt("mentions", 0),
                    mentions24hAgo = o.optInt("mentions_24h_ago", 0),
                    upvotes = o.optInt("upvotes", 0),
                    rank24hAgo = o.optInt("rank_24h_ago", 0),
                )
            }.filter { it.ticker.isNotEmpty() }
            synchronized(cache) { cache[filter] = System.currentTimeMillis() to list }
            return list
        } finally {
            conn.disconnect()
        }
    }

    /** ApeWisdom names sometimes contain HTML entities like &amp;. */
    @Suppress("FunctionName")
    private fun Html2Text(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
