package com.rashestlynx.tradetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var alertList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        CheckWorker.createChannel(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(header("Settings"))
        val ua = field("Your name and email (required by the SEC)", store.userAgent,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val tickers = field("Tickers to watch, e.g. AAPL,NVDA (blank = all)",
            store.tickers.joinToString(","),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val minValue = field("Minimum trade value in \$ (0 = any)",
            if (store.minValue > 0) store.minValue.toLong().toString() else "",
            InputType.TYPE_CLASS_NUMBER)
        val buys = check("Only open-market purchases", store.buysOnly)
        val f4 = check("Watch insider trades (Form 4)", store.watchForm4)
        val f13 = check("Watch big institutions (13F)", store.watch13F)
        listOf(ua, tickers, minValue, buys, f4, f13).forEach { root.addView(it) }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Save & start"
            setOnClickListener {
                if (!ua.text.contains("@")) {
                    Toast.makeText(this@MainActivity,
                        "Enter your name and email first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                store.userAgent = ua.text.toString()
                store.tickers = tickers.text.split(",").map { it.trim().uppercase() }
                    .filter { it.isNotEmpty() }.toSet()
                store.minValue = minValue.text.toString().toDoubleOrNull() ?: 0.0
                store.buysOnly = buys.isChecked
                store.watchForm4 = f4.isChecked
                store.watch13F = f13.isChecked
                CheckWorker.schedule(this@MainActivity)
                checkNow()
                Toast.makeText(this@MainActivity,
                    "Saved. Checking about every 15 minutes.", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Check now"
            setOnClickListener { checkNow() }
        })
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                CheckWorker.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, "Background checks stopped",
                    Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(buttons)

        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        val alertsHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alertsHeader.addView(header("Recent alerts"), LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        alertsHeader.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { store.clearAlerts(); refresh() }
        })
        root.addView(alertsHeader)

        alertList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(alertList)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun checkNow() {
        status.text = "Checking…"
        val id = CheckWorker.runNow(this)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info != null && info.state.isFinished) refresh()
        }
    }

    private fun refresh() {
        status.text = store.lastStatus
        alertList.removeAllViews()
        val alerts = store.alerts()
        if (alerts.isEmpty()) {
            alertList.addView(TextView(this).apply {
                text = "No alerts yet. New filings will show up here."
            })
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (a in alerts) {
            alertList.addView(TextView(this).apply {
                text = "${a.title}  ·  ${fmt.format(Date(a.time))}\n${a.body}"
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link)))
                }
            })
            alertList.addView(View(this).apply {
                setBackgroundColor(0x33888888)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }

    // ---- tiny UI helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        isSingleLine = true
    }

    private fun check(label: String, value: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = value
    }
}
