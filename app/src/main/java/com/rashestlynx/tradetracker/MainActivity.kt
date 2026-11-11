package com.rashestlynx.tradetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ignore taps while another app draws over this one, so a hidden overlay can't trick you into tapping (tapjacking).
        window.decorView.filterTouchesWhenObscured = true
        CheckWorker.createChannel(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val store = Store(this)
        // Keep background checks scheduled (no-op if already scheduled).
        if (store.isSetUp && store.backgroundOn) CheckWorker.schedule(this)
        // Live mode: checks every few seconds, even after the app is closed.
        LiveService.start(this)

        // Hide the app in recent apps and block screenshots while the app lock is on.
        AppLock.applySecureWindow(this, AppLock.active(this, store))
        setContent {
            AppTheme(store) { LockGate(store) { StockTrackerApp(store) } }
        }
    }
}
