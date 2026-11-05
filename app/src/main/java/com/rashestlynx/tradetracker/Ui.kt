package com.rashestlynx.tradetracker

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Increments whenever stored data changes (including from the background worker). */
@Composable
private fun rememberStoreTick(store: Store): Int {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return tick
}

private const val TAB_FEED = 0
private const val TAB_PICKS = 1
private const val TAB_SETTINGS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockTrackerApp(store: Store) {
    val context = LocalContext.current
    val tick = rememberStoreTick(store)
    var tab by rememberSaveable { mutableIntStateOf(if (store.isSetUp) TAB_FEED else TAB_SETTINGS) }
    var refreshing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val status = remember(tick) { store.lastStatus }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun checkNow() {
        if (!store.isSetUp) {
            message("Add your email in Settings first")
            tab = TAB_SETTINGS
            return
        }
        refreshing = true
        CheckWorker.runNow(context)
    }

    LaunchedEffect(status) {
        if (!status.startsWith("Checking")) refreshing = false
    }
    LaunchedEffect(refreshing) {
        if (refreshing) { delay(120_000); refreshing = false } // don't spin forever offline
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (tab) {
                                TAB_FEED -> "Stock Tracker"
                                TAB_PICKS -> "Common picks"
                                else -> "Settings"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (tab != TAB_SETTINGS) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (tab != TAB_SETTINGS) {
                        IconButton(onClick = { checkNow() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Check now")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_FEED,
                    onClick = { tab = TAB_FEED },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    label = { Text("Feed") },
                )
                NavigationBarItem(
                    selected = tab == TAB_PICKS,
                    onClick = { tab = TAB_PICKS },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("Common picks") },
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                TAB_FEED -> FeedScreen(store, tick, refreshing, onRefresh = { checkNow() },
                    onSetup = { tab = TAB_SETTINGS })
                TAB_PICKS -> PicksScreen(store, tick, refreshing, onRefresh = { checkNow() })
                else -> SettingsScreen(store, onMessage = { message(it) }, onReady = {
                    tab = TAB_FEED
                    checkNow()
                })
            }
        }
    }
}

// ============================================================ Category colors

@Composable
private fun categoryColors(c: Category): Pair<Color, Color> = when (c) {
    Category.SENATE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    Category.HOUSE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    Category.INSIDER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    Category.FUND -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun CategoryPill(c: Category, count: Int? = null) {
    val (bg, fg) = categoryColors(c)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(
            if (count != null) "${c.label} $count" else c.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ============================================================ Feed (one section per group)

private val TRADE_FILTERS = listOf("All", "Buys", "Sells")

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FeedScreen(
    store: Store,
    tick: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSetup: () -> Unit,
) {
    val alerts = remember(tick) { store.alerts() }
    val highlights = remember(tick) { Highlights.compute(store.moves(), store.picks()) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    val category = Category.entries[section]

    val inSection = alerts.filter { it.category == category }
    val q = query.trim()
    val shown = inSection.filter { a ->
        val matchesFilter = when (filter) {
            "Buys" -> a.buy
            "Sells" -> a.sell
            else -> true
        } || category == Category.FUND
        val matchesQuery = q.isEmpty() ||
            listOf(a.ticker, a.company, a.headline).any { it.contains(q, ignoreCase = true) } ||
            a.lines.any { it.contains(q, ignoreCase = true) }
        matchesFilter && matchesQuery
    }

    Column(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // First thing on screen: today's top buy and sale (or setup, before that).
                if (!store.isSetUp) item { SetupCard(onSetup) }
                else item(key = "today") { TodayCard(highlights) }
                // Section tabs stay pinned at the top while scrolling.
                stickyHeader(key = "tabs") {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                        ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp,
                            containerColor = MaterialTheme.colorScheme.background) {
                            Category.entries.forEachIndexed { i, c ->
                                val count = alerts.count { it.category == c }
                                Tab(
                                    selected = section == i,
                                    onClick = { section = i; filter = "All" },
                                    text = { Text(if (count > 0) "${c.label}  $count" else c.label) },
                                )
                            }
                        }
                    }
                }
                item { SearchField(query, { query = it }, "Search ${category.label.lowercase()}") }
                if (category != Category.FUND) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TRADE_FILTERS.forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text(f) },
                                )
                            }
                        }
                    }
                }
                if (!store.watching(category)) {
                    item {
                        EmptyState("${category.label} is turned off",
                            "Turn it back on under Settings → Sections.")
                    }
                } else if (shown.isEmpty()) {
                    item {
                        EmptyState(
                            if (inSection.isEmpty()) "No ${category.label.lowercase()} filings yet" else "Nothing matches",
                            if (inSection.isEmpty()) sectionHint(category) else "Try a different search or filter.",
                        )
                    }
                }
                items(shown, key = { it.key }) { AlertCard(it) }
                if (category == Category.SENATE || category == Category.HOUSE) {
                    item { BargoCredit() }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ============================================================ Today's buy & sale

@Composable
private fun TodayCard(h: DailyHighlights) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Today's buy & sale", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(if (h.label == "Today") "Biggest signals so far today" else "Nothing yet today · showing the last 3 days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showInfo = !showInfo }) { Text(if (showInfo) "Hide" else "How?") }
            }
            if (showInfo) {
                Text(
                    "Ranked by trade size, who made it (CEOs and CFOs count most, then other executives, " +
                        "directors and members of Congress), and for buys, how many other tracked people also " +
                        "bought or hold the stock. Only open-market trades count. This shows notable activity, " +
                        "not a prediction, and isn't financial advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HighlightTile("Top buy", h.buy, TradeColors.buy(), TradeColors.buyContainer(),
                "No open-market buys yet. Insider and Congress buys will appear here.")
            HighlightTile("Top sale", h.sale, TradeColors.sell(), TradeColors.sellContainer(),
                "No open-market sales yet.")
        }
    }
}

