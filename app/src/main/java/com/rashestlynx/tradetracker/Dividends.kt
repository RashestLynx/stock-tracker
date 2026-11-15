package com.rashestlynx.tradetracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Calendar

/** A one-time "special" cash dividend a company announced. */
data class SpecialDividend(
    val ticker: String,
    val name: String,
    val amount: Double,     // dollars a share
    val price: Double,      // latest close, 0 if unknown
    val declared: String,   // yyyy-MM-dd, "" if not given
    val exDate: String,
    val payDate: String,
)

/** A stock paying a high regular dividend. */
data class HighYield(
    val ticker: String,
    val name: String,
    val yieldPct: Double,   // yearly dividend / price, in percent
    val perYear: Double,    // dollars a share each year
    val payment: Double,    // latest payment, dollars a share
    val frequency: Int,     // payments a year
    val price: Double,
    val exDate: String,     // latest ex-dividend day
)

data class DividendsResult(val priceDate: String, val items: List<Any>, val fetchedAt: Long)

/**
 * Special dividends and high dividend yields for U.S. stocks, from Massive's (formerly Polygon.io)
 * dividends list plus one day of closing prices. Free plan: 5 requests a minute, so the high-yield
 * list (which reads every regular dividend paid in the last few months) takes a minute or two
 * and is kept for 12 hours.
 */
object Dividends {
    private const val CACHE_MS = 12 * 60 * 60 * 1000L
    private const val BASE = "https://api.massive.com/v3/reference/dividends"
    private val recent = ArrayDeque<Long>()
    private val plainTicker = Regex("^[A-Z]{1,5}$")

    /** Yields above this are flagged: a yield that high usually means the market expects a cut. */
    const val RISKY_YIELD = 10.0
    const val MIN_YIELD = 4.0

    // ---------------- special dividends ----------------

    /** Blocking: call from a background thread. Special dividends with an ex-dividend day in the last 4 months or still to come. */
    fun specials(store: Store, force: Boolean): DividendsResult {
        if (!force) cachedSpecials(store)?.let { if (fresh(it)) return it }
        val key = requireKey(store)
        val (priceDate, prices) = prices(key)
        val names = names(store)
        val since = LocalDate.now().minusDays(120)
        val rows = pages("$BASE?dividend_type=SC&ex_dividend_date.gte=$since&order=desc&sort=ex_dividend_date&limit=1000", key, 3)
        val seen = HashSet<String>()
        val list = rows.mapNotNull { o ->
            val t = o.optString("ticker")
            val amount = o.optDouble("cash_amount", 0.0)
            val ex = o.optString("ex_dividend_date")
            if (!plainTicker.matches(t) || amount <= 0 || ex.isBlank() || !usd(o)) return@mapNotNull null
            if (!seen.add("$t|$ex")) return@mapNotNull null
            val name = names?.get(t) ?: if (names == null) t else return@mapNotNull null
            SpecialDividend(t, name, amount, prices[t]?.first ?: 0.0, o.optString("declaration_date"), ex, o.optString("pay_date"))
        }.sortedByDescending { it.exDate }
        val r = DividendsResult(priceDate, list, System.currentTimeMillis())
        store.specialDividendsCache = specialsJson(r)
        return r
    }

    fun cachedSpecials(store: Store): DividendsResult? = runCatching {
        val o = JSONObject(store.specialDividendsCache.ifBlank { return null })
        val a = o.getJSONArray("i")
        DividendsResult(o.optString("d"), List(a.length()) {
            val x = a.getJSONObject(it)
            SpecialDividend(x.optString("t"), x.optString("n"), x.optDouble("a"), x.optDouble("p"),
                x.optString("dc"), x.optString("ex"), x.optString("pay"))
        }, o.optLong("at"))
    }.getOrNull()

    private fun specialsJson(r: DividendsResult) = JSONObject().put("d", r.priceDate).put("at", r.fetchedAt)
        .put("i", JSONArray().also { a ->
            r.items.filterIsInstance<SpecialDividend>().forEach {
                a.put(JSONObject().put("t", it.ticker).put("n", it.name).put("a", it.amount).put("p", it.price)
                    .put("dc", it.declared).put("ex", it.exDate).put("pay", it.payDate))
            }
        }).toString()

    // ---------------- high regular dividends ----------------

