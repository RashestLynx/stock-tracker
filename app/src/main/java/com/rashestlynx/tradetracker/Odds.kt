package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Chance (0-1) that the home team wins, from each source that has the game. */
data class OddsGame(
    val away: String,          // "Commanders"
    val home: String,          // "Giants"
    val kickoff: String,       // "Thu 7:15 PM"
    val books: Double?,        // sportsbooks, with their built-in cut removed
    val kalshi: Double?,
    val polymarket: Double?,
    val model: Double,         // our rating model
    val kalshiUrl: String,
    val polymarketUrl: String,
    /** The matched markets, so a pick can be logged: (event, home outcome, away outcome). */
    val kalshiPick: Triple<PredEvent, PredOutcome, PredOutcome>? = null,
    val polymarketPick: Triple<PredEvent, PredOutcome, PredOutcome>? = null,
) {
    /** Biggest gap in points between a prediction market and the sportsbooks (or the other market). */
    val gap: Double get() {
        val ref = books ?: return if (kalshi != null && polymarket != null) abs(kalshi - polymarket) * 100 else 0.0
        return maxOf(kalshi?.let { abs(it - ref) } ?: 0.0, polymarket?.let { abs(it - ref) } ?: 0.0) * 100
    }
}

data class OddsResult(
    val games: List<OddsGame>,
    val modelRight: Double,    // share of winners the model picked, since 2023
    val booksRight: Double,    // share the sportsbook favorite won, same games
    val tested: Int,           // how many past games that covers
)

/**
 * NFL odds compared: sportsbooks vs Kalshi vs Polymarket vs a simple rating model.
 *
 * Game results and sportsbook lines come from the free, open nflverse data on GitHub. The model
 * is a standard "Elo" rating: every team starts equal, wins against strong teams and big margins
 * raise a rating more, home field is worth a little, and ratings drift back toward average
 * between seasons. Its past record against the sportsbooks is shown alongside it, honestly.
 */
object Odds {
    private const val GAMES_URL = "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
    private const val CACHE_MS = 6 * 60 * 60 * 1000L
    private const val K = 20.0
    private const val HOME = 30.0
    private const val REVERT = 1.0 / 3

    /** Abbreviation -> (city, nickname). Old codes are mapped to the team's current one. */
    val TEAMS = mapOf(
        "ARI" to ("Arizona" to "Cardinals"), "ATL" to ("Atlanta" to "Falcons"), "BAL" to ("Baltimore" to "Ravens"),
        "BUF" to ("Buffalo" to "Bills"), "CAR" to ("Carolina" to "Panthers"), "CHI" to ("Chicago" to "Bears"),
        "CIN" to ("Cincinnati" to "Bengals"), "CLE" to ("Cleveland" to "Browns"), "DAL" to ("Dallas" to "Cowboys"),
        "DEN" to ("Denver" to "Broncos"), "DET" to ("Detroit" to "Lions"), "GB" to ("Green Bay" to "Packers"),
        "HOU" to ("Houston" to "Texans"), "IND" to ("Indianapolis" to "Colts"), "JAX" to ("Jacksonville" to "Jaguars"),
        "KC" to ("Kansas City" to "Chiefs"), "LA" to ("Los Angeles" to "Rams"), "LAC" to ("Los Angeles" to "Chargers"),
        "LV" to ("Las Vegas" to "Raiders"), "MIA" to ("Miami" to "Dolphins"), "MIN" to ("Minnesota" to "Vikings"),
        "NE" to ("New England" to "Patriots"), "NO" to ("New Orleans" to "Saints"), "NYG" to ("New York" to "Giants"),
        "NYJ" to ("New York" to "Jets"), "PHI" to ("Philadelphia" to "Eagles"), "PIT" to ("Pittsburgh" to "Steelers"),
        "SEA" to ("Seattle" to "Seahawks"), "SF" to ("San Francisco" to "49ers"), "TB" to ("Tampa Bay" to "Buccaneers"),
        "TEN" to ("Tennessee" to "Titans"), "WAS" to ("Washington" to "Commanders"),
    )
    private val OLD = mapOf("OAK" to "LV", "SD" to "LAC", "STL" to "LA")

