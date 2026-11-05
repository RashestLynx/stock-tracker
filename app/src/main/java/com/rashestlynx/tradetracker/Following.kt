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

data class Video(val title: String, val url: String, val time: Long, val id: String)

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
        var title = ""; var link = ""; var date = ""; var id = ""
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (p.name) {
                "entry" -> { inEntry = true; title = ""; link = ""; date = ""; id = "" }
                "title" -> if (inEntry) title = p.nextText().trim()
                "link" -> if (inEntry) link = p.getAttributeValue(null, "href") ?: link
                "published" -> if (inEntry) date = p.nextText().trim()
                "yt:videoId" -> if (inEntry) id = p.nextText().trim()
            } else if (e == XmlPullParser.END_TAG && p.name == "entry") {
                inEntry = false
                val t = try { iso.parse(date)?.time } catch (_: Exception) { null } ?: System.currentTimeMillis()
                if (title.isNotEmpty() && link.isNotEmpty()) out.add(Video(title, link, t, "yt:" + id.ifEmpty { link }))
            }
            e = p.next()
        }
        return out
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
