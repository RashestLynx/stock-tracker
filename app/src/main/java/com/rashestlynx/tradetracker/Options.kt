package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** One option contract's quote. Prices are per share; one contract covers 100 shares. */
data class OptQuote(val expiry: LocalDate, val call: Boolean, val strike: Double, val bid: Double, val ask: Double,
                    val iv: Double?, val delta: Double?, val openInterest: Int, val volume: Int) {
    val mid get() = if (ask > 0) (bid + ask) / 2 else bid
}

data class OptChain(val ticker: String, val price: Double, val asOf: String, val source: String, val quotes: List<OptQuote>) {
    fun expiries() = quotes.map { it.expiry }.distinct().sorted()
}

/** One covered call to consider: what you'd collect, and what can happen. */
data class CallIdea(
    val label: String, val q: OptQuote, val days: Int, val contracts: Int,
    val income: Double,          // premium for all your contracts, at the bid
    val yieldPct: Double,        // premium / share price, for this one call
    val annualPct: Double,       // the same, scaled to a year (if you could repeat it all year)
    val ifCalledPct: Double,     // your total gain if the shares are called away: premium + rise to the strike
    val chanceCalled: Double,    // about = delta: the options market's own odds the stock ends above the strike
    val breakeven: Double,       // price you could fall to and still be even, counting the premium
    val wideSpread: Boolean,     // bid and ask far apart: you may not get a fair price
)

/** checkedEarnings / checkedDividends: the lookup answered (so a null date really means none is coming up). */
data class StockEvents(val earnings: LocalDate?, val exDiv: LocalDate?, val dividend: Double?, val exDivGuessed: Boolean,
                       val checkedEarnings: Boolean = false, val checkedDividends: Boolean = false)

/**
 * Covered calls: sell someone the right to buy your shares at a set price (the strike) by a set date,
 * and get paid for it now (the premium). Free option prices come from CBOE's delayed quotes (15 minutes old),
 * with Nasdaq's option chain as a backup. Nothing here places a trade.
 */
object Options {
    private const val RATE = 0.04

    // ---------------- math ----------------
    private fun ncdf(x: Double): Double {
        // Abramowitz-Stegun approximation of the normal distribution
        val t = 1 / (1 + 0.2316419 * abs(x))
        val d = 0.3989423 * exp(-x * x / 2)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (x > 0) 1 - p else p
    }

    fun callPrice(s: Double, k: Double, sigma: Double, t: Double): Double {
        val sq = sigma * sqrt(t)
        val d1 = (ln(s / k) + (RATE + sigma * sigma / 2) * t) / sq
        return s * ncdf(d1) - k * exp(-RATE * t) * ncdf(d1 - sq)
    }

    fun callDelta(s: Double, k: Double, sigma: Double, t: Double): Double =
        ncdf((ln(s / k) + (RATE + sigma * sigma / 2) * t) / (sigma * sqrt(t)))

    /** The volatility that makes the price match (bisection). */
    fun impliedVol(price: Double, s: Double, k: Double, t: Double): Double? {
        if (price <= 0 || t <= 0) return null
        var lo = 0.01; var hi = 4.0
        if (callPrice(s, k, hi, t) < price) return null
        repeat(60) {
            val m = (lo + hi) / 2
            if (callPrice(s, k, m, t) > price) hi = m else lo = m
        }
        return (lo + hi) / 2
    }

    // ---------------- data ----------------
    private fun http(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) StockTracker")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    @Volatile private var memo: Pair<Long, OptChain>? = null

    fun chain(ticker: String): OptChain? {
        val t = ticker.uppercase(Locale.US).trim()
        memo?.let { (at, c) -> if (c.ticker == t && System.currentTimeMillis() - at < 5 * 60_000L) return c }
        val c = runCatching { cboe(t) }.getOrNull() ?: runCatching { nasdaq(t) }.getOrNull()
        if (c != null) memo = System.currentTimeMillis() to c
        return c
    }

