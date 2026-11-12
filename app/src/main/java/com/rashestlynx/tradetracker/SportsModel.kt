package com.rashestlynx.tradetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.pow

/** Nightly team ratings and side-by-side numbers (from sports.py), tested on seasons the model never saw. */
data class TeamRow(
    val name: String, val abbr: String, val elo: Int, val rank: Int?, val of: Int?,
    val w: Int, val l: Int, val t: Int, val pf: Double?, val pa: Double?, val home: String, val away: String,
    val l10: String, val streak: String, val sos: Int?, val trend: Int,
    val games: List<RecentGame>, val injuries: List<Injury>,
) {
    val record: String get() = if (t > 0) "$w-$l-$t" else "$w-$l"
}

data class RecentGame(val date: String, val opp: String, val pf: Int, val pa: Int, val home: Boolean)
data class Injury(val name: String, val pos: String, val status: String, val note: String)
data class CalBin(val from: Int, val to: Int, val n: Int, val said: Double, val won: Double)

/** A game in the next week: the plain rating chance, the matchup-and-weather chance (if it earned a place), and the forecast. */
data class Upcoming(val date: String, val home: String, val away: String, val venue: String, val indoor: Boolean,
                    val elo: Double, val full: Double?, val temp: Int?, val wind: Int?, val rain: Double?,
                    val rest: Double?, val form: Double?, val season: Double?, val h2h: Double?)

/** How the matchup layer did on unseen seasons: log loss (lower is better) and favorite-win rate for each version. */
data class MatchupTest(val version: String, val logloss: Double, val acc: Double)

data class LeagueModel(
    val name: String, val teams: Map<String, TeamRow>, val homeEdge: Int, val drawRate: Double?,
    val n: Int, val acc: Double?, val brier: Double?, val seasons: Int, val from: String, val to: String,
    val booksAcc: Double?, val booksBrier: Double?, val modelSameAcc: Double?, val modelSameBrier: Double?,
    val calibration: List<CalBin>,
    val upcoming: List<Upcoming> = emptyList(), val matchupUsed: String = "elo", val weatherHelped: Boolean = false,
    val matchupTests: List<MatchupTest> = emptyList(),
) {
    fun upcomingFor(a: TeamRow, b: TeamRow): Upcoming? =
        upcoming.firstOrNull { (it.home == a.name && it.away == b.name) || (it.home == b.name && it.away == a.name) }

    /** The team this label means ("Boston" -> "Boston Celtics"), if exactly one fits. */
    fun find(label: String): TeamRow? {
        val l = label.trim()
        teams.values.firstOrNull { it.abbr.equals(l, true) }?.let { return it }
        val hits = teams.values.filter { SportsData.same(l, it.name) }
        return hits.singleOrNull() ?: hits.minByOrNull { it.name.length }.takeIf { hits.size in 2..3 && hits.all { h -> h.name.contains(l, true) } }
    }

    /** Chance [a] beats [b] by rating; [aHome] true/false, or null for a neutral site. Draws for soccer. */
    fun chance(a: TeamRow, b: TeamRow, aHome: Boolean?): Triple<Double, Double, Double> {
        val edge = when (aHome) { true -> homeEdge; false -> -homeEdge; null -> 0 }
        val e = 1 / (1 + 10.0.pow(-(a.elo - b.elo + edge) / 400.0))
        val d = (drawRate ?: 0.0) / 0.95 * 4 * e * (1 - e)
        return Triple(e - d / 2, d, 1 - e - d / 2)
    }
}

data class SportsModel(val date: String, val leagues: Map<String, LeagueModel>, val kalshiBins: List<CalBin>, val kalshiN: Int)

