package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One person's activity in one stock. side: "buy", "sell" or "hold". */
data class WhoEntry(val who: String, val side: String, val action: String, val detail: String, val date: String, val link: String)

/**
 * For every stock: who you follow has bought, sold or holds it.
 * Sources: funds' latest two 13F filings, followed people's SEC Form 4s, and their known/disclosed holdings.
 * Heavy (many SEC requests), so it's cached for 12 hours.
 */
object WhoTraded {
    private const val FILE = "who_traded"
    private const val MAX_AGE = 12L * 60 * 60 * 1000

    fun cached(store: Store, followed: List<Followed>): Map<String, List<WhoEntry>>? {
        val raw = store.cachedText(FILE, MAX_AGE) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.optString("who") != sig(followed)) return null
            val m = o.getJSONObject("map")
            m.keys().asSequence().associateWith { t ->
                val a = m.getJSONArray(t)
                (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    WhoEntry(it.getString("w"), it.getString("s"), it.getString("a"), it.optString("d"), it.optString("t"), it.optString("l")) } }
            }
        }.getOrNull()
    }

    private fun sig(f: List<Followed>) = f.joinToString("|") { it.name }

    /** Blocking. */
    fun build(store: Store, followed: List<Followed>): Map<String, List<WhoEntry>> {
        val out = HashMap<String, MutableList<WhoEntry>>()
        fun add(t: String, e: WhoEntry) { if (t.isNotBlank()) out.getOrPut(t.uppercase(Locale.US)) { mutableListOf() }.add(e) }
        val ua = store.userAgent
        val names = store.cachedTickerMap() ?: runCatching { Edgar.companyTickers(ua).also { store.saveTickerMap(it) } }.getOrNull() ?: emptyMap()

        for (f in followed) {
            // Known and disclosed holdings (videos, interviews, Briefs).
            Following.KNOWN[f.name]?.let { k ->
                k.owns.forEach { h -> add(h.ticker, WhoEntry(f.name, "hold", "Owns", h.note, "", h.sourceUrl)) }
                if (k.disclosureFeed.isNotBlank()) runCatching { Following.disclosures(k.disclosureFeed, f.name, store) }.getOrNull()?.forEach { h ->
                    add(h.ticker, WhoEntry(f.name, "hold", "Disclosed owning", h.note, "", h.sourceUrl))
                }
            }
            // Congress members followed from their profile: their captured trades.
            if (f.person.startsWith("cg:")) store.moves().filter { it.person == f.person }.forEach { m ->
                add(m.ticker, WhoEntry(f.name, m.side, if (m.side == "buy") "Bought" else "Sold", m.valueLabel,
                    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(m.time)), m.link))
            }
            // SEC Form 4: company insiders' buys and sells.
            if (f.insider.isNotBlank()) runCatching { Following.insiderFilings(f, store, 12) }.getOrNull()?.forEach { x ->
                val text = x.lines.joinToString(" · ")
                val side = when { text.contains("Sold") || text.contains("Disposed") -> "sell"; text.contains("Bought") -> "buy"; else -> "hold" }
                val action = when (side) { "sell" -> "Sold"; "buy" -> "Bought"; else -> "Filed" }
                add(x.ticker, WhoEntry(f.name, side, action, text + if (x.ownedAfter > 0) String.format(Locale.US, " · owns %,.0f shares after", x.ownedAfter) else "", x.date, x.link))
            }
            // 13F: fund holdings now vs last quarter.
            if (f.cik.isNotBlank()) runCatching {
                val filings = Edgar.latest13Fs(f.cik, ua, 2)
                if (filings.isEmpty()) return@runCatching
                val (now, period) = filings[0]
                fun byTicker(rows: List<Edgar.Row13F>) = rows.filter { !it.option }
                    .mapNotNull { r -> names[Edgar.normalizeName(r.name)]?.let { it to r } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, r) -> r.sumOf { it.value } to r.sumOf { it.shares } }
                val a = byTicker(Edgar.rows13F(now, ua))
                val b = if (filings.size > 1) byTicker(Edgar.rows13F(filings[1].first, ua)) else emptyMap()
                for ((t, v) in a) {
                    val old = b[t]
                    val (side, action) = when {
                        b.isEmpty() -> "hold" to "Holds"
                        old == null -> "buy" to "New position"
                        old.second > 0 && (v.second - old.second) / old.second > 0.01 ->
                            "buy" to String.format(Locale.US, "Added %.0f%%", (v.second - old.second) / old.second * 100)
                        old.second > 0 && (v.second - old.second) / old.second < -0.01 ->
                            "sell" to String.format(Locale.US, "Cut %.0f%%", (old.second - v.second) / old.second * 100)
                        else -> "hold" to "Holds"
                    }
                    add(t, WhoEntry(f.name, side, action, "${money(v.first)} in 13F (quarter ending $period)", period, now.link))
                }
                for ((t, v) in b) if (t !in a)
                    add(t, WhoEntry(f.name, "sell", "Sold out", "Had ${money(v.first)} the quarter before; gone in the 13F for $period", period, now.link))
            }
        }
        val map = out.mapValues { (_, l) -> l.distinctBy { it.who + it.action + it.date }.sortedByDescending { it.date } }
        val jm = JSONObject()
        map.forEach { (t, l) ->
            jm.put(t, JSONArray(l.map { JSONObject().put("w", it.who).put("s", it.side).put("a", it.action).put("d", it.detail).put("t", it.date).put("l", it.link) }))
        }
        store.saveText(FILE, JSONObject().put("who", sig(followed)).put("map", jm).toString())
        return map
    }

    /** Everyone else the app tracks (Congress, insiders from alerts) for one ticker. */
    fun others(store: Store, ticker: String, followedNames: Set<String>): List<WhoEntry> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return store.moves().filter { it.ticker == ticker && it.who !in followedNames }
            .sortedByDescending { it.time }
            .map { m -> WhoEntry(m.who, m.side, if (m.side == "sell") "Sold" else "Bought",
                listOf(m.role, m.valueLabel).filter { it.isNotBlank() }.joinToString(" · "), f.format(Date(m.time)), m.link) }
            .distinctBy { it.who + it.action + it.date }
    }

    /**
     * Anyone else who bought or sold this stock, looked up per ticker (cached 12 hours):
     *  - members of Congress (Bargo, filtered to this ticker)
     *  - the company's own insiders: executives, directors and big owners (SEC Form 4)
     * Blocking.
     */
    fun lookup(store: Store, ticker: String): List<WhoEntry> {
        val t = ticker.uppercase(Locale.US)
        val file = "others_" + t.replace(Regex("[^A-Z0-9]"), "_")
        store.cachedText(file, MAX_AGE)?.let { raw -> runCatching { return fromJson(JSONArray(raw)) } }
        val out = mutableListOf<WhoEntry>()
        runCatching {
            Congress.recent(15, store.bargoKey, t).filter { it.ticker == t }.forEach { c ->
                val sell = c.type.lowercase().let { "sale" in it || "sell" in it }
                val chamber = if (c.chamber == "senate") "Senator" else "Representative"
                out += WhoEntry(c.member, if (sell) "sell" else "buy", if (sell) "Sold" else "Bought",
                    listOf("$chamber${if (c.state.isNotBlank()) " (${c.state})" else ""}", c.amountRange).filter { it.isNotBlank() }.joinToString(" · "),
                    c.transactionDate, Congress.SOURCE_URL)
            }
        }
        runCatching {
            val ua = store.userAgent
            val ciks = store.cachedCikMap() ?: Edgar.tickerCiks(ua).also { store.saveCikMap(it) }
            val cik = ciks[t] ?: return@runCatching
            val j = JSONObject(Edgar.get(String.format(Locale.US, "https://data.sec.gov/submissions/CIK%010d.json", cik), ua))
            val r = j.getJSONObject("filings").getJSONObject("recent")
            val forms = r.getJSONArray("form"); val accs = r.getJSONArray("accessionNumber"); val dates = r.getJSONArray("filingDate")
            var checked = 0; var found = 0
            for (i in 0 until forms.length()) {
                if (forms.getString(i) != "4") continue
                if (++checked > 15 || found >= 6) break
                val acc = accs.getString(i)
                val link = "https://www.sec.gov/Archives/edgar/data/$cik/${acc.replace("-", "")}/$acc-index.htm"
                val f4 = runCatching { Edgar.parseForm4(Filing(acc, "4", "", link), ua) }.getOrNull() ?: continue
                val buys = f4.trades.filter { it.code == "P" }; val sells = f4.trades.filter { it.code == "S" }
                if (buys.isEmpty() && sells.isEmpty()) continue   // skip grants, tax withholding etc.
                found++
                val sell = sells.sumOf { it.value } >= buys.sumOf { it.value } && sells.isNotEmpty()
                val name = f4.owners.firstOrNull()?.let(::prettyName) ?: "Insider"
                out += WhoEntry(name, if (sell) "sell" else "buy", if (sell) "Sold" else "Bought",
                    (listOfNotNull(f4.roles.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "Company insider: $it" } ?: "Company insider") +
                        Edgar.summarize(buys + sells)).joinToString(" · "), dates.getString(i), link)
            }
        }
        val list = out.sortedByDescending { it.date }
        store.saveText(file, toJson(list).toString())
        return list
    }

    /** "MUSK ELON" -> "Elon Musk". */
    private fun prettyName(raw: String): String {
        val parts = raw.trim().split(Regex("\\s+")).map { p -> p.lowercase().replaceFirstChar { it.uppercase() } }
        return if (parts.size >= 2) (parts.drop(1) + parts.first()).joinToString(" ") else parts.joinToString(" ")
    }

    private fun toJson(l: List<WhoEntry>) = JSONArray(l.map { JSONObject().put("w", it.who).put("s", it.side).put("a", it.action).put("d", it.detail).put("t", it.date).put("l", it.link) })
    private fun fromJson(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let {
        WhoEntry(it.getString("w"), it.getString("s"), it.getString("a"), it.optString("d"), it.optString("t"), it.optString("l")) } }

    fun money(v: Double): String = when {
        v >= 1e9 -> String.format(Locale.US, "\$%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "\$%.1fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.US, "\$%.0fK", v / 1e3)
        else -> String.format(Locale.US, "\$%.0f", v)
    }
}
