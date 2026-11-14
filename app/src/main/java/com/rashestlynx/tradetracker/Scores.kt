package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ScoredStock(
    val ticker: String,
    val name: String,
    val sector: String,
    val score: Int,          // 0-100
    val price: Double,
    val facts: List<String>, // short plain-English reasons, e.g. "Sales +32%"
)

data class ScoresResult(
    val priceDate: String,
    val fiscalYear: Int,
    val screened: Int,       // how many companies were scored
    val growth: List<ScoredStock>,
    val income: List<ScoredStock>,
    val wealth: List<ScoredStock>,
    val fetchedAt: Long,
)

class ScoreCompany(val cik: Long, val ticker: String, val name: String)

/** Everything the scoring math needs. Kept free of network code so it can be tested on its own. */
class ScoreInput(
    val year: Int,
    val companies: List<ScoreCompany>,
    /** One full-year number for every company: (tag, year) -> SEC company number -> value. */
    val annual: (String, Int) -> Map<Long, Double>,
    /** Latest balance-sheet number for every company. */
    val balance: (String) -> Map<Long, Double>,
    /** Ticker -> (closing price, volume) on the latest day, about a month ago and about a year ago. */
    val now: Map<String, Pair<Double, Double>>,
    val monthAgo: Map<String, Pair<Double, Double>>,
    val yearAgo: Map<String, Pair<Double, Double>>,
)

class ScoreCandidate(
    val company: ScoreCompany,
    val score: Double,
    val price: Double,
    val facts: List<String>,
    val profitPayout: Double?, // dividends as a share of profit (income list only)
)

class ScoreRanking(
    val growth: List<ScoreCandidate>,
    val income: List<ScoreCandidate>,
    val wealth: List<ScoreCandidate>,
    val screened: Int,
)

/**
 * Scores every U.S. company three ways and ranks them:
 *  - Growth: sales and profit rising fast, margins improving, price trending up.
 *  - Income: a solid dividend that has been paid for 10 years, is covered by cash, and keeps growing.
 *  - Wealth: steady compounders: high profitability, low debt, reliable growth year after year.
 *
 * Each measure is turned into a 0-100 percentile against every other company that passed the
 * basic filters, then combined with fixed weights. The filters keep out penny stocks, tiny or
 * thinly traded companies, shells and obvious data errors.
 */
object ScoreEngine {
    private val REVENUE = listOf("Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax")
    private val DIVIDENDS = listOf("PaymentsOfDividendsCommonStock", "PaymentsOfDividends")
    private const val M = 1_000_000.0

    private class Row(val c: ScoreCompany, val price: Double) {
        var marketCap = 0.0
        var dollarVolume = 0.0
        var momentum: Double? = null      // price change from a year ago to a month ago
        var change12: Double? = null      // price change over the last year
        var rev: Map<Int, Double> = emptyMap()
        var ni: Map<Int, Double> = emptyMap()
        var div: Map<Int, Double> = emptyMap()
        var revGrowth1: Double? = null
        var revCagr3: Double? = null
        var revCagr5: Double? = null
        var epsGrowth: Double? = null
        var marginTrend: Double? = null
        var fcf: Double? = null
        var fcfMargin: Double? = null
        var fcfConversion: Double? = null
        var roe: Double? = null
        var roa: Double? = null
        var grossProfitability: Double? = null
        var equityRatio: Double? = null
        var marginSwing: Double? = null
        var niCagr5: Double? = null
        var consistency: Double? = null
        var dilution: Double? = null
        var profitableYears = 0
        var yieldPct: Double? = null
        var divCagr5: Double? = null
        var divStreak: Double? = null
        var divYears = 0
        var fcfPayout: Double? = null
        var profitPayout: Double? = null
        var fcfToLiabilities: Double? = null
        var divVsAverage: Double? = null
    }

    private class Metric(val weight: Double, val higherIsBetter: Boolean, val required: Boolean, val get: (Row) -> Double?)

