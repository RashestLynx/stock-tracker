package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject

/** A named group inside the watchlist, with the user's own notes. */
data class WatchGroup(val name: String, val tickers: List<String>, val notes: String = "")

fun Store.loadGroups(): List<WatchGroup> = runCatching {
    val a = JSONArray(cachedText("watch_groups", Long.MAX_VALUE) ?: "[]")
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val t = o.optJSONArray("tickers") ?: JSONArray()
        WatchGroup(o.getString("name"), (0 until t.length()).map { t.getString(it) }, o.optString("notes"))
    }
}.getOrDefault(emptyList())

fun Store.saveGroups(groups: List<WatchGroup>) {
    val a = JSONArray()
    groups.forEach { g -> a.put(JSONObject().put("name", g.name).put("tickers", JSONArray(g.tickers.distinct())).put("notes", g.notes)) }
    saveText("watch_groups", a.toString())
}

/**
 * Plain-language checkup of a group: what's in it, how spread out it is, and stocks to research.
 * Built from SEC industry codes and what the people you track hold. It's general information, not advice.
 */
object Portfolio {
    /** Funds that already spread money across many companies. */
    val BROAD = mapOf(
        "VOO" to "S&P 500 index fund", "SPY" to "S&P 500 index fund", "IVV" to "S&P 500 index fund", "SPLG" to "S&P 500 index fund",
        "RSP" to "Equal-weight S&P 500 fund", "VTI" to "Total US market fund", "ITOT" to "Total US market fund", "SCHB" to "Total US market fund",
        "QQQ" to "Nasdaq-100 fund", "QQQM" to "Nasdaq-100 fund", "DIA" to "Dow 30 fund", "IWM" to "Small-company fund (Russell 2000)",
        "VT" to "Whole-world stock fund", "VXUS" to "International stock fund", "VEA" to "Developed-markets fund", "VWO" to "Emerging-markets fund",
        "SCHD" to "Dividend stock fund", "VYM" to "Dividend stock fund", "VUG" to "Large growth fund", "VTV" to "Large value fund",
        "FXAIX" to "S&P 500 index fund", "SWPPX" to "S&P 500 index fund", "VFIAX" to "S&P 500 index fund", "VTSAX" to "Total US market fund",
    )
    val BONDS = mapOf("BND" to "US bond fund", "AGG" to "US bond fund", "TLT" to "Long-term Treasury fund", "IEF" to "7-10 yr Treasury fund",
        "SHY" to "Short-term Treasury fund", "SGOV" to "Treasury bill fund", "BIL" to "Treasury bill fund", "VGIT" to "Treasury fund")
    val CRYPTO = mapOf("IBIT" to "Bitcoin fund", "FBTC" to "Bitcoin fund", "GBTC" to "Bitcoin fund", "BITO" to "Bitcoin futures fund",
        "ETHA" to "Ethereum fund", "ETHE" to "Ethereum fund", "COIN" to "Crypto exchange", "MSTR" to "Bitcoin treasury company")
    val GOLD = mapOf("GLD" to "Gold fund", "IAU" to "Gold fund", "SLV" to "Silver fund")

    fun sector(ticker: String, industry: String): String {
        val t = ticker.uppercase()
        if (t in BROAD) return "Broad index funds"
        if (t in BONDS) return "Bonds"
        if (t in CRYPTO) return "Crypto"
        if (t in GOLD) return "Gold & metals"
        val i = industry.lowercase()
        return when {
            i.isBlank() -> "Other / unknown"
            "semiconductor" in i -> "Chips"
            listOf("software", "computer", "data processing", "electronic", "internet", "business services").any { it in i } -> "Technology"
            listOf("pharmaceutical", "biological", "medical", "surgical", "health", "hospital", "diagnostic").any { it in i } -> "Healthcare"
            listOf("bank", "finance", "insurance", "credit", "security brokers", "investment", "savings").any { it in i } -> "Financials"
            listOf("petroleum", "crude", "oil", "natural gas", "drilling", "coal").any { it in i } -> "Energy"
            listOf("electric services", "gas & other", "water supply", "cogeneration").any { it in i } -> "Utilities"
            listOf("real estate", "reit").any { it in i } -> "Real estate"
            listOf("telephone", "communications", "radiotelephone", "cable", "television", "motion picture", "periodicals").any { it in i } -> "Communications & media"
            listOf("motor vehicle", "auto").any { it in i } -> "Autos"
            listOf("aircraft", "guided missile", "ordnance", "space").any { it in i } -> "Aerospace & defense"
            listOf("retail", "stores", "eating", "restaurant", "apparel", "beverage", "food", "tobacco", "soap", "cosmetic", "hotels").any { it in i } -> "Consumer"
            listOf("mining", "metal", "chemical", "paper", "steel", "gold").any { it in i } -> "Materials"
            else -> "Industrials"
        }
    }

