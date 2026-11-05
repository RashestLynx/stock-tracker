package com.rashestlynx.tradetracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Background job: checks every source for new filings, saves them and posts notifications. */
class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val store = Store(applicationContext)
    private val newAlerts = mutableListOf<Alert>()
    private val newPicks = mutableListOf<Pick>()
    private val newSeen = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    override fun doWork(): Result {
        val ua = store.userAgent
        if (!ua.contains("@")) {
            store.lastStatus = "Add your email in Settings to start"
            return Result.success()
        }
        store.lastStatus = "Checking…"

        // First run: load what's already posted into the app, but don't send notifications.
        val firstRun = !store.hasSeenAnything()
        val seen = store.seenSet()

        if (store.watching(Category.SENATE) || store.watching(Category.HOUSE)) checkCongress(seen)
        if (store.watching(Category.INSIDER)) checkForm4(ua, seen, if (firstRun) 40 else 100)
        if (store.watching(Category.FUND)) check13F(ua, seen, if (firstRun) 12 else 40)

        store.addPicks(newPicks)
        store.addAlerts(newAlerts)
        store.markSeen(newSeen)
        if (!firstRun) notifyAll(newAlerts)

        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        store.lastStatus = when {
            errors.isNotEmpty() && newAlerts.isEmpty() -> "Error at $time: ${errors.first()}"
            firstRun -> "Loaded ${newAlerts.size} recent filings · $time"
            newAlerts.isEmpty() -> "Up to date · checked $time"
            else -> "${newAlerts.size} new · checked $time"
        }
        return Result.success()
    }

    // ------------------------------------------------------------ Congress
    private fun checkCongress(seen: Set<String>) {
        // Stay inside the free daily limits: keyless allows ~100 rows a day.
        val hasKey = store.bargoKey.isNotBlank()
        val every = if (hasKey) 60 * 60_000L else 5 * 60 * 60_000L
        val limit = if (hasKey) 40 else 20
        if (System.currentTimeMillis() - store.lastCongressCheck < every) return

        val trades = try {
            Congress.recent(limit, store.bargoKey)
        } catch (e: Exception) {
            errors.add(e.message ?: "Congress data unavailable")
            return
        }
        store.lastCongressCheck = System.currentTimeMillis()

        for (t in trades) {
            if (t.key in seen || t.key in newSeen) continue
            newSeen.add(t.key)
            val cat = if (t.chamber == "senate") Category.SENATE else Category.HOUSE
            val title = if (cat == Category.SENATE) "Sen." else "Rep."
            val who = "$title ${t.member}" + if (t.state.isNotEmpty()) " · ${t.state}" else ""
            val when_ = parseDate(t.transactionDate)

            if (t.isBuy && t.ticker.isNotEmpty()) {
                newPicks.add(Pick(when_, t.ticker, t.asset, t.member, cat, "buy"))
            }
            if (!store.watching(cat)) continue
            if (!passesFilters(t.ticker, t.isBuy, t.amountLow)) continue

            val action = when {
                t.isBuy -> "Bought"
                t.isSell -> "Sold"
                else -> t.type.replaceFirstChar { it.uppercase() }.ifEmpty { "Traded" }
            }
            newAlerts.add(
                Alert(
                    time = System.currentTimeMillis(),
                    category = cat,
                    ticker = t.ticker,
                    company = t.asset,
                    headline = who,
                    lines = listOf(
                        "$action ${t.amountRange}".trim(),
                        "Traded ${t.transactionDate} · disclosed ${t.disclosureDate}",
                    ),
                    link = t.portal.ifEmpty { Congress.SOURCE_URL },
                    key = t.key,
                )
            )
        }
    }

    // ------------------------------------------------------------ Insiders (Form 4)
    private fun checkForm4(ua: String, seen: Set<String>, max: Int) {
        val filings = try {
            Edgar.feed("4", ua)
        } catch (e: Exception) {
            errors.add("Insiders: ${e.message}")
            return
        }
        for (f in filings.filter { it.accession !in seen }.take(max)) {
            newSeen.add(f.accession)
            val info = try {
                Edgar.parseForm4(f, ua)
            } catch (e: Exception) {
                errors.add("Insiders: ${e.message}"); null
            } ?: continue

            val who = info.owners.joinToString(", ").ifEmpty { "Unknown" }
            val company = info.issuer

            if (info.trades.any { it.code == "P" } && info.ticker.isNotEmpty()) {
                newPicks.add(Pick(System.currentTimeMillis(), info.ticker, company, who,
                    Category.INSIDER, "buy"))
            }

            if (store.tickers.isNotEmpty() && info.ticker !in store.tickers) continue
            var trades = info.trades
            if (store.buysOnly) trades = trades.filter { it.code == "P" }
            if (store.minValue > 0) trades = trades.filter { it.value >= store.minValue }
            if (trades.isEmpty() && (store.buysOnly || store.minValue > 0)) continue

            val role = if (info.roles.isNotEmpty()) " · ${info.roles.joinToString(", ")}" else ""
            newAlerts.add(
                Alert(
                    time = System.currentTimeMillis(),
                    category = Category.INSIDER,
                    ticker = info.ticker,
                    company = company,
                    headline = "$who$role",
                    lines = Edgar.summarize(trades).ifEmpty { listOf("Options/derivatives only") },
                    link = f.link,
                    key = f.accession,
                )
            )
        }
    }

    // ------------------------------------------------------------ Funds (13F)
    private fun check13F(ua: String, seen: Set<String>, max: Int) {
        val filings = try {
            Edgar.feed("13F-HR", ua)
        } catch (e: Exception) {
            errors.add("Funds: ${e.message}")
            return
        }
        val fresh = filings.filter { it.accession !in seen }.take(max)
        if (fresh.isEmpty()) return
        val tickerMap = tickerMap(ua)

        for (f in fresh) {
            newSeen.add(f.accession)
            val fund = f.title.replace(Regex("^13F-HR\\s*-\\s*"), "")
                .replace(Regex("\\s*\\(\\d+\\)\\s*\\(Filer\\)\\s*$"), "")
            val holdings = try {
                Edgar.parse13FHoldings(f, ua)
            } catch (e: Exception) {
                errors.add("Funds: ${e.message}"); emptyList()
            }
            val total = holdings.sumOf { it.second }
            val mapped = holdings.mapNotNull { (name, value) ->
                tickerMap[Edgar.normalizeName(name)]?.let { Triple(it, name, value) }
            }
            mapped.forEach { (ticker, name, _) ->
                newPicks.add(Pick(System.currentTimeMillis(), ticker, titleCase(name), fund,
                    Category.FUND, "hold"))
            }

            val watch = store.tickers
            if (watch.isNotEmpty() && mapped.none { it.first in watch }) continue

            val lines = mutableListOf<String>()
            if (mapped.isNotEmpty()) {
                lines.add("Top holdings: " + mapped.take(6).joinToString(", ") { it.first })
            }
            if (total > 0) lines.add("Top ${holdings.size} positions worth ${money(total)}")
            newAlerts.add(
                Alert(
                    time = System.currentTimeMillis(),
                    category = Category.FUND,
                    ticker = "",
                    company = "Quarterly holdings report",
                    headline = fund,
                    lines = lines,
                    link = f.link,
                    key = f.accession,
                )
            )
        }
    }

    private fun tickerMap(ua: String): Map<String, String> {
        store.cachedTickerMap()?.let { return it }
        return try {
            Edgar.companyTickers(ua).also { store.saveTickerMap(it) }
        } catch (e: Exception) {
            errors.add("Ticker list: ${e.message}")
            emptyMap()
        }
    }

    // ------------------------------------------------------------ helpers
    private fun passesFilters(ticker: String, isBuy: Boolean, amount: Double): Boolean {
        if (store.tickers.isNotEmpty() && ticker !in store.tickers) return false
        if (store.buysOnly && !isBuy) return false
        if (store.minValue > 0 && amount < store.minValue) return false
        return true
    }

    private fun parseDate(d: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }

    private fun titleCase(s: String) = s.lowercase(Locale.US).split(" ")
        .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }

    private fun money(v: Double): String = when {
        v >= 1e12 -> String.format(Locale.US, "\$%.1fT", v / 1e12)
        v >= 1e9 -> String.format(Locale.US, "\$%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "\$%.1fM", v / 1e6)
        else -> String.format(Locale.US, "\$%,.0f", v)
    }

    private fun notifyAll(alerts: List<Alert>) {
        if (alerts.isEmpty()) return
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(ctx)

        if (alerts.size > 4) {
            // Too many to show one by one: send a single summary that opens the app.
            val counts = alerts.groupingBy { it.category }.eachCount()
            val summary = Category.entries.filter { (counts[it] ?: 0) > 0 }
                .joinToString(" · ") { "${it.label} ${counts[it]}" }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(1, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("${alerts.size} new filings")
                .setContentText(summary)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            return
        }

        for (a in alerts) {
            val open = PendingIntent.getActivity(
                ctx, a.key.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(a.link)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(a.key.hashCode(), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(a.notificationTitle)
                .setContentText(a.notificationBody.lineSequence().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.notificationBody))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    companion object {
        const val CHANNEL = "filings"
        private const val PERIODIC = "edgar-periodic"
        private const val NOW = "check-now"

        fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, "New filings", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Android's minimum for background jobs is 15 minutes. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun stop(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
