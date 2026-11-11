package com.rashestlynx.tradetracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One thing you own. kind: "stock", "fund" or "cash". */
data class OwnedHolding(
    val account: String,
    val ticker: String,
    val name: String,
    val kind: String,
    val value: Double,
    val units: Double?,
    val cost: Double?,      // average price paid per share, if the brokerage knows it
)

/** Something you typed in yourself, e.g. a T. Rowe Price retirement fund. */
data class ManualHolding(val account: String, val name: String, val ticker: String, val value: Double, val updated: Long)

data class MyMoney(val holdings: List<OwnedHolding>, val fetchedAt: Long, val error: String?) {
    val total: Double get() = holdings.sumOf { it.value }
}

/** A line in the checkup. good = true for reassuring notes, false for warnings. */
data class CheckNote(val good: Boolean, val text: String, val ticker: String = "")

/**
 * Your own accounts, read-only.
 *  - Robinhood (and other supported brokerages) through SnapTrade Personal, which is free for personal use.
 *    You paste your own key into Settings; it's encrypted on this phone and never leaves it except to sign
 *    requests to SnapTrade. The app can only read. It can't trade or move money.
 *  - T. Rowe Price isn't supported by SnapTrade, so those funds are typed in by hand.
 */
object Accounts {
    const val SIGNUP = "https://dashboard.snaptrade.com/signup"
    private const val BASE = "https://api.snaptrade.com"
    private const val CACHE_MS = 15 * 60 * 1000L

    // ---------------- saved settings ----------------
    fun clientId(ctx: Context): String = Secret.get(ctx, "snap_client")
    fun hasKey(ctx: Context): Boolean = clientId(ctx).isNotBlank() && Secret.get(ctx, "snap_key").isNotBlank()

    fun saveKey(ctx: Context, clientId: String, consumerKey: String) {
        Secret.put(ctx, "snap_client", clientId.trim())
        Secret.put(ctx, "snap_key", consumerKey.trim())
        ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().remove("cache").apply()
    }

    fun forgetKey(ctx: Context) = saveKey(ctx, "", "")