    private var cache: OddsResult? = null
    private var cachedAt = 0L

    /** Blocking: call from a background thread. */
    fun load(store: Store, force: Boolean): OddsResult {
        cache?.let { if (!force && System.currentTimeMillis() - cachedAt < 120_000) return it }
        val csv = store.cachedText("nfl_games.csv", if (force) 30 * 60 * 1000L else CACHE_MS)
            ?: get(GAMES_URL).also { store.saveText("nfl_games.csv", it) }
        val kalshi = runCatching { kalshiGames() }.getOrDefault(emptyList()) +
            runCatching { Predictions.load(false) }.getOrDefault(emptyList()).filter { it.source == "Kalshi" }
        val poly = runCatching { polymarketGames() }.getOrDefault(emptyList()) +
            runCatching { Predictions.load(false) }.getOrDefault(emptyList()).filter { it.source == "Polymarket" }
        val r = compute(csv, kalshi, poly, Date())
        cache = r
        cachedAt = System.currentTimeMillis()
        return r
    }

    /** All the math, kept free of network code so it can be tested on its own. */
    fun compute(csv: String, kalshi: List<PredEvent>, poly: List<PredEvent>, now: Date): OddsResult {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) throw IOException("No NFL schedule data")
        val head = splitCsv(lines[0])
        fun col(name: String) = head.indexOf(name).also { if (it < 0) throw IOException("NFL data is missing '$name'") }
        val cSeason = col("season"); val cDay = col("gameday"); val cTime = col("gametime")
        val cAway = col("away_team"); val cHome = col("home_team")
        val cAs = col("away_score"); val cHs = col("home_score"); val cLoc = col("location")
        val cAml = col("away_moneyline"); val cHml = col("home_moneyline")

        val rows = lines.drop(1).map { splitCsv(it) }.filter { it.size > maxOf(cHml, cLoc) }
            .sortedWith(compareBy({ it[cDay] }, { it[cTime] }))

