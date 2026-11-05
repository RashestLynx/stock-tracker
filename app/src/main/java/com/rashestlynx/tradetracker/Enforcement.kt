package com.rashestlynx.tradetracker

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.util.Locale

/** One SEC enforcement case. */
data class Charge(val title: String, val link: String, val summary: String, val date: String, val key: String)

/**
 * SEC enforcement actions about illegal insider trading, from the SEC's official RSS feeds
 * (litigation releases and press releases). These are cases the SEC has brought, not live trades.
 */
object Enforcement {
    private val FEEDS = listOf(
        "https://www.sec.gov/enforcement-litigation/litigation-releases/rss",
        "https://www.sec.gov/news/pressreleases.rss",
    )

    fun insiderTradingCases(userAgent: String): List<Charge> {
        val out = LinkedHashMap<String, Charge>()
        var anyWorked = false
        for (url in FEEDS) {
            val xml = try {
                Edgar.get(url, userAgent).also { anyWorked = true }
            } catch (e: Exception) {
                continue
            }
            for (c in parse(xml)) {
                val text = (c.title + " " + c.summary).lowercase(Locale.US)
                if ("insider trading" in text || "insider-trading" in text) out.putIfAbsent(c.key, c)
            }
        }
        if (!anyWorked) throw IOException("SEC enforcement feed unavailable")
        return out.values.toList()
    }

    private fun parse(xml: String): List<Charge> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val items = mutableListOf<Charge>()
        var inItem = false
        var title = ""; var link = ""; var desc = ""; var date = ""; var guid = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item", "entry" -> { inItem = true; title = ""; link = ""; desc = ""; date = ""; guid = "" }
                    "title" -> if (inItem) title = clean(parser.nextText())
                    "link" -> if (inItem) {
                        val href = parser.getAttributeValue(null, "href")
                        link = href ?: parser.nextText().trim()
                    }
                    "description", "summary", "content" -> if (inItem && desc.isEmpty()) desc = clean(parser.nextText())
                    "pubDate", "published", "updated" -> if (inItem && date.isEmpty()) date = parser.nextText().trim()
                    "guid", "id" -> if (inItem) guid = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG && (parser.name == "item" || parser.name == "entry")) {
                inItem = false
                if (title.isNotEmpty()) items.add(Charge(title, link, desc, shortDate(date), "sec:" + guid.ifEmpty { link.ifEmpty { title } }))
            }
            event = parser.next()
        }
        return items
    }

    /** Strip HTML tags and entities from feed text. */
    private fun clean(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    /** "Tue, 04 Nov 2026 10:15:00 -0500" -> "Nov 4, 2026" (falls back to the raw text). */
    private fun shortDate(raw: String): String {
        val m = Regex("(\\d{1,2}) (\\w{3}) (\\d{4})").find(raw) ?: return raw.take(10)
        val (d, mon, y) = m.destructured
        return "$mon ${d.toInt()}, $y"
    }
}
