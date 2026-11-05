package com.rashestlynx.tradetracker

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

data class NewsItem(
    val title: String,
    val source: String,
    val url: String,
    val time: Long,
    val ticker: String = "",
    val detail: String = "",
    val isFiling: Boolean = false,
) {
    val key: String get() = "news:" + url
}

/**
 * Near-live market news from free sources:
 *  - Google News RSS: headlines usually appear within minutes of being published
 *  - SEC EDGAR 8-K feed: official company announcements (earnings, CEO changes, deals)
 *    the moment they are filed
 */
object News {
    private val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    fun market(): List<NewsItem> = google("stock market OR stocks OR Wall Street when:1d")

    fun forTickers(tickers: List<String>): List<NewsItem> {
        if (tickers.isEmpty()) return emptyList()
        val q = tickers.take(8).joinToString(" OR ") { "\"$it\"" } + " stock when:2d"
        return google(q).map { n -> n.copy(ticker = tickers.firstOrNull { t -> Regex("\\b$t\\b").containsMatchIn(n.title) } ?: "") }
    }

    /** Latest 8-K filings, tagged with tickers where known. */
    fun filings(store: Store): List<NewsItem> {
        val ua = store.userAgent
        val ciks = store.cachedCikMap() ?: Edgar.tickerCiks(ua).also { store.saveCikMap(it) }
        val byCik = HashMap<Long, String>()
        ciks.forEach { (t, c) -> byCik.putIfAbsent(c, t) }
        return Edgar.feed("8-K", ua).map { f ->
            val cik = Regex("\\((\\d+)\\)").find(f.title)?.groupValues?.get(1)?.toLongOrNull()
            val company = f.title.removePrefix("8-K - ").replace(Regex("\\s*\\(\\d+\\)\\s*\\(Filer\\)\\s*$"), "")
            val items = Regex("Item \\d+\\.\\d+: ([^<\\n]+)").findAll(clean(f.summary).replace(" Item ", "\nItem "))
                .map { it.groupValues[1].trim() }.filter { !it.startsWith("Financial Statements") }.toList()
            NewsItem(
                title = titleCase(company),
                source = "SEC filing",
                url = f.link,
                time = parse(f.updated, iso),
                ticker = cik?.let { byCik[it] } ?: "",
                detail = items.joinToString(" · ").ifEmpty { "Company announcement (8-K)" },
                isFiling = true,
            )
        }
    }

    fun search(query: String): List<NewsItem> = google("$query when:14d")

    private fun google(query: String): List<NewsItem> {
        val url = "https://news.google.com/rss/search?hl=en-US&gl=US&ceid=US:en&q=" + URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) StockTracker/2.0")
        conn.connectTimeout = 20_000; conn.readTimeout = 20_000
        val xml = try {
            if (conn.responseCode !in 200..299) throw IOException("News returned HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val out = mutableListOf<NewsItem>()
        var inItem = false
        var title = ""; var link = ""; var date = ""; var source = ""
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "item" -> { inItem = true; title = ""; link = ""; date = ""; source = "" }
                "title" -> if (inItem) title = clean(parser.nextText())
                "link" -> if (inItem) link = parser.nextText().trim()
                "pubDate" -> if (inItem) date = parser.nextText().trim()
                "source" -> if (inItem) source = clean(parser.nextText())
            } else if (e == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
                // Google appends " - Publisher" to titles.
                val t = if (source.isNotEmpty()) title.removeSuffix(" - $source") else title
                if (t.isNotEmpty() && link.isNotEmpty()) out.add(NewsItem(t, source, link, parse(date, rfc822)))
            }
            e = parser.next()
        }
        return out.sortedByDescending { it.time }
    }

    private fun parse(s: String, f: SimpleDateFormat): Long =
        try { synchronized(f) { f.parse(s)?.time } ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("[ \\t]+"), " ").trim()

    private fun titleCase(s: String) = if (s.any { it.isLowerCase() }) s
    else s.lowercase(Locale.US).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
}
