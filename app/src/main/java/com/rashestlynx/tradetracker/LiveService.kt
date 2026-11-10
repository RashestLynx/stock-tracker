package com.rashestlynx.tradetracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Live mode. Runs in the background with a small permanent notification (Android requires one) and
 * checks the SEC's insider filing feed every [Store.liveSeconds] seconds. When something new shows up,
 * it starts a quick check that reads those filings and sends the usual notifications. Every few
 * minutes it also starts a full check, so the other sections stay current too.
 *
 * Outside EDGAR's filing hours (weekdays 6 a.m. to 10 p.m. Eastern) nothing new can appear,
 * so it checks once a minute instead, which saves battery and data.
 */
class LiveService : Service() {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        if (!store.isSetUp || !store.liveOn) { stopSelf(); return START_NOT_STICKY }

        createChannel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val secs = store.liveSeconds
        val every = if (secs == 1) "every second" else "every $secs seconds"
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Live alerts on")
            .setContentText("Checking for new filings $every. Turn off in Settings → Live alerts.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            // Android refused to start it from the background; it starts again next time the app opens.
            stopSelf(); return START_NOT_STICKY
        }

        if (!running) {
            running = true
            // Keeps the phone's processor awake between checks while the screen is off.
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockTracker:live")
                ?.apply { setReferenceCounted(false); acquire() }
            thread = Thread({ loop() }, "live-checks").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        thread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun loop() {
        val store = Store(this)
        var known: Set<String> = store.seenSet()
        var lastFull = 0L
        while (running) {
            val started = System.currentTimeMillis()
            if (!store.liveOn || !store.isSetUp) { stopSelf(); return }
            val ua = store.userAgent

            if (ua.contains("@") && store.watching(Category.INSIDER)) {
                try {
                    // Each Form 4 is listed twice in the feed, so 20 entries are about the 10 newest filings.
                    val latest = Edgar.feed("4", ua, count = 20).map { it.accession }
                    if (latest.any { it !in known }) {
                        known = store.seenSet()   // reload only when something looks new
                        if (latest.any { it !in known }) CheckWorker.runFast(this)
                    }
                } catch (_: InterruptedException) {
                    return
                } catch (_: Exception) {
                    // Offline or the SEC is busy: try again next round.
                }
            }

            // Everything else (Congress, funds, SEC cases, news) every 5 minutes.
            if (started - lastFull >= FULL_EVERY) {
                lastFull = started
                CheckWorker.runNow(this)
            }

            val wait = (if (filingHours()) store.liveSeconds * 1000L else QUIET_EVERY) -
                (System.currentTimeMillis() - started)
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    companion object {
        private const val CHANNEL = "live"
        private const val NOTE_ID = 42
        private const val FULL_EVERY = 5 * 60_000L
        private const val QUIET_EVERY = 60_000L
        private val EASTERN = ZoneId.of("America/New_York")

        /** EDGAR accepts filings on weekdays from 6 a.m. to 10 p.m. Eastern. */
        fun filingHours(): Boolean {
            val now = ZonedDateTime.now(EASTERN)
            if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
            return now.hour in 6..21
        }

        private fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, "Live alerts running", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** Starts live mode if it's turned on. Safe to call often. */
        fun start(ctx: Context) {
            val store = Store(ctx)
            if (!store.isSetUp || !store.liveOn) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background right now; the app or the next alarm tries again.
            }
        }

        /** Restarts it so a new interval shows in its notification. */
        fun restart(ctx: Context) {
            stop(ctx); start(ctx)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LiveService::class.java))
        }
    }
}
