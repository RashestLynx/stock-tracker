package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/** One IPO on the calendar. Prices are dollars a share; value is the whole deal in dollars. */
data class Ipo(
    val key: String,          // normalized company name, used to match sources and remember stages
    val name: String,
    val symbol: String,
    val date: String,         // yyyy-MM-dd, "" if not set yet
    val exchange: String,
    val low: Double,          // price range (low == high once priced)
    val high: Double,
    val shares: Double,
    val value: Double,
    val status: String,       // filed, expected, priced, withdrawn
    val cornerstone: String = "",  // link to the filing that names investors lined up to buy, "" if none found
    val mentions: Int = 0,         // Reddit mentions in the last 24 hours
    val open: Double = 0.0,        // first trading price seen (priced IPOs only)
    val now: Double = 0.0,
) {
    val big: Boolean get() = value >= Ipos.BIG_DEAL
    val ipoPrice: Double get() = if (high > 0) high else low
}

/** A company that just filed with the SEC to go public (form S-1 or F-1), before any price is set. */
data class IpoFiling(val key: String, val name: String, val cik: String, val form: String, val date: String,
                     val link: String, val cornerstone: String = "")

data class IpoEvent(val id: String, val title: String, val body: String, val link: String, val time: Long)

data class IpoResult(val upcoming: List<Ipo>, val priced: List<Ipo>, val filings: List<IpoFiling>, val fetchedAt: Long)

/**
 * IPOs from three free sources:
 *  - Finnhub's IPO calendar (with your Finnhub key): date, price range, shares, deal size, priced or not.
 *  - SEC EDGAR: brand-new S-1 / F-1 filings, the earliest public sign a company plans to go public, and a
 *    full-text search for filings that say investors "indicated an interest in purchasing" shares
 *    (cornerstone investors, named in the prospectus).
 *  - ApeWisdom: how much Reddit is talking about each one.
 *
 * Each check is compared with the last one so the app can alert at every stage: filed, range set,
 * range raised or cut, deal upsized, priced, first trade.
 */
object Ipos {
    const val BIG_DEAL = 100_000_000.0
    private const val CHECK_EVERY_MS = 20 * 60_000L
    private const val PHRASE = "indicated an interest in purchasing"
    private val SUFFIX = setOf("inc", "incorporated", "corp", "corporation", "co", "company", "ltd", "limited", "plc",
        "holdings", "holding", "group", "the", "sa", "nv", "ag", "llc", "lp", "se", "class", "a", "ordinary", "shares")
    // S-1s that aren't company IPOs: crypto and other trusts, ETFs, funds, blank-check SPACs.
    private val NOT_IPO = Regex("(?i)\\b(trust|etf|fund|acquisition|blank check|spac|bitcoin|ether|solana|xrp)\\b")

