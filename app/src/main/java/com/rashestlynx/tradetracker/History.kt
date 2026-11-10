package com.rashestlynx.tradetracker

import android.text.Html
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Loads older filings a batch at a time, after each regular check, so every section fills in its
 * history instead of starting from the day the app was installed. Older items never send
 * notifications. Progress is saved, so it picks up where it left off.
 *
 * - SEC charges: the SEC's press-release and litigation-release archives, searched for insider trading,
 *   back to the start of the archive.
 * - Senate and House: older pages from the Bargo Congress Trades API, back to its first trade.
 * - Insiders and Funds: the SEC's daily index of every filing, one day at a time, back one year.
 */
class History(private val store: Store) {
    private val p get() = store.prefs

    var chargesPage: Int
        get() = p.getInt("hist_charges_page", 0)
        set(v) = p.edit().putInt("hist_charges_page", v).apply()
    var chargesDone: Boolean
        get() = p.getBoolean("hist_charges_done", false)
        set(v) = p.edit().putBoolean("hist_charges_done", v).apply()

    var congressPage: Int
        get() = p.getInt("hist_congress_page", 2)
        set(v) = p.edit().putInt("hist_congress_page", v).apply()
    var congressDone: Boolean
        get() = p.getBoolean("hist_congress_done", false)
        set(v) = p.edit().putBoolean("hist_congress_done", v).apply()

    /** Older Congress pages loaded today (kept small to leave room in Bargo's daily limit for new trades). */
    fun congressPagesToday(): Int = if (p.getString("hist_congress_day", "") == today()) p.getInt("hist_congress_count", 0) else 0
    fun countCongressPage() = p.edit().putString("hist_congress_day", today())
        .putInt("hist_congress_count", congressPagesToday() + 1).apply()

    /** The day (yyyyMMdd) whose filings are being loaded, and how many of that day's entries are done. */
    var day: String
        get() = p.getString("hist_day", null) ?: shift(today(), -1).also { day = it }
        set(v) = p.edit().putString("hist_day", v).apply()
    var offset: Int
        get() = p.getInt("hist_offset", 0)
        set(v) = p.edit().putInt("hist_offset", v).apply()
    /** Oldest day to load filings for: one year before history loading started. */
    val stopDay: String
        get() = p.getString("hist_stop", null) ?: shift(today(), -365).also { p.edit().putString("hist_stop", it).apply() }
    val filingsDone: Boolean get() = day < stopDay

    /** "History loaded back to Nov 3, 2025" for the Sections tab. */
    fun status(): String {
        val d = runCatching { SimpleDateFormat("yyyyMMdd", Locale.US).parse(day) }.getOrNull() ?: return ""
        val shown = SimpleDateFormat("MMM d, yyyy", Locale.US).format(d)
        return when {
            filingsDone && chargesDone && congressDone -> "Full history loaded"
            else -> "Loading history: insider and fund filings back to $shown so far. Older filings keep loading with each check."
        }
    }

    companion object {
        private val dayFmt get() = SimpleDateFormat("yyyyMMdd", Locale.US)

        fun today(): String = dayFmt.format(Calendar.getInstance().time)

        fun shift(day: String, days: Int): String {
            val c = Calendar.getInstance()
            c.time = dayFmt.parse(day) ?: return day
            c.add(Calendar.DAY_OF_MONTH, days)
            return dayFmt.format(c.time)
        }

        fun dayMs(day: String): Long = runCatching { dayFmt.parse(day)?.time }.getOrNull() ?: System.currentTimeMillis()

        /** One row of the SEC daily index. */
        data class Entry(val form: String, val company: String, val cik: String, val filing: Filing, val time: Long)

        private val ROW = Regex("""^(\S+(?: \S+)*?)\s{2,}(.+?)\s{2,}(\d+)\s+(\d{8}|\d{4}-\d{2}-\d{2})\s+(edgar/data/\S+)\s*$""")

        /**
         * Every Form 4 and 13F-HR filed on [day] (yyyyMMdd), from the SEC's daily form index.
         * Returns null when there's no index for that day (weekends and holidays).
         */
        fun dailyIndex(day: String, ua: String): List<Entry>? {
            val year = day.take(4)
            val qtr = (day.substring(4, 6).toInt() - 1) / 3 + 1
            val text = try {
                Edgar.get("https://www.sec.gov/Archives/edgar/daily-index/$year/QTR$qtr/form.$day.idx", ua)
            } catch (e: IOException) {
                if ("403" in (e.message ?: "") || "404" in (e.message ?: "")) return null
                throw e
            }
            val time = dayMs(day) + 12 * 3_600_000L
            val out = LinkedHashMap<String, Entry>()
            for (line in text.lineSequence()) {
                val m = ROW.find(line) ?: continue
                val (form, company, cik, _, file) = m.destructured
                if (form != "4" && form != "13F-HR") continue
                val acc = file.substringAfterLast("/").removeSuffix(".txt")
                if (acc in out) continue   // Form 4s are listed under both the company and the insider
                val folder = "https://www.sec.gov/Archives/edgar/data/${cik.trimStart('0')}/${acc.replace("-", "")}/"
                val title = if (form == "13F-HR") "13F-HR - ${company.trim()} ($cik) (Filer)" else company.trim()
                out[acc] = Entry(form, company.trim(), cik, Filing(acc, form, title, folder + "$acc-index.htm"), time)
            }
            return out.values.toList()
        }

        private val ANCHOR = Regex(
            """href="((?:https://www\.sec\.gov)?/(?:newsroom/press-releases|enforcement-litigation/litigation-releases)/[^"#?]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL)
        private val DATETIME = Regex("""datetime="(\d{4})-(\d{2})-(\d{2})""")
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /**
         * One page (0 = newest) of the SEC's press releases and litigation releases about insider trading.
         * Empty when both archives have run out.
         */
        fun chargesPage(page: Int, ua: String): List<Charge> {
            val urls = listOf(
                "https://www.sec.gov/newsroom/press-releases?combine=insider%20trading&year=All&month=All&page=$page",
                "https://www.sec.gov/enforcement-litigation/litigation-releases?combine=insider%20trading&year=All&month=All&page=$page",
            )
            val out = LinkedHashMap<String, Charge>()
            var anyWorked = false
            for (url in urls) {
                val html = try { Edgar.get(url, ua).also { anyWorked = true } } catch (_: Exception) { continue }
                // Each listing row holds one release: its link, title and date.
                val rows = html.split(Regex("<tr|class=\"views-row")).drop(1).ifEmpty { listOf(html) }
                for (row in rows) {
                    val a = ANCHOR.find(row) ?: continue
                    val link = a.groupValues[1].let { if (it.startsWith("/")) "https://www.sec.gov$it" else it }
                    val title = clean(a.groupValues[2])
                    val text = clean(row).lowercase(Locale.US)
                    if (title.isEmpty() || ("insider trading" !in text && "insider-trading" !in text)) continue
                    val date = DATETIME.find(row)?.destructured?.let { (y, m, d) -> "${MONTHS[m.toInt() - 1]} ${d.toInt()}, $y" } ?: ""
                    out.putIfAbsent(link, Charge(title, link, "", date, "sec:$link"))
                }
            }
            if (!anyWorked) throw IOException("SEC archive unavailable")
            return out.values.toList()
        }

        /** "Nov 4, 2026" -> time in ms (now if it can't be read). */
        fun chargeTime(date: String): Long =
            runCatching { SimpleDateFormat("MMM d, yyyy", Locale.US).parse(date)?.time }.getOrNull() ?: System.currentTimeMillis()

        private fun clean(s: String): String =
            Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
    }
}
