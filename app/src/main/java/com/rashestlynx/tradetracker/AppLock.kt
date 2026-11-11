package com.rashestlynx.tradetracker

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Locks the app behind the phone's own screen lock (fingerprint, face, PIN, pattern or password).
 * It locks when the app opens and again after it has been in the background for a minute. While the
 * lock is on, the app's screen is also hidden in the recent-apps view and can't be screenshotted.
 */
object AppLock {
    const val RELOCK_MS = 60_000L

    /** True when the phone has a screen lock set, which is what the app lock uses. */
    fun available(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun active(ctx: Context, store: Store): Boolean = store.appLock && available(ctx)

    /** Hides the app in recent apps and blocks screenshots while the lock is on. */
    fun applySecureWindow(activity: Activity, on: Boolean) {
        if (on) activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Shows the system unlock prompt on Android 9+. Returns false when the caller should use the older screen. */
    fun prompt(activity: Activity, onOk: () -> Unit, onFail: (String) -> Unit): Boolean = try {
        show(activity, onOk, onFail)
    } catch (_: Exception) {
        false // never let the lock crash the app; fall back to the phone's confirm-lock screen
    }

    private fun show(activity: Activity, onOk: () -> Unit, onFail: (String) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Stock Tracker")
            .setSubtitle("Use your fingerprint, face or screen lock")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(code: Int, msg: CharSequence) = onFail(msg.toString())
            })
        return true
    }
}

/** Wraps the whole app. Shows a lock screen until the phone's screen lock is confirmed. */
@Composable
fun LockGate(store: Store, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var unlocked by remember { mutableStateOf(!AppLock.active(context, store)) }
    var leftAt by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf("") }

    // Android 8 has no unlock prompt for apps, so it uses the phone's own confirm-lock screen.
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) unlocked = true else message = "Not unlocked."
    }

    fun unlock() {
        val a = activity ?: run { unlocked = true; return }
        message = ""
        val shown = AppLock.prompt(a, onOk = { unlocked = true }, onFail = { message = it })
        if (!shown) {
            try {
                @Suppress("DEPRECATION")
                val intent: Intent? = (a.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                    .createConfirmDeviceCredentialIntent("Unlock Stock Tracker", "Confirm your screen lock")
                if (intent != null) legacy.launch(intent) else unlocked = true
            } catch (_: Exception) {
                // No way to ask for the screen lock on this phone: open rather than lock you out.
                unlocked = true
            }
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> leftAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    activity?.let { AppLock.applySecureWindow(it, AppLock.active(context, store)) }
                    if (AppLock.active(context, store) && leftAt > 0 &&
                        System.currentTimeMillis() - leftAt > AppLock.RELOCK_MS) unlocked = false
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(unlocked) { if (!unlocked) unlock() }

    if (unlocked) {
        content()
    } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)) {
                    Text("🔒", style = MaterialTheme.typography.displayMedium)
                    Text("Stock Tracker is locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (message.isNotEmpty()) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { unlock() }) { Text("Unlock") }
                }
            }
        }
    }
}