    fun rank(inp: ScoreInput): ScoreRanking {
        val y = inp.year
        val frames = HashMap<String, Map<Long, Double>>()
        fun fr(tag: String, year: Int) = frames.getOrPut("$tag/$year") { inp.annual(tag, year) }

        /** Year -> value, using whichever tag the company reports most consistently. */
        fun series(tags: List<String>, cik: Long, years: IntRange): Map<Int, Double> {
            var best: Map<Int, Double> = emptyMap()
            for (tag in tags) {
                val m = HashMap<Int, Double>()
                for (yr in years) fr(tag, yr)[cik]?.let { if (it.isFinite()) m[yr] = it }
                if (m.size > best.size) best = m
            }
            return best
        }
        fun one(tag: String, year: Int, cik: Long): Double? = fr(tag, year)[cik]?.takeIf { it.isFinite() }

        val assets = inp.balance("Assets")
        val equity = inp.balance("StockholdersEquity")

        val rows = ArrayList<Row>()
        for (c in inp.companies) {
            val key = c.ticker.replace('-', '.')
            val px = inp.now[key] ?: continue
            val close = px.first
            if (close < 5) continue
            val month = inp.monthAgo[key] ?: continue   // skips brand-new listings
            val r = Row(c, close)
            r.dollarVolume = minOf(close * px.second, month.first * month.second)
            inp.yearAgo[key]?.first?.takeIf { it > 0 }?.let {
                r.momentum = month.first / it - 1
                r.change12 = close / it - 1
            }

            r.rev = series(REVENUE, c.cik, (y - 5)..y)
            r.ni = series(listOf("NetIncomeLoss"), c.cik, (y - 5)..y)
            val rev = r.rev[y]
            val ni = r.ni[y] ?: continue
            // A profit far bigger than sales is almost always a mislabeled number in the filing.
            if (rev != null && abs(ni) > 1.5 * abs(rev)) continue

            val eps = one("EarningsPerShareDiluted", y, c.cik)
            val epsPrev = one("EarningsPerShareDiluted", y - 1, c.cik)
            var shares = one("WeightedAverageNumberOfDilutedSharesOutstanding", y, c.cik)
            val implied = if (eps != null && eps != 0.0 && ni / eps > 0) ni / eps else null
            if (shares == null || shares <= 0) shares = implied
            else if (implied != null && (shares / implied > 2 || shares / implied < 0.5)) shares = implied
            if (shares == null || shares <= 0) continue
            r.marketCap = shares * close
            // A company "worth" less than two years of profit means the share count or profit is wrong.
            if (ni > 0 && r.marketCap < 2 * ni) continue

            // ---- growth ----
            val revPrev = r.rev[y - 1]
            if (rev != null && revPrev != null && revPrev > 0) r.revGrowth1 = rev / revPrev - 1
            r.revCagr3 = cagr(r.rev[y - 3], rev, 3)
            r.revCagr5 = cagr(r.rev[y - 5], rev, 5)
            if (eps != null && epsPrev != null) {
                r.epsGrowth = when {
                    eps <= 0 -> -1.0
                    epsPrev <= 0 -> 0.5 // swung from a loss to a profit
                    else -> (eps / epsPrev - 1).coerceIn(-1.0, 3.0)
                }
            }
            val op = one("OperatingIncomeLoss", y, c.cik)
            val opOld = one("OperatingIncomeLoss", y - 2, c.cik)
            val revOld = r.rev[y - 2]
            if (op != null && opOld != null && rev != null && revOld != null && rev > 0 && revOld > 0) {
                r.marginTrend = op / rev - opOld / revOld
            }

            // ---- cash flow ----
            var fcfSum = 0.0
            var niSum = 0.0
            var fcfYears = 0
            for (yr in (y - 2)..y) {
                val ocf = one("NetCashProvidedByUsedInOperatingActivities", yr, c.cik) ?: continue
                val capex = one("PaymentsToAcquirePropertyPlantAndEquipment", yr, c.cik) ?: 0.0
                val n = r.ni[yr] ?: continue
                if (yr == y) r.fcf = ocf - abs(capex)
                fcfSum += ocf - abs(capex)
                niSum += n
                fcfYears++
            }
            val fcf = r.fcf
            if (fcf != null && rev != null && rev > 0) r.fcfMargin = fcf / rev
            if (fcfYears == 3 && niSum > 0) r.fcfConversion = (fcfSum / niSum).coerceIn(-1.0, 1.5)

            // ---- balance sheet ----
            val a = assets[c.cik]?.takeIf { it > 0 }
            val e = equity[c.cik]
            if (a != null) {
                r.roa = ni / a
                if (e != null) {
                    r.equityRatio = e / a
                    // Return on equity is meaningless when equity is close to zero or negative.
                    if (e / a >= 0.05) r.roe = ni / e
                    val liabilities = a - e
                    if (fcf != null) r.fcfToLiabilities = if (liabilities <= 0) 10.0 else (fcf / liabilities).coerceAtMost(10.0)
                }
                val gp = one("GrossProfit", y, c.cik) ?: run {
                    val cost = one("CostOfRevenue", y, c.cik) ?: one("CostOfGoodsAndServicesSold", y, c.cik)
                    if (rev != null && cost != null) rev - cost else null
                }
                if (gp != null) r.grossProfitability = gp / a
            }

            // ---- steadiness over the last six years ----
            r.profitableYears = r.ni.values.count { it > 0 }
            r.niCagr5 = cagr(r.ni[y - 5], ni, 5)
            val margins = ((y - 5)..y).mapNotNull { yr ->
                val rv = r.rev[yr]; val n = r.ni[yr]
                if (rv != null && n != null && rv > 0) n / rv else null
            }
            if (margins.size >= 5) r.marginSwing = stdev(margins)
            var steps = 0
            var ups = 0
            for (yr in (y - 4)..y) {
                val r0 = r.rev[yr - 1]; val r1 = r.rev[yr]
                if (r0 != null && r1 != null) { steps++; if (r1 > r0) ups++ }
                val n0 = r.ni[yr - 1]; val n1 = r.ni[yr]
                if (n0 != null && n1 != null) { steps++; if (n1 > n0) ups++ }
            }
            if (steps >= 8) r.consistency = ups.toDouble() / steps
            val sharesPrev = one("WeightedAverageNumberOfDilutedSharesOutstanding", y - 1, c.cik)
            val sharesNow = one("WeightedAverageNumberOfDilutedSharesOutstanding", y, c.cik)
            if (sharesPrev != null && sharesNow != null && sharesPrev > 0) {
                val change = sharesNow / sharesPrev - 1
                if (change > -0.3 && change < 0.4) r.dilution = change // bigger jumps are stock splits
            }

            // ---- dividends (total cash paid, so stock splits don't distort it) ----
            r.div = series(DIVIDENDS, c.cik, (y - 9)..y).mapValues { abs(it.value) }
            val d = r.div[y]
            if (d != null && d > 0) {
                r.yieldPct = d / r.marketCap
                r.divYears = r.div.values.count { it > 0 }
                if (r.div.values.any { it <= 0 }) r.divYears = 0
                r.divCagr5 = cagr(r.div[y - 5], d, 5)
                var streak = 0
                for (yr in y downTo (y - 8)) {
                    val cur = r.div[yr]; val prev = r.div[yr - 1]
                    if (cur != null && prev != null && cur >= prev * 0.97) streak++ else break
                }
                r.divStreak = streak.toDouble()
                val earlier = ((y - 4)..(y - 1)).mapNotNull { r.div[it] }
                if (earlier.isNotEmpty()) r.divVsAverage = d / earlier.average()
                if (fcf != null && fcf > 0) r.fcfPayout = d / fcf
                if (ni > 0) r.profitPayout = d / ni
            }
            rows.add(r)
        }

        // ---------------- Growth ----------------
        val growthRows = rows.filter { r ->
            val prev = r.rev[y - 1]
            val g = r.revGrowth1
            r.marketCap >= 300 * M && r.dollarVolume >= 2 * M && prev != null && prev >= 50 * M && g != null && g > 0
        }
        val growth = composite(growthRows, listOf(
            Metric(25.0, true, true) { it.revGrowth1 },
            Metric(20.0, true, false) { it.revCagr3 },
            Metric(15.0, true, false) { it.epsGrowth },
            Metric(10.0, true, false) { it.marginTrend },
            Metric(20.0, true, false) { it.momentum },
            Metric(10.0, true, false) { it.fcfMargin },
        )).map { (r, s) ->
            ScoreCandidate(r.c, s, r.price, listOfNotNull(
                r.revGrowth1?.let { "Sales ${pct(it)} in a year" },
                r.revCagr3?.let { "${pct(it)} a year over 3 years" },
                r.change12?.let { "Price ${pct(it)} in 12 months" },
            ), null)
        }

        // ---------------- Income ----------------
        val incomeRows = rows.filter { r ->
            val yld = r.yieldPct
            val payout = r.fcfPayout
            val vsAvg = r.divVsAverage
            val change = r.change12
            r.marketCap >= 1000 * M && r.dollarVolume >= 3 * M &&
                yld != null && yld >= 0.015 && yld <= 0.10 &&      // above 10% is usually a dividend about to be cut
                r.divYears >= 9 &&                                  // paid every year for a decade (one gap in the data allowed)
                (r.ni[y] ?: 0.0) > 0 &&
                payout != null && payout <= 1.0 &&                  // covered by the cash the business makes
                vsAvg != null && vsAvg >= 0.9 &&                    // not recently cut
                (change == null || change > -0.35)
        }
        val income = composite(incomeRows, listOf(
            Metric(25.0, true, true) { it.yieldPct },
            Metric(20.0, true, false) { it.divCagr5 },
            Metric(15.0, true, false) { it.divStreak },
            Metric(15.0, false, false) { it.fcfPayout },
            Metric(15.0, true, false) { it.fcfToLiabilities },
            Metric(10.0, true, false) { it.roe },
        )).map { (r, s) ->
            ScoreCandidate(r.c, s, r.price, listOfNotNull(
                r.yieldPct?.let { String.format(Locale.US, "Yield %.1f%%", it * 100) },
                r.fcfPayout?.let { String.format(Locale.US, "Pays out %.0f%% of its cash flow", it * 100) },
                r.divCagr5?.let { "Dividend ${pct(it)} a year" },
            ), r.profitPayout)
        }

        // ---------------- Wealth ----------------
        val wealthRows = rows.filter { r ->
            val now = r.rev[y]
            val old = r.rev[y - 5]
            r.marketCap >= 2000 * M && r.dollarVolume >= 5 * M &&
                r.ni.size >= 6 && r.profitableYears >= 5 && (r.ni[y] ?: 0.0) > 0 &&
                r.rev.size >= 6 && now != null && old != null && now > old && now >= 50 * M &&
                (r.equityRatio ?: 0.0) > 0
        }
        val wealth = composite(wealthRows, listOf(
            Metric(15.0, true, true) { it.roe },
            Metric(10.0, true, false) { it.roa },
            Metric(10.0, true, false) { it.grossProfitability },
            Metric(10.0, false, false) { it.marginSwing },
            Metric(10.0, true, false) { it.equityRatio },
            Metric(10.0, true, false) { it.revCagr5 },
            Metric(10.0, true, false) { it.niCagr5 },
            Metric(10.0, true, false) { it.consistency },
            Metric(10.0, true, false) { it.fcfConversion },
            Metric(5.0, false, false) { it.dilution },
        )).map { (r, s) ->
            ScoreCandidate(r.c, s, r.price, listOfNotNull(
                r.roe?.let { String.format(Locale.US, "Earns %.0f%% on shareholders' money", it * 100) },
                r.revCagr5?.let { "Sales ${pct(it)} a year over 5 years" },
                "Profitable ${r.profitableYears} of the last ${r.ni.size} years",
            ), null)
        }
        return ScoreRanking(growth, income, wealth, rows.size)
    }

