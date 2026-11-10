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
    private val newMoves = mutableListOf<Move>()
    private val newCrypto = mutableListOf<Store.CryptoPos>()
    private val errors = mutableListOf<String>()

    override fun doWork(): Result {
        val ua = store.userAgent
        if (!ua.contains("@")) {
            store.lastStatus = "Add your email in Settings to start"
            return Result.success()
        }
        // Live mode's quick check: only new insider filings, no history step.
        val fast = inputData.getBoolean(FAST, false)
        if (!fast) store.lastStatus = "Checking…"

        // One check at a time looks at new filings, so the same filing is never notified twice.
        val firstRun = synchronized(LOCK) {
            // First run: load what's already posted into the app, but don't send notifications.
            val firstRun = !store.hasSeenAnything()
            val seen = store.seenSet()

            if (fast) {
                if (store.watching(Category.INSIDER)) checkForm4(ua, seen, if (firstRun) 40 else 100)
            } else {
                if (store.watching(Category.SENATE) || store.watching(Category.HOUSE)) checkCongress(seen)
                if (store.watching(Category.INSIDER)) checkForm4(ua, seen, if (firstRun) 40 else 100)
                if (store.watching(Category.FUND)) check13F(ua, seen, if (firstRun) 12 else 40)
                if (store.watching(Category.CHARGES)) checkCharges(ua, seen)

                if (store.newsAlerts && (store.tickers.isNotEmpty() || store.watchlist.isNotEmpty())) checkNews(seen, firstRun)
                checkFollowing(seen, firstRun)
            }

            // Save and notify about new filings right away, before the slower history step.
            saveAll(newAlerts)
            if (!firstRun && store.appNotify) notifyAll(newAlerts)
            firstRun
        }
        // A quick check with nothing new leaves the status line alone.
        if (fast && newAlerts.isEmpty() && errors.isEmpty()) return Result.success()

        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        store.lastStatus = when {
            errors.isNotEmpty() && newAlerts.isEmpty() -> "Error at $time: ${errors.first()}"
            firstRun -> "Loaded ${newAlerts.size} recent filings · $time"
            newAlerts.isEmpty() -> "Up to date · checked $time"
            else -> "${newAlerts.size} new · checked $time"
        }

        // Then load a batch of older filings (never notifies).
        if (!fast && !isStopped) {
            runCatching { runHistory(ua, store.seenSet()) }
            saveAll(histAlerts)
        }
        return Result.success()
    }

    /** Saves what's been collected so far and clears the buffers, so nothing is saved twice. */
    private fun saveAll(alerts: List<Alert>) {
        store.addCryptoPositions(newCrypto.toList()); newCrypto.clear()
        store.addMoves(newMoves.toList()); newMoves.clear()
        store.addPicks(newPicks.toList()); newPicks.clear()
        store.addAlerts(alerts)
        store.markSeen(newSeen.toList()); newSeen.clear()
    }

    // ------------------------------------------------------------ History (older filings)
    private val histAlerts = mutableListOf<Alert>()

    /** Loads one batch of older filings for each section. Never notifies. */
    private fun runHistory(ua: String, seen: Set<String>) {
        val h = History(store)

        if (store.watching(Category.CHARGES) && !h.chargesDone) {
            repeat(3) {
                if (h.chargesDone) return@repeat
                val page = h.chargesPage
                val cases = runCatching { History.chargesPage(page, ua) }.getOrNull() ?: return@repeat
                if (cases.isEmpty() || page >= 80) h.chargesDone = true
                else { processCharges(cases, seen, histAlerts, history = true); h.chargesPage = page + 1 }
            }
        }

        // Older Congress pages need a Bargo key (keyless allows only 100 rows a day). Up to 3 pages a day.
        if ((store.watching(Category.SENATE) || store.watching(Category.HOUSE)) && !h.congressDone &&
            store.bargoKey.isNotBlank() && h.congressPagesToday() < 3) {
            val page = h.congressPage
            runCatching { Congress.recent(100, store.bargoKey, page = page) }.getOrNull()?.let { trades ->
                h.countCongressPage()
                if (trades.isEmpty()) h.congressDone = true
                else { processCongress(trades, seen, histAlerts, history = true); h.congressPage = page + 1 }
            }
        }

        // Insiders and funds: walk back through the SEC's daily index. A 13F costs more (it's a big file).
        val wantForm4 = store.watching(Category.INSIDER)
        val want13F = store.watching(Category.FUND)
        if (!wantForm4 && !want13F) return
        var budget = 180
        var skipped = 0
        while (budget > 0 && !h.filingsDone && skipped < 6) {
            val day = h.day
            val entries = try { History.dailyIndex(day, ua) } catch (_: Exception) { return }
            if (entries == null) { h.day = History.shift(day, -1); h.offset = 0; skipped++; continue }
            val todo = entries.drop(h.offset)
            var used = 0
            val f4 = mutableListOf<Pair<Filing, Long>>()
            val f13 = mutableListOf<Pair<Filing, Long>>()
            for (e in todo) {
                val cost = if (e.form == "13F-HR") 6 else 1
                if (cost > budget) break
                used++
                if (e.filing.accession in seen || e.filing.accession in newSeen) continue
                if (e.form == "4" && wantForm4) { f4.add(e.filing to e.time); budget -= cost }
                if (e.form == "13F-HR" && want13F) { f13.add(e.filing to e.time); budget -= cost }
            }
            val times = (f4 + f13).associate { it.first.accession to it.second }
            processForm4(f4.map { it.first }, ua, histAlerts) { times[it.accession] ?: System.currentTimeMillis() }
            process13F(f13.map { it.first }, ua, histAlerts) { times[it.accession] ?: System.currentTimeMillis() }
            newSeen.addAll(times.keys)
            if (h.offset + used >= entries.size) { h.day = History.shift(day, -1); h.offset = 0 }
            else { h.offset = h.offset + used; break }
        }
    }

    // ------------------------------------------------------------ Congress
    private fun checkCongress(seen: Set<String>) {
        // Stay inside the free daily limits: keyless allows ~100 rows a day.
        val hasKey = store.bargoKey.isNotBlank()
        val every = if (hasKey) 60 * 60_000L else 5 * 60 * 60_000L
        val limit = if (hasKey) 25 else 20
        if (System.currentTimeMillis() - store.lastCongressCheck < every) return

        val trades = try {
            Congress.recent(limit, store.bargoKey)
        } catch (e: Exception) {
            errors.add(e.message ?: "Congress data unavailable")
            return
        }
        store.lastCongressCheck = System.currentTimeMillis()
        processCongress(trades, seen, newAlerts, history = false)
    }

    /** Turns Congress trades into feed posts, moves and picks. History posts use the disclosure date. */
    private fun processCongress(trades: List<CongressTrade>, seen: Set<String>, into: MutableList<Alert>, history: Boolean) {
        for (t in trades) {
            if (t.key in seen || t.key in newSeen) continue
            newSeen.add(t.key)
            val cat = if (t.chamber == "senate") Category.SENATE else Category.HOUSE
            val title = if (cat == Category.SENATE) "Sen." else "Rep."
            val who = "$title ${t.member}" + if (t.state.isNotEmpty()) " · ${t.state}" else ""
            val when_ = parseDate(t.transactionDate)
            val pk = People.congressKey(t.memberSlug, t.chamber, t.state, t.member)

            if (t.isBuy && t.ticker.isNotEmpty()) {
                newPicks.add(Pick(when_, t.ticker, t.asset, t.member, cat, "buy", person = pk))
            }
            if ((t.isBuy || t.isSell) && t.ticker.isNotEmpty() && t.estValue > 0) {
                newMoves.add(Move(
                    // Use the disclosure day so it shows up as "today" when it's made public.
                    time = if (t.disclosureDate.isNotEmpty()) parseDate(t.disclosureDate)
                    else System.currentTimeMillis(),
                    category = cat, ticker = t.ticker, company = t.asset,
                    who = "$title ${t.member}", role = if (cat == Category.SENATE) "Senator" else "Representative",
                    side = if (t.isBuy) "buy" else "sell", value = t.estValue,
                    valueLabel = t.amountRange.ifEmpty { money(t.estValue) },
                    link = t.portal.ifEmpty { Congress.SOURCE_URL }, key = t.key,
                    price = t.estPrice, person = pk,
                ))
            }
            if (!store.watching(cat)) continue
            if (!passesFilters(t.ticker, t.isBuy, t.amountLow)) continue

            val action = when {
                t.isBuy -> "Bought"
                t.isSell -> "Sold"
                else -> t.type.replaceFirstChar { it.uppercase() }.ifEmpty { "Traded" }
            }
            into.add(
                Alert(
                    time = if (history) parseDate(t.disclosureDate.ifEmpty { t.transactionDate }) else System.currentTimeMillis(),
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
                    person = pk,
                )
            )
        }
    }

    // ------------------------------------------------------------ SEC insider trading cases
    private fun checkCharges(ua: String, seen: Set<String>) {
        val cases = try {
            Enforcement.insiderTradingCases(ua)
        } catch (e: Exception) {
            errors.add("SEC charges: ${e.message}")
            return
        }
        processCharges(cases, seen, newAlerts, history = false)
    }

    private fun processCharges(cases: List<Charge>, seen: Set<String>, into: MutableList<Alert>, history: Boolean) {
        for (c in cases) {
            if (c.key in seen || c.key in newSeen) continue
            newSeen.add(c.key)
            val summary = c.summary.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
            into.add(
                Alert(
                    time = if (history) History.chargeTime(c.date) else System.currentTimeMillis(),
                    category = Category.CHARGES,
                    ticker = "",
                    company = "SEC enforcement action" + if (c.date.isNotEmpty()) " · ${c.date}" else "",
                    headline = c.title,
                    lines = listOfNotNull(summary.ifBlank { null }),
                    link = c.link.ifEmpty { "https://www.sec.gov/enforcement-litigation/litigation-releases" },
                    key = c.key,
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
        processForm4(filings.filter { it.accession !in seen }.take(max), ua, newAlerts) { System.currentTimeMillis() }
    }

    /** Reads each Form 4 and turns it into a feed post, moves and picks, dated with [timeOf]. */
    private fun processForm4(filings: List<Filing>, ua: String, into: MutableList<Alert>, timeOf: (Filing) -> Long) {
        for (f in filings) {
            newSeen.add(f.accession)
            val info = try {
                Edgar.parseForm4(f, ua)
            } catch (e: Exception) {
                errors.add("Insiders: ${e.message}"); null
            } ?: continue

            val who = info.owners.joinToString(", ").ifEmpty { "Unknown" }
            val company = info.issuer
            val pk = info.ownerCiks.firstOrNull()?.let { "ins:$it" } ?: ""

            if (info.trades.any { it.code == "P" } && info.ticker.isNotEmpty()) {
                newPicks.add(Pick(timeOf(f), info.ticker, company, who,
                    Category.INSIDER, "buy", person = pk))
            }
            if (info.ticker.isNotEmpty()) {
                val roleText = info.roles.joinToString(", ")
                for ((code, side) in listOf("P" to "buy", "S" to "sell")) {
                    val these = info.trades.filter { it.code == code }
                    val v = these.sumOf { it.value }
                    val shares = these.sumOf { it.shares }
                    if (v > 0) newMoves.add(Move(
                        time = timeOf(f), category = Category.INSIDER,
                        ticker = info.ticker, company = company, who = who, role = roleText,
                        side = side, value = v, valueLabel = money(v), link = f.link,
                        key = "${f.accession}|$side",
                        price = if (shares > 0) v / shares else 0.0, person = pk,
                    ))
                }
            }

            if (store.tickers.isNotEmpty() && info.ticker !in store.tickers) continue
            var trades = info.trades
            if (store.buysOnly) trades = trades.filter { it.code == "P" }
            if (store.minValue > 0) trades = trades.filter { it.value >= store.minValue }
            if (trades.isEmpty() && (store.buysOnly || store.minValue > 0)) continue

            val role = if (info.roles.isNotEmpty()) " · ${info.roles.joinToString(", ")}" else ""
            into.add(
                Alert(
                    time = timeOf(f),
                    category = Category.INSIDER,
                    ticker = info.ticker,
                    company = company,
                    headline = "$who$role",
                    lines = Edgar.summarize(trades).ifEmpty { listOf("Options/derivatives only") },
                    link = f.link,
                    key = f.accession,
                    person = pk,
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
        process13F(fresh, ua, newAlerts) { System.currentTimeMillis() }
    }

    /** Reads each 13F and turns it into a feed post, picks and crypto positions, dated with [timeOf]. */
    private fun process13F(fresh: List<Filing>, ua: String, into: MutableList<Alert>, timeOf: (Filing) -> Long) {
        if (fresh.isEmpty()) return
        val tickerMap = tickerMap(ua)

        for (f in fresh) {
            newSeen.add(f.accession)
            val fund = f.title.replace(Regex("^13F-HR\\s*-\\s*"), "")
                .replace(Regex("\\s*\\(\\d+\\)\\s*\\(Filer\\)\\s*$"), "")
            val pk = Regex("\\((\\d+)\\)").find(f.title)?.groupValues?.get(1)?.trimStart('0')
                ?.let { "fund:$it" } ?: ""
            val rows = try {
                Edgar.rows13F(f, ua)
            } catch (e: Exception) {
                errors.add("Funds: ${e.message}"); emptyList()
            }
            val holdings = Edgar.top13F(rows)
            rows.filter { !it.option }.groupBy { it.name }.forEach { (name, rs) ->
                val asset = Crypto.assetOf(name) ?: return@forEach
                newCrypto.add(Store.CryptoPos(fund, pk, asset, name, rs.sumOf { it.value }, timeOf(f)))
            }
            val total = holdings.sumOf { it.second }
            val mapped = holdings.mapNotNull { (name, value) ->
                tickerMap[Edgar.normalizeName(name)]?.let { Triple(it, name, value) }
            }
            mapped.forEach { (ticker, name, _) ->
                newPicks.add(Pick(timeOf(f), ticker, titleCase(name), fund,
                    Category.FUND, "hold", person = pk))
            }

            val watch = store.tickers
            if (watch.isNotEmpty() && mapped.none { it.first in watch }) continue

            val lines = mutableListOf<String>()
            if (mapped.isNotEmpty()) {
                lines.add("Top holdings: " + mapped.take(6).joinToString(", ") { it.first })
            }
            if (total > 0) lines.add("Top ${holdings.size} positions worth ${money(total)}")
            into.add(
                Alert(
                    time = timeOf(f),
                    category = Category.FUND,
                    ticker = "",
                    company = "Quarterly holdings report",
                    headline = fund,
                    lines = lines,
                    link = f.link,
                    key = f.accession,
                    person = pk,
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

    // ------------------------------------------------------------ News about watchlist stocks
    private fun checkNews(seen: Set<String>, firstRun: Boolean) {
        val watch = (store.watchlist + store.tickers).distinct()
        val items = mutableListOf<NewsItem>()
        try { items += News.filings(store).filter { it.ticker in watch } } catch (_: Exception) {}
        try { items += News.forTickers(watch).filter { it.ticker.isNotEmpty() } } catch (_: Exception) {}
        val fresh = items.filter { it.key !in seen && System.currentTimeMillis() - it.time < 6 * 3600_000L }
        newSeen.addAll(fresh.map { it.key })
        if (firstRun || fresh.isEmpty()) return
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(ctx)
        for (n in fresh.take(3)) {
            val open = PendingIntent.getActivity(ctx, n.key.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(n.url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(n.key.hashCode(), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle((if (n.isFiling) "${n.ticker} filed with the SEC" else "${n.ticker} in the news"))
                .setContentText(if (n.isFiling) n.detail else n.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    if (n.isFiling) "${n.title}\n${n.detail}" else "${n.title}\n${n.source}"))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    // ------------------------------------------------------------ New videos from people you follow
    private fun checkFollowing(seen: Set<String>, firstRun: Boolean) {
        val ctx = applicationContext
        for (f in Following.load(store)) {
            val vids = try { Following.videos(f, store) } catch (_: Exception) { continue }
            // Without any record yet for this person, just remember their current videos.
            val known = vids.any { it.id in seen }
            val fresh = vids.filter { it.id !in seen }
            newSeen.addAll(fresh.map { it.id })
            if (firstRun || !known) continue
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            for (v in fresh.take(2)) {
                val open = PendingIntent.getActivity(ctx, v.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(v.url)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                NotificationManagerCompat.from(ctx).notify(v.id.hashCode(), NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("${f.name} posted a new video")
                    .setContentText(v.title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(v.title))
                    .setContentIntent(open).setAutoCancel(true).build())
            }
        }
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
        private const val LIVE = "check-live"
        private const val FAST = "fast"
        private val LOCK = Any()

        fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, "New filings", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Android's minimum for background jobs is 15 minutes, and battery saving can hold them back for
         * hours. An alarm that's allowed to fire while the phone is idle backs it up (see [CheckAlarm]).
         */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
            CheckAlarm.arm(ctx)
        }

        fun stop(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            CheckAlarm.cancel(ctx)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        /** Live mode found new insider filings: read and notify just those, skipping everything slow. */
        fun runFast(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>()
                .setInputData(androidx.work.workDataOf(FAST to true))
                .setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(LIVE, ExistingWorkPolicy.KEEP, req)
        }
    }
}