    fun describe(ticker: String, s: StockSummary?): String {
        val t = ticker.uppercase()
        (BROAD[t] ?: BONDS[t] ?: CRYPTO[t] ?: GOLD[t])?.let { return it }
        if (s == null) return "Loading…"
        return listOf(s.name, s.industry.lowercase().replaceFirstChar { it.uppercase() }).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No details found" }
    }

    data class Idea(val ticker: String, val sector: String, val why: String)
    data class Checkup(val sectors: List<Pair<String, Int>>, val notes: List<String>, val ideas: List<Idea>, val dayMove: Double?)

    fun checkup(tickers: List<String>, info: Map<String, StockSummary?>, live: Map<String, LiveQuote>, picks: List<Pick>): Checkup {
        val sec = tickers.associateWith { sector(it, info[it]?.industry ?: "") }
        val counts = sec.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        val n = tickers.size
        val notes = mutableListOf<String>()
        val hasBroad = sec.values.any { it == "Broad index funds" }
        val singles = sec.filterValues { it !in setOf("Broad index funds", "Bonds", "Gold & metals") }.size
        if (n == 0) return Checkup(emptyList(), emptyList(), emptyList(), null)

        val top = counts.firstOrNull()
        if (top != null && n >= 3 && top.second * 2 > n && top.first != "Broad index funds")
            notes += "⚠ Concentrated: ${top.second} of $n are in ${top.first}. If that area has a bad stretch, this whole group drops together."
        if (!hasBroad && singles in 1..9)
            notes += "⚠ Only $singles individual ${if (singles == 1) "stock" else "stocks"} and no broad index fund. One bad earnings report can swing the whole group. Many investors anchor with a fund like VOO or VTI."
        if (hasBroad && singles >= 1) notes += "✓ Has a broad index fund, which already spreads money across hundreds of companies."
        if (counts.size >= 5) notes += "✓ Spread across ${counts.size} different areas."
        if (sec.values.count { it == "Crypto" } * 3 > n && n >= 2) notes += "⚠ A big share is crypto, which can move 5-10% in a day."
        if (sec.values.none { it == "Bonds" } && n >= 5) notes += "• No bonds. Bonds usually move less than stocks and soften big drops; whether you need them depends on your timeline."
        val overlap = BROAD.keys.intersect(tickers.map { it.uppercase() }.toSet())
        if (overlap.size >= 2) notes += "• ${overlap.joinToString(" and ")} hold many of the same companies, so owning both adds less spread than it looks."
        val covered = tickers.filter { t -> picks.any { it.ticker == t } }
        if (covered.isNotEmpty()) notes += "• People you track also hold or bought: ${covered.joinToString(", ")}."

        val moves = tickers.mapNotNull { live[it]?.changePct }
        val dayMove = if (moves.isNotEmpty()) moves.average() else null

        // Ideas: stocks several tracked people hold, in areas this group doesn't cover yet.
        val coveredSectors = sec.values.toSet()
        val ideas = picks.filter { it.ticker !in tickers && it.ticker.isNotBlank() }
            .groupBy { it.ticker }
            .map { (t, ps) -> t to ps.map { it.who }.distinct() }
            .sortedByDescending { it.second.size }
            .mapNotNull { (t, who) ->
                val s = sector(t, info[t]?.industry ?: "")
                if (s in coveredSectors || s == "Other / unknown") null
                else Idea(t, s, "${who.size} you track: ${who.take(2).joinToString(", ")}${if (who.size > 2) " +${who.size - 2}" else ""}")
            }
            .distinctBy { it.sector }
            .take(4)
        return Checkup(counts, notes, ideas, dayMove)
    }

    /** Tickers worth loading company info for, so ideas can be sorted by area. */
    fun ideaCandidates(tickers: List<String>, picks: List<Pick>): List<String> =
        picks.filter { it.ticker !in tickers && it.ticker.isNotBlank() }.groupBy { it.ticker }
            .entries.sortedByDescending { e -> e.value.map { it.who }.distinct().size }.take(12).map { it.key }
}