    /**
     * Picks the final 10 per list. A stock appears on one list only (wealth is filled first, then
     * income, then growth), and no more than 3 per list come from the same industry group.
     * [industry] returns the SEC industry code and name, or null when unknown.
     */
    fun select(
        ranking: ScoreRanking,
        industry: (Long) -> Pair<Int, String>?,
    ): Triple<List<ScoredStock>, List<ScoredStock>, List<ScoredStock>> {
        val taken = HashSet<Long>()
        fun pick(list: List<ScoreCandidate>, isIncome: Boolean): List<ScoredStock> {
            val out = ArrayList<ScoredStock>()
            val perGroup = HashMap<Int, Int>()
            for (c in list) {
                if (out.size == 10) break
                if (c.company.cik in taken) continue
                val sic = industry(c.company.cik)
                val code = sic?.first ?: 0
                if (code == 6770) continue // blank-check shell companies
                // Property trusts are allowed to pay out more than their accounting profit.
                val payout = c.profitPayout
                if (isIncome && code != 6798 && payout != null && payout > 0.9) continue
                val group = code / 100
                if (group > 0 && (perGroup[group] ?: 0) >= 3) continue
                perGroup[group] = (perGroup[group] ?: 0) + 1
                taken.add(c.company.cik)
                out.add(ScoredStock(c.company.ticker, c.company.name, sic?.second ?: "",
                    c.score.safeRound().coerceIn(0, 100), c.price, c.facts))
            }
            return out
        }
        val wealth = pick(ranking.wealth, false)
        val income = pick(ranking.income, true)
        val growth = pick(ranking.growth, false)
        return Triple(growth, income, wealth)
    }

