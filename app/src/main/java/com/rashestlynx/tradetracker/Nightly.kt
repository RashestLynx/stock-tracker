package com.rashestlynx.tradetracker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.zip.GZIPInputStream

// ============================================================ Nightly scan of every U.S. stock

data class NightlyPattern(val id: String, val name: String, val kind: String, val mood: Int, val desc: String,
                          val n: Int, val beat: Double, val avg: Double) {
    /** Average result in the pattern's own direction: positive means it has worked. */
    val edge: Double get() = if (n > 0) avg * (if (mood == 0) 1 else mood) else 0.0
    val record: String get() = if (n == 0) "" else String.format(Locale.US,
        "Across all stocks: beat the S&P 500 %.0f%% of %,d times, average %+.1f%% over 20 trading days", beat * 100, n, avg * 100)
}

data class NightlyHit(val pattern: NightlyPattern, val ago: Int, val note: String,
                      val ownN: Int, val ownBeat: Double, val ownAvg: Double) {
    val whenText: String get() = when (ago) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$ago trading days ago" }
    val ownRecord: String get() = if (ownN < 3) "" else String.format(Locale.US,
        "This stock: beat the S&P 500 %.0f%% of %d times, average %+.1f%%", ownBeat * 100, ownN, ownAvg * 100)
}

data class NightlyStock(val ticker: String, val price: Double, val chg: Double, val hi20: Double, val lo20: Double,
                        val hi52: Double, val lo52: Double, val hits: List<NightlyHit>)

data class NightlyResult(val date: String, val scanned: Int, val patterns: List<NightlyPattern>,
                         val stocks: Map<String, NightlyStock>) {
    /** Every signal, best track record first; smart-money signals (no price history) after the strongest ones. */
    fun signals(): List<Pair<NightlyStock, NightlyHit>> =
        stocks.values.flatMap { s -> s.hits.map { s to it } }
            .sortedWith(compareByDescending<Pair<NightlyStock, NightlyHit>> {
                if (it.second.pattern.kind == "Smart money") 0.02 else it.second.pattern.edge
            }.thenBy { it.second.ago })
}

