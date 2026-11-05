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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Background job: checks EDGAR for new filings and posts notifications. */
class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val store = Store(applicationContext)
        val ua = store.userAgent
        if (!ua.contains("@")) {
            store.lastStatus = "Add your name and email in settings first."
            return Result.success()
        }

        val forms = buildList {
            if (store.watchForm4) add("4")
            if (store.watch13F) add("13F-HR")
        }
        // First ever run: just remember what's already there so you don't get flooded.
        val firstRun = !store.hasSeenAnything()
        var alerted = 0
        val errors = mutableListOf<String>()

        for (form in forms) {
            val filings = try {
                Edgar.feed(form, ua)
            } catch (e: Exception) {
                errors.add("$form: ${e.message}")
                continue
            }
            val fresh = filings.filter { !store.isSeen(it.accession) }.reversed() // oldest first
            for (f in fresh) {
                if (!firstRun) {
                    try {
                        val alert = if (form == "4") form4Alert(f, store) else form13FAlert(f)
                        if (alert != null) {
                            store.addAlert(alert)
                            notify(alert)
                            alerted++
                        }
                    } catch (e: Exception) {
                        errors.add("${f.accession}: ${e.message}")
                    }
                }
                store.markSeen(listOf(f.accession))
            }
        }

        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        store.lastStatus = when {
            errors.isNotEmpty() -> "Last check $time: ${errors.first()}"
            firstRun -> "Last check $time: setup done. New filings will alert from now on."
            else -> "Last check $time: $alerted new alert(s)"
        }
        return Result.success()
    }

    private fun form4Alert(f: Filing, store: Store): Alert? {
        val info = Edgar.parseForm4(f, store.userAgent) ?: return null
        val tickers = store.tickers
        if (tickers.isNotEmpty() && info.ticker !in tickers) return null

        var trades = info.trades
        if (store.buysOnly) trades = trades.filter { it.code == "P" }
        if (store.minValue > 0) trades = trades.filter { it.value >= store.minValue }
        if (trades.isEmpty() && (store.buysOnly || store.minValue > 0)) return null

        val who = info.owners.joinToString(", ").ifEmpty { "Unknown" }
        val role = if (info.roles.isNotEmpty()) " (${info.roles.joinToString(", ")})" else ""
        val lines = Edgar.summarize(trades).ifEmpty { listOf("Options/derivatives only") }
        return Alert(
            System.currentTimeMillis(),
            "Form 4: ${info.ticker.ifEmpty { info.issuer }}",
            "$who$role\n${info.issuer}\n${lines.joinToString("\n")}",
            f.link,
        )
    }

    private fun form13FAlert(f: Filing): Alert {
        val name = f.title.replace(Regex("^13F-HR\\s*-\\s*"), "")
            .replace(Regex("\\s*\\(\\d+\\)\\s*\\(Filer\\)\\s*$"), "")
        return Alert(System.currentTimeMillis(), "New 13F filing",
            "$name\nQuarterly holdings report posted.", f.link)
    }

    private fun notify(a: Alert) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            ctx, a.link.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(a.link)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(a.title)
            .setContentText(a.body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(a.link.hashCode(), n)
    }

    companion object {
        const val CHANNEL = "filings"
        private const val PERIODIC = "edgar-periodic"

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

        fun stop(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)

        fun runNow(ctx: Context): UUID {
            val req = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueue(req)
            return req.id
        }
    }
}