    private fun cboe(t: String): OptChain? {
        val body = http("https://cdn.cboe.com/api/global/delayed_quotes/options/${URLEncoder.encode(t, "UTF-8")}.json") ?: return null
        val o = JSONObject(body)
        val d = o.getJSONObject("data")
        val price = d.optDouble("current_price").takeIf { it > 0 } ?: d.optDouble("close")
        val a = d.getJSONArray("options")
        val out = ArrayList<OptQuote>(a.length())
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            val sym = x.optString("option")
            if (sym.length < 15) continue
            // standard contracts only: an adjusted contract ("KO1...", left over from a split or merger) covers other than 100 shares
            if (sym.dropLast(15).trim() != t.replace(".", "")) continue
            val tail = sym.takeLast(15)   // YYMMDD + C/P + strike x 1000 (8 digits)
            val exp = runCatching { LocalDate.parse("20" + tail.substring(0, 6), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: continue
            val call = tail[6] == 'C'
            val strike = tail.substring(7).toDoubleOrNull()?.div(1000) ?: continue
            out += OptQuote(exp, call, strike, x.optDouble("bid", 0.0), x.optDouble("ask", 0.0),
                x.optDouble("iv").takeIf { it > 0 }, x.optDouble("delta").takeIf { !it.isNaN() && x.optDouble("iv") > 0 },
                x.optDouble("open_interest", 0.0).toInt(), x.optDouble("volume", 0.0).toInt())
        }
        if (out.isEmpty() || price.isNaN()) return null
        return OptChain(t, price, o.optString("timestamp") + " UTC (15-min delay)", "CBOE", out)
    }

    private fun nasdaq(t: String): OptChain? {
        val body = http("https://api.nasdaq.com/api/quote/${URLEncoder.encode(t, "UTF-8")}/option-chain?assetclass=stocks&limit=1000" +
            "&fromdate=all&todate=undefined&excode=oprac&callput=callput&money=all&type=all") ?: return null
        val d = JSONObject(body).optJSONObject("data") ?: return null
        val price = Regex("\\$([\\d.,]+)").find(d.optString("lastTrade"))?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val rows = d.optJSONObject("table")?.optJSONArray("rows") ?: return null
        val fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
        var exp: LocalDate? = null
        val today = LocalDate.now()
        val out = ArrayList<OptQuote>()
        fun num(s: String) = s.replace(",", "").toDoubleOrNull() ?: 0.0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val g = r.optString("expirygroup")
            if (g.isNotBlank()) { exp = runCatching { LocalDate.parse(g, fmt) }.getOrNull(); continue }
            val e = exp ?: continue
            val k = num(r.optString("strike")).takeIf { it > 0 } ?: continue
            val bid = num(r.optString("c_Bid")); val ask = num(r.optString("c_Ask"))
            val years = (ChronoUnit.DAYS.between(today, e).coerceAtLeast(1)) / 365.0
            val iv = impliedVol(if (ask > 0) (bid + ask) / 2 else bid, price, k, years)
            out += OptQuote(e, true, k, bid, ask, iv, iv?.let { callDelta(price, k, it, years) },
                num(r.optString("c_Openinterest")).toInt(), num(r.optString("c_Volume")).toInt())
        }
        if (out.isEmpty()) return null
        return OptChain(t, price, "Nasdaq (delayed)", "Nasdaq", out)
    }

    /** Next earnings report (Finnhub, with your key) and next ex-dividend day (Massive, with your key). */
    fun events(ticker: String, store: Store): StockEvents {
        val t = URLEncoder.encode(ticker.uppercase(Locale.US), "UTF-8")
        val today = LocalDate.now()
        var earnings: LocalDate? = null
        var gotEarnings = false; var gotDividends = false
        val fk = Live.key(store)
        if (fk.isNotBlank()) runCatching {
            val b = http("https://finnhub.io/api/v1/calendar/earnings?symbol=$t&from=$today&to=${today.plusDays(120)}&token=$fk")
            val a = JSONObject(b ?: "{}").optJSONArray("earningsCalendar")
            if (a != null) gotEarnings = true
            if (a != null) earnings = (0 until a.length()).mapNotNull { runCatching { LocalDate.parse(a.getJSONObject(it).optString("date")) }.getOrNull() }
                .filter { !it.isBefore(today) }.minOrNull()
        }
        var exDiv: LocalDate? = null; var amount: Double? = null; var guessed = false
        val mk = store.massiveKey
        if (mk.isNotBlank()) runCatching {
            val b = http("https://api.massive.com/v3/reference/dividends?ticker=$t&order=desc&sort=ex_dividend_date&limit=6&apiKey=$mk")
            val a = JSONObject(b ?: "{}").optJSONArray("results")
            if (a != null) gotDividends = true
            if (a != null && a.length() > 0) {
                val list = (0 until a.length()).mapNotNull { i ->
                    val x = a.getJSONObject(i)
                    runCatching { LocalDate.parse(x.optString("ex_dividend_date")) to x.optDouble("cash_amount") }.getOrNull()
                }.sortedBy { it.first }
                val next = list.firstOrNull { !it.first.isBefore(today) }
                if (next != null) { exDiv = next.first; amount = next.second }
                else if (list.size >= 2) {
                    // not announced yet: assume the usual spacing between past ex-dividend days
                    val gap = ChronoUnit.DAYS.between(list[list.size - 2].first, list.last().first)
                    var g = list.last().first
                    while (g.isBefore(today)) g = g.plusDays(gap.coerceAtLeast(28))
                    exDiv = g; amount = list.last().second; guessed = true
                }
            }
        }
        return StockEvents(earnings, exDiv, amount, guessed, gotEarnings, gotDividends)
    }