    /**
     * Blocking: call from a background thread. Each company's latest regular dividend (ex-dividend day in the
     * last ~3 months), times how many times a year it pays, divided by the latest closing price.
     */
    fun highYield(store: Store, force: Boolean, progress: (String) -> Unit = {}): DividendsResult {
        if (!force) cachedHighYield(store)?.let { if (fresh(it)) return it }
        val key = requireKey(store)
        progress("Getting today's prices…")
        val (priceDate, prices) = prices(key)
        val names = names(store)
        val since = LocalDate.now().minusDays(100)
        val rows = pages("$BASE?dividend_type=CD&ex_dividend_date.gte=$since&order=desc&sort=ex_dividend_date&limit=1000", key, 15) { n ->
            progress("Reading dividends (page $n)…")
        }
        val latest = LinkedHashMap<String, JSONObject>()
        rows.forEach { o -> val t = o.optString("ticker"); if (t !in latest) latest[t] = o } // newest first
        val list = latest.values.mapNotNull { o ->
            val t = o.optString("ticker")
            val f = o.optInt("frequency", 0)
            val amount = o.optDouble("cash_amount", 0.0)
            val (price, volume) = prices[t] ?: return@mapNotNull null
            if (!plainTicker.matches(t) || f !in setOf(1, 2, 4, 12) || amount <= 0 || !usd(o)) return@mapNotNull null
            if (price < 5 || volume < 100_000) return@mapNotNull null
            val name = names?.get(t) ?: if (names == null) t else return@mapNotNull null
            val y = amount * f / price * 100
            if (y < MIN_YIELD || y > 30) return@mapNotNull null // above 30% is almost always a data error
            HighYield(t, name, y, amount * f, amount, f, price, o.optString("ex_dividend_date"))
        }.sortedByDescending { it.yieldPct }.take(60)
        val r = DividendsResult(priceDate, list, System.currentTimeMillis())
        store.highYieldCache = highJson(r)
        return r
    }

    fun cachedHighYield(store: Store): DividendsResult? = runCatching {
        val o = JSONObject(store.highYieldCache.ifBlank { return null })
        val a = o.getJSONArray("i")
        DividendsResult(o.optString("d"), List(a.length()) {
            val x = a.getJSONObject(it)
            HighYield(x.optString("t"), x.optString("n"), x.optDouble("y"), x.optDouble("py"), x.optDouble("a"),
                x.optInt("f"), x.optDouble("p"), x.optString("ex"))
        }, o.optLong("at"))
    }.getOrNull()

    private fun highJson(r: DividendsResult) = JSONObject().put("d", r.priceDate).put("at", r.fetchedAt)
        .put("i", JSONArray().also { a ->
            r.items.filterIsInstance<HighYield>().forEach {
                a.put(JSONObject().put("t", it.ticker).put("n", it.name).put("y", it.yieldPct).put("py", it.perYear)
                    .put("a", it.payment).put("f", it.frequency).put("p", it.price).put("ex", it.exDate))
            }
        }).toString()

    fun frequencyLabel(f: Int) = when (f) { 12 -> "monthly"; 4 -> "quarterly"; 2 -> "twice a year"; 1 -> "yearly"; else -> "" }

    // ---------------- shared ----------------

    private fun fresh(r: DividendsResult) = System.currentTimeMillis() - r.fetchedAt < CACHE_MS

    private fun requireKey(store: Store): String =
        store.massiveKey.ifBlank { throw IOException("Add a free Massive key first") }

    private fun usd(o: JSONObject) = o.optString("currency", "USD").let { it.isBlank() || it == "USD" }

    private fun names(store: Store): Map<String, String>? =
        store.cachedNameMap() ?: runCatching { Edgar.tickerNames(store.userAgent).also { store.saveNameMap(it) } }.getOrNull()

    private fun prices(key: String): Pair<String, Map<String, Pair<Double, Double>>> {
        Movers.resetCalls()
        waitTurn()
        val day = Movers.findDay(Calendar.getInstance(), key, 5)
        synchronized(this) { repeat(Movers.callCount - 1) { recent.addLast(System.currentTimeMillis()) } }
        return day ?: ("" to emptyMap())
    }

    /** Follows next_url up to [maxPages] pages. */
    private fun pages(first: String, key: String, maxPages: Int, onPage: (Int) -> Unit = {}): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var url: String? = "$first&apiKey=$key"
        var n = 0
        while (url != null && n < maxPages) {
            n++
            onPage(n)
            val body = JSONObject(get(url))
            body.optJSONArray("results")?.let { a -> for (i in 0 until a.length()) out.add(a.getJSONObject(i)) }
            url = body.optString("next_url").takeIf { it.startsWith("https://") }?.let { "$it&apiKey=$key" }
        }
        return out
    }

    /** Keeps to the free plan's 5 requests a minute. */
    @Synchronized
    private fun waitTurn() {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first() > 61_000) recent.removeFirst()
        if (recent.size >= 4) Thread.sleep((61_000 - (now - recent.first())).coerceAtLeast(0))
        recent.addLast(System.currentTimeMillis())
    }

    private fun get(url: String, retried: Boolean = false): String {
        waitTurn()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401 -> throw IOException("Massive key not accepted. Check it in Settings.")
                429 -> if (!retried) { Thread.sleep(20_000); return get(url, true) } else
                    throw IOException("Massive is busy (too many requests). Try again in a minute.")
                else -> throw IOException("Dividend data returned HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