    /** Weighted average of each measure's percentile (0-100) among [rows], best first. */
    private fun composite(rows: List<Row>, metrics: List<Metric>): List<Pair<Row, Double>> {
        val sum = DoubleArray(rows.size)
        val weight = DoubleArray(rows.size)
        val ok = BooleanArray(rows.size) { true }
        for (m in metrics) {
            val vals = ArrayList<Pair<Int, Double>>()
            for (i in rows.indices) m.get(rows[i])?.let { if (it.isFinite()) vals.add(i to it) }
            vals.sortBy { it.second }
            val has = BooleanArray(rows.size)
            vals.forEachIndexed { k, (i, _) ->
                var p = if (vals.size == 1) 50.0 else k * 100.0 / (vals.size - 1)
                if (!m.higherIsBetter) p = 100 - p
                sum[i] += p * m.weight
                weight[i] += m.weight
                has[i] = true
            }
            if (m.required) for (i in rows.indices) if (!has[i]) ok[i] = false
        }
        val total = metrics.sumOf { it.weight }
        // Needs most of the measures present; a missing number is never counted as zero.
        return rows.indices.filter { ok[it] && weight[it] >= 0.7 * total }
            .map { rows[it] to sum[it] / weight[it] }
            .sortedByDescending { it.second }
    }