    // ---------------- ideas ----------------
    /** Expirations worth looking at: about 3 to 8 weeks out (the usual sweet spot), else the nearest a week+ out. */
    fun goodExpiries(c: OptChain, today: LocalDate = LocalDate.now()): List<LocalDate> {
        val all = c.expiries().filter { ChronoUnit.DAYS.between(today, it) >= 7 }
        val sweet = all.filter { ChronoUnit.DAYS.between(today, it) in 21..60 }
        return sweet.ifEmpty { all.take(3) }
    }

    fun defaultExpiry(c: OptChain, today: LocalDate = LocalDate.now()): LocalDate? =
        goodExpiries(c, today).minByOrNull { abs(ChronoUnit.DAYS.between(today, it) - 35) }

    /** Calls above today's price for one expiration, each worked out for your shares. */
    fun calls(c: OptChain, expiry: LocalDate, shares: Double, today: LocalDate = LocalDate.now()): List<CallIdea> {
        val days = ChronoUnit.DAYS.between(today, expiry).toInt().coerceAtLeast(1)
        val contracts = floor(shares / 100).toInt().coerceAtLeast(0)
        return c.quotes.filter { it.call && it.expiry == expiry && it.strike > c.price && it.bid >= 0.05 }
            .sortedBy { it.strike }
            .map { q ->
                val delta = q.delta ?: q.iv?.let { callDelta(c.price, q.strike, it, days / 365.0) } ?: 0.0
                val y = q.bid / c.price
                CallIdea("", q, days, contracts, q.bid * 100 * contracts, y, y * 365 / days,
                    (q.strike - c.price + q.bid) / c.price, delta, c.price - q.bid,
                    q.ask > 0 && (q.ask - q.bid) / q.mid > 0.25)
            }
            .filter { it.chanceCalled in 0.04..0.5 }
    }

    /** Three to choose from: fewer chances of losing your shares vs. more income. */
    fun picks(all: List<CallIdea>): List<CallIdea> {
        val targets = listOf(0.15 to "Safer: keep your shares more often", 0.25 to "Balanced", 0.35 to "More income, more likely called away")
        val used = HashSet<Double>()
        return targets.mapNotNull { (d, label) ->
            all.filter { it.q.strike !in used && abs(it.chanceCalled - d) <= 0.08 }.minByOrNull { abs(it.chanceCalled - d) }
                ?.also { used += it.q.strike }?.copy(label = label)
        }
    }

    /** Plain-English things to know before selling this call. */
    fun warnings(c: OptChain, idea: CallIdea, ev: StockEvents?, grade: StockGrade?, shares: Double): List<String> {
        val w = ArrayList<String>()
        val f = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        if (shares < 100) w += "You need 100 shares for each call. You have ${shares.toInt()}; 100 shares cost about " +
            money(c.price * 100) + " today."
        ev?.earnings?.let { e ->
            if (!e.isAfter(idea.q.expiry)) w += "Earnings come out ${e.format(f)}, before this call expires. Big jumps often happen on " +
                "earnings day; a jump above the strike means your shares get sold at the strike."
        }
        ev?.exDiv?.let { x ->
            if (!x.isAfter(idea.q.expiry)) {
                val amt = ev.dividend?.let { " (${money(it)} a share)" } ?: ""
                w += "Ex-dividend day ${if (ev.exDivGuessed) "is usually around" else "is"} ${x.format(f)}$amt, before expiry. You keep the " +
                    "dividend if you still own the shares that morning, but if the stock is above the strike by then, the buyer may " +
                    "take your shares the day before to get the dividend."
            }
        }
        val missing = listOfNotNull(
            if (ev?.checkedEarnings != true) "earnings date (needs a Finnhub key in Settings)" else null,
            if (ev?.checkedDividends != true) "ex-dividend date (needs a Massive key in Settings)" else null)
        if (missing.isNotEmpty()) w += "Couldn't check the ${missing.joinToString(" or the ")}. Look it up before selling."
        grade?.let { g ->
            if (g.growth.startsWith("A")) w += "Your Growth grade for this stock is ${g.growth}. Covered calls cap your gain at the " +
                "strike, which costs the most on strong growers. A higher strike (\"Safer\") gives up less."
        }
        if (idea.wideSpread) w += "The bid (${money(idea.q.bid)}) and ask (${money(idea.q.ask)}) are far apart. Use a limit order " +
            "near the middle (${money(idea.q.mid)}) instead of selling at the market."
        if (idea.q.openInterest < 50) w += "Few contracts are open at this strike (${idea.q.openInterest}), so prices can be less fair."
        w += "If the shares are called away, that's a sale: in a taxable account you may owe tax on the gain."
        return w
    }

