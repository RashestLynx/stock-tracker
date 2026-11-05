package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Who was tapped. [key] is the person id stored on trades (see [People.congressKey]). */
data class PersonTarget(val key: String, val name: String, val category: Category, val role: String = "")

/** A disclosed congressional trade with how the stock has done since. */
data class RecordTrade(
    val ticker: String, val type: String, val amount: String, val date: String, val perfPct: Double?,
)

data class PersonProfile(
    val name: String,
    val subtitle: String,
    val facts: List<Pair<String, String>>,
    val committees: List<String>,
    val record: List<RecordTrade>,
    val links: List<Pair<String, String>>,
    val note: String = "",
)

/**
 * Investor profiles built only from public information about each person's public role:
 *  - Congress: official legislator data (unitedstates/congress-legislators, public domain:
 *    party, state, district, years served, committees, official website) and their disclosed
 *    trades with performance since (Bargo)
 *  - Insiders and funds: their SEC filing record
 * Personal details such as home addresses that sometimes appear in filings are never shown.
 */
object People {
    private const val LEGIS = "https://unitedstates.github.io/congress-legislators/"
    private val cache = HashMap<String, PersonProfile>()

    fun congressKey(slug: String, chamber: String, state: String, member: String) =
        "cg:" + listOf(slug, chamber, state, member).joinToString("|") { it.replace("|", " ") }

    /** Blocking: call from a background thread. */
    fun load(t: PersonTarget, store: Store): PersonProfile {
        synchronized(cache) { cache[t.key]?.let { return it } }
        val p = when {
            t.key.startsWith("cg:") -> congress(t, store)
            t.key.startsWith("ins:") -> insider(t, store)
            t.key.startsWith("fund:") -> fund(t, store)
            else -> PersonProfile(t.name, t.role.ifEmpty { t.category.label }, emptyList(), emptyList(),
                emptyList(), emptyList(), "This entry was saved before profiles were added, so only its activity below is available.")
        }
        synchronized(cache) { cache[t.key] = p }
        return p
    }

    // ------------------------------------------------------------ Congress
    private fun congress(t: PersonTarget, store: Store): PersonProfile {
        val parts = t.key.removePrefix("cg:").split("|")
        val slug = parts.getOrElse(0) { "" }
        val chamber = parts.getOrElse(1) { "" }
        val state = parts.getOrElse(2) { "" }.take(2).uppercase(Locale.US)
        val member = parts.getOrElse(3) { t.name }

        val facts = mutableListOf<Pair<String, String>>()
        val committees = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        var name = member
        var subtitle = if (chamber == "senate") "U.S. Senator" else "U.S. Representative"

        try {
            val leg = findLegislator(member, chamber, state, store)
            if (leg != null) {
                val n = leg.getJSONObject("name")
                name = n.optString("official_full").ifBlank { "${n.optString("first")} ${n.optString("last")}" }
                val terms = leg.getJSONArray("terms")
                val cur = terms.getJSONObject(terms.length() - 1)
                val party = cur.optString("party")
                val st = cur.optString("state")
                val isSen = cur.optString("type") == "sen"
                subtitle = (if (isSen) "U.S. Senator" else "U.S. Representative") +
                    listOf(party, stateName(st) + if (!isSen && cur.has("district")) " · District ${cur.optInt("district")}" else "")
                        .filter { it.isNotBlank() }.joinToString(" · ", prefix = " · ")
                var firstYear = 9999
                var senTerms = 0; var repTerms = 0
                for (i in 0 until terms.length()) {
                    val tm = terms.getJSONObject(i)
                    firstYear = minOf(firstYear, tm.optString("start").take(4).toIntOrNull() ?: 9999)
                    if (tm.optString("type") == "sen") senTerms++ else repTerms++
                }
                if (party.isNotBlank()) facts.add("Party" to party)
                facts.add("State" to stateName(st))
                if (firstYear < 9999) facts.add("In Congress since" to firstYear.toString())
                facts.add("Terms served" to listOfNotNull(
                    if (senTerms > 0) "$senTerms in the Senate" else null,
                    if (repTerms > 0) "$repTerms in the House" else null).joinToString(", "))
                cur.optString("end").takeIf { it.isNotBlank() }?.let { facts.add("Current term ends" to it.take(4)) }
                cur.optString("url").takeIf { it.isNotBlank() }?.let { links.add("Official website" to it) }
                val bioguide = leg.getJSONObject("id").optString("bioguide")
                if (bioguide.isNotBlank()) {
                    links.add("Congressional biography" to "https://bioguide.congress.gov/search/bio/$bioguide")
                    committees.addAll(committeesFor(bioguide, store))
                }
            }
        } catch (_: Exception) {
        }

        val record = try { memberTrades(member, slug, store.bargoKey) } catch (_: Exception) { emptyList() }
        val withPerf = record.filter {
            val ty = it.type.lowercase(Locale.US)
            it.perfPct != null && ("buy" in ty || "purchase" in ty)
        }
        if (withPerf.isNotEmpty()) {
            val up = withPerf.count { (it.perfPct ?: 0.0) > 0 }
            val avg = withPerf.mapNotNull { it.perfPct }.average()
            facts.add("Recent buys up since" to "$up of ${withPerf.size}")
            facts.add("Average move since buy" to String.format(Locale.US, "%+.1f%%", avg))
        }
        links.add("Trades on Bargo" to Congress.SOURCE_URL)
        return PersonProfile(name, subtitle, facts, committees, record, links)
    }

