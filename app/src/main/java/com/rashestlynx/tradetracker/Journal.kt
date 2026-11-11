package com.rashestlynx.tradetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Your track record: each stock you log as a buy, graded against the S&P 500 over the same days.
 * Entries are encrypted on the phone like your account data.
 */
data class JournalPick(
    val id: Long,             // when it was logged (ms)
    val ticker: String,
    val price: Double,        // stock price when logged
    val spy: Double,          // S&P 500 fund (SPY) price when logged
    val reason: String,
    val soldPrice: Double? = null,
    val soldSpy: Double? = null,
    val soldAt: Long? = null,
)

data class Graded(val pick: JournalPick, val now: Double?, val spyNow: Double?) {
    val endPrice get() = pick.soldPrice ?: now
    val endSpy get() = pick.soldSpy ?: spyNow
    val ret: Double? get() = endPrice?.let { it / pick.price - 1 }
    val spyRet: Double? get() = endSpy?.let { it / pick.spy - 1 }
    val beat: Boolean? get() { val r = ret ?: return null; val s = spyRet ?: return null; return r > s }
}

data class TrackRecord(val graded: List<Graded>) {
    private val scored get() = graded.filter { it.ret != null && it.spyRet != null }
    val count get() = scored.size
    val avg get() = scored.map { it.ret!! }.average()
    val spyAvg get() = scored.map { it.spyRet!! }.average()
    val wins get() = scored.count { it.beat == true }
}

/** What to look at before buying, in a few plain lines. */
data class BuyCheck(val lines: List<Pair<Boolean, String>>)

object Journal {
    fun all(ctx: Context): List<JournalPick> = try {
        val a = JSONArray(Secret.get(ctx, "journal").ifBlank { "[]" })
        List(a.length()) {
            val o = a.getJSONObject(it)
            JournalPick(o.getLong("id"), o.getString("t"), o.getDouble("p"), o.getDouble("s"), o.optString("r"),
                o.optDouble("sp").takeIf { d -> !d.isNaN() }, o.optDouble("ss").takeIf { d -> !d.isNaN() },
                o.optLong("sa").takeIf { l -> l > 0 })
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<JournalPick>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("t", it.ticker).put("p", it.price).put("s", it.spy).put("r", it.reason)
                .put("sp", it.soldPrice ?: JSONObject.NULL).put("ss", it.soldSpy ?: JSONObject.NULL).put("sa", it.soldAt ?: 0))
        }
        Secret.put(ctx, "journal", a.toString())
    }

    /** Latest price: live (Finnhub) when you have a key, otherwise the last close. Blocking. */
    fun price(store: Store, ticker: String): Double? =
        Live.quote(ticker, store)?.price?.takeIf { it > 0 } ?: StockInfo.price(ticker).first?.takeIf { it > 0 }

    /** Logs a buy at today's price. Blocking. Returns false when a price couldn't be found. */
    fun logBuy(ctx: Context, store: Store, ticker: String, reason: String): Boolean {
        val p = price(store, ticker) ?: return false
        val s = price(store, "SPY") ?: return false
        save(ctx, all(ctx) + JournalPick(System.currentTimeMillis(), ticker.uppercase(Locale.US), p, s, reason.trim()))
        return true
    }

    /** Marks a pick as sold at today's price, which freezes its result. Blocking. */
    fun markSold(ctx: Context, store: Store, id: Long): Boolean {
        val list = all(ctx)
        val pick = list.firstOrNull { it.id == id } ?: return false
        val p = price(store, pick.ticker) ?: return false
        val s = price(store, "SPY") ?: return false
        save(ctx, list.map { if (it.id == id) it.copy(soldPrice = p, soldSpy = s, soldAt = System.currentTimeMillis()) else it })
        return true
    }

    fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /** Grades every pick at today's prices. Blocking. */
    fun grade(ctx: Context, store: Store): TrackRecord {
        val picks = all(ctx).sortedByDescending { it.id }
        val needed = picks.filter { it.soldPrice == null }.map { it.ticker }.toSet()
        val prices = needed.associateWith { price(store, it) }
        val spy = if (needed.isNotEmpty()) price(store, "SPY") else null
        return TrackRecord(picks.map { Graded(it, prices[it.ticker], spy) })
    }

    /** Quick facts to look at before buying [ticker]. Uses saved data only, so it's instant. */
    fun check(ctx: Context, store: Store, ticker: String): BuyCheck {
        val t = ticker.uppercase(Locale.US)
        val lines = ArrayList<Pair<Boolean, String>>()
        // How much you already have in it.
        val owned = Accounts.owned(ctx)
        val total = owned.values.sumOf { it.second }
        owned[t]?.let { (_, value) ->
            val share = if (total > 0) value / total else 0.0
            lines.add((share <= 0.2) to String.format(Locale.US, "You already own %s of %s (%.0f%% of your money).",
                String.format(Locale.US, "\$%,.0f", value), t, share * 100))
        }
        // Top 30.
        Scores.cached(store)?.let { s ->
            val hit = listOf("growth" to s.growth, "income" to s.income, "wealth" to s.wealth)
                .firstNotNullOfOrNull { (n, l) -> l.firstOrNull { it.ticker == t }?.let { n to it.score } }
            if (hit != null) lines.add(true to "On the Top 30 for ${hit.first} (score ${hit.second}).")
        }
        // Insiders, Congress and funds in the last 90 days.
        val since = System.currentTimeMillis() - 90 * Store.DAY_MS
        val recent = store.moves().filter { it.ticker == t && it.time >= since }
        val sold = recent.filter { it.side == "sell" }
        val bought = recent.filter { it.side == "buy" }
        if (sold.isNotEmpty() || bought.isNotEmpty()) {
            val good = bought.sumOf { it.value } >= sold.sumOf { it.value }
            lines.add(good to "Last 90 days, people the app tracks: ${bought.size} buy${if (bought.size == 1) "" else "s"}, " +
                "${sold.size} sale${if (sold.size == 1) "" else "s"}.")
        }
        // Your own record so far.
        val picks = all(ctx)
        if (picks.size >= 5) lines.add(true to "You've logged ${picks.size} buys. Check Track record to see how they're doing against the S&P 500.")
        lines.add(true to "Rule of thumb: keep any one stock under about 5–10% of your money.")
        return BuyCheck(lines)
    }
}
