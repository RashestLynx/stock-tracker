package com.rashestlynx.tradetracker

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Letter grades for every stock (growth, income, wealth preservation) from the nightly scan. */
data class StockGrade(
    val ticker: String, val name: String, val capM: Long,
    val growth: String, val growthPct: Int, val income: String, val incomePct: Int, val wealth: String, val wealthPct: Int,
    val whyGrowth: List<String>, val whyIncome: List<String>, val whyWealth: List<String>,
    /** Measured on past months the model never saw: chance to beat the S&P 500 over 3 months, and the typical worst drop. */
    val odds: List<Double?> = listOf(null, null, null, null), val drops: List<Double?> = listOf(null, null, null, null),
    val moat: String = "", val moatPct: Int = -1, val whyMoat: List<String> = emptyList(),
) {
    /** goal: 0 growth, 1 income, 2 wealth, 3 moat */
    fun grade(goal: Int) = when (goal) { 0 -> growth; 1 -> income; 2 -> wealth; else -> moat }
    fun pct(goal: Int) = when (goal) { 0 -> growthPct; 1 -> incomePct; 2 -> wealthPct; else -> moatPct }
    fun why(goal: Int) = when (goal) { 0 -> whyGrowth; 1 -> whyIncome; 2 -> whyWealth; else -> whyMoat }
}

data class RisingStar(val ticker: String, val name: String, val capM: Long, val industry: String, val why: String, val growth: String)

/** How stocks with each grade did over the following year, when graded by the same rules a year ago. */
data class GradeBucket(val letter: String, val n: Int, val ret: Double, val beat: Double, val drop: Double?)

data class GradesResult(
    val date: String, val fiscalYear: Int, val graded: Int, val grades: Map<String, StockGrade>, val stars: List<RisingStar>,
    val record: Map<String, List<GradeBucket>>, val spy: Double?, val spyDrop: Double?, val recordFrom: String,
    val starsRet: Double?, val starsBeat: Double?, val months: Int = 12,
    val note: String = "", val learned: Boolean = false,
) {
    fun ranked(goal: Int): List<StockGrade> =
        grades.values.filter { it.grade(goal).isNotEmpty() && (goal != 1 || it.incomePct >= 0) }.sortedByDescending { it.pct(goal) }
}

object Grades {
    const val URL = "https://github.com/RashestLynx/stock-Tracker-patterns/releases/download/latest/grades.json.gz"
    @Volatile private var memo: Pair<Long, GradesResult>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "grades.json.gz")

    fun cached(ctx: Context): GradesResult? = runCatching {
        val f = file(ctx)
        if (!f.exists()) null else memo?.takeIf { it.first == f.lastModified() }?.second ?: read(f)
    }.getOrNull()

    fun load(ctx: Context, force: Boolean = false): GradesResult {
        val f = file(ctx)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 3 * 3600_000L) cached(ctx)?.let { return it }
        try {
            Nightly.download(URL, f) { parse(it) }
        } catch (e: Exception) {
            cached(ctx)?.let { return it }
            throw Exception("Grades aren't available yet. They appear after the next nightly scan.")
        }
        return read(f)
    }

    private fun read(f: File): GradesResult {
        val r = parse(GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() })
        memo = f.lastModified() to r
        return r
    }

    fun parse(text: String): GradesResult {
        val o = JSONObject(text)
        val gj = o.getJSONObject("grades")
        val grades = HashMap<String, StockGrade>(gj.length() * 2)
        fun list(j: JSONObject?, k: String) = j?.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        for (t in gj.keys()) {
            val g = gj.getJSONObject(t)
            val why = g.optJSONObject("why")
            grades[t] = StockGrade(t, g.optString("name"), g.optLong("cap"),
                g.optString("g"), g.optInt("gp", -1), g.optString("i"), g.optInt("ip", -1), g.optString("w"), g.optInt("wp", -1),
                list(why, "g"), list(why, "i"), list(why, "w"),
                listOf("gb", "ib", "wb", "mb").map { if (g.has(it)) g.optDouble(it) else null },
                listOf("gd", "id", "wd", "md").map { if (g.has(it)) g.optDouble(it) else null },
                g.optString("m"), g.optInt("mp", -1), list(why, "m"))
        }
        val sj = o.optJSONArray("stars")
        val stars = if (sj == null) emptyList() else List(sj.length()) {
            val s = sj.getJSONObject(it)
            RisingStar(s.optString("t"), s.optString("name"), s.optLong("cap"), s.optString("industry"), s.optString("why"), s.optString("g"))
        }
        val rj = o.optJSONObject("record") ?: JSONObject()
        val record = HashMap<String, List<GradeBucket>>()
        for (goal in listOf("g", "i", "w", "m")) {
            val b = rj.optJSONObject(goal) ?: continue
            record[goal] = b.keys().asSequence().sorted().map { k ->
                val x = b.getJSONObject(k)
                GradeBucket(k, x.optInt("n"), x.optDouble("ret"), x.optDouble("beat"), if (x.isNull("dd")) null else x.optDouble("dd"))
            }.toList()
        }
        val st = rj.optJSONObject("stars")
        return GradesResult(o.optString("date"), o.optInt("fy"), o.optInt("graded"), grades, stars, record,
            if (rj.has("spy")) rj.optDouble("spy") else null, if (rj.has("spy_dd")) rj.optDouble("spy_dd") else null,
            rj.optString("from"),
            st?.let { if (it.isNull("ret")) null else it.optDouble("ret") }, st?.let { if (it.isNull("beat")) null else it.optDouble("beat") },
            rj.optInt("months", 12), rj.optString("note"), rj.optString("method") == "model")
    }

    fun money(capM: Long): String = when {
        capM >= 1_000_000 -> String.format(Locale.US, "\$%.1fT", capM / 1e6)
        capM >= 1000 -> String.format(Locale.US, "\$%.1fB", capM / 1e3)
        else -> "\$${capM}M"
    }
}
