package com.rashestlynx.tradetracker

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A public figure the user follows, such as a finance YouTuber. Private investors don't have to
 * report their trades, so this tracks what they say publicly: their YouTube videos and news.
 */
data class Followed(
    val name: String,
    val youtube: String,            // YouTube @handle, without the @
    val newsQuery: String,          // what to search news for
    val businesses: List<String>,   // public info about their companies
    val links: List<Pair<String, String>>,
)

data class Video(val title: String, val url: String, val time: Long, val id: String, val description: String = "")

/** A stock someone talked about publicly. stance = "buying", "selling" or "mentioned". */
data class Mention(val ticker: String, val company: String, val stance: String, val count: Int, val latest: Long, val url: String)

object Following {
    val DEFAULTS = listOf(
        Followed(
            name = "Jaspreet Singh",
            youtube = "minoritymindset",
            newsQuery = "\"Jaspreet Singh\" (\"Minority Mindset\" OR \"Market Briefs\")",
            businesses = listOf(
                "Founder of Minority Mindset, a financial education media company and YouTube channel",
                "CEO of Briefs Media, publisher of the free daily newsletter Market Briefs",
            ),
            links = listOf("Minority Mindset" to "https://theminoritymindset.com", "Market Briefs" to "https://www.briefs.co"),
        ),
        Followed(
            name = "Mark Tilbury",
            youtube = "marktilbury",
            newsQuery = "\"Mark Tilbury\"",
            businesses = listOf(
                "Model World Ltd, a UK company selling remote-controlled toys",
                "Century UK Ltd",
                "Mark Tilbury Coaching Ltd, business and investing coaching",
            ),
            links = listOf(
                "Website" to "https://www.marktilbury.com",
                "UK company register" to "https://find-and-update.company-information.service.gov.uk/search?q=Mark+Tilbury",
            ),
        ),
    )