        val et = TimeZone.getTimeZone("America/New_York")
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = et }
        val today = dayFmt.format(now)
        val horizon = dayFmt.format(Date(now.time + 8 * Store.DAY_MS))

        val elo = HashMap<String, Double>()
        var season = -1
        var tested = 0
        var modelHits = 0
        var bookHits = 0
        val upcoming = ArrayList<OddsGame>()
        for (r in rows) {
            val s = r[cSeason].toIntOrNull() ?: continue
            if (s != season) {
                for (k in elo.keys.toList()) elo[k] = elo[k]!! * (1 - REVERT) + 1505 * REVERT
                season = s
            }
            val a = OLD[r[cAway]] ?: r[cAway]
            val h = OLD[r[cHome]] ?: r[cHome]
            val ea = elo[a] ?: 1505.0
            val eh = elo[h] ?: 1505.0
            val diff = eh + (if (r[cLoc] == "Neutral") 0.0 else HOME) - ea
            val p = 1 / (1 + 10.0.pow(-diff / 400))
            val book = noVig(r[cHml], r[cAml])

            val hs = r[cHs].toIntOrNull()
            val aw = r[cAs].toIntOrNull()
            if (hs == null || aw == null) {
                val day = r[cDay]
                if (day >= today && day <= horizon) {
                    val km = match(kalshi, a, h)
                    val pm = match(poly, a, h)
                    upcoming.add(OddsGame(
                        away = TEAMS[a]?.second ?: a, home = TEAMS[h]?.second ?: h,
                        kickoff = kickoff(day, r[cTime], et), books = book,
                        kalshi = km?.first, polymarket = pm?.first, model = p,
                        kalshiUrl = km?.second?.first?.url ?: "", polymarketUrl = pm?.second?.first?.url ?: "",
                        kalshiPick = km?.second, polymarketPick = pm?.second,
                    ))
                }
                continue
            }
            val out = if (hs > aw) 1.0 else if (hs < aw) 0.0 else 0.5
            if (s >= 2023 && book != null && out != 0.5) {
                tested++
                if ((p > 0.5) == (out == 1.0)) modelHits++
                if ((book > 0.5) == (out == 1.0)) bookHits++
            }
            val margin = abs(hs - aw)
            val winnerDiff = if (out == 1.0) diff else -diff
            val mult = if (margin > 0) ln(margin + 1.0) * 2.2 / (winnerDiff * 0.001 + 2.2) else 1.0
            val delta = K * mult * (out - p)
            elo[h] = eh + delta
            elo[a] = ea - delta
        }
        return OddsResult(
            games = upcoming.sortedByDescending { it.gap },
            modelRight = if (tested > 0) modelHits.toDouble() / tested else 0.0,
            booksRight = if (tested > 0) bookHits.toDouble() / tested else 0.0,
            tested = tested,
        )
    }

    /** Home team's chance from the two moneylines, with the sportsbook's cut taken out. */
    private fun noVig(home: String, away: String): Double? {
        val h = implied(home.toDoubleOrNull() ?: return null)
        val a = implied(away.toDoubleOrNull() ?: return null)
        return h / (h + a)
    }

    private fun implied(ml: Double) = if (ml > 0) 100 / (ml + 100) else -ml / (-ml + 100)

    /** Finds this game in a market's list and returns the home team's chance (normalized) and a link. */
    fun market(events: List<PredEvent>, away: String, home: String): Pair<Double?, String> =
        match(events, away, home)?.let { it.first to it.second.first.url } ?: (null to "")

    /** Like [market], but also returns the matched event with its (home, away) outcomes. */
    fun match(events: List<PredEvent>, away: String, home: String): Pair<Double, Triple<PredEvent, PredOutcome, PredOutcome>>? {
        for (e in events) {
            if (e.count != 2 || e.outcomes.size != 2) continue
            val o0 = e.outcomes[0]
            val o1 = e.outcomes[1]
            val t0 = teamFor(o0.label, away, home) ?: continue
            val t1 = teamFor(o1.label, away, home) ?: continue
            if (t0 == t1) continue
            val homeO = if (t0 == home) o0 else o1
            val awayO = if (t0 == home) o1 else o0
            if (homeO.prob + awayO.prob <= 0) continue
            return homeO.prob / (homeO.prob + awayO.prob) to Triple(e, homeO, awayO)
        }
        return null
    }

    /** Which of the two teams an outcome label like "Detroit", "Lions" or "New York G" means. */
    fun teamFor(label: String, away: String, home: String): String? {
        val l = " " + label.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (Regex("\\d").containsMatchIn(l.replace("49ers", ""))) return null // spreads and totals
        val hits = listOf(away, home).filter { code ->
            val (city, nick) = TEAMS[code] ?: return@filter false
            val c = city.lowercase(Locale.US)
            val n = nick.lowercase(Locale.US)
            val sharedCity = TEAMS[if (code == away) home else away]?.first == city
            " $n " in l || l.trim() == code.lowercase(Locale.US) ||
                (!sharedCity && " $c " in l) || " $c ${n.first()} " in l
        }
        return hits.singleOrNull()
    }

    private fun kickoff(day: String, time: String, et: TimeZone): String = try {
        val parse = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = et }
        SimpleDateFormat("EEE h:mm a", Locale.US).format(parse.parse("$day $time")!!)
    } catch (_: Exception) {
        day
    }

    /** Splits one CSV line, keeping commas inside quotes. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ---------------- game markets (targeted, so they're never cut off by the general list) ----------------
    private fun kalshiGames(): List<PredEvent> {
        for (base in listOf("https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2")) {
            try {
                val json = JSONObject(get("$base/events?series_ticker=KXNFLGAME&status=open&with_nested_markets=true&limit=200"))
                return Predictions.parse(json)
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun polymarketGames(): List<PredEvent> =
        Predictions.parsePolymarket(JSONArray(get(
            "https://gamma-api.polymarket.com/events?tag_slug=nfl&closed=false&active=true&limit=100")))

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0 (personal app)")
        conn.connectTimeout = 20_000
        conn.readTimeout = 40_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("Odds data returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