object Nightly {
    const val URL_RESULTS = "https://github.com/RashestLynx/stock-Tracker-patterns/releases/download/latest/patterns.json.gz"
    private const val FRESH_MS = 3 * 3600_000L
    @Volatile private var memo: Pair<Long, NightlyResult>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "nightly.json.gz")

    /** The latest scan: from memory, then the saved copy if it's recent, else downloaded. */
    fun load(ctx: Context, force: Boolean = false): NightlyResult {
        val f = file(ctx)
        val age = System.currentTimeMillis() - f.lastModified()
        if (!force && f.exists() && age < FRESH_MS) {
            memo?.let { if (it.first == f.lastModified()) return it.second }
            runCatching { return remember(f) }
        }
        try {
            download(f)
        } catch (e: Exception) {
            if (f.exists()) return remember(f)   // offline: use the last copy
            throw Exception("The nightly scan isn't available yet. Results appear after the first scan finishes.")
        }
        return remember(f)
    }

    /** The saved copy only, never downloads (for background checks that shouldn't wait). */
    fun cached(ctx: Context): NightlyResult? = runCatching {
        val f = file(ctx); if (!f.exists()) null else (memo?.takeIf { it.first == f.lastModified() }?.second ?: remember(f))
    }.getOrNull()

    private fun remember(f: File): NightlyResult {
        val r = parse(GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() })
        memo = f.lastModified() to r
        return r
    }

    private fun download(f: File) {
        var url = URL_RESULTS
        repeat(5) {   // GitHub release downloads redirect to a storage host
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "StockTracker")
            try {
                when (c.responseCode) {
                    in 300..399 -> { url = c.getHeaderField("Location") ?: throw Exception("Bad redirect"); return@repeat }
                    in 200..299 -> {
                        val tmp = File(f.parentFile, f.name + ".tmp")
                        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        parse(GZIPInputStream(tmp.inputStream()).bufferedReader().use { it.readText() })  // check it's valid
                        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                        return
                    }
                    else -> throw Exception("HTTP ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        throw Exception("Too many redirects")
    }

    fun parse(text: String): NightlyResult {
        val o = JSONObject(text)
        val pj = o.getJSONObject("patterns")
        val patterns = pj.keys().asSequence().map { id ->
            val p = pj.getJSONObject(id)
            NightlyPattern(id, p.optString("name", id), p.optString("kind"), p.optInt("mood"), p.optString("desc"),
                p.optInt("n"), p.optDouble("beat", 0.0), p.optDouble("avg", 0.0))
        }.toList()
        val byId = patterns.associateBy { it.id }
        val sj = o.getJSONObject("stocks")
        val stocks = HashMap<String, NightlyStock>(sj.length() * 2)
        for (t in sj.keys()) {
            val s = sj.getJSONObject(t)
            val hj = s.optJSONArray("hits") ?: JSONArray()
            val hits = (0 until hj.length()).mapNotNull { i ->
                val h = hj.getJSONObject(i)
                val p = byId[h.optString("p")] ?: return@mapNotNull null
                val own = h.optJSONObject("own")
                NightlyHit(p, h.optInt("ago"), h.optString("note"),
                    own?.optInt("n") ?: 0, own?.optDouble("beat", 0.0) ?: 0.0, own?.optDouble("avg", 0.0) ?: 0.0)
            }
            stocks[t] = NightlyStock(t, s.optDouble("price"), s.optDouble("chg"), s.optDouble("hi20"), s.optDouble("lo20"),
                s.optDouble("hi52"), s.optDouble("lo52"), hits)
        }
        return NightlyResult(o.optString("date"), o.optInt("stocks_scanned"), patterns, stocks)
    }
}

// ============================================================ Live price alerts during market hours

data class PriceAlert(val key: String, val ticker: String, val title: String, val text: String, val time: Long, val up: Boolean)

object PriceWatch {
    private val EASTERN = ZoneId.of("America/New_York")
    private const val MAX_STOCKS = 45   // Finnhub's free plan allows 60 quotes a minute
    private const val BIG_MOVE = 5.0

    fun marketOpen(now: ZonedDateTime = ZonedDateTime.now(EASTERN)): Boolean {
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val m = now.hour * 60 + now.minute
        return m in (9 * 60 + 30) until (16 * 60)
    }

    /** Watchlist first, then the strongest price patterns from last night's scan. */
    fun targets(store: Store, nightly: NightlyResult?): List<String> {
        val list = LinkedHashSet(store.watchlist)
        nightly?.signals()?.filter { it.second.pattern.mood != 0 && it.second.pattern.kind != "Smart money" &&
            it.second.ago <= 1 && it.second.pattern.edge > 0 }
            ?.forEach { if (list.size < MAX_STOCKS) list.add(it.first.ticker) }
        return list.take(MAX_STOCKS)
    }

    /** What's worth an alert for one stock right now (pure, so the rules are easy to check). */
    fun rules(ticker: String, price: Double, changePct: Double, s: NightlyStock?, day: String): List<PriceAlert> {
        val out = mutableListOf<PriceAlert>()
        val now = System.currentTimeMillis()
        fun money(x: Double) = String.format(Locale.US, "\$%,.2f", x)
        if (s != null && price > 0) {
            val bull = s.hits.firstOrNull { it.pattern.mood > 0 && it.pattern.kind != "Smart money" }
            val bear = s.hits.firstOrNull { it.pattern.mood < 0 && it.pattern.kind != "Smart money" }
            when {
                s.hi52 > 0 && price > s.hi52 -> out += PriceAlert("px:$ticker:hi52:$day", ticker, "$ticker hit a new 52-week high",
                    "${money(price)}, above last year's high of ${money(s.hi52)}." + (bull?.let { " Last night's scan found a ${it.pattern.name.lowercase()}." } ?: ""), now, true)
                s.hi20 > 0 && price > s.hi20 -> out += PriceAlert("px:$ticker:hi20:$day", ticker, "$ticker broke above its 20-day high",
                    "${money(price)}, above ${money(s.hi20)}." + (bull?.let { " That confirms last night's ${it.pattern.name.lowercase()}." } ?: ""), now, true)
            }
            when {
                s.lo52 > 0 && price < s.lo52 -> out += PriceAlert("px:$ticker:lo52:$day", ticker, "$ticker hit a new 52-week low",
                    "${money(price)}, below last year's low of ${money(s.lo52)}." + (bear?.let { " Last night's scan found a ${it.pattern.name.lowercase()}." } ?: ""), now, false)
                s.lo20 > 0 && price < s.lo20 -> out += PriceAlert("px:$ticker:lo20:$day", ticker, "$ticker broke below its 20-day low",
                    "${money(price)}, below ${money(s.lo20)}." + (bear?.let { " That confirms last night's ${it.pattern.name.lowercase()}." } ?: ""), now, false)
            }
        }
        if (kotlin.math.abs(changePct) >= BIG_MOVE) {
            out += PriceAlert("px:$ticker:move:$day", ticker,
                "$ticker is ${if (changePct > 0) "up" else "down"} ${String.format(Locale.US, "%.1f", kotlin.math.abs(changePct))}% today",
                if (price > 0) "Now ${money(price)}." else "", now, changePct > 0)
        }
        return out
    }

    /** One round of checks. Runs only while the market is open. Returns the new alerts. */
    fun check(ctx: Context, store: Store, notify: Boolean = true): List<PriceAlert> {
        if (!store.priceAlertsOn || !marketOpen()) return emptyList()
        val nightly = runCatching { Nightly.load(ctx) }.getOrNull() ?: Nightly.cached(ctx)
        // Levels are from the last scan; ignore them if the scan is more than 4 days old.
        val fresh = nightly?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.let { it.isAfter(LocalDate.now(EASTERN).minusDays(5)) } == true
        val day = LocalDate.now(EASTERN).toString()
        val seen = store.seenSet()
        val found = mutableListOf<PriceAlert>()
        val hasKey = Live.key(store).isNotBlank()
        for (t in targets(store, nightly)) {
            val q = runCatching { Live.quote(t, store) }.getOrNull() ?: runCatching { Live.yahooDay(t) }.getOrNull() ?: continue
            val s = if (fresh) nightly?.stocks?.get(t) else null
            found += rules(t, q.price, q.changePct, s, day).filter { it.key !in seen }
            if (hasKey) Thread.sleep(1100)   // stay under Finnhub's 60 a minute
        }
        if (found.isEmpty()) return found
        store.markSeen(found.map { it.key })
        store.addPriceAlerts(found)
        if (notify && store.appNotify) post(ctx, found)
        return found
    }

    private fun post(ctx: Context, alerts: List<PriceAlert>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = NotificationManagerCompat.from(ctx)
        val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (alerts.size > 3) {
            nm.notify(9, NotificationCompat.Builder(ctx, CheckWorker.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("${alerts.size} price alerts")
                .setContentText(alerts.joinToString(" · ") { it.ticker })
                .setStyle(NotificationCompat.BigTextStyle().bigText(alerts.joinToString("\n") { it.title }))
                .setContentIntent(open).setAutoCancel(true).build())
            return
        }
        for (a in alerts) nm.notify(a.key.hashCode(), NotificationCompat.Builder(ctx, CheckWorker.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(a.title).setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