    fun load(store: Store): List<Followed> {
        val raw = store.prefs.getString("following", null) ?: return DEFAULTS
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                val b = o.optJSONArray("biz") ?: JSONArray()
                val l = o.optJSONArray("links") ?: JSONArray()
                Followed(
                    o.optString("name"), o.optString("yt"), o.optString("q"),
                    List(b.length()) { i -> b.getString(i) },
                    List(l.length()) { i -> l.getJSONArray(i).let { p -> p.getString(0) to p.getString(1) } },
                )
            }
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun save(store: Store, list: List<Followed>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().put("name", f.name).put("yt", f.youtube).put("q", f.newsQuery)
                .put("biz", JSONArray(f.businesses))
                .put("links", JSONArray(f.links.map { JSONArray(listOf(it.first, it.second)) })))
        }
        store.prefs.edit().putString("following", arr.toString()).apply()
    }

    /** Latest uploads from their YouTube channel (public RSS feed). Blocking. */
    fun videos(f: Followed, store: Store): List<Video> {
        if (f.youtube.isBlank()) return emptyList()
        val handle = f.youtube.trim().removePrefix("@")
        val key = "yt_channel_$handle"
        var channel = store.prefs.getString(key, null)
        if (channel == null) {
            channel = try {
                val page = get("https://www.youtube.com/@$handle")
                Regex("\"(?:channelId|externalId)\":\"(UC[\\w-]{22})\"").find(page)?.groupValues?.get(1)
                    ?: Regex("youtube\\.com/channel/(UC[\\w-]{22})").find(page)?.groupValues?.get(1)
            } catch (_: Exception) {
                null
            }
            if (channel != null) store.prefs.edit().putString(key, channel).apply()
        }
        val feed = if (channel != null) "https://www.youtube.com/feeds/videos.xml?channel_id=$channel"
        else "https://www.youtube.com/feeds/videos.xml?user=$handle"
        return parseFeed(get(feed))
    }

    private fun parseFeed(xml: String): List<Video> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val out = mutableListOf<Video>()
        var inEntry = false
        var title = ""; var link = ""; var date = ""; var id = ""; var desc = ""
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (p.name) {
                "entry" -> { inEntry = true; title = ""; link = ""; date = ""; id = ""; desc = "" }
                "media:description" -> if (inEntry) desc = p.nextText().trim()
                "title" -> if (inEntry) title = p.nextText().trim()
                "link" -> if (inEntry) link = p.getAttributeValue(null, "href") ?: link
                "published" -> if (inEntry) date = p.nextText().trim()
                "yt:videoId" -> if (inEntry) id = p.nextText().trim()
            } else if (e == XmlPullParser.END_TAG && p.name == "entry") {
                inEntry = false
                val t = try { iso.parse(date)?.time } catch (_: Exception) { null } ?: System.currentTimeMillis()
                if (title.isNotEmpty() && link.isNotEmpty()) out.add(Video(title, link, t, "yt:" + id.ifEmpty { link }, desc))
            }
            e = p.next()
        }
        return out
    }

    // ------------------------------------------------------------ stocks they talk about
    private var companies: List<Triple<String, String, String>>? = null // ticker, display name, normalized name
    private val STOP = setOf("ALL", "NOW", "ARE", "ONE", "CAN", "BIG", "FAST", "GOOD", "BEST", "REAL", "SAFE", "LOW",
        "HIGH", "FREE", "CASH", "GAIN", "LOVE", "PLAY", "RUN", "TRUE", "OPEN", "HOME", "LIFE", "WELL", "KEY", "CEO",
        "IPO", "ETF", "USA", "AI", "IT", "ON", "SO", "GO", "BE", "DO", "BY", "AT", "OR", "UP", "TV", "FBI", "IRS",
        "YOU", "NEW", "BUY", "MAN", "HAS", "OUT", "SEE", "WAY", "HOW", "WHY", "NOT", "AND", "THE", "FOR", "TOP")

    /** The ~600 largest U.S. companies (SEC list is ordered by size), cached for a week. */
    private fun bigCompanies(store: Store): List<Triple<String, String, String>> {
        companies?.let { return it }
        val raw = store.cachedText("company_tickers_raw.json", 7 * Store.DAY_MS)
            ?: Edgar.get("https://www.sec.gov/files/company_tickers.json", store.userAgent)
                .also { store.saveText("company_tickers_raw.json", it) }
        val o = JSONObject(raw)
        val rows = o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.getJSONObject(k) } }
            .sortedBy { it.first }.take(600).map { it.second }
        val seen = HashSet<String>()
        val list = rows.mapNotNull { r ->
            val t = r.optString("ticker").uppercase(Locale.US)
            val title = r.optString("title")
            if (t.isEmpty() || !seen.add(t)) null else Triple(t, title, Edgar.normalizeName(title))
        }.toList()
        companies = list
        return list
    }

    private val BUY = Regex("\\b(buying|bought|buy|adding|added|loading up|i own|my portfolio|investing in)\\b", RegexOption.IGNORE_CASE)
    private val SELL = Regex("\\b(selling|sold|sell|dumping|dumped|trimming|getting out)\\b", RegexOption.IGNORE_CASE)

    /**
     * Stocks named in their video titles/descriptions and news headlines. Public statements only:
     * private investors don't report actual trades.
     */
    fun mentions(videos: List<Video>, news: List<NewsItem>, store: Store): List<Mention> {
        val cos = try { bigCompanies(store) } catch (_: Exception) { return emptyList() }
        val byTicker = cos.associateBy { it.first }
        data class Src(val text: String, val title: String, val time: Long, val url: String)
        val sources = videos.map { Src(it.title + "\n" + it.description.take(1500), it.title, it.time, it.url) } +
            news.map { Src(it.title, it.title, it.time, it.url) }
        val agg = LinkedHashMap<String, MutableList<Pair<Src, String>>>()
        for (src in sources) {
            val found = HashSet<String>()
            Regex("\\$([A-Z]{1,5})\\b").findAll(src.text).forEach { found.add(it.groupValues[1]) }
            Regex("\\b([A-Z]{3,5})\\b").findAll(src.text).forEach { m ->
                val t = m.groupValues[1]
                if (t in byTicker && t !in STOP) found.add(t)
            }
            val norm = " " + Edgar.normalizeName(src.text) + " "
            for ((t, _, n) in cos) {
                if (n.length >= 4 && n !in STOP && " $n " in norm) found.add(t)
            }
            // The stance comes from the title, where creators say what they're doing.
            val stance = when {
                SELL.containsMatchIn(src.title) && !BUY.containsMatchIn(src.title) -> "selling"
                BUY.containsMatchIn(src.title) -> "buying"
                else -> "mentioned"
            }
            found.forEach { agg.getOrPut(it) { mutableListOf() }.add(src to stance) }
        }
        return agg.map { (t, hits) ->
            val newest = hits.maxBy { it.first.time }
            val stance = hits.firstOrNull { it.second != "mentioned" }?.second ?: "mentioned"
            Mention(t, byTicker[t]?.second ?: t, stance, hits.size, newest.first.time, newest.first.url)
        }.sortedWith(compareByDescending<Mention> { it.stance != "mentioned" }.thenByDescending { it.latest })
            .take(15)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) StockTracker/2.0")
        conn.setRequestProperty("Accept-Language", "en-US")
        conn.connectTimeout = 20_000; conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