    fun manual(ctx: Context): List<ManualHolding> = try {
        val a = JSONArray(ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE).getString("manual", "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            ManualHolding(o.optString("a"), o.optString("n"), o.optString("t"), o.optDouble("v", 0.0), o.optLong("u"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveManual(ctx: Context, list: List<ManualHolding>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("a", it.account).put("n", it.name).put("t", it.ticker).put("v", it.value).put("u", it.updated)) }
        ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().putString("manual", a.toString()).apply()
    }

    // ---------------- loading ----------------
    /** Blocking: call from a background thread. Linked accounts are cached for 15 minutes unless forced. */
    fun load(ctx: Context, force: Boolean): MyMoney {
        val manual = manual(ctx).map {
            OwnedHolding(it.account, it.ticker.uppercase(Locale.US), it.name, if (it.ticker.isBlank()) "fund" else guessKind(it.ticker, it.name), it.value, null, null)
        }
        if (!hasKey(ctx)) return MyMoney(manual, System.currentTimeMillis(), null)
        val prefs = ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE)
        var linked: List<OwnedHolding>? = null
        var error: String? = null
        if (!force) {
            val at = prefs.getLong("cache_at", 0)
            if (System.currentTimeMillis() - at < CACHE_MS) linked = fromJson(prefs.getString("cache", "") ?: "")
        }
        if (linked == null) {
            try {
                linked = fetchLinked(clientId(ctx), Secret.get(ctx, "snap_key"))
                prefs.edit().putString("cache", toJson(linked)).putLong("cache_at", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach SnapTrade"
                linked = fromJson(prefs.getString("cache", "") ?: "") ?: emptyList()
            }
        }
        return MyMoney(linked + manual, System.currentTimeMillis(), error)
    }

    private fun fetchLinked(clientId: String, key: String): List<OwnedHolding> {
        val out = ArrayList<OwnedHolding>()
        val accounts = JSONArray(signedGet("/accounts", clientId, key))
        for (i in 0 until accounts.length()) {
            val acct = accounts.optJSONObject(i) ?: continue
            val id = acct.optString("id")
            val inst = acct.optString("institution_name").ifBlank { "Brokerage" }
            val label = acct.optString("name").let { if (it.isBlank() || it.contains(inst, true)) inst else "$inst $it" }
            var path = "/accounts/$id/positions/all"
            var pages = 0
            while (pages++ < 10) {
                val page = JSONObject(signedGet(path, clientId, key))
                val results = page.optJSONArray("results") ?: JSONArray()
                for (k in 0 until results.length()) {
                    val p = results.optJSONObject(k) ?: continue
                    val inst2 = p.optJSONObject("instrument") ?: JSONObject()
                    val units = num(p.opt("units")) ?: continue
                    val price = num(p.opt("price")) ?: continue
                    val ticker = inst2.optString("raw_symbol").ifBlank { inst2.optString("symbol") }.uppercase(Locale.US)
                    val cash = p.optBoolean("cash_equivalent", false)
                    val kindRaw = inst2.optString("kind").lowercase(Locale.US)
                    val kind = when {
                        cash -> "cash"
                        "fund" in kindRaw || "etf" in kindRaw -> "fund"
                        else -> guessKind(ticker, inst2.optString("description"))
                    }
                    out.add(OwnedHolding(label, ticker, inst2.optString("description").ifBlank { ticker }, kind,
                        units * price, units, num(p.opt("cost_basis"))))
                }
                val next = page.optString("next")
                if (next.isBlank() || next == "null") break
                path = URL(next).path
                if (path.startsWith("/api/v1")) path = path.removePrefix("/api/v1")
                val nextQuery = URL(next).query?.split('&')?.filter { !it.startsWith("clientId=") && !it.startsWith("timestamp=") }
                if (!nextQuery.isNullOrEmpty()) path += "?" + nextQuery.joinToString("&")
            }
            // Uninvested cash.
            try {
                val balances = JSONArray(signedGet("/accounts/$id/balances", clientId, key))
                for (b in 0 until balances.length()) {
                    val cash = num(balances.optJSONObject(b)?.opt("cash")) ?: continue
                    if (cash > 0.5) out.add(OwnedHolding(label, "", "Cash", "cash", cash, null, null))
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** GET with SnapTrade's request signature: HMAC-SHA256 of {"content":null,"path":…,"query":…}. */
    private fun signedGet(pathAndQuery: String, clientId: String, key: String): String {
        val path = pathAndQuery.substringBefore('?')
        val extra = pathAndQuery.substringAfter('?', "")
        val ts = System.currentTimeMillis() / 1000
        val query = "clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8") + "&timestamp=$ts" +
            if (extra.isNotEmpty()) "&$extra" else ""
        val canonical = "{\"content\":null,\"path\":" + jsonQuote(path) + ",\"query\":" + jsonQuote(query) + "}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val sig = Base64.encodeToString(mac.doFinal(canonical.toByteArray()), Base64.NO_WRAP)
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        conn.setRequestProperty("Signature", sig)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 20_000
        conn.readTimeout = 40_000
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401, 403 -> throw IOException("SnapTrade didn't accept your key. Check the Client ID and Consumer Key.")
                429 -> throw IOException("SnapTrade is busy. Try again in a minute.")
                else -> throw IOException("SnapTrade returned HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** JSON string quoting that leaves "/" alone (Android's built-in version escapes it, which breaks the signature). */
    private fun jsonQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun guessKind(ticker: String, name: String): String {
        val n = name.lowercase(Locale.US)
        val fundWords = listOf(" fund", " etf", "index", "trust", "target", "retirement", "portfolio", "admiral", "s&p 500")
        return if (fundWords.any { it in " $n" } || (ticker.length == 5 && ticker.endsWith("X"))) "fund" else "stock"
    }

    /** Shares and value you hold of each ticker, from the last saved account data (no network). */
    fun owned(ctx: Context): Map<String, Pair<Double?, Double>> {
        val prefs = ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE)
        val linked = if (hasKey(ctx)) fromJson(prefs.getString("cache", "") ?: "") ?: emptyList() else emptyList()
        val all = linked + manual(ctx).filter { it.ticker.isNotBlank() }
            .map { OwnedHolding(it.account, it.ticker.uppercase(Locale.US), it.name, "fund", it.value, null, null) }
        return all.filter { it.ticker.isNotBlank() }.groupBy { it.ticker }.mapValues { (_, l) ->
            val units = if (l.all { it.units != null }) l.sumOf { it.units!! } else null
            units to l.sumOf { it.value }
        }
    }

    // ---------------- checkup ----------------
    /** Plain-English warnings and good signs about what you own. */
    fun checkup(p: MyMoney, moves: List<Move>, scores: ScoresResult?): List<CheckNote> {
        val total = p.total
        if (total <= 0) return emptyList()
        val notes = ArrayList<CheckNote>()
        fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v / total * 100)
        val byTicker = p.holdings.filter { it.ticker.isNotBlank() }.groupBy { it.ticker }
            .mapValues { (_, list) -> list.sumOf { it.value } }
        val stocks = p.holdings.filter { it.kind == "stock" && it.ticker.isNotBlank() }.map { it.ticker }.toSet()

        // Too much in one company.
        byTicker.filter { it.key in stocks && it.value / total > 0.20 }.forEach { (t, v) ->
            notes.add(CheckNote(false, "$t is ${pct(v)} of your money. If one company that big drops 50%, " +
                "you lose ${String.format(Locale.US, "%.0f%%", v / total * 50)} of everything.", t))
        }
        val top3 = byTicker.filter { it.key in stocks }.values.sortedDescending().take(3).sum()
        if (top3 / total > 0.5 && byTicker.count { it.key in stocks } >= 3) {
            notes.add(CheckNote(false, "Your 3 biggest single stocks are ${pct(top3)} of your money. " +
                "Index funds spread the same money across hundreds of companies."))
        }
        // Cash sitting idle.
        val cash = p.holdings.filter { it.kind == "cash" }.sumOf { it.value }
        if (cash / total > 0.20) notes.add(CheckNote(false, "${pct(cash)} of your money is uninvested cash."))

        // Insiders, Congress and funds trading what you own, last 90 days.
        val since = System.currentTimeMillis() - 90 * Store.DAY_MS
        for (t in byTicker.keys) {
            val recent = moves.filter { it.ticker == t && it.time >= since }
            val sold = recent.filter { it.side == "sell" }
            val bought = recent.filter { it.side == "buy" }
            if (sold.size >= 2 && sold.sumOf { it.value } > 2 * bought.sumOf { it.value }) {
                notes.add(CheckNote(false, "Insiders or others the app tracks sold $t ${sold.size} times in the last " +
                    "90 days. Often routine, but worth a look.", t))
            } else if (bought.isNotEmpty() && bought.sumOf { it.value } > sold.sumOf { it.value }) {
                notes.add(CheckNote(true, "${bought.size} recent buy${if (bought.size > 1) "s" else ""} of $t by people the app tracks.", t))
            }
        }
        // Big losses.
        for (h in p.holdings) {
            val c = h.cost ?: continue
            val u = h.units ?: continue
            if (c > 0 && u > 0 && h.kind == "stock" && h.value / (c * u) < 0.7) {
                notes.add(CheckNote(false, "${h.ticker} is down ${String.format(Locale.US, "%.0f%%", (1 - h.value / (c * u)) * 100)} " +
                    "from what you paid. Ask whether you'd buy it today; if not, why keep it?", h.ticker))
            }
        }
        // On the Top 30.
        if (scores != null) {
            val lists = listOf("growth" to scores.growth, "income" to scores.income, "wealth" to scores.wealth)
            for (t in byTicker.keys) for ((name, l) in lists) {
                l.firstOrNull { it.ticker == t }?.let { notes.add(CheckNote(true, "$t is on the Top 30 for $name (score ${it.score}).", t)) }
            }
        }
        if (notes.none { !it.good }) notes.add(0, CheckNote(true, "No big warning signs. Your money isn't piled into one stock."))
        return notes
    }

    // ---------------- cache ----------------
    private fun toJson(list: List<OwnedHolding>) = JSONArray().also { a ->
        list.forEach { h ->
            a.put(JSONObject().put("a", h.account).put("t", h.ticker).put("n", h.name).put("k", h.kind).put("v", h.value)
                .put("u", h.units ?: JSONObject.NULL).put("c", h.cost ?: JSONObject.NULL))
        }
    }.toString()

    private fun fromJson(s: String): List<OwnedHolding>? = try {
        if (s.isBlank()) null else JSONArray(s).let { a ->
            List(a.length()) {
                val o = a.getJSONObject(it)
                OwnedHolding(o.optString("a"), o.optString("t"), o.optString("n"), o.optString("k"), o.optDouble("v"),
                    o.optDouble("u").takeIf { d -> !d.isNaN() }, o.optDouble("c").takeIf { d -> !d.isNaN() })
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Small values encrypted with a key kept in Android's hardware-backed Keystore. The Keystore key never
 * leaves the phone and isn't included in backups, so a restored backup can't read the saved values.
 */
object Secret {
    private const val ALIAS = "tracker_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String) {
        val prefs = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
        if (value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(c.doFinal(value.toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString(name, enc).apply()
    }

    fun get(ctx: Context, name: String): String = try {
        val enc = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString(name, null) ?: ""
        if (!enc.contains(':')) "" else {
            val (iv, data) = enc.split(':', limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }
    } catch (_: Exception) {
        ""
    }
}