    private fun cagr(from: Double?, to: Double?, years: Int): Double? =
        if (from == null || to == null || from <= 0 || to <= 0) null else (to / from).pow(1.0 / years) - 1

    private fun stdev(v: List<Double>): Double {
        val mean = v.average()
        return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    }

    private fun pct(v: Double) = String.format(Locale.US, "%+.0f%%", v * 100)
}

/**
 * Downloads what [ScoreEngine] needs, straight from the phone:
 *  - company financials from the SEC's free "frames" data (one request returns one number for
 *    every U.S. company, so about 57 requests cover the whole market; kept for a week),
 *  - closing prices from Massive (three requests: latest day, a month ago, a year ago).
 */
object Scores {
    const val FRAME_COUNT = 57
    private const val CACHE_MS = 12 * 60 * 60 * 1000L
    private const val WEEK_MS = 7 * Store.DAY_MS
    private val FACT = Regex("\"cik\":(\\d+)[^{}]*?\"val\":(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)")

    fun cached(store: Store): ScoresResult? = try {
        store.cachedText("scores.json", 3650 * Store.DAY_MS)?.let { fromJson(JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    /** Blocking: call from a background thread. [progress] gets short status lines to show. */
    fun load(store: Store, force: Boolean, progress: (String) -> Unit): ScoresResult {
        if (!force) cached(store)?.let { if (System.currentTimeMillis() - it.fetchedAt < CACHE_MS) return it }
        val key = store.massiveKey
        if (key.isBlank()) throw IOException("Add a free Massive key first")
        if (!store.isSetUp) throw IOException("Add your email in Settings first")
        val ua = store.userAgent

        progress("Getting prices…")
        Movers.resetCalls()
        val latest = Movers.findDay(Calendar.getInstance(), key, 7)
            ?: throw IOException("No recent market data available")
        fun back(days: Int) = Calendar.getInstance().apply {
            time = Movers.parseDay(latest.first); add(Calendar.DAY_OF_YEAR, -days)
        }
        val month = Movers.findDay(back(30), key, 5) ?: throw IOException("No market data for last month")
        val year = Movers.findDay(back(365), key, 5) ?: throw IOException("No market data for last year")

        progress("Getting the company list…")
        val companies = companies(store, ua)

        var done = 0
        fun frame(tag: String, unit: String, period: String): Map<Long, Double> {
            val m = frame(store, ua, tag, unit, period)
            done++
            progress("Reading company financials… ${minOf(done, FRAME_COUNT)} of $FRAME_COUNT")
            return m
        }

        // Use last year's annual reports once most companies have filed them, otherwise the year before.
        val cal = Calendar.getInstance()
        var fy = cal.get(Calendar.YEAR) - 1
        if (frame("NetIncomeLoss", "USD", "CY$fy").size < 2500) { fy--; done = 0 }

        // Latest quarter-end balance sheets that most companies have filed (about 2 months after quarter end).
        val q = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -150) }
        fun quarter(c: Calendar) = "CY${c.get(Calendar.YEAR)}Q${c.get(Calendar.MONTH) / 3 + 1}I"
        val older = quarter(q)
        q.add(Calendar.MONTH, 3)
        val newer = quarter(q)

        val input = ScoreInput(
            year = fy,
            companies = companies,
            annual = { tag, yr ->
                val unit = when (tag) {
                    "EarningsPerShareDiluted" -> "USD-per-shares"
                    "WeightedAverageNumberOfDilutedSharesOutstanding" -> "shares"
                    else -> "USD"
                }
                frame(tag, unit, "CY$yr")
            },
            balance = { tag -> frame(tag, "USD", older) + frame(tag, "USD", newer) },
            now = latest.second, monthAgo = month.second, yearAgo = year.second,
        )
        val ranking = ScoreEngine.rank(input)
        if (ranking.screened < 500) throw IOException("Couldn't read enough company data. Try again later.")

        progress("Checking industries…")
        val sics = loadIndustries(store)
        val wanted = (ranking.wealth.take(30) + ranking.income.take(30) + ranking.growth.take(30))
            .map { it.company.cik }.distinct()
        var added = false
        for (cik in wanted) {
            if (cik in sics) continue
            try {
                val j = JSONObject(Edgar.get(String.format(Locale.US, "https://data.sec.gov/submissions/CIK%010d.json", cik), ua))
                sics[cik] = (j.optString("sic").toIntOrNull() ?: 0) to titleCase(j.optString("sicDescription"))
                added = true
            } catch (_: Exception) {
            }
        }
        if (added) saveIndustries(store, sics)

        val (growth, income, wealth) = ScoreEngine.select(ranking) { sics[it] }
        val result = ScoresResult(latest.first, fy, ranking.screened, growth, income, wealth, System.currentTimeMillis())
        store.saveText("scores.json", toJson(result))
        return result
    }

    // ---------------- SEC data ----------------
    private fun frame(store: Store, ua: String, tag: String, unit: String, period: String): Map<Long, Double> {
        val file = "frame_${tag}_$period.txt"
        val text = store.cachedText(file, WEEK_MS) ?: run {
            val body = try {
                Edgar.get("https://data.sec.gov/api/xbrl/frames/us-gaap/$tag/$unit/$period.json", ua)
            } catch (e: IOException) {
                // The SEC answers 404 when no company has reported this number for the period yet.
                if (e.message?.contains("404") == true) "" else throw e
            }
            val sb = StringBuilder()
            for (m in FACT.findAll(body)) sb.append(m.groupValues[1]).append('=').append(m.groupValues[2]).append('\n')
            sb.toString().also { store.saveText(file, it) }
        }
        val map = HashMap<Long, Double>()
        for (line in text.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val cik = line.substring(0, i).toLongOrNull() ?: continue
            val v = line.substring(i + 1).toDoubleOrNull() ?: continue
            map[cik] = v
        }
        return map
    }

    /** Companies listed on the NYSE or Nasdaq, one (main) ticker each. */
    private fun companies(store: Store, ua: String): List<ScoreCompany> {
        val body = store.cachedText("score_companies.json", WEEK_MS)
            ?: Edgar.get("https://www.sec.gov/files/company_tickers_exchange.json", ua)
                .also { store.saveText("score_companies.json", it) }
        val json = JSONObject(body)
        val fields = json.getJSONArray("fields")
        val idx = HashMap<String, Int>()
        for (i in 0 until fields.length()) idx[fields.getString(i)] = i
        val iCik = idx["cik"] ?: 0
        val iName = idx["name"] ?: 1
        val iTicker = idx["ticker"] ?: 2
        val iExchange = idx["exchange"] ?: 3
        val data = json.getJSONArray("data")
        val seen = HashSet<Long>()
        val out = ArrayList<ScoreCompany>()
        val shape = Regex("^[A-Z]{1,5}(-[A-Z])?$")
        for (i in 0 until data.length()) {
            val row = data.getJSONArray(i)
            val exchange = row.optString(iExchange).uppercase(Locale.US)
            if (exchange != "NYSE" && exchange != "NASDAQ") continue
            val ticker = row.optString(iTicker).uppercase(Locale.US)
            val cik = row.optLong(iCik)
            if (!shape.matches(ticker) || !seen.add(cik)) continue
            out.add(ScoreCompany(cik, ticker, titleCase(row.optString(iName))))
        }
        return out
    }

    private fun loadIndustries(store: Store): HashMap<Long, Pair<Int, String>> {
        val map = HashMap<Long, Pair<Int, String>>()
        val text = store.cachedText("score_industries.txt", 365 * Store.DAY_MS) ?: return map
        for (line in text.lineSequence()) {
            val p = line.split('|', limit = 3)
            if (p.size == 3) map[p[0].toLongOrNull() ?: continue] = (p[1].toIntOrNull() ?: 0) to p[2]
        }
        return map
    }

    private fun saveIndustries(store: Store, map: Map<Long, Pair<Int, String>>) {
        store.saveText("score_industries.txt",
            map.entries.joinToString("\n") { "${it.key}|${it.value.first}|${it.value.second.replace('|', ' ')}" })
    }

    private fun titleCase(s: String): String =
        if (s.any { it.isLowerCase() }) s
        else s.lowercase(Locale.US).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }

