package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One source's view: buy/hold/sell counts and 12-month price targets. */
data class AnalystSource(val name: String, val buy: Int, val hold: Int, val sell: Int,
                         val low: Double?, val avg: Double?, val high: Double?, val targetAnalysts: Int) {
    val total: Int get() = buy + hold + sell
    fun pct(n: Int): Int = if (total == 0) 0 else Math.round(n * 100.0 / total).toInt()
}

/** Wall Street analysts' consensus for one stock, combined from every source that answers. */
data class AnalystView(val ratings: AnalystSource?, val targets: AnalystSource?, val all: List<AnalystSource>) {
    val buy get() = ratings?.buy ?: 0
    val hold get() = ratings?.hold ?: 0
    val sell get() = ratings?.sell ?: 0
    val total get() = ratings?.total ?: 0
    fun pct(n: Int) = ratings?.pct(n) ?: 0
    val low get() = targets?.low
    val avg get() = targets?.avg
    val high get() = targets?.high
}

object Analysts {
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    private val memo = ConcurrentHashMap<String, Pair<Long, AnalystView?>>()
    @Volatile private var yahoo: Pair<String, String>? = null   // cookie, crumb

    /** Asks Nasdaq, Finnhub (with your key) and Yahoo Finance; uses whichever covers the most analysts.
     *  null when none of them cover the stock. Cached for 6 hours. */
    fun get(ticker: String, finnhubKey: String = ""): AnalystView? {
        val t = ticker.uppercase().trim()
        memo[t]?.let { if (System.currentTimeMillis() - it.first < 6 * 3600_000L) return it.second }
        val all = listOfNotNull(
            runCatching { nasdaq(t) }.getOrNull(),
            if (finnhubKey.isNotBlank()) runCatching { finnhub(t, finnhubKey) }.getOrNull() else null,
            runCatching { yahoo(t) }.getOrNull(),
        ).filter { it.total > 0 || it.avg != null }
        val v = if (all.isEmpty()) null else AnalystView(
            all.filter { it.total > 0 }.maxByOrNull { it.total },
            all.filter { it.avg != null && it.low != null && it.high != null }.maxByOrNull { it.targetAnalysts },
            all.sortedByDescending { it.total })
        memo[t] = System.currentTimeMillis() to v
        return v
    }

    fun parseNasdaq(body: String): AnalystSource? {
        val c = JSONObject(body).optJSONObject("data")?.optJSONObject("consensusOverview") ?: return null
        fun num(k: String) = if (c.isNull(k)) null else c.optDouble(k).takeIf { !it.isNaN() && it > 0 }
        val buy = c.optInt("buy"); val hold = c.optInt("hold"); val sell = c.optInt("sell")
        return AnalystSource("Nasdaq", buy, hold, sell, num("lowPriceTarget"), num("priceTarget"), num("highPriceTarget"), buy + hold + sell)
    }

    private fun nasdaq(t: String): AnalystSource? = parseNasdaq(http("https://api.nasdaq.com/api/analyst/${t.replace(".", "-")}/targetprice",
        mapOf("User-Agent" to BROWSER, "Accept" to "application/json, text/plain, */*", "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to "https://www.nasdaq.com", "Referer" to "https://www.nasdaq.com/")).second)

    /** Finnhub's latest monthly count (free with a key; ratings only, its price targets are a paid feature). */
    private fun finnhub(t: String, key: String): AnalystSource? {
        val body = http("https://finnhub.io/api/v1/stock/recommendation?symbol=${URLEncoder.encode(t, "UTF-8")}&token=$key").second
        val arr = org.json.JSONArray(body)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)   // newest month first
        return AnalystSource("Finnhub", o.optInt("strongBuy") + o.optInt("buy"), o.optInt("hold"),
            o.optInt("sell") + o.optInt("strongSell"), null, null, null, 0)
    }

    /** Yahoo Finance: ratings and price targets. Yahoo needs a session cookie and a "crumb" first. */
    private fun yahoo(t: String): AnalystSource? {
        val (cookie, crumb) = yahoo ?: run {
            val first = http("https://fc.yahoo.com", mapOf("User-Agent" to BROWSER), okCodes = 200..599)
            val ck = first.third.filter { it.startsWith("A3=") || it.startsWith("A1=") }.joinToString("; ") { it.substringBefore(";") }
            if (ck.isEmpty()) return null
            val cr = http("https://query2.finance.yahoo.com/v1/test/getcrumb", mapOf("User-Agent" to BROWSER, "Cookie" to ck)).second.trim()
            if (cr.isEmpty() || cr.contains("<")) return null
            (ck to cr).also { yahoo = it }
        }
        val sym = t.replace(".", "-")
        val (code, body) = http("https://query2.finance.yahoo.com/v10/finance/quoteSummary/$sym?modules=financialData,recommendationTrend&crumb=" +
            URLEncoder.encode(crumb, "UTF-8"), mapOf("User-Agent" to BROWSER, "Cookie" to cookie), okCodes = 200..499).let { it.first to it.second }
        if (code == 401 || code == 403) { yahoo = null; return null }
        return parseYahoo(body)
    }

    fun parseYahoo(body: String): AnalystSource? {
        val r = JSONObject(body).optJSONObject("quoteSummary")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val fd = r.optJSONObject("financialData")
        fun raw(k: String) = fd?.optJSONObject(k)?.optDouble("raw")?.takeIf { !it.isNaN() && it > 0 }
        val trend = r.optJSONObject("recommendationTrend")?.optJSONArray("trend")
        var buy = 0; var hold = 0; var sell = 0
        if (trend != null) for (i in 0 until trend.length()) {
            val o = trend.getJSONObject(i)
            if (o.optString("period") == "0m") {
                buy = o.optInt("strongBuy") + o.optInt("buy"); hold = o.optInt("hold"); sell = o.optInt("sell") + o.optInt("strongSell")
            }
        }
        val n = fd?.optJSONObject("numberOfAnalystOpinions")?.optInt("raw") ?: 0
        return AnalystSource("Yahoo Finance", buy, hold, sell, raw("targetLowPrice"), raw("targetMeanPrice"), raw("targetHighPrice"), n)
    }

    /** (status, body, Set-Cookie values) */
    private fun http(url: String, headers: Map<String, String> = mapOf("User-Agent" to "StockTracker"),
                    okCodes: IntRange = 200..299): Triple<Int, String, List<String>> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 15_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            if (code !in okCodes) throw Exception("HTTP $code")
            val stream = if (code < 400) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Triple(code, body, c.headerFields["Set-Cookie"] ?: c.headerFields["set-cookie"] ?: emptyList())
        } finally { c.disconnect() }
    }
}
