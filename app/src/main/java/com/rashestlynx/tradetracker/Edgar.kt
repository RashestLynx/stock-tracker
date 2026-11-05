package com.rashestlynx.tradetracker

import android.util.Xml
import org.json.JSONObject
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class Filing(val accession: String, val form: String, val title: String, val link: String)

data class Trade(val code: String, val shares: Double, val price: Double) {
    val value: Double get() = shares * price
}

data class Form4(
    val ticker: String,
    val issuer: String,
    val owners: List<String>,
    val roles: List<String>,
    val trades: List<Trade>,
)

/** Talks to SEC EDGAR. All calls block, so run them off the main thread. */
object Edgar {
    private const val FEED =
        "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=%s" +
            "&company=&dateb=&owner=include&start=0&count=100&output=atom"

    val TX_CODES = mapOf(
        "P" to "Bought (open market)", "S" to "Sold (open market)",
        "A" to "Award/grant", "M" to "Option exercise", "F" to "Tax withholding",
        "G" to "Gift", "D" to "Disposed to issuer", "C" to "Conversion",
        "X" to "Option exercise", "J" to "Other",
    )

    private var lastRequest = 0L

    /** GET with the SEC-required User-Agent, kept under the SEC's 10 requests/second limit. */
    fun get(url: String, userAgent: String): String {
        synchronized(this) {
            val wait = 150 - (System.currentTimeMillis() - lastRequest)
            if (wait > 0) Thread.sleep(wait)
            lastRequest = System.currentTimeMillis()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("SEC returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Latest filings of one form type ("4" or "13F-HR"), newest first, one per accession. */
    fun feed(form: String, userAgent: String): List<Filing> {
        val xml = get(String.format(FEED, URLEncoder.encode(form, "UTF-8")), userAgent)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val out = LinkedHashMap<String, Filing>()
        var inEntry = false
        var title = ""; var link = ""; var term = ""; var id = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> { inEntry = true; title = ""; link = ""; term = ""; id = "" }
                    "title" -> if (inEntry) title = parser.nextText()
                    "id" -> if (inEntry) id = parser.nextText()
                    "link" -> if (inEntry) link = parser.getAttributeValue(null, "href") ?: ""
                    "category" -> if (inEntry) term = parser.getAttributeValue(null, "term") ?: ""
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                inEntry = false
                val acc = Regex("accession-number=([\\d-]+)").find(id)?.groupValues?.get(1)
                // Each Form 4 is listed twice (reporting person + company); keep the first.
                if (acc != null && term == form && acc !in out) {
                    out[acc] = Filing(acc, term, title, link)
                }
            }
            event = parser.next()
        }
        return out.values.toList()
    }

    /** Downloads a Form 4's XML and pulls out who traded what. */
    fun parseForm4(filing: Filing, userAgent: String): Form4? {
        val folder = filing.link.substringBeforeLast("/") + "/"
        val items = JSONObject(get(folder + "index.json", userAgent))
            .getJSONObject("directory").getJSONArray("item")
        var xmlName: String? = null
        for (i in 0 until items.length()) {
            val name = items.getJSONObject(i).getString("name")
            if (name.lowercase(Locale.US).endsWith(".xml")) { xmlName = name; break }
        }
        if (xmlName == null) return null

        val xml = get(folder + xmlName, userAgent)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val root = doc.documentElement

        val owners = mutableListOf<String>()
        val roles = mutableListOf<String>()
        for (owner in root.children("reportingOwner")) {
            owner.text("reportingOwnerId/rptOwnerName")?.let { owners.add(it) }
            val rel = owner.child("reportingOwnerRelationship") ?: continue
            val title = rel.text("officerTitle")
            when {
                !title.isNullOrBlank() -> roles.add(title)
                rel.flag("isDirector") -> roles.add("Director")
                rel.flag("isTenPercentOwner") -> roles.add("10% owner")
            }
        }

        val trades = mutableListOf<Trade>()
        root.child("nonDerivativeTable")?.children("nonDerivativeTransaction")?.forEach { t ->
            trades.add(
                Trade(
                    code = t.text("transactionCoding/transactionCode") ?: "",
                    shares = t.text("transactionAmounts/transactionShares/value")?.toDoubleOrNull() ?: 0.0,
                    price = t.text("transactionAmounts/transactionPricePerShare/value")?.toDoubleOrNull() ?: 0.0,
                )
            )
        }

        return Form4(
            ticker = (root.text("issuer/issuerTradingSymbol") ?: "").uppercase(Locale.US),
            issuer = root.text("issuer/issuerName") ?: "",
            owners = owners, roles = roles, trades = trades,
        )
    }

    /** One readable line per transaction type, e.g. "Bought (open market): 1,000 shares @ ~$200.50 ($200,500)". */
    fun summarize(trades: List<Trade>): List<String> {
        val grouped = LinkedHashMap<String, DoubleArray>() // [shares, value]
        for (t in trades) {
            val agg = grouped.getOrPut(t.code) { DoubleArray(2) }
            agg[0] += t.shares; agg[1] += t.value
        }
        return grouped.map { (code, agg) ->
            val label = TX_CODES[code] ?: "Code $code"
            val shares = String.format(Locale.US, "%,.0f", agg[0])
            if (agg[0] > 0 && agg[1] > 0) {
                val avg = String.format(Locale.US, "%,.2f", agg[1] / agg[0])
                val total = String.format(Locale.US, "%,.0f", agg[1])
                "$label: $shares shares @ ~\$$avg (\$$total)"
            } else {
                "$label: $shares shares"
            }
        }
    }

    // ---- small XML helpers ----
    private fun Element.children(name: String): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == name) list.add(n)
        }
        return list
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.text(path: String): String? {
        var el: Element = this
        for (part in path.split("/")) el = el.child(part) ?: return null
        // Many Form 4 fields wrap their content in a <value> element.
        val inner = el.child("value") ?: el
        return inner.textContent?.trim()?.ifEmpty { null }
    }

    private fun Element.flag(name: String): Boolean =
        text(name)?.lowercase(Locale.US) in setOf("1", "true")
}