    fun norm(name: String): String = name.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.isNotBlank() && it !in SUFFIX }.take(3).joinToString(" ")

    // ---------------- loading ----------------

    /** Blocking. Loads all three sources; works without a Finnhub key (filings only). */
    fun load(store: Store): IpoResult {
        val ua = store.userAgent
        val today = LocalDate.now()
        val errors = mutableListOf<String>()
        val cal = runCatching { calendar(store, today) }.onFailure { errors += it.message ?: "IPO calendar failed" }.getOrDefault(emptyList())
        val filings = runCatching { newFilings(store, ua) }.onFailure { errors += it.message ?: "SEC filings failed" }.getOrDefault(emptyList())
        val corner = runCatching { cornerstones(ua, today) }.getOrDefault(emptyMap())
        val buzz = runCatching { Wsb.trending("all-stocks", false).associate { it.ticker to it.mentions } }.getOrDefault(emptyMap())
        if (cal.isEmpty() && filings.isEmpty() && errors.isNotEmpty()) throw IOException(errors.first())

        val withExtras = cal.map { i -> i.copy(cornerstone = corner[i.key] ?: "", mentions = buzz[i.symbol] ?: 0) }
        val todayS = today.toString()
        val upcoming = withExtras.filter { it.status != "priced" && it.status != "withdrawn" && (it.date.isBlank() || it.date >= todayS) }
            .sortedWith(compareBy<Ipo> { it.date.ifBlank { "9999" } }.thenByDescending { it.value })
        // First trade for the ones that just priced (needs the Finnhub key).
        val priced = withExtras.filter { it.status == "priced" }.sortedByDescending { it.date }.take(25)
            .mapIndexed { n, i ->
                if (n >= 12 || i.symbol.isBlank() || i.date > todayS) i
                else runCatching { Live.quote(i.symbol, store) }.getOrNull()?.let { q ->
                    // The first day's open is the IPO's first trade; after that only a saved one counts.
                    val open = stockOpen(store, i) ?: if (i.date == todayS && q.open > 0) q.open else 0.0
                    rememberOpen(store, i.symbol, open)
                    i.copy(open = open, now = q.price)
                } ?: i
            }
        val filed = filings.map { f -> f.copy(cornerstone = corner[f.key] ?: "") }
        val r = IpoResult(upcoming, priced, filed, System.currentTimeMillis())
        store.prefs.edit().putString("ipo_cache", toJson(r).toString()).apply()
        return r
    }

    /** Remembers each IPO's first opening price, since later quotes only have that day's open. */
    private fun stockOpen(store: Store, i: Ipo): Double? {
        val o = JSONObject(store.prefs.getString("ipo_opens", "{}") ?: "{}")
        return o.optDouble(i.symbol, 0.0).takeIf { it > 0 }
    }

    private fun rememberOpen(store: Store, symbol: String, open: Double) {
        val o = JSONObject(store.prefs.getString("ipo_opens", "{}") ?: "{}")
        if (o.has(symbol) || open <= 0) return
        o.put(symbol, open)
        store.prefs.edit().putString("ipo_opens", o.toString()).apply()
    }

    fun cached(store: Store): IpoResult? = runCatching { fromJson(JSONObject(store.prefs.getString("ipo_cache", "") ?: "")) }.getOrNull()

    private fun calendar(store: Store, today: LocalDate): List<Ipo> {
        val k = Live.key(store)
        if (k.isBlank()) return emptyList()
        val url = "https://finnhub.io/api/v1/calendar/ipo?from=${today.minusDays(21)}&to=${today.plusDays(90)}&token=" +
            URLEncoder.encode(k, "UTF-8")
        val a = JSONObject(http(url, null)).optJSONArray("ipoCalendar") ?: return emptyList()
        return (0 until a.length()).mapNotNull { n ->
            val o = a.getJSONObject(n)
            val name = o.optString("name").trim()
            if (name.isBlank() || NOT_IPO.containsMatchIn(name)) return@mapNotNull null
            val nums = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(o.optString("price")).map { it.value.toDouble() }.toList()
            val low = nums.minOrNull() ?: 0.0
            val high = nums.maxOrNull() ?: 0.0
            val shares = o.optDouble("numberOfShares", 0.0).takeIf { it.isFinite() } ?: 0.0
            val total = o.optDouble("totalSharesValue", 0.0).takeIf { it.isFinite() && it > 0 } ?: (shares * high)
            Ipo(norm(name), name, o.optString("symbol").uppercase(Locale.US), o.optString("date"), o.optString("exchange"),
                low, high, shares, total, o.optString("status").lowercase(Locale.US))
        }.distinctBy { it.key }
    }

    /** New S-1 and F-1 filings from companies that aren't public yet. */
    private fun newFilings(store: Store, ua: String): List<IpoFiling> {
        val publicCiks = (store.cachedCikMap() ?: Edgar.tickerCiks(ua).also { store.saveCikMap(it) }).values.toHashSet()
        val list = Edgar.feed("S-1", ua, 60) + Edgar.feed("F-1", ua, 40)
        return list.mapNotNull { f ->
            val m = Regex("^\\S+ - (.+?) \\((\\d{10})\\)").find(f.title) ?: return@mapNotNull null
            val name = m.groupValues[1].trim()
            val cik = m.groupValues[2]
            if (cik.toLong() in publicCiks || NOT_IPO.containsMatchIn(name)) return@mapNotNull null
            IpoFiling(norm(name), titleCase(name), cik, f.form, f.updated.take(10), f.link)
        }.distinctBy { it.cik }.sortedByDescending { it.date }
    }

    /** Filings from the last 4 months that name investors lined up to buy shares: normalized name -> document link. */
    private fun cornerstones(ua: String, today: LocalDate): Map<String, String> {
        val q = URLEncoder.encode("\"$PHRASE\"", "UTF-8")
        val url = "https://efts.sec.gov/LATEST/search-index?q=$q&forms=S-1,S-1/A,F-1,F-1/A,424B4" +
            "&dateRange=custom&startdt=${today.minusDays(120)}&enddt=$today"
        val hits = JSONObject(Edgar.get(url, ua)).optJSONObject("hits")?.optJSONArray("hits") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (n in 0 until hits.length()) {
            val h = hits.getJSONObject(n)
            val src = h.optJSONObject("_source") ?: continue
            val display = src.optJSONArray("display_names")?.optString(0) ?: continue
            val name = display.substringBefore("(").trim()
            val cik = src.optJSONArray("ciks")?.optString(0)?.trimStart('0') ?: continue
            val id = h.optString("_id")
            val adsh = id.substringBefore(":").replace("-", "")
            val file = id.substringAfter(":", "")
            if (adsh.isBlank() || file.isBlank()) continue
            out.putIfAbsent(norm(name), "https://www.sec.gov/Archives/edgar/data/$cik/$adsh/$file")
        }
        return out
    }

    /** Blocking. Reads a filing and returns the sentences naming investors lined up to buy, or null. */
    fun whoIsBuying(link: String, ua: String): String? {
        val text = Edgar.get(link, ua)
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;|&#160;|&#xa0;"), " ").replace("&amp;", "&").replace(Regex("&#8217;|&rsquo;"), "'")
            .replace(Regex("&#822[01];|&[lr]dquo;"), "\"").replace(Regex("&#[0-9]+;|&[a-z]+;"), " ")
            .replace(Regex("\\s+"), " ")
        val out = LinkedHashSet<String>()
        var from = 0
        while (out.size < 3) {
            val at = text.indexOf(PHRASE, from, ignoreCase = true)
            if (at < 0) break
            val start = text.lastIndexOf(". ", at).let { if (it < 0 || at - it > 600) (at - 600).coerceAtLeast(0) else it + 2 }
            val end = text.indexOf(". ", at).let { if (it < 0 || it - at > 600) (at + 600).coerceAtMost(text.length) else it + 1 }
            out += text.substring(start, end).trim()
            from = end
        }
        return out.joinToString("\n\n").ifBlank { null }
    }

    // ---------------- alerts ----------------

    /**
     * Blocking, for the background check. Loads at most every 20 minutes, compares with what it saw last time and
     * returns what's new. The first time it only remembers, so you don't get a flood of old IPOs.
     */
    fun check(store: Store): List<IpoEvent> {
        if (!store.ipoAlerts) return emptyList()
        val last = store.prefs.getLong("ipo_last_check", 0L)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return emptyList()
        store.prefs.edit().putLong("ipo_last_check", System.currentTimeMillis()).apply()
        val r = load(store)
        val raw = store.prefs.getString("ipo_state", "") ?: ""
        val first = raw.isBlank()
        val state = if (first) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val events = ArrayList<IpoEvent>()
        val now = System.currentTimeMillis()
        fun ev(id: String, title: String, body: String, link: String) { events += IpoEvent(id, title, body, link, now) }

        for (i in r.upcoming + r.priced) {
            val s = state.optJSONObject(i.key)
            val link = i.cornerstone.ifBlank { "https://finnhub.io" }
            val label = if (i.symbol.isNotBlank()) "${i.name} (${i.symbol})" else i.name
            val range = rangeText(i.low, i.high)
            if (s == null) {
                if (i.big && i.status == "priced") ev("ipo:${i.key}:priced", "$label priced its IPO at \$${fmt(i.ipoPrice)}",
                    "About ${money(i.value)} deal. Trades ${if (i.date.isNotBlank()) i.date else "soon"}. Shares usually open well above the IPO price; use a limit order.", link)
                else if (i.big && i.status != "withdrawn") ev("ipo:${i.key}:new", "Big IPO coming: $label",
                    listOfNotNull(range.takeIf { it.isNotBlank() }, "about ${money(i.value)} deal",
                        i.date.takeIf { it.isNotBlank() }?.let { "expected $it" }, i.exchange.takeIf { it.isNotBlank() })
                        .joinToString(" · ") + ". Request shares now if your broker has IPO access.", link)
            } else {
                val ol = s.optDouble("l", 0.0); val oh = s.optDouble("h", 0.0); val osh = s.optDouble("sh", 0.0)
                val os = s.optString("s")
                if (i.status == "priced" && os != "priced" && (i.big || s.optBoolean("big"))) {
                    val p = i.ipoPrice
                    val vs = when {
                        oh <= 0 -> ""
                        p > oh -> " Above its \$${fmt(ol)}–\$${fmt(oh)} range: strong demand."
                        p == oh -> " At the top of its range."
                        p < ol -> " Below its \$${fmt(ol)}–\$${fmt(oh)} range: weak demand."
                        else -> " Inside its range."
                    }
                    ev("ipo:${i.key}:priced", "$label priced its IPO at \$${fmt(p)}",
                        "Starts trading ${if (i.date.isNotBlank()) i.date else "soon"}.$vs Shares usually open well above the IPO price; use a limit order.", link)
                } else if (i.status != "priced" && oh > 0 && i.high > 0) {
                    if (i.high > oh || i.low > ol) ev("ipo:${i.key}:up:${i.high}", "$label raised its IPO price range",
                        "Now ${rangeText(i.low, i.high)} (was ${rangeText(ol, oh)}). A raised range usually means strong demand.", link)
                    else if (i.high < oh || i.low < ol) ev("ipo:${i.key}:down:${i.high}", "$label cut its IPO price range",
                        "Now ${rangeText(i.low, i.high)} (was ${rangeText(ol, oh)}). A cut range usually means weak demand.", link)
                    if (osh > 0 && i.shares > osh * 1.05) ev("ipo:${i.key}:upsized:${i.shares.toLong()}", "$label is selling more shares",
                        "Deal upsized to ${String.format(Locale.US, "%,.0f", i.shares)} shares (was ${String.format(Locale.US, "%,.0f", osh)}), about ${money(i.value)}. Usually a sign of strong demand.", link)
                } else if (oh <= 0 && i.high > 0 && i.big && i.status != "priced") {
                    ev("ipo:${i.key}:range", "$label set its IPO price range",
                        "$range · about ${money(i.value)} deal${if (i.date.isNotBlank()) " · expected ${i.date}" else ""}. This is usually 1–2 weeks before it trades.", link)
                }
                if (i.status == "withdrawn" && os != "withdrawn" && s.optBoolean("big")) ev("ipo:${i.key}:pulled", "$label pulled its IPO", "The company withdrew or postponed its offering.", link)
                if (i.cornerstone.isNotBlank() && !s.optBoolean("c") && (i.big || i.value == 0.0))
                    ev("ipo:${i.key}:corner", "Big investors lined up for $label",
                        "Its filing says investors have indicated an interest in buying shares in the IPO. Open the IPOs view to see who.", i.cornerstone)
                if (i.open > 0 && !s.optBoolean("o") && i.ipoPrice > 0 && (i.big || s.optBoolean("big"))) {
                    ev("ipo:${i.key}:open", "${i.symbol.ifBlank { i.name }} opened at \$${fmt(i.open)}",
                        String.format(Locale.US, "%+.0f%% vs its \$%s IPO price. Now \$%s.", (i.open / i.ipoPrice - 1) * 100, fmt(i.ipoPrice), fmt(i.now)), link)
                }
                if (i.mentions >= 50 && !s.optBoolean("b")) ev("ipo:${i.key}:buzz", "${i.symbol} is trending on Reddit",
                    "${i.mentions} mentions in the last 24 hours across stock subreddits.", "https://apewisdom.io/stocks/${i.symbol}/")
            }
            val prev = s ?: JSONObject()
            state.put(i.key, JSONObject().put("s", i.status).put("l", i.low).put("h", i.high).put("sh", i.shares)
                .put("big", i.big || prev.optBoolean("big")).put("c", i.cornerstone.isNotBlank() || prev.optBoolean("c"))
                .put("o", i.open > 0 || prev.optBoolean("o")).put("b", i.mentions >= 50 || prev.optBoolean("b")).put("t", now))
        }

        val newFiled = r.filings.filter { !state.has("f:" + it.cik) }
        newFiled.forEach { state.put("f:" + it.cik, JSONObject().put("t", now)) }
        if (store.ipoFilingAlerts && !first) {
            if (newFiled.size > 2) ev("ipo:filed:${now}", "${newFiled.size} companies filed to go public",
                newFiled.take(5).joinToString(", ") { it.name } + ". Open the IPOs view to see them.", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=S-1")
            else newFiled.forEach { f -> ev("ipo:filed:${f.cik}", "${f.name} filed to go public",
                "${f.form} filed with the SEC ${f.date}. No price or date yet; this is the earliest public sign.", f.link) }
        }

        // Forget IPOs not seen for 60 days.
        val stale = state.keys().asSequence().filter { now - (state.optJSONObject(it)?.optLong("t") ?: now) > 60L * 86_400_000 }.toList()
        stale.forEach { state.remove(it) }
        store.prefs.edit().putString("ipo_state", state.toString()).apply()
        if (first) return emptyList()
        saveLog(store, events)
        return events
    }

    fun log(store: Store): List<IpoEvent> = runCatching {
        val a = JSONArray(store.prefs.getString("ipo_log", "[]") ?: "[]")
        List(a.length()) { val o = a.getJSONObject(it); IpoEvent(o.optString("id"), o.optString("ti"), o.optString("b"), o.optString("l"), o.optLong("t")) }
    }.getOrDefault(emptyList())

    private fun saveLog(store: Store, new: List<IpoEvent>) {
        if (new.isEmpty()) return
        val all = (new + log(store)).distinctBy { it.id }.take(40)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("id", it.id).put("ti", it.title).put("b", it.body).put("l", it.link).put("t", it.time)) }
        store.prefs.edit().putString("ipo_log", a.toString()).apply()
    }

    // ---------------- helpers ----------------

    fun fmt(v: Double): String = if (v == Math.floor(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)

    fun rangeText(low: Double, high: Double): String = when {
        high <= 0 -> ""
        low <= 0 || low == high -> "\$${fmt(high)}"
        else -> "\$${fmt(low)}–\$${fmt(high)}"
    }

    fun money(v: Double): String = when {
        v >= 1e9 -> String.format(Locale.US, "\$%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "\$%.0fM", v / 1e6)
        v > 0 -> String.format(Locale.US, "\$%,.0f", v)
        else -> "size not set"
    }

    private fun titleCase(s: String) = s.lowercase(Locale.US).split(" ").joinToString(" ") { w ->
        if (w.length <= 3 && w.none { it in "aeiouy" }) w.uppercase(Locale.US) else w.replaceFirstChar { it.titlecase(Locale.US) }
    }

    private fun http(url: String, ua: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (ua != null) conn.setRequestProperty("User-Agent", ua)
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401, 403 -> throw IOException("Finnhub key not accepted for the IPO calendar")
                else -> throw IOException("IPO calendar returned HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun ipoJson(i: Ipo) = JSONObject().put("k", i.key).put("n", i.name).put("sy", i.symbol).put("d", i.date).put("x", i.exchange)
        .put("l", i.low).put("h", i.high).put("sh", i.shares).put("v", i.value).put("s", i.status).put("c", i.cornerstone)
        .put("m", i.mentions).put("o", i.open).put("p", i.now)

    private fun ipoFrom(o: JSONObject) = Ipo(o.optString("k"), o.optString("n"), o.optString("sy"), o.optString("d"), o.optString("x"),
        o.optDouble("l"), o.optDouble("h"), o.optDouble("sh"), o.optDouble("v"), o.optString("s"), o.optString("c"),
        o.optInt("m"), o.optDouble("o"), o.optDouble("p"))

    private fun toJson(r: IpoResult) = JSONObject().put("at", r.fetchedAt)
        .put("u", JSONArray().also { a -> r.upcoming.forEach { a.put(ipoJson(it)) } })
        .put("p", JSONArray().also { a -> r.priced.forEach { a.put(ipoJson(it)) } })
        .put("f", JSONArray().also { a -> r.filings.forEach {
            a.put(JSONObject().put("k", it.key).put("n", it.name).put("ck", it.cik).put("fm", it.form).put("d", it.date)
                .put("l", it.link).put("c", it.cornerstone)) } })

    private fun fromJson(o: JSONObject): IpoResult {
        fun list(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { ipoFrom(a.getJSONObject(it)) } }
        val f = o.getJSONArray("f")
        return IpoResult(list("u"), list("p"), List(f.length()) {
            val x = f.getJSONObject(it)
            IpoFiling(x.optString("k"), x.optString("n"), x.optString("ck"), x.optString("fm"), x.optString("d"), x.optString("l"), x.optString("c"))
        }, o.optLong("at"))
    }
}
