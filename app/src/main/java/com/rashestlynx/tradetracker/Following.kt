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
    val cik: String = "",           // SEC filer number for investment firms (13F holdings)
)

data class Video(val title: String, val url: String, val time: Long, val id: String, val description: String = "")

/** A stock someone talked about publicly. stance = "buying", "selling" or "mentioned". */
data class Mention(val ticker: String, val company: String, val stance: String, val count: Int, val latest: Long, val url: String)

/** Something a person has publicly said they own, with where they said it. */
data class Holding(val name: String, val ticker: String, val note: String, val source: String, val sourceUrl: String)

/** Public statements gathered from interviews, articles and their own company's write-ups. */
data class KnownInfo(val owns: List<Holding>, val recommends: List<String>, val recommendsSource: String, val disclosureFeed: String = "")

object Following {
    private const val BRIEFS_ETF = "https://www.briefs.co/best-etfs-to-buy-three-etf-portfolios/"
    private const val MARK_TRANSCRIPT = "https://singjupost.com/transcript-of-how-to-invest-for-beginners-in-2025-by-mark-tilbury/"

    val KNOWN = mapOf(
        "Jaspreet Singh" to KnownInfo(
            owns = listOf(
                Holding("Vanguard S&P 500 ETF", "VOO", "Briefs says he personally invests in VOO", "Briefs", BRIEFS_ETF),
                Holding("Vanguard international high-dividend ETF", "", "Briefs says he personally invests in it (ticker not named)", "Briefs", BRIEFS_ETF),
                Holding("Bitcoin", "", "Bought since 2017; later sold a large portion to reinvest in real estate", "Yahoo Finance",
                    "https://finance.yahoo.com/news/jaspreet-singh-cuts-bitcoin-why-120039231.html"),
                Holding("Physical gold", "", "Buys a little every month, about 2% of his portfolio", "Yahoo Finance",
                    "https://finance.yahoo.com/news/jaspreet-singh-says-rich-people-172918130.html"),
                Holding("Fundrise (real estate)", "", "Has discussed his position in Fundrise", "People & Media",
                    "https://www.peopleandmedia.com/jaspreet-singh-net-worth/"),
            ),
            recommends = listOf("SCHD", "VUG", "QQQ", "VIG", "GLD", "SMH", "VTI", "SPY"),
            recommendsSource = BRIEFS_ETF,
            disclosureFeed = "https://www.briefs.co/feed/",
        ),
        "Mark Tilbury" to KnownInfo(
            owns = listOf(
                Holding("S&P 500 index funds", "", "Says index funds hold the majority of his money; the S&P 500 is \"part of my portfolio\"",
                    "Mark Tilbury on X", "https://x.com/marktilbury/status/1890400994409734435"),
                Holding("Nvidia", "NVDA", "Named with Apple and Microsoft: \"luckily I have some of those in my personal portfolio\" (didn't say which)",
                    "Video transcript", MARK_TRANSCRIPT),
                Holding("Apple", "AAPL", "Same statement as Nvidia above, so he owns at least some of these three", "Video transcript", MARK_TRANSCRIPT),
                Holding("Microsoft", "MSFT", "Same statement as Nvidia above", "Video transcript", MARK_TRANSCRIPT),
                Holding("Bitcoin and Ethereum", "", "Keeps a small percentage of his portfolio in crypto", "Mark Tilbury on X",
                    "https://x.com/marktilbury/status/1966849360534401375"),
            ),
            recommends = listOf("AAPL"),
            recommendsSource = "https://x.com/marktilbury/status/1959964001850560980",
        ),
    )

