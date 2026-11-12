package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** A headline about one stock, from Massive's or Finnhub's news feed (both use the keys in Settings). */
data class Headline(val title: String, val source: String, val time: Long, val url: String, val sentiment: String, val why: String)

object StockNews {
    private val memo = ConcurrentHashMap<String, Pair<Long, List<Headline>>>()

    /** Newest first, duplicates across the two feeds removed. Cached 15 minutes. */
    fun get(ticker: String, massiveKey: String, finnhubKey: String): List<Headline> {
        val t = ticker.uppercase()
        memo[t]?.let { if (System.currentTimeMillis() - it.first < 15 * 60_000L) return it.second }
        val all = mutableListOf<Headline>()
        if (massiveKey.isNotBlank()) runCatching {
            val j = JSONObject(http("https://api.massive.com/v2/reference/news?ticker=${enc(t)}&limit=20&order=desc&sort=published_utc&apiKey=${enc(massiveKey)}"))
            val res = j.optJSONArray("results") ?: JSONArray()
            for (i in 0 until res.length()) {
                val r = res.getJSONObject(i)
                val ins = r.optJSONArray("insights")
                var sent = ""; var why = ""
                if (ins != null) for (k in 0 until ins.length()) {
                    val x = ins.getJSONObject(k)
                    if (x.optString("ticker").equals(t, true)) { sent = x.optString("sentiment"); why = x.optString("sentiment_reasoning") }
                }
                val time = runCatching { Instant.parse(r.optString("published_utc")).toEpochMilli() }.getOrDefault(0L)
                all += Headline(r.optString("title"), r.optJSONObject("publisher")?.optString("name") ?: "", time,
                    r.optString("article_url"), sent, why)
            }
        }
        if (finnhubKey.isNotBlank()) runCatching {
            val to = LocalDate.now(); val from = to.minusDays(10)
            val arr = JSONArray(http("https://finnhub.io/api/v1/company-news?symbol=${enc(t)}&from=$from&to=$to&token=${enc(finnhubKey)}"))
            for (i in 0 until minOf(arr.length(), 30)) {
                val r = arr.getJSONObject(i)
                all += Headline(r.optString("headline"), r.optString("source"), r.optLong("datetime") * 1000, r.optString("url"), "", "")
            }
        }
        val seen = HashSet<String>()
        val out = all.filter { it.title.isNotBlank() && it.url.startsWith("http") }
            .sortedByDescending { it.time }
            .filter { seen.add(it.title.lowercase().filter { c -> c.isLetterOrDigit() }.take(60)) }
            .take(10)
        memo[t] = System.currentTimeMillis() to out
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "StockTracker")
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
