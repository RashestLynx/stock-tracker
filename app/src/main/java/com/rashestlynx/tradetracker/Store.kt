package com.rashestlynx.tradetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Alert(val time: Long, val title: String, val body: String, val link: String)

/** Settings, seen filings and recent alerts, kept in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    var userAgent: String
        get() = prefs.getString("user_agent", "") ?: ""
        set(v) = prefs.edit().putString("user_agent", v.trim()).apply()

    var tickers: Set<String>
        get() = (prefs.getString("tickers", "") ?: "").split(",")
            .map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        set(v) = prefs.edit().putString("tickers", v.joinToString(",")).apply()

    var buysOnly: Boolean
        get() = prefs.getBoolean("buys_only", false)
        set(v) = prefs.edit().putBoolean("buys_only", v).apply()

    var minValue: Double
        get() = prefs.getFloat("min_value", 0f).toDouble()
        set(v) = prefs.edit().putFloat("min_value", v.toFloat()).apply()

    var watchForm4: Boolean
        get() = prefs.getBoolean("watch_4", true)
        set(v) = prefs.edit().putBoolean("watch_4", v).apply()

    var watch13F: Boolean
        get() = prefs.getBoolean("watch_13f", true)
        set(v) = prefs.edit().putBoolean("watch_13f", v).apply()

    var lastStatus: String
        get() = prefs.getString("last_status", "Not checked yet") ?: ""
        set(v) = prefs.edit().putString("last_status", v).apply()

    // ---- seen filings (ordered, capped so it doesn't grow forever) ----
    private fun seenList(): MutableList<String> {
        val arr = JSONArray(prefs.getString("seen", "[]"))
        return MutableList(arr.length()) { arr.getString(it) }
    }

    fun hasSeenAnything(): Boolean = seenList().isNotEmpty()

    fun isSeen(acc: String): Boolean = acc in seenList()

    fun markSeen(accs: Collection<String>) {
        if (accs.isEmpty()) return
        val list = seenList()
        list.addAll(accs.filter { it !in list })
        val trimmed = list.takeLast(3000)
        prefs.edit().putString("seen", JSONArray(trimmed).toString()).apply()
    }

    // ---- recent alerts shown in the app ----
    fun alerts(): List<Alert> {
        val arr = JSONArray(prefs.getString("alerts", "[]"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Alert(o.getLong("t"), o.getString("title"), o.getString("body"), o.getString("link"))
        }
    }

    fun addAlert(a: Alert) {
        val list = listOf(a) + alerts()
        val arr = JSONArray()
        list.take(200).forEach {
            arr.put(JSONObject().put("t", it.time).put("title", it.title)
                .put("body", it.body).put("link", it.link))
        }
        prefs.edit().putString("alerts", arr.toString()).apply()
    }

    fun clearAlerts() = prefs.edit().putString("alerts", "[]").apply()
}