    /** New "he personally invests in X" lines from the person's own company articles (Briefs for Jaspreet). Blocking. */
    fun disclosures(feedUrl: String, name: String, store: Store): List<Holding> {
        if (feedUrl.isBlank()) return emptyList()
        val xml = get(feedUrl)
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val first = name.split(" ").first()
        val out = LinkedHashMap<String, Holding>()
        var inItem = false
        var title = ""; var link = ""; var body = ""
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (p.name) {
                "item" -> { inItem = true; title = ""; link = ""; body = "" }
                "title" -> if (inItem) title = p.nextText().trim()
                "link" -> if (inItem) link = p.nextText().trim()
                "description", "content:encoded" -> if (inItem) body += " " + p.nextText()
            } else if (e == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                val text = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace(Regex("\\s+"), " ")
                val sentences = text.split(Regex("(?<=[.!?])\\s+"))
                sentences.forEachIndexed { i, sent ->
                    val low = sent.lowercase(Locale.US)
                    val disclosed = first.lowercase(Locale.US) in low &&
                        listOf("personally invest", "personally own", "is invested in", "he owns", "he holds").any { it in low }
                    if (!disclosed) return@forEachIndexed
                    var tickers = TICKER.findAll(sent).map { it.groupValues[1] }.filter { it !in STOP_TICK }.toList()
                    if (tickers.isEmpty() && i > 0) tickers = TICKER.findAll(sentences[i - 1]).map { it.groupValues[1] }
                        .filter { it !in STOP_TICK }.toList()
                    if (tickers.isEmpty()) {
                        out.putIfAbsent(sent, Holding(sent.take(80), "", "Disclosed in \"$title\"", "Briefs", link))
                    } else tickers.forEach { t ->
                        out.putIfAbsent(t, Holding(t, t, "Disclosed in \"$title\"", "Briefs", link))
                    }
                }
            }
            e = p.next()
        }
        return out.values.toList()
    }

    private val TICKER = Regex("\\b([A-Z]{2,5})\\b")
    private val STOP_TICK = setOf("ETF", "ETFS", "AI", "US", "USA", "CEO", "IPO", "SPDR", "GDP", "FED", "IRA", "VC", "OK")

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

    /** Famous investment firms, followed through their quarterly 13F holdings. */
    val FUNDS = listOf(
        Followed("Berkshire Hathaway", "", "\"Berkshire Hathaway\" stocks", listOf("Warren Buffett's company, run by Greg Abel since 2026"),
            listOf("13F filings" to "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1067983&type=13F"), cik = "1067983"),
        Followed("Pershing Square (Bill Ackman)", "", "\"Bill Ackman\" OR \"Pershing Square\"", listOf("Bill Ackman's concentrated hedge fund"),
            listOf("13F filings" to "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1336528&type=13F"), cik = "1336528"),
        Followed("Duquesne (Stanley Druckenmiller)", "", "\"Druckenmiller\"", listOf("Stanley Druckenmiller's family office"),
            listOf("13F filings" to "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1536411&type=13F"), cik = "1536411"),
        Followed("Bridgewater (Ray Dalio)", "", "\"Bridgewater Associates\" OR \"Ray Dalio\"", listOf("The world's largest hedge fund, founded by Ray Dalio"),
            listOf("13F filings" to "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1350694&type=13F"), cik = "1350694"),
    )

    fun load(store: Store): List<Followed> {
        // One-time: add the famous funds to existing lists too.
        if (store.prefs.getInt("following_v", 1) < 2) {
            val current = loadRaw(store)
            save(store, current + FUNDS.filter { f -> current.none { it.name == f.name } })
            store.prefs.edit().putInt("following_v", 2).apply()
        }
        return loadRaw(store)
    }

    private fun loadRaw(store: Store): List<Followed> {
        val raw = store.prefs.getString("following", null) ?: return DEFAULTS + FUNDS
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
                    o.optString("cik"),
                )
            }
        } catch (e: Exception) {
            DEFAULTS + FUNDS
        }
    }

    fun save(store: Store, list: List<Followed>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().put("name", f.name).put("yt", f.youtube).put("q", f.newsQuery)
                .put("biz", JSONArray(f.businesses)).put("cik", f.cik)
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

    // ------------------------------------------------------------ fund holdings
    data class FundHolding(val name: String, val value: Double, val change: String)
    data class FundSnapshot(val period: String, val total: Double, val top: List<FundHolding>,
                            val newBuys: List<String>, val soldOut: List<String>, val crypto: List<FundHolding>)

    /** Latest 13F with changes vs the one before. Blocking. */
    fun fundSnapshot(cik: String, store: Store): FundSnapshot? {
        val ua = store.userAgent
        val filings = Edgar.latest13Fs(cik, ua, 2)
        if (filings.isEmpty()) return null
        val now = Edgar.rows13F(filings[0].first, ua).filter { !it.option }
        val before = if (filings.size > 1) Edgar.rows13F(filings[1].first, ua).filter { !it.option } else emptyList()
        fun byName(rows: List<Edgar.Row13F>) = rows.groupBy { it.name }.mapValues { (_, r) -> r.sumOf { it.value } to r.sumOf { it.shares } }
        val a = byName(now); val b = byName(before)
        fun change(n: String): String {
            val sh = a[n]?.second ?: 0.0
            val old = b[n]?.second ?: return if (before.isEmpty()) "" else "New"
            if (old <= 0) return ""
            val pct = (sh - old) / old * 100
            return when {
                pct > 1 -> String.format(Locale.US, "Added %.0f%%", pct)
                pct < -1 -> String.format(Locale.US, "Cut %.0f%%", -pct)
                else -> "No change"
            }
        }
        val top = a.entries.sortedByDescending { it.value.first }.take(10).map { FundHolding(it.key, it.value.first, change(it.key)) }
        val newBuys = if (before.isEmpty()) emptyList() else a.keys.filter { it !in b }.sortedByDescending { a[it]?.first ?: 0.0 }.take(6)
        val sold = b.keys.filter { it !in a }.sortedByDescending { b[it]?.first ?: 0.0 }.take(6)
        val crypto = a.filter { Crypto.assetOf(it.key) != null }.map { FundHolding(it.key, it.value.first, change(it.key)) }
        // Record their crypto positions for the Crypto tab.
        store.addCryptoPositions(crypto.map {
            Store.CryptoPos(filings[0].first.title, "fund:$cik", Crypto.assetOf(it.name)!!, it.name, it.value, System.currentTimeMillis())
        })
        return FundSnapshot(filings[0].second, a.values.sumOf { it.first }, top, newBuys, sold, crypto)
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
