package com.rashestlynx.tradetracker

import java.net.URLEncoder
import java.util.Locale

/**
 * Opens a ticker in the user's brokerage. These are regular web links that phones hand to the
 * brokerage's app when it's installed (Robinhood supports this), and to the browser otherwise.
 */
object Broker {
    data class Choice(val name: String, val stock: String, val crypto: String)

    val CHOICES = listOf(
        Choice("Robinhood", "https://robinhood.com/stocks/{T}", "https://robinhood.com/crypto/{T}"),
        Choice("Fidelity", "https://digital.fidelity.com/prgw/digital/research/quote/dashboard/summary?symbol={T}", ""),
        Choice("Yahoo Finance", "https://finance.yahoo.com/quote/{T}", "https://finance.yahoo.com/quote/{T}-USD"),
    )
    const val CUSTOM = "Custom"

    fun name(store: Store): String = store.prefs.getString("broker_name", "Robinhood") ?: "Robinhood"

    fun set(store: Store, name: String, customTemplate: String = "") {
        store.prefs.edit().putString("broker_name", name).putString("broker_custom", customTemplate.trim()).apply()
    }

    fun customTemplate(store: Store): String = store.prefs.getString("broker_custom", "") ?: ""

    /** Link for a stock ticker, or for "BTC"/"ETH" when [crypto] is true. */
    fun link(store: Store, ticker: String, crypto: Boolean = false): String? {
        val t = URLEncoder.encode(ticker.uppercase(Locale.US), "UTF-8")
        val n = name(store)
        val template = if (n == CUSTOM) customTemplate(store)
        else CHOICES.firstOrNull { it.name == n }?.let { if (crypto) it.crypto else it.stock } ?: ""
        if (template.isBlank() || "{T}" !in template) return null
        return template.replace("{T}", t)
    }
}
