package com.rashestlynx.tradetracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock

/**
 * Backup for background checks. Android's job scheduler can hold checks back for hours when the phone
 * is idle; this alarm is allowed to fire while idle (about every 15 minutes), starts a check, and
 * re-arms itself. It's also re-armed after the phone restarts or the app is updated.
 */
class CheckAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (!store.isSetUp || !store.backgroundOn) return
        if (intent.action == ACTION) CheckWorker.runNow(context)
        else CheckWorker.schedule(context)   // after a restart or update
        arm(context)
    }

    companion object {
        private const val ACTION = "com.rashestlynx.tradetracker.CHECK"
        private const val EVERY = 15 * 60_000L

        private fun pending(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, CheckAlarm::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun arm(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY, pending(ctx))
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
        }

        /** True when Android lets the app run in the background without battery limits. */
        fun unrestricted(ctx: Context): Boolean =
            ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    }
}