    fun money(v: Double) = if (abs(v) >= 1000) String.format(Locale.US, "$%,.0f", v) else String.format(Locale.US, "$%.2f", v)
    fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)

    // ---------------- the guide ----------------
    val GUIDE: List<Pair<String, String>> = listOf(
        "What's an option?" to "A contract about 100 shares of a stock. A CALL gives its buyer the right to buy those 100 shares at a set " +
            "price (the strike) until a set date (the expiration). A PUT gives the right to sell. The price of the contract is the " +
            "premium, quoted per share: a $1.20 premium is $120 for one contract.",
        "What's a covered call?" to "You own 100 shares and SELL one call on them. The buyer pays you the premium today, and it's yours " +
            "whatever happens. In return, you promise to sell your 100 shares at the strike if the buyer asks. \"Covered\" means you " +
            "already own the shares, so you can always keep that promise.",
        "What can happen by expiration" to "1. The stock stays below the strike: the call expires worthless, you keep the premium AND " +
            "your shares, and you can sell another call.\n2. The stock ends above the strike: your shares are sold at the strike " +
            "(\"called away\"). You keep the premium plus the rise up to the strike, but you miss anything above it.\n3. The stock " +
            "falls: you still own the shares and lose on them like any owner. The premium only softens the fall a little.",
        "Picking a strike" to "Higher strike = smaller premium but less chance of losing your shares. Lower strike = more premium, more " +
            "likely called away. The app shows each strike's chance of being called away, using the options market's own estimate " +
            "(called delta). Many people sell strikes with about a 15 to 30% chance.",
        "Picking an expiration" to "Calls 3 to 8 weeks out are the usual sweet spot: they pay more per day than far-out ones and give " +
            "you a chance to adjust each month. Weekly calls pay little per trade and need constant attention.",
        "Reading the numbers" to "Income: the premium for all your contracts, at the bid (what buyers pay right now).\n" +
            "Yearly rate: the premium as a % of the stock price, scaled to a year, if you could repeat it every month. It's a rate, " +
            "not a promise.\nIf called away: your total gain if your shares are sold at the strike.\nBreak-even: how far the stock " +
            "can fall before you're behind, counting the premium.",
        "The risks" to "• You give up big gains above the strike, which hurts most on fast-rising stocks.\n• You still carry all of the " +
            "stock's downside.\n• Your shares can be taken early, most often the day before an ex-dividend day.\n• Shares called away " +
            "count as a sale for taxes.\n• Selling at the market price when bid and ask are far apart gives money away: use a limit order.",
        "What the history test says" to "The app tested selling a call every month for 10 years on each stock (see the history line on " +
            "each stock). Calls tended to beat simply holding on slow, steady stocks, and almost never on fast growers, where capping the " +
            "upside cost more than the premiums paid. They did lower the worst drops a little. It's a simulation with estimated option " +
            "prices, not a record of real trades.",
        "Ending a call early" to "You don't have to wait for expiration. You can BUY THE CALL BACK (\"buy to close\") at any time: if it " +
            "has lost most of its value, buying it back cheaply frees your shares to sell a new call. If the stock is near the strike " +
            "and you want to keep your shares, you can buy it back and sell a later one (\"rolling\").",
        "Doing it on Robinhood" to "1. Get options approval: Account → menu → Investing → Options trading. Covered calls are in " +
            "Robinhood's lowest options level.\n2. Open the stock → Trade → Trade options.\n3. Choose Sell and Call, pick the " +
            "expiration, then the strike.\n4. Use a limit price at or a bit below the middle of bid and ask, and quantity = number of " +
            "100-share lots you own.\n5. Review and submit. Robinhood holds 100 of your shares per call until it ends.",
        "Not advice" to "This explains how covered calls work and shows live prices. It isn't a recommendation to trade. Options " +
            "carry real risk; read the Options Disclosure Document your broker gives you before trading.",
    )
}
