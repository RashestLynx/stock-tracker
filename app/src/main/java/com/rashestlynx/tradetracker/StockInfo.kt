package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Everything shown in a stock's summary sheet. Blank fields are simply hidden. */
data class StockSummary(
    val name: String,
    val industry: String,
    val exchange: String,
    val location: String,
    val about: String,
    val aboutUrl: String,
    val price: Double?,
    val priceDate: String,
)

/**
 * Builds a short company profile from free public sources:
 *  - SEC company profile (name, industry, exchange, headquarters)
 *  - Wikipedia summary (what the company does)
 *  - Stooq delayed quote (latest price)
 * Each part is optional; if one source fails the rest still show.
 */
object StockInfo {
    private val cache = HashMap<String, StockSummary>()

    /** Blocking: call from a background thread. */
    fun load(ticker: String, company: String, store: Store): StockSummary {
        synchronized(cache) { cache[ticker]?.let { return it } }
        val ua = store.userAgent

        var name = company
        var industry = ""
        var exchange = ""
        var location = ""
        try {
            val cik = cikFor(ticker, store)
            if (cik != null) {
                val j = JSONObject(Edgar.get(String.format(Locale.US,
                    "https://data.sec.gov/submissions/CIK%010d.json", cik), ua))
                name = j.optString("name").ifBlank { company }
                industry = j.optString("sicDescription").let { if (it == "null") "" else it }
                exchange = j.optJSONArray("exchanges")?.optString(0)?.takeIf { it != "null" } ?: ""
                val b = j.optJSONObject("addresses")?.optJSONObject("business")
                location = listOf(b?.optString("city"), b?.optString("stateOrCountryDescription"))
                    .filter { !it.isNullOrBlank() && it != "null" }
                    .joinToString(", ") { titleCase(it!!) }
            }
        } catch (_: Exception) {
        }

        val (about, aboutUrl) = try { wikipedia(name) } catch (_: Exception) { "" to "" }
        val (price, date) = try { quote(ticker) } catch (_: Exception) { null to "" }

        val s = StockSummary(titleCase(name), titleCase(industry), exchange, location,
            about, aboutUrl, price, date)
        synchronized(cache) { cache[ticker] = s }
        return s
    }

    private fun cikFor(ticker: String, store: Store): Long? {
        val map = store.cachedCikMap() ?: Edgar.tickerCiks(store.userAgent).also { store.saveCikMap(it) }
        return map[ticker.uppercase(Locale.US)]
    }

    // ---------------- Wikipedia ----------------
    private val COMPANY_WORDS = listOf(
        "company", "corporation", "conglomerate", "manufacturer", "bank", "firm", "retailer",
        "provider", "developer", "producer", "operator", "trust", "holding", "airline", "insurer",
        "maker", "business", "brand", "publicly traded", "multinational", "chain", "utility",
    )

    private fun wikipedia(companyName: String): Pair<String, String> {
        val clean = Edgar.normalizeName(companyName)
        if (clean.isEmpty()) return "" to ""
        val firstWord = clean.split(" ").first().lowercase(Locale.US)
        val search = JSONObject(get(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=3&srsearch=" +
                enc("$clean company")))
        val results = search.optJSONObject("query")?.optJSONArray("search") ?: return "" to ""
        for (i in 0 until results.length()) {
            val title = results.getJSONObject(i).optString("title")
            val page = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/" +
                enc(title.replace(' ', '_'))))
            if (page.optString("type") == "disambiguation") continue
            val extract = page.optString("extract")
            val text = (page.optString("description") + " " + extract).lowercase(Locale.US)
            // Only accept pages that are clearly about this company, not a person or place.
            if (firstWord in text && COMPANY_WORDS.any { it in text }) {
                val url = page.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: ""
                return firstSentences(extract, 3) to url
            }
        }
        return "" to ""
    }

    private fun firstSentences(text: String, n: Int): String {
        val parts = text.split(Regex("(?<=[.!?])\\s+"))
        return parts.take(n).joinToString(" ")
    }

    // ---------------- Price ----------------
    /** Latest delayed close from Stooq, e.g. "AAPL.US,2026-11-05,22:00:00,230.10". */
    private fun quote(ticker: String): Pair<Double?, String> {
        val sym = ticker.lowercase(Locale.US).replace('.', '-') + ".us"
        val csv = get("https://stooq.com/q/l/?s=$sym&f=sd2t2c&h&e=csv")
        val row = csv.lines().getOrNull(1)?.split(",") ?: return null to ""
        val price = row.getOrNull(3)?.toDoubleOrNull()
        return price to (row.getOrNull(1)?.takeIf { it != "N/D" } ?: "")
    }

    // ---------------- helpers ----------------
    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0 (personal app)")
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun titleCase(s: String): String =
        if (s.any { it.isLowerCase() }) s
        else s.lowercase(Locale.US).split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { it.titlecase(Locale.US) }
        }
}