object SportsModels {
    const val URL = "https://github.com/RashestLynx/stock-Tracker-patterns/releases/download/latest/sports.json.gz"
    @Volatile private var memo: Pair<Long, SportsModel>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "sports.json.gz")

    fun load(ctx: Context): SportsModel {
        val f = file(ctx)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 3 * 3600_000L) {
            runCatching { Nightly.download(URL, f) { parse(it) } }
                .onFailure { if (!f.exists()) throw Exception("Team ratings aren't available yet. They're built each night.") }
        }
        memo?.let { if (it.first == f.lastModified()) return it.second }
        val m = parse(GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() })
        memo = f.lastModified() to m
        return m
    }

    private fun bins(a: JSONArray?, said: String) = if (a == null) emptyList() else List(a.length()) {
        val b = a.getJSONObject(it)
        CalBin(b.optInt("from"), b.optInt("to"), b.optInt("n"), b.optDouble(said), b.optDouble("won"))
    }

    fun parse(text: String): SportsModel {
        val o = JSONObject(text)
        val lj = o.optJSONObject("leagues") ?: JSONObject()
        val leagues = HashMap<String, LeagueModel>()
        for (key in lj.keys()) {
            val l = lj.getJSONObject(key)
            val inj = l.optJSONObject("injuries") ?: JSONObject()
            val tj = l.optJSONObject("teams") ?: JSONObject()
            val teams = HashMap<String, TeamRow>()
            for (name in tj.keys()) {
                val t = tj.getJSONObject(name)
                val gj = t.optJSONArray("games") ?: JSONArray()
                val games = List(gj.length()) { i -> gj.getJSONArray(i).let {
                    RecentGame(it.getString(0), it.getString(1), it.optInt(2), it.optInt(3), it.optInt(4) == 1) } }
                val ij = inj.optJSONArray(name) ?: JSONArray()
                val injuries = List(ij.length()) { i -> ij.getJSONObject(i).let {
                    Injury(it.optString("name"), it.optString("pos"), it.optString("status"), it.optString("note")) } }
                teams[name] = TeamRow(name, t.optString("abbr"), t.optInt("elo", 1500),
                    if (t.has("rank")) t.optInt("rank") else null, if (t.has("of")) t.optInt("of") else null,
                    t.optInt("w"), t.optInt("l"), t.optInt("t"), if (t.has("pf")) t.optDouble("pf") else null,
                    if (t.has("pa")) t.optDouble("pa") else null, t.optString("home"), t.optString("away"),
                    t.optString("l10"), t.optString("streak"), if (t.has("sos")) t.optInt("sos") else null, t.optInt("trend"),
                    games, injuries)
            }
            val rec = l.optJSONObject("record") ?: JSONObject()
            val books = rec.optJSONObject("books")
            val same = rec.optJSONObject("model_same_games")
            fun d(j: JSONObject?, k: String) = if (j != null && j.has(k) && !j.isNull(k)) j.optDouble(k) else null
            leagues[key] = LeagueModel(l.optString("name"), teams, l.optInt("home_edge"),
                if (l.isNull("draw_rate")) null else l.optDouble("draw_rate"),
                rec.optInt("n"), d(rec, "acc"), d(rec, "brier"), rec.optInt("tested_seasons"), rec.optString("from"), rec.optString("to"),
                d(books, "acc"), d(books, "brier"), d(same, "acc"), d(same, "brier"), bins(rec.optJSONArray("calibration"), "said"),
                upcoming(l.optJSONArray("upcoming")), rec.optJSONObject("matchup")?.optString("used") ?: "elo",
                rec.optJSONObject("matchup")?.optBoolean("weather_helped") ?: false,
                rec.optJSONObject("matchup")?.optJSONObject("tested")?.let { t -> t.keys().asSequence().map { k ->
                    MatchupTest(k, t.getJSONObject(k).optDouble("logloss"), t.getJSONObject(k).optDouble("acc")) }.toList() } ?: emptyList())
        }
        val k = o.optJSONObject("kalshi")
        return SportsModel(o.optString("date"), leagues, bins(k?.optJSONArray("bins"), "price"), k?.optInt("n") ?: 0)
    }

    private fun upcoming(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { i ->
        val u = a.getJSONObject(i)
        val wx = u.optJSONObject("wx")
        val why = u.optJSONObject("why")
        fun d(j: JSONObject?, k: String) = if (j != null && j.has(k) && !j.isNull(k)) j.optDouble(k) else null
        Upcoming(u.optString("date"), u.optString("home"), u.optString("away"), u.optString("venue"), u.optBoolean("indoor"),
            u.optDouble("elo"), d(u, "full"), wx?.optInt("temp"), wx?.optInt("wind"), d(wx, "rain"),
            d(why, "rest"), d(why, "form"), d(why, "season"), d(why, "h2h"))
    }

    fun pct(x: Double) = String.format(Locale.US, "%.0f%%", x * 100)
}