    private fun findLegislator(member: String, chamber: String, state: String, store: Store): JSONObject? {
        val arr = JSONArray(cachedGet(LEGIS + "legislators-current.json", "legislators.json", 7, store))
        val words = fold(member).split(" ").filter { it.length > 1 }
        val type = if (chamber == "senate") "sen" else "rep"
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val terms = o.getJSONArray("terms")
            val cur = terms.getJSONObject(terms.length() - 1)
            if (chamber.isNotEmpty() && cur.optString("type") != type) continue
            if (state.isNotEmpty() && cur.optString("state") != state) continue
            val n = o.getJSONObject("name")
            val last = fold(n.optString("last"))
            if (last.isEmpty() || words.none { it == last || last.endsWith(it) }) continue
            var score = 2
            val first = fold(n.optString("first"))
            val nick = fold(n.optString("nickname"))
            if (words.any { it == first || it == nick }) score += 2
            if (state.isNotEmpty()) score += 1
            if (score > bestScore) { best = o; bestScore = score }
        }
        return best
    }

    private fun committeesFor(bioguide: String, store: Store): List<String> {
        val committees = JSONArray(cachedGet(LEGIS + "committees-current.json", "committees.json", 7, store))
        val names = HashMap<String, String>()
        for (i in 0 until committees.length()) {
            val c = committees.getJSONObject(i)
            names[c.optString("thomas_id")] = c.optString("name")
        }
        val membership = JSONObject(cachedGet(LEGIS + "committee-membership-current.json", "committee_membership.json", 7, store))
        val out = mutableListOf<String>()
        for (id in membership.keys()) {
            val name = names[id] ?: continue // top-level committees only
            val members = membership.getJSONArray(id)
            for (j in 0 until members.length()) {
                val m = members.getJSONObject(j)
                if (m.optString("bioguide") == bioguide) {
                    val title = m.optString("title")
                    out.add(name + if (title.isNotBlank()) " ($title)" else "")
                }
            }
        }
        return out.sorted()
    }

    /** Their recent disclosed trades with performance since, from Bargo (counts toward its daily limit). */
    private fun memberTrades(member: String, slug: String, apiKey: String): List<RecordTrade> {
        val last = member.trim().split(" ").lastOrNull().orEmpty()
        if (last.isEmpty()) return emptyList()
        val url = "https://www.bargo.ai/free-apis/congress/v1/trades?limit=25&member=" + URLEncoder.encode(last, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0")
        if (apiKey.isNotBlank()) conn.setRequestProperty("X-Api-Key", apiKey)
        conn.connectTimeout = 20_000; conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("trades")
                ?: return emptyList()
            val out = mutableListOf<RecordTrade>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (slug.isNotEmpty() && o.optString("member_slug") != slug) continue
                out.add(RecordTrade(
                    ticker = o.optString("ticker").let { if (it == "null") "" else it.uppercase(Locale.US) },
                    type = o.optString("type"),
                    amount = o.optString("amount_range").let { if (it == "null") "" else it },
                    date = o.optString("transaction_date").let { if (it == "null") "" else it },
                    perfPct = if (o.isNull("perf_pct")) null else o.optDouble("perf_pct"),
                ))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ Insiders
    private fun insider(t: PersonTarget, store: Store): PersonProfile {
        val cik = t.key.removePrefix("ins:")
        val facts = mutableListOf<Pair<String, String>>()
        var name = t.name
        try {
            val j = submissions(cik, store)
            name = j.optString("name").ifBlank { t.name }
            val recent = j.optJSONObject("filings")?.optJSONObject("recent")
            val forms = recent?.optJSONArray("form")
            val dates = recent?.optJSONArray("filingDate")
            if (forms != null && dates != null) {
                var form4 = 0
                var oldest = ""
                for (i in 0 until forms.length()) {
                    if (forms.optString(i) in setOf("4", "4/A")) form4++
                    oldest = dates.optString(i)
                }
                facts.add("Insider trade reports (recent)" to form4.toString())
                if (oldest.isNotBlank()) facts.add("Filings on record since" to oldest.take(4))
            }
        } catch (_: Exception) {
        }
        val links = listOf(
            "Every company they're an insider at" to "https://www.sec.gov/cgi-bin/own-disp?action=getowner&CIK=$cik",
            "All SEC filings" to "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$cik",
        )
        return PersonProfile(titleCase(name), t.role.ifEmpty { "Company insider" }, facts, emptyList(), emptyList(), links)
    }

    // ------------------------------------------------------------ Funds
    private fun fund(t: PersonTarget, store: Store): PersonProfile {
        val cik = t.key.removePrefix("fund:")
        val facts = mutableListOf<Pair<String, String>>()
        var name = t.name
        try {
            val j = submissions(cik, store)
            name = j.optString("name").ifBlank { t.name }
            // A firm's business address is its office, which is public.
            val b = j.optJSONObject("addresses")?.optJSONObject("business")
            val loc = listOf(b?.optString("city"), b?.optString("stateOrCountryDescription"))
                .filter { !it.isNullOrBlank() && it != "null" }.joinToString(", ") { titleCase(it!!) }
            if (loc.isNotBlank()) facts.add("Headquarters" to loc)
            val recent = j.optJSONObject("filings")?.optJSONObject("recent")
            val forms = recent?.optJSONArray("form")
            val dates = recent?.optJSONArray("filingDate")
            if (forms != null && dates != null) {
                var count = 0
                var oldest = ""
                for (i in 0 until forms.length()) {
                    if (forms.optString(i).startsWith("13F-HR")) { count++; oldest = dates.optString(i) }
                }
                if (count > 0) facts.add("Quarterly 13F reports on record" to count.toString())
                if (oldest.isNotBlank()) facts.add("Reporting holdings since" to oldest.take(4))
            }
        } catch (_: Exception) {
        }
        val links = listOf("13F filings on SEC" to
            "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$cik&type=13F")
        return PersonProfile(titleCase(name), "Investment manager", facts, emptyList(), emptyList(), links)
    }

    private fun submissions(cik: String, store: Store): JSONObject {
        val n = cik.toLongOrNull() ?: throw IOException("Bad CIK")
        return JSONObject(Edgar.get(String.format(Locale.US, "https://data.sec.gov/submissions/CIK%010d.json", n), store.userAgent))
    }

    // ------------------------------------------------------------ helpers
    private fun cachedGet(url: String, file: String, days: Int, store: Store): String {
        store.cachedText(file, days * Store.DAY_MS)?.let { return it }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0")
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            store.saveText(file, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase(Locale.US).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun titleCase(s: String): String =
        if (s.any { it.isLowerCase() }) s
        else s.lowercase(Locale.US).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }

    private val STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "FL" to "Florida", "GA" to "Georgia",
        "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts",
        "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana",
        "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico",
        "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
        "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
        "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
        "DC" to "District of Columbia", "PR" to "Puerto Rico", "GU" to "Guam", "VI" to "U.S. Virgin Islands",
        "AS" to "American Samoa", "MP" to "Northern Mariana Islands",
    )

    private fun stateName(code: String) = STATES[code] ?: code
}