    // ---------------- cache (de)serialization ----------------
    private fun listJson(list: List<ScoredStock>) = JSONArray().also { a ->
        list.forEach { s ->
            a.put(JSONObject().put("t", s.ticker).put("n", s.name).put("i", s.sector).put("s", s.score)
                .put("p", s.price).put("f", JSONArray(s.facts)))
        }
    }

    private fun listFrom(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) {
        val o = a.getJSONObject(it)
        val f = o.optJSONArray("f")
        ScoredStock(o.optString("t"), o.optString("n"), o.optString("i"), o.optInt("s"), o.optDouble("p"),
            if (f == null) emptyList() else List(f.length()) { k -> f.optString(k) })
    }

    private fun toJson(r: ScoresResult) = JSONObject()
        .put("date", r.priceDate).put("fy", r.fiscalYear).put("n", r.screened).put("at", r.fetchedAt)
        .put("g", listJson(r.growth)).put("i", listJson(r.income)).put("w", listJson(r.wealth))
        .toString()

    private fun fromJson(o: JSONObject) = ScoresResult(
        o.optString("date"), o.optInt("fy"), o.optInt("n"),
        listFrom(o.optJSONArray("g")), listFrom(o.optJSONArray("i")), listFrom(o.optJSONArray("w")),
        o.optLong("at"),
    )
}
