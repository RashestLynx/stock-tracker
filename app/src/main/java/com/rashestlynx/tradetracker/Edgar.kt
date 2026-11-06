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

data class Filing(
    val accession: String, val form: String, val title: String, val link: String,
    val updated: String = "", val summary: String = "",
)

data class Trade(val code: String, val shares: Double, val price: Double) {
    val value: Double get() = shares * price
}

data class Form4(
    val ticker: String,
    val issuer: String,
    val owners: List<String>,
    val ownerCiks: List<String>,
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
        var title = ""; var link = ""; var term = ""; var id = ""; var updated = ""; var summary = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> { inEntry = true; title = ""; link = ""; term = ""; id = ""; updated = ""; summary = "" }
                    "updated" -> if (inEntry) updated = parser.nextText()
                    "summary" -> if (inEntry) summary = parser.nextText()
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
                    out[acc] = Filing(acc, term, title, link, updated, summary)
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
        val ownerCiks = mutableListOf<String>()
        val roles = mutableListOf<String>()
        for (owner in root.children("reportingOwner")) {
            owner.text("reportingOwnerId/rptOwnerName")?.let { owners.add(it) }
            owner.text("reportingOwnerId/rptOwnerCik")?.let { ownerCiks.add(it.trimStart('0')) }
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
            owners = owners, ownerCiks = ownerCiks, roles = roles, trades = trades,
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

    /** One row of a 13F holdings table. */
    data class Row13F(val name: String, val cls: String, val value: Double, val shares: Double, val option: Boolean)

    /** Every row of a 13F holdings table. */
    fun rows13F(filing: Filing, userAgent: String): List<Row13F> {
        val folder = filing.link.substringBeforeLast("/") + "/"
        val items = JSONObject(get(folder + "index.json", userAgent))
            .getJSONObject("directory").getJSONArray("item")
        var tableName: String? = null
        for (i in 0 until items.length()) {
            val name = items.getJSONObject(i).getString("name")
            val lower = name.lowercase(Locale.US)
            if (lower.endsWith(".xml") && lower != "primary_doc.xml") { tableName = name; break }
        }
        if (tableName == null) return emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(get(folder + tableName, userAgent).byteInputStream())
        return doc.documentElement.children("infoTable").mapNotNull { row ->
            val name = row.text("nameOfIssuer") ?: return@mapNotNull null
            Row13F(
                name = name,
                cls = row.text("titleOfClass") ?: "",
                value = row.text("value")?.toDoubleOrNull() ?: 0.0,
                shares = row.text("shrsOrPrnAmt/sshPrnamt")?.toDoubleOrNull() ?: 0.0,
                option = row.child("putCall") != null,
            )
        }
    }

    /** Biggest positions from a list of 13F rows (options skipped), largest first. */
    fun top13F(rows: List<Row13F>, top: Int = 20): List<Pair<String, Double>> {
        val totals = LinkedHashMap<String, Double>()
        rows.filter { !it.option }.forEach { totals[it.name] = (totals[it.name] ?: 0.0) + it.value }
        return totals.entries.sortedByDescending { it.value }.take(top).map { it.key to it.value }
    }

    /** The newest [count] 13F-HR filings for a filer, newest first, as (filing, period of report). */
    fun latest13Fs(cik: String, userAgent: String, count: Int = 2): List<Pair<Filing, String>> {
        val n = cik.toLong()
        val j = JSONObject(get(String.format(Locale.US, "https://data.sec.gov/submissions/CIK%010d.json", n), userAgent))
        val r = j.getJSONObject("filings").getJSONObject("recent")
        val forms = r.getJSONArray("form")
        val accs = r.getJSONArray("accessionNumber")
        val periods = r.optJSONArray("reportDate")
        val out = mutableListOf<Pair<Filing, String>>()
        for (i in 0 until forms.length()) {
            if (forms.getString(i) != "13F-HR") continue
            val acc = accs.getString(i)
            val folder = "https://www.sec.gov/Archives/edgar/data/$n/${acc.replace("-", "")}/"
            out.add(Filing(acc, "13F-HR", j.optString("name"), folder + "$acc-index.htm") to (periods?.optString(i) ?: ""))
            if (out.size >= count) break
        }
        return out
    }

    /** A 13F's largest stock positions: (issuer name, value in dollars), biggest first. */
    fun parse13FHoldings(filing: Filing, userAgent: String, top: Int = 20): List<Pair<String, Double>> {
        val folder = filing.link.substringBeforeLast("/") + "/"
        val items = JSONObject(get(folder + "index.json", userAgent))
            .getJSONObject("directory").getJSONArray("item")
        var tableName: String? = null
        for (i in 0 until items.length()) {
            val name = items.getJSONObject(i).getString("name")
            val lower = name.lowercase(Locale.US)
            if (lower.endsWith(".xml") && lower != "primary_doc.xml") { tableName = name; break }
        }
        if (tableName == null) return emptyList()

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(get(folder + tableName, userAgent).byteInputStream())
        val totals = LinkedHashMap<String, Double>()
        for (row in doc.documentElement.children("infoTable")) {
            if (row.child("putCall") != null) continue // skip options
            val name = row.text("nameOfIssuer") ?: continue
            val value = row.text("value")?.toDoubleOrNull() ?: 0.0
            totals[name] = (totals[name] ?: 0.0) + value
        }
        return totals.entries.sortedByDescending { it.value }.take(top).map { it.key to it.value }
    }

    /** SEC's official list of company names -> tickers, keyed by [normalizeName]. */
    fun companyTickers(userAgent: String): Map<String, String> {
        val json = JSONObject(get("https://www.sec.gov/files/company_tickers.json", userAgent))
        val map = LinkedHashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val o = json.getJSONObject(keys.next())
            val norm = normalizeName(o.optString("title"))
            // The file is ordered by size, so the first ticker for a name is the main one.
            if (norm.isNotEmpty() && norm !in map) map[norm] = o.optString("ticker").uppercase(Locale.US)
        }
        return map
    }

    /** Ticker -> company name, from the SEC's official company list. */
    fun tickerNames(userAgent: String): Map<String, String> {
        val json = JSONObject(get("https://www.sec.gov/files/company_tickers.json", userAgent))
        val map = HashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val o = json.getJSONObject(keys.next())
            val t = o.optString("ticker").uppercase(Locale.US)
            if (t.isNotEmpty() && t !in map) map[t] = o.optString("title")
        }
        return map
    }

    /** Ticker -> SEC CIK number, from the SEC's official company list. */
    fun tickerCiks(userAgent: String): Map<String, Long> {
        val json = JSONObject(get("https://www.sec.gov/files/company_tickers.json", userAgent))
        val map = HashMap<String, Long>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val o = json.getJSONObject(keys.next())
            val t = o.optString("ticker").uppercase(Locale.US)
            if (t.isNotEmpty() && t !in map) map[t] = o.optLong("cik_str")
        }
        return map
    }

    private val NAME_NOISE = setOf(
        "INC", "INCORPORATED", "CORP", "CORPORATION", "CO", "COMPANY", "LTD", "LIMITED",
        "PLC", "LLC", "LP", "NV", "SA", "AG", "SE", "THE", "CL", "CLASS", "NEW", "DEL", "COM",
        "SHS", "ORD", "ADR", "SPONSORED", "REIT",
    )

    /** "Meta Platforms, Inc." and "META PLATFORMS INC CL A" both become "META PLATFORMS". */
    fun normalizeName(name: String): String {
        val words = name.uppercase(Locale.US).replace(Regex("[^A-Z0-9 ]"), " ")
            .split(" ").filter { it.isNotEmpty() && it !in NAME_NOISE }.toMutableList()
        while (words.size > 1 && words.last().length == 1) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    // ---- small XML helpers ----
    private fun Element.children(name: String): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            // Compare without any namespace prefix (13F tables use e.g. "ns1:infoTable").
            if (n is Element && n.tagName.substringAfter(':') == name) list.add(n)
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
