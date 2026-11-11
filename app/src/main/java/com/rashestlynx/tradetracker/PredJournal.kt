package com.rashestlynx.tradetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One prediction you logged: you "bought" [side] of [label] at the market's price then. */
data class PredPick(
    val id: Long,           // when it was logged (ms)
    val source: String,     // "Kalshi" or "Polymarket"
    val title: String,
    val url: String,
    val label: String,
    val side: String,       // "yes" or "no"
    val price: Double,      // what one $1 contract cost then, 0-1 (the market's chance)
    val ref: String,        // see PredOutcome.ref
)

/** Where a pick stands now. won is null until the market settles. */
data class PredStatus(val pick: PredPick, val now: Double?, val won: Boolean?) {
    /** Profit per $1 staked: each contract costs [PredPick.price] and pays $1 if right, $0 if wrong.
     *  Open picks are valued at today's price. */
    val profit: Double? get() {
        if (pick.price <= 0) return null
        val value = when (won) { true -> 1.0; false -> 0.0; null -> now ?: return null }
        return value / pick.price - 1
    }
}

data class PredRecord(val picks: List<PredStatus>) {
    val settled get() = picks.filter { it.won != null }
    val wins get() = settled.count { it.won == true }
    /** How many of the settled picks the market's odds said you'd win. */
    val expected get() = settled.sumOf { it.pick.price }
    val profit get() = picks.mapNotNull { it.profit }.sum()
}

/**
 * Your prediction-market track record. Each pick is followed on Kalshi or Polymarket until it settles.
 * The fair test of skill: did you win more picks than the market's own odds said you would?
 * Saved encrypted on the phone, like your stock picks.
 */
object PredJournal {
    fun all(ctx: Context): List<PredPick> = try {
        val a = JSONArray(Secret.get(ctx, "pred_journal").ifBlank { "[]" })
        List(a.length()) {
            val o = a.getJSONObject(it)
            PredPick(o.getLong("id"), o.optString("src"), o.optString("t"), o.optString("u"), o.optString("l"),
                o.optString("s", "yes"), o.getDouble("p"), o.optString("r"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<PredPick>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("src", it.source).put("t", it.title).put("u", it.url).put("l", it.label)
                .put("s", it.side).put("p", it.price).put("r", it.ref))
        }
        Secret.put(ctx, "pred_journal", a.toString())
    }

    fun log(ctx: Context, e: PredEvent, o: PredOutcome, side: String) {
        if (o.ref.isEmpty()) return
        val price = if (side == "yes") o.prob else 1 - o.prob
        save(ctx, all(ctx) + PredPick(System.currentTimeMillis(), e.source, e.title, e.url, o.label, side, price, o.ref))
    }

    fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /** Checks each pick on its market. Blocking. */
    fun grade(ctx: Context): PredRecord =
        PredRecord(all(ctx).sortedByDescending { it.id }.map { p -> runCatching { status(p) }.getOrDefault(PredStatus(p, null, null)) })

    private fun status(p: PredPick): PredStatus {
        val parts = p.ref.split(':')
        return when (parts.firstOrNull()) {
            "k" -> {
                val ticker = parts.getOrNull(1) ?: return PredStatus(p, null, null)
                val m = kalshiMarket(ticker) ?: return PredStatus(p, null, null)
                val result = m.optString("result")
                val yesNow = Predictions.kalshiProb(m)
                val now = yesNow?.let { if (p.side == "yes") it else 1 - it }
                val won = if (result == "yes" || result == "no") result == p.side else null
                PredStatus(p, now, won)
            }
            "p" -> {
                val id = parts.getOrNull(1) ?: return PredStatus(p, null, null)
                val idx = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val m = JSONObject(Predictions.get("https://gamma-api.polymarket.com/markets/$id"))
                val prices = JSONArray(m.optString("outcomePrices").ifBlank { "[]" })
                val chosen = if (p.side == "yes") idx else 1 - idx
                val now = prices.optString(chosen).toDoubleOrNull()
                val closed = m.optBoolean("closed", false)
                val won = when {
                    !closed || now == null -> null
                    now >= 0.99 -> true
                    now <= 0.01 -> false
                    else -> null
                }
                PredStatus(p, now, won)
            }
            else -> PredStatus(p, null, null)
        }
    }

    private fun kalshiMarket(ticker: String): JSONObject? {
        for (base in listOf("https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2")) {
            try {
                return JSONObject(Predictions.get("$base/markets/$ticker")).optJSONObject("market")
            } catch (_: Exception) {
            }
        }
        return null
    }
}
