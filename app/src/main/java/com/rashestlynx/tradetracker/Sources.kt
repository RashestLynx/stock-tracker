package com.rashestlynx.tradetracker

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** One place the app gets information from. [usedFor] says what it backs in the app. */
data class Source(val name: String, val usedFor: String, val url: String)

/** Every source the app uses: live data feeds, plus where each person's reported holdings and links come from. */
object Sources {
    /** Live data the app downloads. */
    val FEEDS = listOf(
        Source("SEC EDGAR: latest filings", "Insiders (Form 4) and Funds (13F) sections, company announcements (8-K)",
            "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent"),
        Source("SEC EDGAR: company submissions", "Each company's and filer's filing history, insider filings of people you follow",
            "https://data.sec.gov/submissions/"),
        Source("SEC EDGAR: full-text search", "Finding the SEC filer number for people you follow", "https://efts.sec.gov/LATEST/search-index"),
        Source("SEC company ticker list", "Matching company names and 13F holdings to stock tickers", "https://www.sec.gov/files/company_tickers.json"),
        Source("SEC litigation releases", "SEC charges section", "https://www.sec.gov/enforcement-litigation/litigation-releases/rss"),
        Source("SEC press releases", "SEC charges section", "https://www.sec.gov/news/pressreleases.rss"),
        Source("Bargo Congress Trades API", "Senate and House trades (from House Clerk and Senate eFD filings)", Congress.SOURCE_URL),
        Source("congress-legislators (unitedstates.github.io)", "Members of Congress: party, state, photo", "https://unitedstates.github.io/congress-legislators/"),
        Source("Congressional Bioguide", "Members' official biographies", "https://bioguide.congress.gov"),
        Source("ApeWisdom", "WSB section: most-discussed stocks on Reddit", Wsb.SOURCE_URL),
        Source("Finnhub", "Live stock prices", "https://finnhub.io"),
        Source("Massive (formerly Polygon.io)", "Movers and price charts", "https://massive.com"),
        Source("Stooq", "Delayed latest price in stock summaries", "https://stooq.com"),
        Source("Wikipedia", "Company descriptions in stock summaries", "https://en.wikipedia.org"),
        Source("CoinGecko", "Bitcoin and Ethereum prices, companies holding crypto", "https://www.coingecko.com"),
        Source("Google News", "News tab and news about people you follow", "https://news.google.com"),
        Source("YouTube", "Newest videos from people you follow", "https://www.youtube.com"),
    )

    /** A person or group, and the sources behind what the app says about them. */
    data class Group(val who: String, val sources: List<Source>)

    /** Sources for every built-in person plus anyone the user added. */
    fun people(followed: List<Followed>): List<Group> {
        val all = (Following.DEFAULTS + Following.FUNDS + Following.PUBLIC + Following.GOVERNMENT + Following.COUNTRIES + followed).distinctBy { it.name }
        return all.mapNotNull { f ->
            val out = LinkedHashMap<String, Source>()
            Following.KNOWN[f.name]?.let { k ->
                k.owns.filter { it.sourceUrl.isNotBlank() }.groupBy { it.sourceUrl }.forEach { (url, hs) ->
                    out[url] = Source(hs.first().source, hs.joinToString(", ") { it.ticker.ifEmpty { it.name } }, url)
                }
                if (k.recommendsSource.isNotBlank()) out.putIfAbsent(k.recommendsSource,
                    Source("Recommendations", k.recommends.joinToString(", "), k.recommendsSource))
                if (k.disclosureFeed.isNotBlank()) out.putIfAbsent(k.disclosureFeed,
                    Source("Briefs articles feed", "New holdings they disclose", k.disclosureFeed))
            }
            f.links.forEach { (label, url) -> out.putIfAbsent(url, Source(label, "Link", url)) }
            if (f.youtube.isNotBlank()) {
                val url = "https://www.youtube.com/@" + f.youtube.removePrefix("@")
                out.putIfAbsent(url, Source("YouTube channel", "Their newest videos", url))
            }
            if (f.cik.isNotBlank()) {
                val url = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=${f.cik}&type=13F"
                out.putIfAbsent(url, Source("SEC 13F filings", "Their quarterly stock holdings", url))
            }
            if (f.insider.isNotBlank() && f.insider.all { it.isDigit() }) {
                val url = "https://www.sec.gov/cgi-bin/own-disp?action=getowner&CIK=${f.insider}"
                out.putIfAbsent(url, Source("SEC insider filings", "Their Form 4 buys and sells", url))
            } else if (f.insider.isNotBlank()) {
                val url = "https://efts.sec.gov/LATEST/search-index?keysTyped=" + Uri.encode(f.insider)
                out.putIfAbsent(url, Source("SEC insider filings", "Their Form 4 buys and sells (found by name: ${f.insider})", url))
            }
            if (out.isEmpty()) null else Group(f.name, out.values.toList())
        }
    }
}

@Composable
fun SourcesScreen(store: Store) {
    val context = LocalContext.current
    val groups = remember { Sources.people(Following.load(store)) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase(Locale.US)
    fun match(s: Source, who: String = "") = q.isEmpty() ||
        listOf(s.name, s.usedFor, s.url, who).any { q in it.lowercase(Locale.US) }
    val feeds = Sources.FEEDS.filter { match(it) }
    val people = groups.map { g -> g.copy(sources = g.sources.filter { match(it, g.who) }) }.filter { it.sources.isNotEmpty() }
    val total = Sources.FEEDS.size + groups.sumOf { it.sources.size }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("$total sources. Tap any one to open it.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                placeholder = { Text("Search sources, people or tickers") })
        }
        if (feeds.isNotEmpty()) item {
            Text("Live data feeds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp))
        }
        items(feeds) { s -> SourceRow(s) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) } }
        people.forEach { g ->
            item {
                Text(g.who, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(g.sources) { s -> SourceRow(s) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) } }
        }
        if (feeds.isEmpty() && people.isEmpty()) item { Text("No sources match \"$query\".") }
    }
}

@Composable
private fun SourceRow(s: Source, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(s.usedFor, style = MaterialTheme.typography.bodyMedium)
        Text(s.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
    HorizontalDivider()
}
