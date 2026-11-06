package com.rashestlynx.tradetracker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The sections of the feed. */
enum class Category(val id: String, val label: String) {
    SENATE("senate", "Senate"),
    HOUSE("house", "House"),
    INSIDER("insider", "Insiders"),
    FUND("fund", "Funds"),
    CHARGES("sec", "SEC charges");

    companion object {
        fun of(id: String): Category = entries.firstOrNull { it.id == id } ?: INSIDER
    }
}

/** One alert shown in the feed and as a notification. */
data class Alert(
    val time: Long,
    val category: Category,
    val ticker: String,
    val company: String,
    val headline: String,      // who traded, or the fund's name
    val lines: List<String>,   // summary lines; "Bought…"/"Sold…" lines are colored
    val link: String,
    val key: String,           // unique id used to avoid duplicates
    val person: String = "",   // person id for investor profiles (see People.kt)
) {
    val buy: Boolean get() = lines.any { it.startsWith("Bought") }
    val sell: Boolean get() = lines.any { it.startsWith("Sold") }

    val notificationTitle: String
        get() = when (category) {
            Category.FUND -> "New 13F: $headline"
            Category.CHARGES -> "SEC insider trading case"
            else -> {
                val who = when (category) {
                    Category.SENATE -> "Senator"
                    Category.HOUSE -> "Representative"
                    else -> "Insider"
                }
                val action = when {
                    buy && sell -> "bought and sold"
                    buy -> "bought"
                    sell -> "sold"
                    else -> "filed"
                }
                "${ticker.ifEmpty { company }}: $who $action"
            }
        }

    val notificationBody: String
        get() = (listOf(headline, company) + lines).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * A record that one person bought or holds one stock. Used for "Common picks".
 * action = "buy" or "hold" (13F holdings).
 */
data class Pick(
    val time: Long,
    val ticker: String,
    val company: String,
    val who: String,
    val category: Category,
    val action: String,
    val person: String = "",
)

/** One buy or sale with a dollar size, used for "Today's buy & sale". side = "buy" or "sell". */
data class Move(
    val time: Long,
    val category: Category,
    val ticker: String,
    val company: String,
    val who: String,
    val role: String,
    val side: String,
    val value: Double,
    val valueLabel: String,   // e.g. "$2.1M" or "$1,001 - $15,000"
    val link: String,
    val key: String,
    val price: Double = 0.0,  // price per share at the trade, when known
    val person: String = "",
)

/** Settings, seen filings, alerts and picks, kept in SharedPreferences. */
class Store(context: Context) {
    private val appContext = context.applicationContext
    val prefs: SharedPreferences =
        appContext.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    // ---------------- settings ----------------
    var userAgent: String
        get() = prefs.getString("user_agent", "") ?: ""
        set(v) = prefs.edit().putString("user_agent", v.trim()).apply()

    val isSetUp: Boolean get() = userAgent.contains("@")

    var tickers: Set<String>
        get() = (prefs.getString("tickers", "") ?: "").split(",")
            .map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        set(v) = prefs.edit().putString("tickers", v.joinToString(",")).apply()

    /** Stocks and funds the user keeps an eye on (separate from the alert-only ticker filter). */
    var watchlist: List<String>
        get() = (prefs.getString("watchlist", "") ?: "").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("watchlist", v.distinct().joinToString(",")).apply()

    var buysOnly: Boolean
        get() = prefs.getBoolean("buys_only", false)
        set(v) = prefs.edit().putBoolean("buys_only", v).apply()

    var minValue: Double
        get() = prefs.getFloat("min_value", 0f).toDouble()
        set(v) = prefs.edit().putFloat("min_value", v.toFloat()).apply()

    fun watching(c: Category): Boolean = prefs.getBoolean("watch_${c.id}", true)
    fun setWatching(c: Category, on: Boolean) = prefs.edit().putBoolean("watch_${c.id}", on).apply()

    var backgroundOn: Boolean
        get() = prefs.getBoolean("background_on", true)
        set(v) = prefs.edit().putBoolean("background_on", v).apply()

    var bargoKey: String
        get() = prefs.getString("bargo_key", "") ?: ""
        set(v) = prefs.edit().putString("bargo_key", v.trim()).apply()

    /** Background notifications for news and SEC filings about watchlist stocks. */
    var newsAlerts: Boolean
        get() = prefs.getBoolean("news_alerts", true)
        set(v) = prefs.edit().putBoolean("news_alerts", v).apply()

    var massiveKey: String
        // The user's own Massive key is built in; a key saved in Settings replaces it.
        get() = (prefs.getString("massive_key", "") ?: "").ifBlank { "0h37HkvcAnafUh9rx27J_3NePJpqmQpx" }
        set(v) = prefs.edit().putString("massive_key", v.trim()).apply()

    var moversCache: String
        get() = prefs.getString("movers_cache", "") ?: ""
        set(v) = prefs.edit().putString("movers_cache", v).apply()

    var lastCongressCheck: Long
        get() = prefs.getLong("last_congress", 0L)
        set(v) = prefs.edit().putLong("last_congress", v).apply()

    var lastStatus: String
        get() = prefs.getString("last_status", "Not checked yet") ?: ""
        set(v) = prefs.edit().putString("last_status", v).apply()

    // ---------------- seen items ----------------
    private fun seenList(): MutableList<String> {
        val arr = JSONArray(prefs.getString("seen", "[]"))
        return MutableList(arr.length()) { arr.getString(it) }
    }

    fun hasSeenAnything(): Boolean = seenList().isNotEmpty()

    fun seenSet(): Set<String> = seenList().toHashSet()

    fun markSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val list = seenList()
        val existing = list.toHashSet()
        keys.forEach { if (existing.add(it)) list.add(it) }
        prefs.edit().putString("seen", JSONArray(list.takeLast(5000)).toString()).apply()
    }

    // ---------------- alerts ----------------
    fun alerts(): List<Alert> {
        val arr = JSONArray(prefs.getString("alerts", "[]"))
        val out = ArrayList<Alert>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val linesArr = o.optJSONArray("lines") ?: JSONArray()
            out.add(
                Alert(
                    time = o.optLong("t"),
                    category = Category.of(o.optString("cat")),
                    ticker = o.optString("ticker"),
                    company = o.optString("company"),
                    headline = o.optString("headline"),
                    lines = List(linesArr.length()) { linesArr.getString(it) },
                    link = o.optString("link"),
                    key = o.optString("key", o.optString("link")),
                    person = o.optString("person"),
                )
            )
        }
        return out
    }

    fun addAlerts(new: List<Alert>) {
        if (new.isEmpty()) return
        val newKeys = new.map { it.key }.toHashSet()
        val list = new.sortedByDescending { it.time } + alerts().filter { it.key !in newKeys }
        val arr = JSONArray()
        list.take(600).forEach {
            arr.put(
                JSONObject()
                    .put("t", it.time).put("cat", it.category.id).put("ticker", it.ticker)
                    .put("company", it.company).put("headline", it.headline)
                    .put("lines", JSONArray(it.lines)).put("link", it.link).put("key", it.key)
                    .put("person", it.person)
            )
        }
        prefs.edit().putString("alerts", arr.toString()).apply()
    }

    fun clearAlerts() = prefs.edit().putString("alerts", "[]").apply()

    // ---------------- picks (for Common picks) ----------------
    fun picks(): List<Pick> {
        val arr = JSONArray(prefs.getString("picks", "[]"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Pick(o.optLong("t"), o.optString("ticker"), o.optString("company"),
                o.optString("who"), Category.of(o.optString("cat")), o.optString("action"),
                o.optString("person"))
        }
    }

    fun addPicks(new: List<Pick>) {
        if (new.isEmpty()) return
        val cutoff = System.currentTimeMillis() - PICK_WINDOW_MS
        // One record per person + ticker + action; newest wins.
        val merged = LinkedHashMap<String, Pick>()
        (new + picks()).filter { it.time >= cutoff && it.ticker.isNotEmpty() }.forEach { p ->
            val k = "${p.who}|${p.ticker}|${p.action}"
            val old = merged[k]
            if (old == null || p.time > old.time) merged[k] = p
        }
        val arr = JSONArray()
        merged.values.sortedByDescending { it.time }.take(6000).forEach {
            arr.put(
                JSONObject().put("t", it.time).put("ticker", it.ticker).put("company", it.company)
                    .put("who", it.who).put("cat", it.category.id).put("action", it.action)
                    .put("person", it.person)
            )
        }
        prefs.edit().putString("picks", arr.toString()).apply()
    }

    // ---------------- moves (for Today's buy & sale), kept 7 days ----------------
    fun moves(): List<Move> {
        val arr = JSONArray(prefs.getString("moves", "[]"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Move(o.optLong("t"), Category.of(o.optString("cat")), o.optString("ticker"),
                o.optString("company"), o.optString("who"), o.optString("role"),
                o.optString("side"), o.optDouble("value", 0.0), o.optString("label"),
                o.optString("link"), o.optString("key"), o.optDouble("price", 0.0),
                o.optString("person"))
        }
    }

    fun addMoves(new: List<Move>) {
        if (new.isEmpty()) return
        val cutoff = System.currentTimeMillis() - 7 * DAY_MS
        val merged = LinkedHashMap<String, Move>()
        (new + moves()).filter { it.time >= cutoff }.forEach { if (it.key !in merged) merged[it.key] = it }
        val arr = JSONArray()
        merged.values.sortedByDescending { it.time }.take(3000).forEach {
            arr.put(JSONObject().put("t", it.time).put("cat", it.category.id).put("ticker", it.ticker)
                .put("company", it.company).put("who", it.who).put("role", it.role)
                .put("side", it.side).put("value", it.value).put("label", it.valueLabel)
                .put("link", it.link).put("key", it.key).put("price", it.price)
                .put("person", it.person))
        }
        prefs.edit().putString("moves", arr.toString()).apply()
    }

    // ---------------- SEC name -> ticker map (cached in a file, refreshed weekly) ----------------
    private val tickerFile = File(appContext.filesDir, "company_tickers.json")

    fun cachedTickerMap(): Map<String, String>? {
        if (!tickerFile.exists()) return null
        if (System.currentTimeMillis() - tickerFile.lastModified() > 7 * DAY_MS) return null
        return try {
            val o = JSONObject(tickerFile.readText())
            val map = HashMap<String, String>()
            o.keys().forEach { map[it] = o.getString(it) }
            map
        } catch (e: Exception) {
            null
        }
    }

    fun saveTickerMap(map: Map<String, String>) {
        tickerFile.writeText(JSONObject(map).toString())
    }

    // ---------------- ticker -> SEC CIK number (for company profiles) ----------------
    private val cikFile = File(appContext.filesDir, "ticker_cik.json")

    fun cachedCikMap(): Map<String, Long>? {
        if (!cikFile.exists() || System.currentTimeMillis() - cikFile.lastModified() > 7 * DAY_MS) return null
        return try {
            val o = JSONObject(cikFile.readText())
            val map = HashMap<String, Long>()
            o.keys().forEach { map[it] = o.getLong(it) }
            map
        } catch (e: Exception) {
            null
        }
    }

    fun saveCikMap(map: Map<String, Long>) {
        cikFile.writeText(JSONObject(map).toString())
    }

    private val nameFile = File(appContext.filesDir, "ticker_names.json")

    fun cachedNameMap(): Map<String, String>? {
        if (!nameFile.exists() || System.currentTimeMillis() - nameFile.lastModified() > 7 * DAY_MS) return null
        return try {
            val o = JSONObject(nameFile.readText())
            val map = HashMap<String, String>()
            o.keys().forEach { map[it] = o.getString(it) }
            map
        } catch (e: Exception) {
            null
        }
    }

    fun saveNameMap(map: Map<String, String>) {
        nameFile.writeText(JSONObject(map).toString())
    }

    // ---------------- crypto fund positions seen in 13Fs ----------------
    data class CryptoPos(val fund: String, val person: String, val asset: String, val product: String,
                         val value: Double, val time: Long)

    fun cryptoPositions(): List<CryptoPos> {
        val arr = JSONArray(prefs.getString("crypto_pos", "[]"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            CryptoPos(o.optString("f"), o.optString("p"), o.optString("a"), o.optString("n"), o.optDouble("v"), o.optLong("t"))
        }
    }

    fun addCryptoPositions(new: List<CryptoPos>) {
        if (new.isEmpty()) return
        val merged = LinkedHashMap<String, CryptoPos>()
        (new + cryptoPositions()).forEach { val k = "${it.fund}|${it.product}"; if (k !in merged) merged[k] = it }
        val arr = JSONArray()
        merged.values.sortedByDescending { it.value }.take(1500).forEach {
            arr.put(JSONObject().put("f", it.fund).put("p", it.person).put("a", it.asset).put("n", it.product)
                .put("v", it.value).put("t", it.time))
        }
        prefs.edit().putString("crypto_pos", arr.toString()).apply()
    }

    // ---------------- generic cached text files (investor profile data) ----------------
    fun cachedText(name: String, maxAgeMs: Long): String? {
        val f = File(appContext.filesDir, name)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    fun saveText(name: String, text: String) {
        try { File(appContext.filesDir, name).writeText(text) } catch (_: Exception) {}
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val PICK_WINDOW_MS = 90 * DAY_MS
    }
}
