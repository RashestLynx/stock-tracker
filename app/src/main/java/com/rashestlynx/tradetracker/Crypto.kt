package com.rashestlynx.tradetracker

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class CoinPrice(val usd: Double, val change24h: Double)
data class TreasuryCompany(val name: String, val symbol: String, val country: String, val coins: Double, val valueUsd: Double)

/** Bitcoin and Ethereum: prices, and how to spot crypto in filings and trades. */
object Crypto {
    /** Bitcoin/Ethereum funds and the best-known crypto-heavy stocks. */
    val BTC_TICKERS = setOf("IBIT", "FBTC", "GBTC", "BTC", "BITB", "ARKB", "HODL", "BRRR", "EZBC", "BTCO", "BTCW", "BITO")
    val ETH_TICKERS = setOf("ETHA", "FETH", "ETHE", "ETH", "ETHW", "CETH", "EZET", "QETH", "ETHV")
    val STOCKS = mapOf("MSTR" to "Holds Bitcoin as its main treasury asset", "COIN" to "Crypto exchange",
        "MARA" to "Bitcoin miner", "RIOT" to "Bitcoin miner")

    /** "BTC" or "ETH" if a 13F issuer name is a Bitcoin or Ethereum fund. */
    fun assetOf(issuer: String): String? {
        val n = issuer.uppercase(Locale.US)
        return when {
            "BITCOIN" in n -> "BTC"
            Regex("\\bETHER(EUM)?\\b").containsMatchIn(n) -> "ETH"
            else -> null
        }
    }

    fun tickerAsset(t: String): String? = when (t.uppercase(Locale.US)) {
        in BTC_TICKERS -> "BTC"
        in ETH_TICKERS -> "ETH"
        in STOCKS.keys -> "STOCK"
        else -> null
    }

    /** Live prices from CoinGecko's free public API. Blocking. */
    fun prices(): Map<String, CoinPrice> {
        val j = JSONObject(get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true"))
        val out = HashMap<String, CoinPrice>()
        j.optJSONObject("bitcoin")?.let { out["BTC"] = CoinPrice(it.optDouble("usd"), it.optDouble("usd_24h_change")) }
        j.optJSONObject("ethereum")?.let { out["ETH"] = CoinPrice(it.optDouble("usd"), it.optDouble("usd_24h_change")) }
        return out
    }

    /** Public companies holding the coin ("bitcoin" or "ethereum"), from CoinGecko. Blocking. */
    fun treasuries(coin: String): List<TreasuryCompany> {
        val j = JSONObject(get("https://api.coingecko.com/api/v3/companies/public_treasury/$coin"))
        val arr = j.optJSONArray("companies") ?: return emptyList()
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            TreasuryCompany(o.optString("name"), o.optString("symbol"), o.optString("country"),
                o.optDouble("total_holdings"), o.optDouble("total_current_value_usd"))
        }.sortedByDescending { it.coins }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "StockTracker-Android/2.0")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 20_000; conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("Crypto data returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
