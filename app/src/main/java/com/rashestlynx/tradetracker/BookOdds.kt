package com.rashestlynx.tradetracker

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One sportsbook's moneyline on one side: American odds and the chance it implies (after removing the book's cut). */
data class BookLine(val book: String, val label: String, val american: Int, val fair: Double) {
    /** What $1 returns in profit if it wins. */
    val payout: Double get() = if (american > 0) american / 100.0 else 100.0 / -american
    val oddsText: String get() = if (american > 0) "+$american" else "$american"
}

data class BookGame(val home: String, val away: String, val start: String, val lines: List<BookLine>) {
    fun sideLines(label: String): List<BookLine> = lines.filter { SportsData.same(label, it.label) || it.label == label }
    /** The book paying the most on this side. */
    fun best(label: String): BookLine? = sideLines(label).maxByOrNull { it.payout }
}

/** FanDuel and DraftKings odds from The Odds API (your own free key, kept encrypted on this phone). */
object BookOdds {
    private const val KEY = "odds_api_key"
    const val SIGNUP = "https://the-odds-api.com/"
    private val SPORT = mapOf("nfl" to "americanfootball_nfl", "nba" to "basketball_nba", "nhl" to "icehockey_nhl",
        "mlb" to "baseball_mlb", "cfb" to "americanfootball_ncaaf", "cbb" to "basketball_ncaab", "epl" to "soccer_epl",
        "mls" to "soccer_usa_mls", "ucl" to "soccer_uefa_champs_league", "ufc" to "mma_mixed_martial_arts")
    private val memo = ConcurrentHashMap<String, Pair<Long, List<BookGame>>>()
    @Volatile var remaining: String = ""   // lookups left this month, from the last answer

    fun key(ctx: Context) = Secret.get(ctx, KEY)
    fun setKey(ctx: Context, k: String) = Secret.put(ctx, KEY, k.trim())

    /** All games in a league with FanDuel and DraftKings moneylines. One lookup per league, cached 30 minutes
     *  (the free plan has 500 a month). */
    fun league(ctx: Context, polyTag: String, force: Boolean = false): List<BookGame> {
        val sport = SPORT[polyTag] ?: return emptyList()
        val k = key(ctx)
        if (k.isBlank()) return emptyList()
        memo[sport]?.let { if (!force && System.currentTimeMillis() - it.first < 30 * 60_000L) return it.second }
        val url = "https://api.the-odds-api.com/v4/sports/$sport/odds?regions=us&markets=h2h&oddsFormat=american" +
            "&bookmakers=fanduel,draftkings&apiKey=${URLEncoder.encode(k, "UTF-8")}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 20_000
        val body = try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                401 -> throw Exception("The Odds API didn't accept the key. Check it in Settings.")
                429 -> throw Exception("This month's free Odds API lookups are used up.")
                else -> throw Exception("The Odds API answered HTTP ${c.responseCode}")
            }.also { remaining = c.getHeaderField("x-requests-remaining") ?: remaining }
        } finally { c.disconnect() }
        val out = parse(body)
        memo[sport] = System.currentTimeMillis() to out
        return out
    }

    fun parse(body: String): List<BookGame> {
        val arr = JSONArray(body)
        return List(arr.length()) { i ->
            val g = arr.getJSONObject(i)
            val lines = mutableListOf<BookLine>()
            val books = g.optJSONArray("bookmakers") ?: JSONArray()
            for (b in 0 until books.length()) {
                val bk = books.getJSONObject(b)
                val mk = bk.optJSONArray("markets") ?: continue
                for (m in 0 until mk.length()) {
                    val market = mk.getJSONObject(m)
                    if (market.optString("key") != "h2h") continue
                    val oc = market.optJSONArray("outcomes") ?: continue
                    val raw = List(oc.length()) { o -> oc.getJSONObject(o).let { it.optString("name") to it.optInt("price") } }
                    val implied = raw.map { (_, p) -> if (p > 0) 100.0 / (p + 100) else -p / (-p + 100.0) }
                    val total = implied.sum()
                    raw.forEachIndexed { o, (name, price) ->
                        lines += BookLine(bk.optString("title"), name, price, if (total > 0) implied[o] / total else 0.0)
                    }
                }
            }
            BookGame(g.optString("home_team"), g.optString("away_team"), g.optString("commence_time"), lines)
        }
    }

    /** The game whose two teams match these labels. */
    fun find(games: List<BookGame>, labels: List<String>): BookGame? = games.firstOrNull { g ->
        labels.filterNot { SportsData.isDraw(it) }.let { l -> l.size >= 2 &&
            l.all { x -> SportsData.same(x, g.home) || SportsData.same(x, g.away) } }
    }
}
