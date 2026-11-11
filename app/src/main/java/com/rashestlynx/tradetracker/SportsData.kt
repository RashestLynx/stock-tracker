package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** A league: its Kalshi game series and its Polymarket tag. */
data class League(val name: String, val kalshiSeries: String, val polyTag: String, val hasDraw: Boolean = false)

/** One side of a game (a team, a fighter, or the draw), with each site's chance. */
data class SportSide(val label: String, val kalshi: PredOutcome?, val poly: PredOutcome?,
                     val kalshiP: Double?, val polyP: Double?) {
    /** Best estimate: the average of the sites that have it (each one's prices adjusted to add up to 100%). */
    val best: Double get() = listOfNotNull(kalshiP, polyP).average()
}

data class SportGame(
    val title: String,
    val date: String,       // e.g. "Sun, Nov 15" (from Kalshi's ticker), or ""
    val sortKey: String,
    val sides: List<SportSide>,
    val kalshiEvent: PredEvent,
    val polyEvent: PredEvent?,
    val volume: Double,
) {
    /** Biggest gap in points between Kalshi and Polymarket on any side. */
    val gap: Double get() = sides.maxOfOrNull { s ->
        if (s.kalshiP != null && s.polyP != null) abs(s.kalshiP - s.polyP) * 100 else 0.0 } ?: 0.0
}

/**
 * Every game for a league, from Kalshi's single-game markets (checked by the build's test for each league),
 * with Polymarket's price added when it lists the same game.
 */
object SportsData {
    val LEAGUES = listOf(
        League("NFL", "KXNFLGAME", "nfl"),
        League("College football", "KXNCAAFGAME", "cfb"),
        League("NBA", "KXNBAGAME", "nba"),
        League("College basketball", "KXNCAAMBGAME", "cbb"),
        League("NHL", "KXNHLGAME", "nhl"),
        League("MLB", "KXMLBGAME", "mlb"),
        League("UFC", "KXUFCFIGHT", "ufc"),
        League("MLS", "KXMLSGAME", "mls", hasDraw = true),
        League("Premier League", "KXEPLGAME", "epl", hasDraw = true),
        League("Champions League", "KXUCLGAME", "ucl", hasDraw = true),
    )

    private val cache = HashMap<String, Pair<Long, List<SportGame>>>()

    /** Blocking. Cached for 2 minutes unless forced. */
    fun load(league: League, force: Boolean): List<SportGame> {
        cache[league.name]?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < 120_000) return list }
        val kalshi = kalshiGames(league)
        val poly = runCatching { polyGames(league) }.getOrDefault(emptyList()) +
            runCatching { Predictions.load(false) }.getOrDefault(emptyList()).filter { it.source == "Polymarket" }
        val games = kalshi.mapNotNull { build(it, poly) }
            .sortedWith(compareBy<SportGame>({ it.sortKey }).thenByDescending { it.volume })
        cache[league.name] = System.currentTimeMillis() to games
        return games
    }

    fun build(k: PredEvent, poly: List<PredEvent>): SportGame? {
        val kSides = k.all
        if (kSides.size < 2) return null
        val kTotal = kSides.sumOf { it.prob }
        if (kTotal <= 0) return null
        val p = poly.firstOrNull { matches(kSides, it.all) }
        val pTotal = p?.all?.sumOf { it.prob } ?: 0.0
        val sides = kSides.map { ko ->
            val po = p?.all?.firstOrNull { same(ko.label, it.label) }
            SportSide(ko.label, ko, po, ko.prob / kTotal, if (po != null && pTotal > 0) po.prob / pTotal else null)
        }
        // Teams first in the order Kalshi lists them in the title, the draw last.
        val ordered = sides.sortedBy { s -> if (isDraw(s.label)) Int.MAX_VALUE else k.title.indexOf(s.label).let { if (it < 0) 1000 else it } }
        val (date, key) = dateOf(k.ticker)
        return SportGame(k.title, date, key, ordered, k, p, k.volume24)
    }

    // ---------------- matching Kalshi labels ("New York G", "Ball St.") to Polymarket's ("New York Giants") ----------------
    fun isDraw(label: String) = label.lowercase(Locale.US).let { it == "tie" || it == "draw" || it.startsWith("draw ") || it.startsWith("draw(") }

    private fun words(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() }

    private fun wordMatch(a: String, b: String) = a == b || (a.length <= 3 && b.startsWith(a)) || (b.length <= 3 && a.startsWith(b))

    /** True when every word of one label is in the other ("Boston" ~ "Boston Celtics", "New York G" ~ "New York Giants"). */
    fun same(a: String, b: String): Boolean {
        if (isDraw(a) || isDraw(b)) return isDraw(a) && isDraw(b)
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        val (short, long) = if (wa.size <= wb.size) wa to wb else wb to wa
        return short.all { w -> long.any { wordMatch(w, it) } }
    }

    /** Same game: every side matches exactly one side on the other site, one to one. */
    fun matches(k: List<PredOutcome>, p: List<PredOutcome>): Boolean {
        if (k.size != p.size || k.size < 2) return false
        val used = HashSet<Int>()
        for (ko in k) {
            val hits = p.indices.filter { it !in used && same(ko.label, p[it].label) }
            if (hits.size != 1) return false
            used.add(hits[0])
        }
        return true
    }

    /** "KXNFLGAME-26NOV15CHIDET" → ("Sun, Nov 15", "2026-11-15"). */
    fun dateOf(ticker: String): Pair<String, String> {
        val m = Regex("-(\\d{2})([A-Z]{3})(\\d{2})").find(ticker) ?: return "" to "9999"
        return try {
            val d = SimpleDateFormat("yyMMMdd", Locale.US).parse(m.groupValues[1] + m.groupValues[2] + m.groupValues[3])!!
            SimpleDateFormat("EEE, MMM d", Locale.US).format(d) to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        } catch (_: Exception) {
            "" to "9999"
        }
    }

    // ---------------- data ----------------
    private fun kalshiGames(league: League): List<PredEvent> {
        var last: Exception? = null
        for (base in listOf("https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2")) {
            try {
                val out = ArrayList<PredEvent>()
                var cursor = ""
                for (page in 0 until 3) {
                    val json = JSONObject(Predictions.get("$base/events?series_ticker=${league.kalshiSeries}&status=open" +
                        "&with_nested_markets=true&limit=200" + if (cursor.isEmpty()) "" else "&cursor=$cursor"))
                    out.addAll(Predictions.parse(json, minVolume = 0.0))
                    cursor = json.optString("cursor")
                    if (cursor.isEmpty()) break
                }
                return out
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("Couldn't load ${league.name} games")
    }

    private fun polyGames(league: League): List<PredEvent> =
        Predictions.parsePolymarket(JSONArray(Predictions.get(
            "https://gamma-api.polymarket.com/events?tag_slug=${league.polyTag}&closed=false&active=true&limit=200")), minVolume = 0.0)
}
