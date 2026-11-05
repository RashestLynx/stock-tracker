package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class CongressTrade(
    val member: String,
    val memberSlug: String,
    val chamber: String,      // "house" or "senate"
    val state: String,
    val ticker: String,
    val asset: String,
    val type: String,
    val amountLow: Double,
    val amountHigh: Double,
    val amountRange: String,
    val transactionDate: String,
    val disclosureDate: String,
    val portal: String,
) {
    val isBuy: Boolean get() = type.lowercase().let { "buy" in it || "purchase" in it }
    val isSell: Boolean get() = type.lowercase().let { "sell" in it || "sale" in it }
    /** Midpoint of the disclosed range (members report ranges, not exact amounts). */
    val estValue: Double get() = if (amountHigh > amountLow) (amountLow + amountHigh) / 2 else amountLow
    val key: String get() = "cg:$memberSlug|$ticker|$transactionDate|$type|$amountRange"
}

/**
 * U.S. House and Senate STOCK Act trades via the free Bargo Congress Trades API
 * (built from official House Clerk and Senate eFD filings).
 * Keyless: 30 requests and 100 rows per day. A free key raises it to 100 requests / 1,000 rows.
 */
object Congress {
    const val SOURCE_URL = "https://www.bargo.ai/free-apis/congress"
    private const val TRADES = "https://www.bargo.ai/free-apis/congress/v1/trades"

    fun recent(limit: Int, apiKey: String): List<CongressTrade> {
        val conn = URL("$TRADES?limit=$limit").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0")
        conn.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("X-Api-Key", apiKey.trim())
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        try {
            val code = conn.responseCode
            if (code == 429) throw IOException("Congress data daily limit reached; will retry later")
            if (code !in 200..299) throw IOException("Congress data returned HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("trades") ?: return emptyList()
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CongressTrade(
                    member = o.str("member"),
                    memberSlug = o.str("member_slug").ifEmpty { o.str("member") },
                    chamber = o.str("chamber").lowercase(),
                    state = o.str("state"),
                    ticker = o.str("ticker").uppercase().let { if (it == "--" || it == "N/A") "" else it },
                    asset = o.str("asset"),
                    type = o.str("type"),
                    amountLow = if (o.isNull("amount_low")) 0.0 else o.optDouble("amount_low", 0.0),
                    amountHigh = if (o.isNull("amount_high")) 0.0 else o.optDouble("amount_high", 0.0),
                    amountRange = o.str("amount_range"),
                    transactionDate = o.str("transaction_date"),
                    disclosureDate = o.str("disclosure_date"),
                    portal = o.str("filing_portal"),
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.str(name: String): String =
        if (isNull(name)) "" else optString(name, "").trim()
}