@Composable
private fun HighlightTile(title: String, h: Highlight?, accent: Color, container: Color, emptyText: String) {
    val context = LocalContext.current
    Surface(
        color = container,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = h != null) {
            h?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.move.link))) }
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = accent)
            if (h == null) {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val m = h.move
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.ticker, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.company, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.who + if (m.role.isNotEmpty() && m.category == Category.INSIDER) " · ${m.role}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CategoryPill(m.category)
            }
            h.reasons.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(r, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun sectionHint(c: Category) = when (c) {
    Category.SENATE, Category.HOUSE ->
        "Congress members have up to 45 days to disclose trades. New ones are checked every few hours. Pull down to check now."
    Category.INSIDER -> "Insider trades usually show up within 2 business days. Pull down to check now."
    Category.FUND -> "Big funds file holdings every quarter, mostly near mid-Feb, May, Aug and Nov."
}

@Composable
private fun BargoCredit() {
    val context = LocalContext.current
    TextButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Congress.SOURCE_URL))) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Congress data from Bargo, built from official House and Senate filings",
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun SetupCard(onSetup: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Add your name and email so the SEC lets the app check new filings.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSetup) { Text("Go to Settings") }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TickerBadge(text: String, bg: Color, fg: Color) {
    Box(
        Modifier.size(48.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.take(5), color = fg, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun AlertCard(a: Alert) {
    val context = LocalContext.current
    val isFund = a.category == Category.FUND
    val (badgeBg, badgeFg) = when {
        isFund -> categoryColors(Category.FUND)
        a.buy && !a.sell -> TradeColors.buyContainer() to TradeColors.buy()
        a.sell && !a.buy -> TradeColors.sellContainer() to TradeColors.sell()
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    ElevatedCard(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickerBadge(if (isFund) "13F" else a.ticker.ifEmpty { "—" }, badgeBg, badgeFg)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isFund) a.headline else a.ticker.ifEmpty { a.company },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        a.company,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(timeAgo(a.time), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (!isFund) {
                Text(a.headline, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
            }
            a.lines.forEach { TradeLine(it) }
        }
    }
}

@Composable
private fun TradeLine(line: String) {
    val color = when {
        line.startsWith("Bought") -> TradeColors.buy()
        line.startsWith("Sold") -> TradeColors.sell()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun timeAgo(time: Long): String =
    DateUtils.getRelativeTimeSpanString(
        time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

// ============================================================ Common picks

/** A stock that two or more of the tracked people bought or hold. */
private data class Overlap(
    val ticker: String,
    val company: String,
    val people: List<Pick>,                 // one entry per person
    val perCategory: Map<Category, Int>,
    val latest: Long,
)

private fun computeOverlaps(picks: List<Pick>): List<Overlap> =
    picks.groupBy { it.ticker }.mapNotNull { (ticker, list) ->
        val perPerson = list.groupBy { it.who }.map { (_, ps) ->
            // Prefer a buy over a holding for the same person, then the newest.
            ps.sortedWith(compareBy<Pick> { if (it.action == "buy") 0 else 1 }.thenByDescending { it.time }).first()
        }
        if (perPerson.size < 2) return@mapNotNull null
        Overlap(
            ticker = ticker,
            company = list.firstOrNull { it.company.isNotBlank() }?.company ?: "",
            people = perPerson.sortedWith(compareBy<Pick> { it.category.ordinal }.thenBy { it.who }),
            perCategory = perPerson.groupingBy { it.category }.eachCount(),
            latest = list.maxOf { it.time },
        )
    }.sortedWith(
        compareByDescending<Overlap> { it.perCategory.size }
            .thenByDescending { it.people.size }
            .thenByDescending { it.latest }
    )

private val PICK_FILTERS = listOf("Across groups", "All", "Congress", "Insiders")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicksScreen(store: Store, tick: Int, refreshing: Boolean, onRefresh: () -> Unit) {
    val overlaps = remember(tick) { computeOverlaps(store.picks()) }
    var filter by rememberSaveable { mutableStateOf(PICK_FILTERS[0]) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()

    val shown = overlaps.filter { o ->
        val f = when (filter) {
            "Across groups" -> o.perCategory.size >= 2
            "Congress" -> (o.perCategory[Category.SENATE] ?: 0) + (o.perCategory[Category.HOUSE] ?: 0) >= 2
            "Insiders" -> (o.perCategory[Category.INSIDER] ?: 0) >= 2
            else -> true
        }
        f && (q.isEmpty() || o.ticker.contains(q, true) || o.company.contains(q, true) ||
            o.people.any { it.who.contains(q, true) })
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Stocks that two or more of the people you track bought or hold in the last 90 days. " +
                        "Ones shared across different groups, like a senator and a fund, are listed first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SearchField(query, { query = it }, "Search ticker, company or person") }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PICK_FILTERS.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        if (overlaps.isEmpty()) "No common picks yet" else "Nothing matches",
                        if (overlaps.isEmpty())
                            "As filings come in, stocks that several people buy will show up here."
                        else if (filter == "Across groups")
                            "No stock is shared across groups yet. Try \"All\" to see overlaps within a group."
                        else "Try a different search or filter.",
                    )
                }
            }
            itemsIndexed(shown) { i, o -> PickCard(rank = i + 1, o = o) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCard(rank: Int, o: Overlap) {
    var expanded by rememberSaveable(o.ticker) { mutableStateOf(false) }
    val buyers = o.people.count { it.action == "buy" }
    val holders = o.people.size - buyers

    ElevatedCard(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickerBadge(o.ticker, TradeColors.buyContainer(), TradeColors.buy())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.ticker, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(o.company, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("#$rank", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                buildString {
                    append("${o.people.size} people")
                    if (o.perCategory.size > 1) append(" across ${o.perCategory.size} groups")
                    if (buyers > 0) append(" · $buyers bought")
                    if (holders > 0) append(" · $holders hold")
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Category.entries.forEach { c ->
                    val n = o.perCategory[c] ?: 0
                    if (n > 0) CategoryPill(c, n)
                }
            }
            if (expanded) {
                Category.entries.forEach { c ->
                    val people = o.people.filter { it.category == c }
                    if (people.isNotEmpty()) {
                        Column {
                            Text(c.label, style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold)
                            people.forEach { p ->
                                Text(
                                    "• ${p.who}" + if (p.action == "hold") " (holds)" else " (bought)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                Text("Tap to see who", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ============================================================ Settings

private val MIN_VALUES = listOf(0.0 to "Any", 10_000.0 to "\$10K", 100_000.0 to "\$100K", 1_000_000.0 to "\$1M")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(store: Store, onMessage: (String) -> Unit, onReady: () -> Unit) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf(store.userAgent) }
    var bargoKey by rememberSaveable { mutableStateOf(store.bargoKey) }
    var tickerInput by rememberSaveable { mutableStateOf("") }
    var tickers by remember { mutableStateOf(store.tickers.sorted()) }
    var buysOnly by remember { mutableStateOf(store.buysOnly) }
    var minValue by remember { mutableDoubleStateOf(store.minValue) }
    var background by remember { mutableStateOf(store.backgroundOn) }
    var watching by remember { mutableStateOf(Category.entries.associateWith { store.watching(it) }) }

    fun addTicker() {
        val t = tickerInput.trim().uppercase()
        if (t.isNotEmpty() && t !in tickers) {
            tickers = (tickers + t).sorted()
            store.tickers = tickers.toSet()
        }
        tickerInput = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Your info",
            "The SEC requires apps to identify themselves. This is only sent to sec.gov.") {
            val wasSetUp = store.isSetUp
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Name and email") },
                placeholder = { Text("Jane Doe jane@email.com") },
                singleLine = true,
                isError = email.isNotBlank() && !email.contains("@"),
                supportingText = {
                    if (email.isNotBlank() && !email.contains("@")) Text("Include an email address")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (!email.contains("@")) {
                        onMessage("Include an email address")
                        return@Button
                    }
                    store.userAgent = email
                    if (store.backgroundOn) CheckWorker.schedule(context)
                    if (!wasSetUp) onReady() else onMessage("Saved")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (wasSetUp) "Save" else "Save and start") }
        }

        Section("Sections", "Choose which groups to follow. Each gets its own tab in the feed.") {
            val subtitles = mapOf(
                Category.SENATE to "U.S. senators' stock trades (STOCK Act)",
                Category.HOUSE to "U.S. representatives' stock trades (STOCK Act)",
                Category.INSIDER to "Executives, directors and 10% owners (SEC Form 4)",
                Category.FUND to "Big investment funds' quarterly holdings (SEC 13F)",
            )
            Category.entries.forEach { c ->
                SwitchRow(c.label, subtitles[c] ?: "", watching[c] == true) { on ->
                    watching = watching + (c to on)
                    store.setWatching(c, on)
                }
            }
        }

        Section("Alert filters", "Filters only change what you get alerted about. Common picks always uses everything.") {
            SwitchRow("Purchases only", "Skip sales, stock awards and option exercises", buysOnly) {
                buysOnly = it; store.buysOnly = it
            }

            Text("Minimum trade size", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MIN_VALUES.forEach { (value, label) ->
                    FilterChip(
                        selected = minValue == value,
                        onClick = { minValue = value; store.minValue = value },
                        label = { Text(label) },
                    )
                }
            }

            Text("Watchlist", style = MaterialTheme.typography.titleSmall)
            Text(
                if (tickers.isEmpty()) "Watching every company. Add tickers to narrow it down."
                else "Only these companies will alert you. Tap one to remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tickerInput,
                    onValueChange = { v ->
                        tickerInput = v.uppercase().filter { it.isLetterOrDigit() || it == '.' }.take(8)
                    },
                    placeholder = { Text("Add ticker, e.g. NVDA") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { addTicker() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { addTicker() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ticker")
                }
            }
            if (tickers.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tickers.forEach { t ->
                        InputChip(
                            selected = false,
                            onClick = {
                                tickers = tickers - t
                                store.tickers = tickers.toSet()
                            },
                            label = { Text(t) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $t",
                                    modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
        }

        Section("Congress data",
            "Without a key, Senate and House are checked about every 5 hours to stay inside the free limit. " +
                "A free Bargo key lets the app check hourly.") {
            OutlinedTextField(
                value = bargoKey,
                onValueChange = { bargoKey = it },
                label = { Text("Bargo API key (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    store.bargoKey = bargoKey
                    store.lastCongressCheck = 0L
                    onMessage("Saved")
                }) { Text("Save key") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Congress.SOURCE_URL)))
                }) { Text("Get a free key") }
            }
        }

        Section("Background checks") {
            SwitchRow("Check automatically",
                "About every 15 minutes when you're online. Battery saver can delay this.",
                background) {
                background = it
                store.backgroundOn = it
                if (it && store.isSetUp) CheckWorker.schedule(context) else CheckWorker.stop(context)
            }
        }

        Section("History") {
            OutlinedButton(
                onClick = { store.clearAlerts(); onMessage("Feed history cleared") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clear feed history")
            }
        }

        Text(
            "Sources: SEC EDGAR (insiders and funds) and Bargo, built from official House Clerk and " +
                "Senate eFD filings (Congress). Form 4s arrive within 2 business days of a trade, congressional " +
                "trades within 45 days, and 13Fs up to 45 days after each quarter. Senior federal officials " +
                "and state legislators aren't included because their disclosures have no live public feed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
