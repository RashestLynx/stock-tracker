package com.rashestlynx.tradetracker

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
private const val TAB_WSB = 4
private const val TAB_NEWS = 5
private const val TAB_CRYPTO = 6
private const val TAB_HOME = 7
private const val TAB_MARKETS = 8
private const val TAB_WATCH = 9
private const val TAB_SOURCES = 10
private const val TAB_MOVERS = 1
private const val TAB_PICKS = 2
private const val TAB_SETTINGS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockTrackerApp(store: Store) {
    val context = LocalContext.current
    val tick = rememberStoreTick(store)
    var tab by rememberSaveable { mutableIntStateOf(if (store.isSetUp) TAB_HOME else TAB_SETTINGS) }
    var refreshing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val status = remember(tick) { store.lastStatus }
    var stock by remember { mutableStateOf<StockTarget?>(null) }
    var person by remember { mutableStateOf<PersonTarget?>(null) }

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

    // Check for new filings whenever the app is opened, and every 5 minutes while it's open.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && store.isSetUp) CheckWorker.runNow(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60_000L)
            if (store.isSetUp) CheckWorker.runNow(context)
        }
    }

    CompositionLocalProvider(LocalOpenPerson provides { person = it }, LocalOpenStock provides { stock = it }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (tab) {
                                TAB_HOME -> "Stock Tracker"
                                TAB_MARKETS -> "Markets"
                                TAB_WATCH -> "Watchlist"
                                TAB_FEED -> "Sections"
                                TAB_MOVERS -> "This week's movers"
                                TAB_WSB -> "WallStreetBets"
                                TAB_NEWS -> "Live news"
                                TAB_CRYPTO -> "Bitcoin & Ethereum"
                                TAB_PICKS -> "Common picks"
                                TAB_SOURCES -> "Sources"
                                else -> "Settings"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (tab == TAB_FEED || tab == TAB_HOME) {
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
                    if (tab == TAB_FEED || tab == TAB_HOME) {
                        IconButton(onClick = { checkNow() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Check now")
                        }
                    }
                    if (tab != TAB_SETTINGS) {
                        IconButton(onClick = { tab = TAB_SETTINGS }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    } else {
                        TextButton(onClick = { tab = TAB_HOME }) { Text("Done") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == TAB_HOME, onClick = { tab = TAB_HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Home") }, alwaysShowLabel = false)
                NavigationBarItem(selected = tab == TAB_WATCH, onClick = { tab = TAB_WATCH },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) }, label = { Text("Watchlist") }, alwaysShowLabel = false)
                NavigationBarItem(selected = tab == TAB_FEED, onClick = { tab = TAB_FEED },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) }, label = { Text("Sections") }, alwaysShowLabel = false)
                NavigationBarItem(selected = tab == TAB_NEWS, onClick = { tab = TAB_NEWS },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) }, label = { Text("News") }, alwaysShowLabel = false)
                NavigationBarItem(selected = tab == TAB_CRYPTO, onClick = { tab = TAB_CRYPTO },
                    icon = { Text("₿", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    label = { Text("Crypto") }, alwaysShowLabel = false)
                NavigationBarItem(selected = tab == TAB_MARKETS || tab == TAB_MOVERS || tab == TAB_PICKS || tab == TAB_WSB,
                    onClick = { tab = TAB_MARKETS },
                    icon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }, label = { Text("Markets") }, alwaysShowLabel = false)
                NavigationBarItem(selected = tab == TAB_SOURCES, onClick = { tab = TAB_SOURCES },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) }, label = { Text("Sources") }, alwaysShowLabel = false)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                TAB_HOME -> HomeScreen(store, tick, refreshing, onRefresh = { checkNow() },
                    onSetup = { tab = TAB_SETTINGS }, onOpen = { stock = it })
                TAB_WATCH -> WatchlistScreen(store, tick, onOpen = { stock = it })
                TAB_MARKETS, TAB_MOVERS, TAB_PICKS, TAB_WSB -> MarketsScreen(store, tick, refreshing,
                    onRefresh = { checkNow() }, onOpen = { stock = it }, onMessage = { message(it) })
                TAB_FEED -> FeedScreen(store, tick, refreshing, onRefresh = { checkNow() },
                    onSetup = { tab = TAB_SETTINGS }, onOpen = { stock = it })
                TAB_CRYPTO -> CryptoScreen(store, onOpen = { stock = it })
                TAB_NEWS -> NewsScreen(store, onOpen = { stock = it })
                TAB_SOURCES -> SourcesScreen(store)
                TAB_WSB -> WsbScreen(store, onOpen = { stock = it })
                TAB_MOVERS -> MoversScreen(store, onOpen = { stock = it }, onMessage = { message(it) })
                TAB_PICKS -> PicksScreen(store, tick, refreshing, onRefresh = { checkNow() },
                    onOpen = { stock = it })
                else -> SettingsScreen(store, onMessage = { message(it) }, onReady = {
                    tab = TAB_HOME
                    checkNow()
                })
            }
        }
    }

    stock?.let { StockSheet(it, store, onDismiss = { stock = null }) }
    person?.let { PersonSheet(it, store, onDismiss = { person = null }) }
    }
}

// ============================================================ Category colors

@Composable
private fun categoryColors(c: Category): Pair<Color, Color> = when (c) {
    Category.SENATE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    Category.HOUSE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    Category.INSIDER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    Category.FUND -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    Category.CHARGES -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
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
    onOpen: (StockTarget) -> Unit,
) {
    val alerts = remember(tick) { store.alerts() }
    val moves = remember(tick) { store.moves() }
    val histStatus = remember(tick) { History(store).status() }
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
        } || category == Category.FUND || category == Category.CHARGES
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
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // First thing on screen: today's top buy and sale (or setup, before that).
                if (!store.isSetUp) item { Box(Modifier.padding(horizontal = 16.dp)) { SetupCard(onSetup) } }
                // Today's buy & sale lives on Home now.
                // Section tabs stay pinned at the top while scrolling.
                stickyHeader(key = "tabs") {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                        PillTabs(Category.entries.map { c ->
                            val count = alerts.count { it.category == c }
                            if (count > 0) "${c.label}  $count" else c.label
                        }, section, { section = it; filter = "All" }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                item { Box(Modifier.padding(horizontal = 16.dp)) { SearchField(query, { query = it }, "Search ${category.label.lowercase()}") } }
                if (histStatus.isNotEmpty()) item {
                    Text(histStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (category != Category.FUND && category != Category.CHARGES) {
                    item {
                        PillTabs(TRADE_FILTERS, TRADE_FILTERS.indexOf(filter), { filter = TRADE_FILTERS[it] },
                            Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (!store.watching(category)) {
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            EmptyState("${category.label} is turned off", "Turn it back on under Settings → Sections.")
                        }
                    }
                } else if (shown.isEmpty()) {
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            EmptyState(
                                if (inSection.isEmpty()) "No ${category.label.lowercase()} filings yet" else "Nothing matches",
                                if (inSection.isEmpty()) sectionHint(category) else "Try a different search or filter.",
                            )
                        }
                    }
                }
                items(shown, key = { it.key }) { a ->
                    SavedPostItem(postOf(a, null), store, onStock = {
                        val price = moves.firstOrNull { it.key == a.key || it.key.startsWith(a.key + "|") }?.price ?: 0.0
                        onOpen(StockTarget(a.ticker, a.company, "This filing", a.headline, a.lines, a.link, price))
                    })
                }
                if (category == Category.SENATE || category == Category.HOUSE) {
                    item { Box(Modifier.padding(horizontal = 16.dp)) { BargoCredit() } }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ============================================================ Today's buy & sale

@Composable
private fun TodayCard(h: DailyHighlights, onOpen: (StockTarget) -> Unit) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    ModernCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
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
                "No open-market buys yet. Insider and Congress buys will appear here.", onOpen)
            HighlightTile("Top sale", h.sale, TradeColors.sell(), TradeColors.sellContainer(),
                "No open-market sales yet.", onOpen)
        }
    }
}

@Composable
private fun HighlightTile(
    title: String, h: Highlight?, accent: Color, container: Color, emptyText: String,
    onOpen: (StockTarget) -> Unit,
) {
    Surface(
        color = container,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = h != null) {
            h?.let {
                val m = it.move
                val who = m.who + if (m.category == Category.INSIDER && m.role.isNotEmpty()) " · ${m.role}" else ""
                onOpen(StockTarget(m.ticker, m.company, "This trade", who, it.reasons, m.link, m.price,
                    if (m.side == "buy") "Bought" else "Sold"))
            }
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
            val openPerson = LocalOpenPerson.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tap for a summary of ${m.ticker}", style = MaterialTheme.typography.labelSmall,
                    color = accent, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { openPerson(personOf(m)) }) { Text("Investor profile") }
            }
        }
    }
}

private fun sectionHint(c: Category) = when (c) {
    Category.SENATE, Category.HOUSE ->
        "Congress members have up to 45 days to disclose trades. New ones are checked every few hours. Pull down to check now."
    Category.INSIDER -> "Insider trades usually show up within 2 business days. Pull down to check now."
    Category.FUND -> "Big funds file holdings every quarter, mostly near mid-Feb, May, Aug and Nov."
    Category.CHARGES -> "When the SEC charges someone with illegal insider trading, the case shows up here. Pull down to check now."
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
private fun AlertCard(a: Alert, onOpen: () -> Unit) {
    val context = LocalContext.current
    val isFund = a.category == Category.FUND || a.category == Category.CHARGES
    val (badgeBg, badgeFg) = when {
        isFund -> categoryColors(a.category)
        a.buy && !a.sell -> TradeColors.buyContainer() to TradeColors.buy()
        a.sell && !a.buy -> TradeColors.sellContainer() to TradeColors.sell()
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    ModernCard(
        onClick = {
            // Stocks open a summary; fund reports (no single stock) open the filing.
            if (a.ticker.isNotEmpty() && !isFund) onOpen()
            else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link)))
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickerBadge(
                    when {
                        a.category == Category.CHARGES -> "SEC"
                        isFund -> "13F"
                        else -> a.ticker.ifEmpty { "—" }
                    }, badgeBg, badgeFg)
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
            if (a.category != Category.CHARGES) {
                val openPerson = LocalOpenPerson.current
                TextButton(onClick = { openPerson(personOf(a)) }) {
                    Text(if (a.category == Category.FUND) "Fund profile" else "Investor profile")
                }
            }
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
private fun PicksScreen(
    store: Store, tick: Int, refreshing: Boolean, onRefresh: () -> Unit,
    onOpen: (StockTarget) -> Unit,
) {
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
                PillTabs(PICK_FILTERS, PICK_FILTERS.indexOf(filter), { filter = PICK_FILTERS[it] })
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
            itemsIndexed(shown) { i, o ->
                PickCard(rank = i + 1, o = o, onSummary = {
                    onOpen(StockTarget(o.ticker, o.company, "", "", emptyList(), "", 0.0))
                })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCard(rank: Int, o: Overlap, onSummary: () -> Unit) {
    var expanded by rememberSaveable(o.ticker) { mutableStateOf(false) }
    val buyers = o.people.count { it.action == "buy" }
    val holders = o.people.size - buyers

    ModernCard(
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
                OutlinedButton(onClick = onSummary) { Text("Summary of ${o.ticker}") }
                Category.entries.forEach { c ->
                    val people = o.people.filter { it.category == c }
                    if (people.isNotEmpty()) {
                        Column {
                            Text(c.label, style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold)
                            val openPerson = LocalOpenPerson.current
                            people.forEach { p ->
                                Text(
                                    "• ${p.who}" + (if (p.action == "hold") " (holds)" else " (bought)") + "  ›",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { openPerson(personOf(p)) }.padding(vertical = 3.dp),
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
                Category.CHARGES to "SEC enforcement cases for illegal insider trading",
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

        var finnhubKey by rememberSaveable { mutableStateOf(Live.key(store)) }
        Section("Live prices", "Live stock prices for your watchlist and stock summaries use a free key from Finnhub.") {
            OutlinedTextField(value = finnhubKey, onValueChange = { finnhubKey = it }, label = { Text("Finnhub API key") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Live.setKey(store, finnhubKey); onMessage("Saved") }) { Text("Save key") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Live.SIGNUP))) }) {
                    Text("Get a free key")
                }
            }
        }

        var massiveKey by rememberSaveable { mutableStateOf(store.massiveKey) }
        var broker by remember { mutableStateOf(Broker.name(store)) }
        var customLink by rememberSaveable { mutableStateOf(Broker.customTemplate(store)) }
        Section("Your brokerage",
            "Stock summaries get a button that opens the ticker in this app. If the app is installed it opens there, otherwise in the browser.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (Broker.CHOICES.map { it.name } + Broker.CUSTOM).forEach { n ->
                    FilterChip(shape = RoundedCornerShape(50), selected = broker == n, onClick = {
                        broker = n
                        Broker.set(store, n, customLink)
                    }, label = { Text(n) })
                }
            }
            if (broker == Broker.CUSTOM) {
                OutlinedTextField(
                    value = customLink,
                    onValueChange = { customLink = it; Broker.set(store, Broker.CUSTOM, it) },
                    label = { Text("Link with {T} where the ticker goes") },
                    placeholder = { Text("https://yourbroker.com/quote/{T}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Open any stock in your brokerage's website, copy the address, and replace the ticker with {T}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section("Market data",
            "The Movers tab uses a free key from Massive (formerly Polygon.io) for weekly stock prices.") {
            OutlinedTextField(
                value = massiveKey,
                onValueChange = { massiveKey = it },
                label = { Text("Massive API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    store.massiveKey = massiveKey
                    store.moversCache = ""
                    onMessage("Saved")
                }) { Text("Save key") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MASSIVE_SIGNUP)))
                }) { Text("Get a free key") }
            }
        }

        var themeMode by remember { mutableStateOf(Appearance.mode(store)) }
        var marketBg by remember { mutableStateOf(Appearance.marketBg(store)) }
        Section("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = themeMode == key,
                        onClick = { themeMode = key; store.prefs.edit().putString(Appearance.MODE, key).apply() },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(label) }
                }
            }
            SwitchRow("Market color background",
                "Green when the S&P 500 is up today, red when it's down. Checked every 5 minutes.", marketBg) {
                marketBg = it; store.prefs.edit().putBoolean(Appearance.MARKET_BG, it).apply()
            }
        }

        var newsAlerts by remember { mutableStateOf(store.newsAlerts) }
        Section("News alerts") {
            SwitchRow("Watchlist news",
                "Get notified about headlines and SEC filings for the tickers in your watchlist.", newsAlerts) {
                newsAlerts = it; store.newsAlerts = it
            }
        }

        Section("Background checks") {
            SwitchRow("Check automatically",
                "About every 15 minutes when you're online, and you get a notification for each new filing.",
                background) {
                background = it
                store.backgroundOn = it
                if (it && store.isSetUp) CheckWorker.schedule(context) else CheckWorker.stop(context)
            }
            NotificationHealth()
        }

        var appNotify by remember { mutableStateOf(store.appNotify) }
        Section("Live alerts",
            "For alerts within a minute or two, around the clock, even with the app closed: set up the free cloud checker " +
                "(see the cloud folder's README in the project) and the free ntfy app. Then turn this off so you don't get each alert twice.") {
            SwitchRow("Also alert from this app", "Notifications for new filings from the app's own checks.", appNotify) {
                appNotify = it; store.appNotify = it
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

/**
 * Shows what's stopping notifications from arriving on their own, with a button to fix each one:
 * notifications turned off, or Android's battery saving holding background checks back.
 */
@Composable
private fun NotificationHealth() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Re-check when coming back from Android's settings screens.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notificationsOn = remember(tick) { androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val unrestricted = remember(tick) { CheckAlarm.unrestricted(context) }

    if (!notificationsOn) {
        Text("Notifications are turned off for this app, so new filings can't alert you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth()) { Text("Turn on notifications") }
    }
    if (!unrestricted) {
        Text("Android's battery saving is holding back background checks, so alerts can arrive late or only when " +
            "you open the app. Allow the app to run in the background to get them on time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.packageName)))
            }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Allow background checks") }
    }
    if (notificationsOn && unrestricted) {
        Text("✓ Notifications are on and background checks aren't limited.",
            style = MaterialTheme.typography.bodySmall, color = TradeColors.buy())
    }
}

// ============================================================ Stock summary sheet

/** What was tapped: a stock, plus (optionally) the trade or filing it came from. */
data class StockTarget(
    val ticker: String,
    val company: String,
    val heading: String,          // "This trade" / "This filing", or "" for none
    val who: String,
    val lines: List<String>,
    val link: String,
    val tradePrice: Double,
    val side: String = "",        // "Bought" / "Sold" when known
)

private data class Activity(val bought: List<Pick>, val held: List<Pick>, val sold: List<Move>)

private fun activityFor(ticker: String, store: Store): Activity {
    val picks = store.picks().filter { it.ticker == ticker }
    return Activity(
        bought = picks.filter { it.action == "buy" }.distinctBy { it.who },
        held = picks.filter { it.action == "hold" }.distinctBy { it.who },
        sold = store.moves().filter { it.ticker == ticker && it.side == "sell" }.distinctBy { it.who },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockSheet(t: StockTarget, store: Store, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var summary by remember(t.ticker) { mutableStateOf<StockSummary?>(null) }
    var loading by remember(t.ticker) { mutableStateOf(true) }
    val activity = remember(t.ticker) { activityFor(t.ticker, store) }

    var liveQ by remember(t.ticker) { mutableStateOf<LiveQuote?>(null) }
    LaunchedEffect(t.ticker) {
        summary = withContext(Dispatchers.IO) {
            runCatching { StockInfo.load(t.ticker, t.company, store) }.getOrNull()
        }
        loading = false
    }
    LaunchedEffect(t.ticker) {
        while (Live.key(store).isNotBlank()) {
            liveQ = withContext(Dispatchers.IO) { runCatching { Live.quote(t.ticker, store) }.getOrNull() }
            delay(15_000)
        }
    }

    fun open(url: String) {
        if (url.isNotEmpty()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---- header ----
            val s = summary
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickerBadge(t.ticker, TradeColors.buyContainer(), TradeColors.buy())
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s?.name?.ifBlank { null } ?: t.company.ifBlank { t.ticker },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val sub = listOfNotNull(t.ticker, s?.exchange?.ifBlank { null }, s?.industry?.ifBlank { null })
                    Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Broker.link(store, t.ticker)?.let { url ->
                Button(onClick = { open(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open ${t.ticker} in ${Broker.name(store)}")
                }
            }
            var watching by remember(t.ticker) { mutableStateOf(t.ticker.uppercase() in store.watchlist) }
            OutlinedButton(onClick = {
                store.watchlist = if (watching) store.watchlist - t.ticker.uppercase() else store.watchlist + t.ticker.uppercase()
                watching = !watching
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (watching) "On your watchlist (tap to remove)" else "Add to watchlist")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            // ---- price ----
            PriceChartBlock(t.ticker, store, liveQ)
            liveQ?.let { q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(TradeColors.sell(), CircleShape))
                    Text(" LIVE · " + String.format(java.util.Locale.US, "%s%.2f (%+.2f%%) today",
                        if (q.change >= 0) "▲ +" else "▼ ", q.change, q.changePct),
                        color = if (q.change >= 0) TradeColors.buy() else TradeColors.sell(),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            (liveQ?.price ?: s?.price)?.let { price ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(String.format(java.util.Locale.US, "\$%,.2f", price),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(if (liveQ != null) "live" else if (!s?.priceDate.isNullOrEmpty()) "close ${s?.priceDate}" else "latest",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                    if (t.tradePrice > 0) {
                        val pct = (price - t.tradePrice) / t.tradePrice * 100
                        val up = pct >= 0
                        Text(
                            String.format(java.util.Locale.US, "%s %.1f%% since this trade at \$%,.2f",
                                if (up) "▲" else "▼", kotlin.math.abs(pct), t.tradePrice),
                            color = if (up) TradeColors.buy() else TradeColors.sell(),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ---- about ----
            if (!loading) {
                SheetBlock("About") {
                    Text(
                        s?.about?.ifBlank { null } ?: "No company description found.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s?.location?.takeIf { it.isNotBlank() }?.let {
                        Text("Headquarters: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!s?.aboutUrl.isNullOrBlank()) {
                        TextButton(onClick = { open(s!!.aboutUrl) }) { Text("Read more on Wikipedia") }
                    }
                }
            }

            // ---- the trade that was tapped ----
            if (t.heading.isNotEmpty()) {
                SheetBlock(t.heading) {
                    if (t.who.isNotBlank()) {
                        Text(t.who, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    t.lines.forEach { TradeLine(it) }
                    if (t.link.isNotEmpty()) {
                        OutlinedButton(onClick = { open(t.link) }) { Text("View official filing") }
                    }
                }
            }

            // ---- others tracking it ----
            SheetBlock("Who else is in it") {
                if (activity.bought.isEmpty() && activity.held.isEmpty() && activity.sold.isEmpty()) {
                    Text("No one else you track has bought, sold or held it recently.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PeopleLine("Bought (90 days)", activity.bought.map { "${it.who} (${it.category.label})" to personOf(it) },
                    TradeColors.buy())
                PeopleLine("Funds holding it", activity.held.map { it.who to personOf(it) },
                    MaterialTheme.colorScheme.onSurface)
                PeopleLine("Sold (7 days)", activity.sold.map { "${it.who} (${it.category.label})" to personOf(it) },
                    TradeColors.sell())
            }

            Text(
                "Company info from the SEC and Wikipedia; price is delayed. This is a summary of public " +
                    "information, not financial advice.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun PeopleLine(label: String, people: List<Pair<String, PersonTarget>>, color: Color) {
    if (people.isEmpty()) return
    val openPerson = LocalOpenPerson.current
    Column {
        Text("$label · ${people.size}", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = color)
        people.take(10).forEach { (label2, target) ->
            Text("• $label2  ›", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openPerson(target) }.padding(vertical = 3.dp))
        }
        if (people.size > 10) Text("• and ${people.size - 10} more", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


// ============================================================ Movers (best / worst of the week)

private const val MASSIVE_SIGNUP = "https://massive.com/pricing"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoversScreen(store: Store, onOpen: (StockTarget) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf(Movers.cached(store)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scope by rememberSaveable { mutableIntStateOf(0) } // 0 = whole market, 1 = people you track
    var hasKey by remember { mutableStateOf(store.massiveKey.isNotBlank()) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }

    LaunchedEffect(hasKey, reloadToken) {
        if (!hasKey) return@LaunchedEffect
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { Movers.load(store, force) } }
        r.onSuccess { result = it }.onFailure { error = it.message ?: "Couldn't load market data" }
        force = false
        loading = false
    }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { force = true; reloadToken++ },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasKey) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("One-time setup", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("Weekly prices for every U.S. stock come from Massive (formerly Polygon.io). " +
                                "Their free plan works: sign up, copy your API key, and paste it here.",
                                style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MASSIVE_SIGNUP)))
                            }) { Text("Get a free key") }
                            OutlinedTextField(
                                value = keyInput, onValueChange = { keyInput = it },
                                label = { Text("Paste API key") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    store.massiveKey = keyInput
                                    hasKey = keyInput.isNotBlank()
                                    if (hasKey) onMessage("Key saved")
                                },
                                enabled = keyInput.isNotBlank(),
                            ) { Text("Save and load movers") }
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                PillTabs(listOf("Whole market", "People you track"), scope, { scope = it })
            }

            val r = result
            item {
                Text(
                    if (r != null) "Price change from ${r.fromDate} to ${r.toDate} (closing prices)." +
                        if (scope == 0) " U.S. stocks over \$5 with real trading volume." else
                            " Only stocks the people you track bought, sold or hold."
                    else "Loading this week's prices…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { e ->
                item {
                    Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (r != null) {
                val gainers = if (scope == 0) r.marketGainers else r.trackedGainers
                val losers = if (scope == 0) r.marketLosers else r.trackedLosers
                item { MoversList("Top 10 this week", gainers, true, onOpen) }
                item { MoversList("Bottom 10 this week", losers, false, onOpen) }
            }
            item {
                Text("Data from Massive. Past performance isn't a prediction or financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MoversList(title: String, list: List<Mover>, up: Boolean, onOpen: (StockTarget) -> Unit) {
    val accent = if (up) TradeColors.buy() else TradeColors.sell()
    val container = if (up) TradeColors.buyContainer() else TradeColors.sellContainer()
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (list.isEmpty()) {
                Text("Nothing to show yet.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
            list.forEachIndexed { i, m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(StockTarget(m.ticker, m.name, "", "", emptyList(), "", 0.0)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.ticker, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(m.name, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(color = container, shape = RoundedCornerShape(50)) {
                            Text(String.format(java.util.Locale.US, "%+.1f%%", m.pct),
                                color = accent, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        Text(String.format(java.util.Locale.US, "\$%,.2f", m.price),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


// ============================================================ WallStreetBets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WsbScreen(store: Store, onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    var source by rememberSaveable { mutableIntStateOf(0) } // 0 = r/wallstreetbets, 1 = all stock subs
    var sort by rememberSaveable { mutableIntStateOf(0) }   // 0 = most talked about, 1 = spiking
    var list by remember { mutableStateOf<List<WsbStock>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    val picks = remember { store.picks() }

    LaunchedEffect(source, reload) {
        loading = true
        error = null
        val filter = if (source == 0) "wallstreetbets" else "all-stocks"
        withContext(Dispatchers.IO) { runCatching { Wsb.trending(filter, force) } }
            .onSuccess { list = it }
            .onFailure { error = it.message ?: "Couldn't load Reddit data" }
        force = false
        loading = false
    }

    val shown = if (sort == 0) list.take(25)
    else list.filter { it.mentions >= 10 && it.mentionChange != null }
        .sortedByDescending { it.mentionChange }.take(25)

    PullToRefreshBox(isRefreshing = loading, onRefresh = { force = true; reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PillTabs(listOf("r/wallstreetbets", "All stock subs"), source, { source = it })
            }
            item {
                PillTabs(listOf("Most talked about", "Spiking"), sort, { sort = it })
            }
            item {
                Text(
                    if (sort == 0) "Most-mentioned stocks on Reddit in the last 24 hours."
                    else "Stocks whose mentions jumped the most vs the day before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            if (!loading && error == null && shown.isEmpty()) {
                item { EmptyState("Nothing trending right now", "Pull down to refresh.") }
            }
            items(shown, key = { it.ticker }) { w ->
                val trackedBy = picks.filter { it.ticker == w.ticker }.map { it.category }.distinct()
                WsbRow(w, trackedBy) { onOpen(StockTarget(w.ticker, w.name, "", "", emptyList(), "", 0.0)) }
            }
            item {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Wsb.SOURCE_URL))) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Reddit data from ApeWisdom. Hype isn't a recommendation; trending stocks often swing hard both ways.",
                        style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WsbRow(w: WsbStock, trackedBy: List<Category>, onClick: () -> Unit) {
    ModernCard(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${w.rank}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(w.name, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                w.mentionChange?.let { ch ->
                    val up = ch >= 0
                    Surface(color = if (up) TradeColors.buyContainer() else TradeColors.sellContainer(),
                        shape = RoundedCornerShape(50)) {
                        Text(String.format(java.util.Locale.US, "%+.0f%%", ch),
                            color = if (up) TradeColors.buy() else TradeColors.sell(),
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                } ?: CategoryPillText("New")
            }
            Text(
                buildString {
                    append(String.format(java.util.Locale.US, "%,d mentions", w.mentions))
                    append(String.format(java.util.Locale.US, " · %,d upvotes", w.upvotes))
                    if (w.rankChange > 0) append(" · ▲${w.rankChange} spots")
                    if (w.rankChange < 0) append(" · ▼${-w.rankChange} spots")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (trackedBy.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Also in:", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    trackedBy.forEach { CategoryPill(it) }
                }
            }
        }
    }
}

@Composable
private fun CategoryPillText(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(50)) {
        Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}


// ============================================================ Investor profiles

val LocalOpenPerson = staticCompositionLocalOf<(PersonTarget) -> Unit> { {} }
val LocalOpenStock = staticCompositionLocalOf<(StockTarget) -> Unit> { {} }

private fun cleanName(who: String) = who.removePrefix("Sen. ").removePrefix("Rep. ").substringBefore(" · ")

fun personOf(m: Move) = PersonTarget(m.person, cleanName(m.who), m.category, m.role)
fun personOf(p: Pick) = PersonTarget(p.person, cleanName(p.who), p.category)
fun personOf(a: Alert) = PersonTarget(a.person, cleanName(a.headline).substringBefore(", "), a.category,
    a.headline.substringAfter(" · ", "").takeIf { a.category == Category.INSIDER } ?: "")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PersonSheet(t: PersonTarget, store: Store, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var profile by remember(t.key, t.name) { mutableStateOf<PersonProfile?>(null) }
    var loading by remember(t.key, t.name) { mutableStateOf(true) }

    // Their activity captured by this app.
    val activity = remember(t.key, t.name) {
        fun mine(person: String, who: String) =
            if (t.key.isNotEmpty() && person.isNotEmpty()) person == t.key else cleanName(who).equals(t.name, true)
        val moves = store.moves().filter { mine(it.person, it.who) }.sortedByDescending { it.time }
        val holds = store.picks().filter { it.action == "hold" && mine(it.person, it.who) }
        moves to holds
    }

    LaunchedEffect(t.key, t.name) {
        profile = withContext(Dispatchers.IO) { runCatching { People.load(t, store) }.getOrNull() }
        loading = false
    }

    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val p = profile
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg) = categoryColors(t.category)
                TickerBadge(if (t.category == Category.FUND) fundBadge(p?.name ?: t.name) else initials(p?.name ?: t.name), bg, fg)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.name ?: t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(p?.subtitle ?: t.category.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Follow: adds them to People you follow, with their trades in the Following feed.
                var followed by remember(t.key, t.name) {
                    mutableStateOf(Following.load(store).any { (t.key.isNotEmpty() && it.person == t.key) || it.name.equals(t.name, true) })
                }
                if (followed) OutlinedButton(onClick = {
                    Following.save(store, Following.load(store).filterNot { (t.key.isNotEmpty() && it.person == t.key) || it.name.equals(t.name, true) })
                    followed = false
                }) { Text("Following") }
                else Button(onClick = {
                    Following.save(store, Following.load(store) + Following.fromProfile(t, p?.name ?: t.name, p?.subtitle ?: t.category.label))
                    followed = true
                }) { Text("Follow") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (p != null && p.note.isNotEmpty()) {
                Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (p != null && p.facts.isNotEmpty()) {
                SheetBlock("Profile") {
                    p.facts.forEach { (k, v) ->
                        Row {
                            Text(k, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if (p != null && p.committees.isNotEmpty()) {
                SheetBlock("Committees") {
                    Text("Committees oversee specific industries, which can matter when a member trades stocks in them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.committees.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (p != null && p.record.isNotEmpty()) {
                SheetBlock("Recent disclosed trades") {
                    p.record.take(15).forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.ticker.ifEmpty { "—" }} · ${r.type}", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(listOf(r.amount, r.date).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            r.perfPct?.let { pct ->
                                Text(String.format(java.util.Locale.US, "%+.1f%%", pct),
                                    color = if (pct >= 0) TradeColors.buy() else TradeColors.sell(),
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Text("% = how the stock has moved since the trade date.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val (moves, holds) = activity
            SheetBlock("In this app") {
                if (moves.isEmpty() && holds.isEmpty()) {
                    Text("No trades from them have been captured yet.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (moves.isNotEmpty()) {
                    Text("Buys ${moves.count { it.side == "buy" }} · Sales ${moves.count { it.side == "sell" }} (last 7 days)",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    moves.take(12).forEach { m -> TradeLine("${if (m.side == "buy") "Bought" else "Sold"} ${m.ticker} · ${m.valueLabel}") }
                }
                if (holds.isNotEmpty()) {
                    Text("Top holdings", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        holds.map { it.ticker }.distinct().take(20).forEach { CategoryPillText(it) }
                    }
                }
            }

            if (p != null && p.links.isNotEmpty()) {
                SheetBlock("Official sources") {
                    p.links.forEach { (label, url) -> TextButton(onClick = { open(url) }) { Text(label) } }
                }
            }
            Text("Only public information about their public role is shown. Personal details such as home " +
                "addresses are left out on purpose.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun initials(name: String): String =
    name.split(" ").filter { it.isNotBlank() && it[0].isLetter() }.let { w ->
        ((w.firstOrNull()?.take(1) ?: "") + (if (w.size > 1) w.last().take(1) else "")).uppercase()
    }.ifEmpty { "?" }


// ============================================================ Live news

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsScreen(store: Store, onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 market, 1 your stocks, 2 company filings
    var items by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var updated by remember { mutableStateOf(0L) }

    val yourTickers = remember {
        (store.tickers.toList() + store.picks().groupingBy { it.ticker }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }).distinct().take(8)
    }

    // Refresh now, then every minute while this tab is open.
    LaunchedEffect(view, reload) {
        while (view != 3) {
            loading = true
            error = null
            withContext(Dispatchers.IO) {
                runCatching {
                    when (view) {
                        0 -> News.market()
                        1 -> News.forTickers(yourTickers)
                        2 -> News.filings(store)
                        else -> emptyList()
                    }
                }
            }.onSuccess { items = it; updated = System.currentTimeMillis() }
                .onFailure { error = it.message ?: "Couldn't load news" }
            loading = false
            delay(60_000)
        }
    }

    PullToRefreshBox(isRefreshing = loading && items.isEmpty(), onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                PillTabs(listOf("Market", "Stocks", "Filings", "People"), view, { view = it; items = emptyList() })
            }
            if (view == 3) {
                item { FollowingList(store) }
                return@LazyColumn
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(TradeColors.sell(), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "LIVE · refreshes every minute" + if (updated > 0) " · updated ${timeAgo(updated)}" else "",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    when (view) {
                        0 -> "Latest market headlines from Google News."
                        1 -> if (yourTickers.isEmpty()) "Add tickers to your watchlist in Settings to follow them here."
                             else "Headlines about ${yourTickers.joinToString(", ")}."
                        else -> "Official company announcements (SEC 8-K), posted the moment they're filed: earnings, CEO changes, deals and more."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            if (loading && items.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(items.take(60), key = { it.key }) { n ->
                // Each headline as a post: the source is the author.
                Row(Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url))) }
                    .padding(vertical = 10.dp)) {
                    Avatar(n.source.ifEmpty { "News" }, 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.source.ifEmpty { "News" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text("  ·  " + timeAgo(n.time), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (n.detail.isNotEmpty()) {
                            Text(n.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (n.ticker.isNotEmpty()) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).clickable {
                                    onOpen(StockTarget(n.ticker, if (n.isFiling) n.title else "", "", "", emptyList(), "", 0.0))
                                }) {
                                Text("$" + n.ticker + "  ›", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            item {
                Text("Headlines from Google News; filings from SEC EDGAR. Tap a story to read it at the source.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ============================================================ People you follow (creators etc.)

@Composable
private fun FollowingList(store: Store) {
    var list by remember { mutableStateOf(Following.load(store)) }
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Private investors like finance YouTubers don't have to report their trades, so this follows " +
            "what they share publicly: their newest videos (where they often say what they're buying or selling), " +
            "news about them, and their businesses. You'll get a notification when they post a new video.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { f ->
            FollowedCard(f, store, onRemove = {
                list = list - f
                Following.save(store, list)
            })
        }
        if (adding) {
            var name by rememberSaveable { mutableStateOf("") }
            var handle by rememberSaveable { mutableStateOf("") }
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Follow someone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(handle, { handle = it }, label = { Text("YouTube handle (optional), e.g. @grahamstephan") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = name.isNotBlank(), onClick = {
                            list = list + Followed(name.trim(), handle.trim().removePrefix("@"), "\"${name.trim()}\"",
                                emptyList(), emptyList())
                            Following.save(store, list)
                            adding = false
                        }) { Text("Follow") }
                        TextButton(onClick = { adding = false }) { Text("Cancel") }
                    }
                }
            }
        } else {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Follow someone else")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowedCard(f: Followed, store: Store, onRemove: () -> Unit) {
    val context = LocalContext.current
    var videos by remember(f.name) { mutableStateOf<List<Video>?>(null) }
    var news by remember(f.name) { mutableStateOf<List<NewsItem>?>(null) }
    var mentions by remember(f.name) { mutableStateOf<List<Mention>?>(null) }
    val openStock = LocalOpenStock.current
    // Actual reported trades, if this person files them (Congress members, insiders, funds).
    val reported = remember(f.name) {
        store.moves().filter { cleanName(it.who).contains(f.name, ignoreCase = true) }.sortedByDescending { it.time }
    }
    LaunchedEffect(f.name) {
        videos = withContext(Dispatchers.IO) { runCatching { Following.videos(f, store) }.getOrDefault(emptyList()) }
        news = withContext(Dispatchers.IO) { runCatching { News.search(f.newsQuery) }.getOrDefault(emptyList()) }
        mentions = withContext(Dispatchers.IO) {
            runCatching { Following.mentions(videos.orEmpty(), news.orEmpty(), store) }.getOrDefault(emptyList())
        }
    }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickerBadge(if (isFund(f)) fundBadge(f.name) else initials(f.name), MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(f.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Unfollow") }
            }
            if (f.businesses.isNotEmpty()) {
                Text("Businesses", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                f.businesses.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            val known = Following.KNOWN[f.name]
            var disclosed by remember(f.name) { mutableStateOf<List<Holding>>(emptyList()) }
            LaunchedEffect(f.name) {
                val feed = known?.disclosureFeed.orEmpty()
                if (feed.isNotEmpty()) disclosed = withContext(Dispatchers.IO) {
                    runCatching { Following.disclosures(feed, f.name, store) }.getOrDefault(emptyList())
                }
            }
            val owns = (known?.owns.orEmpty() + disclosed).distinctBy { it.ticker.ifEmpty { it.name } }
            if (owns.isNotEmpty()) {
                Text(known?.ownsTitle?.ifEmpty { null } ?: if (f.insider.isNotEmpty()) "Other reported holdings" else "Latest holdings (in their own words)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold)
                owns.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = h.ticker.isNotEmpty()) {
                            openStock(StockTarget(h.ticker, h.name, "", "", emptyList(), "", 0.0))
                        }) {
                            Text(if (h.ticker.isNotEmpty()) "${h.ticker} · ${h.name}" else h.name,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(h.note, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { open(h.sourceUrl) }) { Text(h.source, maxLines = 1) }
                    }
                }
            }
            if (known != null && known.recommends.isNotEmpty()) {
                Text("Funds they recommend (not disclosed as owned)", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    known.recommends.forEach { t ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50),
                            modifier = Modifier.clickable { openStock(StockTarget(t, "", "", "", emptyList(), "", 0.0)) }) {
                            Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
                TextButton(onClick = { open(known.recommendsSource) }) { Text("Source") }
            }
            if (reported.isNotEmpty()) {
                Text("Reported trades", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                reported.take(8).forEach { m ->
                    Box(Modifier.clickable { openStock(StockTarget(m.ticker, m.company, "", "", emptyList(), "", 0.0)) }) {
                        TradeLine("${if (m.side == "buy") "Bought" else "Sold"} ${m.ticker} · ${m.valueLabel} · ${timeAgo(m.time)}")
                    }
                }
            }
            Text("Stocks they've talked about", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            when {
                mentions == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                mentions!!.isEmpty() -> Text("No specific stocks named in their recent videos or news.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    mentions!!.forEach { m ->
                        val color = when (m.stance) {
                            "buying" -> TradeColors.buy()
                            "selling" -> TradeColors.sell()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { openStock(StockTarget(m.ticker, m.company, "", "", emptyList(), "", 0.0)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(m.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.width(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.company, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text("${m.count} mention${if (m.count == 1) "" else "s"} · latest ${timeAgo(m.latest)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(m.stance.replaceFirstChar { it.uppercase() }, color = color,
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text("From their video titles and descriptions and news about them. These are things they've said, " +
                        "not confirmed trades.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (f.insider.isNotEmpty()) InsiderBlock(f, store)
            if (f.cik.isNotEmpty()) FundHoldingsBlock(f, store)
            if (f.youtube.isNotBlank()) Text("Latest videos", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (f.youtube.isNotBlank()) when {
                videos == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                videos!!.isEmpty() -> Text(if (f.youtube.isBlank()) "No YouTube channel added." else "Couldn't load videos right now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> videos!!.take(5).forEach { v ->
                    Column(Modifier.fillMaxWidth().clickable { open(v.url) }.padding(vertical = 4.dp)) {
                        Text(v.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(timeAgo(v.time), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("In the news", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            when {
                news == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                news!!.isEmpty() -> Text("No recent news.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> news!!.take(4).forEach { n ->
                    Column(Modifier.fillMaxWidth().clickable { open(n.url) }.padding(vertical = 4.dp)) {
                        Text(n.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${n.source} · ${timeAgo(n.time)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (f.links.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.links.forEach { (label, url) -> OutlinedButton(onClick = { open(url) }) { Text(label) } }
                }
            }
        }
    }
}


// ============================================================ Famous funds' holdings

private fun money(v: Double): String = when {
    v >= 1e12 -> String.format(java.util.Locale.US, "\$%.1fT", v / 1e12)
    v >= 1e9 -> String.format(java.util.Locale.US, "\$%.1fB", v / 1e9)
    v >= 1e6 -> String.format(java.util.Locale.US, "\$%.1fM", v / 1e6)
    else -> String.format(java.util.Locale.US, "\$%,.0f", v)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FundHoldingsBlock(f: Followed, store: Store) {
    var snap by remember(f.cik) { mutableStateOf<Following.FundSnapshot?>(null) }
    var loaded by remember(f.cik) { mutableStateOf(false) }
    LaunchedEffect(f.cik) {
        snap = withContext(Dispatchers.IO) { runCatching { Following.fundSnapshot(f.cik, store) }.getOrNull() }
        loaded = true
    }
    Text("Latest holdings" + (snap?.period?.takeIf { it.isNotBlank() }?.let { " (as of $it)" } ?: ""),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    val s = snap
    when {
        !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
        s == null -> Text("Couldn't load their latest filing right now.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            Text("${money(s.total)} in reported U.S. stocks", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.top.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(money(h.value), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text(h.change, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp),
                        color = when {
                            h.change.startsWith("Added") || h.change == "New" -> TradeColors.buy()
                            h.change.startsWith("Cut") -> TradeColors.sell()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
            }
            if (s.newBuys.isNotEmpty()) {
                Text("New this quarter: " + s.newBuys.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                    color = TradeColors.buy())
            }
            if (s.soldOut.isNotEmpty()) {
                Text("Sold out of: " + s.soldOut.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                    color = TradeColors.sell())
            }
            if (s.crypto.isNotEmpty()) {
                Text("Crypto funds: " + s.crypto.joinToString(", ") { "${it.name} ${money(it.value)}" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ============================================================ Crypto tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CryptoScreen(store: Store, onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    var prices by remember { mutableStateOf<Map<String, CoinPrice>>(emptyMap()) }
    var treasuries by remember { mutableStateOf<List<TreasuryCompany>>(emptyList()) }
    var news by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var coin by rememberSaveable { mutableIntStateOf(0) } // 0 BTC, 1 ETH
    val asset = if (coin == 0) "BTC" else "ETH"

    LaunchedEffect(reload) {
        while (true) {
            loading = true
            withContext(Dispatchers.IO) { runCatching { Crypto.prices() } }.onSuccess { prices = it }
            loading = false
            delay(60_000)
        }
    }
    LaunchedEffect(coin, reload) {
        treasuries = withContext(Dispatchers.IO) {
            runCatching { Crypto.treasuries(if (coin == 0) "bitcoin" else "ethereum") }.getOrDefault(emptyList())
        }
        news = withContext(Dispatchers.IO) {
            runCatching { News.search(if (coin == 0) "bitcoin (ETF OR institutional OR fund OR whale)" else "ethereum (ETF OR institutional OR fund OR whale)") }
                .getOrDefault(emptyList())
        }
    }

    val positions = remember(reload) { store.cryptoPositions() }
    val funds = positions.filter { it.asset == asset }.sortedByDescending { it.value }
    val moves = remember(reload) { store.moves() }
    val tradeMoves = moves.filter {
        val a = Crypto.tickerAsset(it.ticker)
        a == asset || (a == "STOCK" && coin == 0)
    }.sortedByDescending { it.time }
    val charges = remember(reload) {
        store.alerts().filter { it.category == Category.CHARGES &&
            listOf("crypto", "bitcoin", "ether", "token", "digital asset", "coin").any { k ->
                (it.headline + " " + it.lines.joinToString(" ")).contains(k, ignoreCase = true) } }
    }

    PullToRefreshBox(isRefreshing = loading && prices.isEmpty(), onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("BTC" to "Bitcoin", "ETH" to "Ethereum").forEachIndexed { i, (sym, label) ->
                        val p = prices[sym]
                        val up = (p?.change24h ?: 0.0) >= 0
                        ModernCard(onClick = { coin = i }, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f),
                            colors = if (coin == i) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else CardDefaults.cardColors()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(p?.let { String.format(java.util.Locale.US, "\$%,.0f", it.usd) } ?: "—",
                                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                p?.let {
                                    Text(String.format(java.util.Locale.US, "%+.1f%% today", it.change24h),
                                        color = if (up) TradeColors.buy() else TradeColors.sell(),
                                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Broker.link(store, sym, crypto = true)?.let { url ->
                                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                        contentPadding = PaddingValues(0.dp)) { Text("Open in ${Broker.name(store)}") }
                                }
                            }
                        }
                    }
                }
                Text("Prices refresh every minute (CoinGecko).", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                SheetBlock("Big funds holding ${if (coin == 0) "Bitcoin" else "Ethereum"} funds") {
                    Text("From their quarterly SEC filings (13F). Fills in as new filings arrive; the next wave is due mid-November.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (funds.isEmpty()) Text("None captured yet.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    funds.take(25).forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.fund, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.product, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(money(p.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item {
                SheetBlock("Insider & Congress crypto trades") {
                    Text(
                        "Coins have no company insiders who must report trades, so this shows trades in crypto funds " +
                            "(like IBIT) by members of Congress and insider trades at crypto companies" +
                            (if (coin == 0) " (Strategy, Coinbase, miners)." else "."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tradeMoves.isEmpty()) Text("None captured in the last 7 days.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tradeMoves.take(15).forEach { m ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(StockTarget(m.ticker, m.company, "", "", emptyList(), "", m.price)) }
                            .padding(vertical = 3.dp)) {
                            TradeLine("${if (m.side == "buy") "Bought" else "Sold"} ${m.ticker} · ${m.valueLabel}")
                            Text("${m.who} · ${m.category.label} · ${timeAgo(m.time)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (charges.isNotEmpty()) {
                        Text("SEC crypto insider trading cases", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        charges.take(5).forEach { a ->
                            Text("• ${a.headline}", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) })
                        }
                    }
                }
            }

            if (treasuries.isNotEmpty()) {
                item {
                    SheetBlock("Public companies holding ${if (coin == 0) "Bitcoin" else "Ethereum"}") {
                        treasuries.take(15).forEach { t ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = t.symbol.isNotBlank()) {
                                onOpen(StockTarget(t.symbol.substringBefore(".").substringBefore(":"), t.name, "", "", emptyList(), "", 0.0))
                            }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(t.symbol, t.country).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(String.format(java.util.Locale.US, "%,.0f %s", t.coins, asset),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(money(t.valueUsd), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SheetBlock("Big-money ${if (coin == 0) "Bitcoin" else "Ethereum"} news") {
                    if (news.isEmpty()) Text("Loading…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    news.take(8).forEach { n ->
                        Column(Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url))) }
                            .padding(vertical = 4.dp)) {
                            Text(n.title, style = MaterialTheme.typography.bodyMedium)
                            Text("${n.source} · ${timeAgo(n.time)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text("Prices and company holdings from CoinGecko; fund positions from SEC filings; news from Google News. " +
                    "Not financial advice.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ============================================================ Public figures' SEC insider filings

@Composable
private fun InsiderBlock(f: Followed, store: Store) {
    val context = LocalContext.current
    val openStock = LocalOpenStock.current
    var filings by remember(f.name) { mutableStateOf<List<Following.InsiderFiling>?>(null) }
    LaunchedEffect(f.name) {
        filings = withContext(Dispatchers.IO) { runCatching { Following.insiderFilings(f, store, 15) }.getOrDefault(emptyList()) }
    }
    val holdings = Following.latestHoldings(filings.orEmpty())
    if (holdings.isNotEmpty()) {
        Text("Latest holdings (from SEC filings)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        holdings.forEach { h ->
            Row(Modifier.fillMaxWidth().clickable { openStock(StockTarget(h.ticker, h.company, "", "", emptyList(), "", 0.0)) }
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${h.ticker} · ${h.company}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("As of their filing on ${h.date}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(shareCount(h.ownedAfter) + " shares", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    if (h.price > 0) Text("≈ " + money(h.ownedAfter * h.price) + " at trade price",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text("Recent SEC insider filings", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    when {
        filings == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        filings!!.isEmpty() -> Text("No recent company-insider filings found.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> filings!!.take(6).forEach { x ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOf(x.ticker, x.company).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).clickable(enabled = x.ticker.isNotEmpty()) {
                            openStock(StockTarget(x.ticker, x.company, "", "", emptyList(), "", 0.0))
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(x.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                x.lines.forEach { TradeLine(it) }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(x.link))) },
                    contentPadding = PaddingValues(0.dp)) { Text("View filing") }
            }
        }
    }
}


// ============================================================ Shared modern components

/** The app's card: soft tinted surface, thin outline, no shadow. */
@Composable
private fun ModernCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    onClick: (() -> Unit)? = null,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = colors ?: CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = c, border = border, content = content)
    else Card(modifier = modifier, shape = shape, colors = c, border = border, content = content)
}

/** Pill-shaped tabs, like the feed switchers on social apps. Scrolls sideways when they don't fit. */
@Composable
private fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onSelect(i) },
            ) {
                Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** A filing as a social-style post (shared by Home and Sections). */
private fun postOf(a: Alert, followed: Followed?): Post {
    val isFund = a.category == Category.FUND
    val name = when (a.category) {
        Category.FUND -> a.headline
        Category.CHARGES -> "SEC Enforcement"
        else -> cleanName(a.headline).substringBefore(", ")
    }
    val about = when (a.category) {
        Category.SENATE -> "U.S. Senator" + a.headline.substringAfter(" · ", "").let { if (it.isNotEmpty()) " · $it" else "" }
        Category.HOUSE -> "U.S. Representative" + a.headline.substringAfter(" · ", "").let { if (it.isNotEmpty()) " · $it" else "" }
        Category.INSIDER -> listOf(a.headline.substringAfter(" · ", ""), a.company).filter { it.isNotBlank() }.joinToString(" at ")
        Category.CHARGES -> a.company
        else -> "Investment fund"
    }
    val text = when (a.category) {
        Category.FUND -> "Filed new quarterly holdings. " + a.lines.joinToString(" · ")
        Category.CHARGES -> (listOf(a.headline) + a.lines).joinToString("\n")
        else -> a.lines.joinToString(" · ")
    }
    return Post(name, about, a.category, a.time, text, a.ticker, a.company, a.link,
        if (a.category == Category.CHARGES) null else personOf(a), followed)
}

/** [PostItem] that keeps its own like and watchlist state (saved with the rest of the app's settings). */
@Composable
private fun SavedPostItem(p: Post, store: Store, onStock: () -> Unit) {
    val context = LocalContext.current
    val openPerson = LocalOpenPerson.current
    var liked by remember(p.key) { mutableStateOf(p.key in store.prefs.getStringSet("liked_posts", emptySet())!!) }
    var watching by remember(p.key) { mutableStateOf(p.ticker.isNotEmpty() && p.ticker.uppercase() in store.watchlist) }
    PostItem(
        p, liked = liked, watching = watching,
        onAuthor = { p.person?.let(openPerson) },
        onStock = onStock,
        onLike = {
            val set = store.prefs.getStringSet("liked_posts", emptySet())!!.toMutableSet()
            if (!set.remove(p.key)) set.add(p.key)
            store.prefs.edit().putStringSet("liked_posts", set).apply()
            liked = p.key in set
        },
        onWatch = {
            val t = p.ticker.uppercase()
            store.watchlist = if (t in store.watchlist) store.watchlist - t else store.watchlist + t
            watching = t in store.watchlist
        },
        onShare = {
            val text = "${p.who}: ${p.text}" + (if (p.ticker.isNotEmpty()) " \$${p.ticker}" else "") +
                (if (p.link.isNotEmpty()) "\n${p.link}" else "")
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), null))
        },
        onSource = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.link))) },
    )
}

// ============================================================ Home: social-style feed

/** One post in the Home feed. */
private data class Post(
    val who: String,
    val about: String,          // their business / role
    val category: Category?,    // for the avatar color; null = someone you follow
    val time: Long,
    val text: String,
    val ticker: String,
    val company: String,
    val link: String,
    val person: PersonTarget?,
    val followed: Followed?,
) {
    val key get() = who + "|" + time + "|" + text.hashCode()
}

/** "S&P 500 ▲ +0.84% today", explaining the market-color background. */
@Composable
private fun MarketPill(q: LiveQuote, on: Boolean) {
    val up = q.changePct >= 0
    val color = if (up) TradeColors.buy() else TradeColors.sell()
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        .background(if (up) TradeColors.buyContainer() else TradeColors.sellContainer(), RoundedCornerShape(50))
        .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(java.util.Locale.US, "S&P 500 %s %+.2f%% today", if (up) "▲" else "▼", q.changePct),
            color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (on) Text(if (up) "  ·  market's up" else "  ·  market's down", color = color, style = MaterialTheme.typography.labelMedium)
    }
}

private val FEED_FILTERS = listOf("For you", "Following", "Countries", "Congress", "Insiders", "Funds", "Liked")

/** A post in the Home feed, laid out like a social app: avatar, name and time, text, stock card, actions. */
@Composable
private fun PostItem(
    p: Post, liked: Boolean, watching: Boolean,
    onAuthor: () -> Unit, onStock: () -> Unit, onLike: () -> Unit, onWatch: () -> Unit, onShare: () -> Unit, onSource: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)) {
        Box(Modifier.clickable(onClick = onAuthor)) {
            Avatar(p.who, 46, fund = p.category == Category.FUND || (p.followed?.let { isFund(it) } ?: false))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onAuthor)) {
                Text(p.who, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                val badge = p.category?.let { categoryColors(it).first } ?: MaterialTheme.colorScheme.primary
                Box(Modifier.size(16.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = categoryColors(p.category ?: Category.INSIDER).second,
                        modifier = Modifier.size(11.dp))
                }
                if (p.time > 0) Text("  ·  " + timeAgo(p.time), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
            }
            Text(listOf(p.category?.label ?: "Following", p.about).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(p.text, style = MaterialTheme.typography.bodyLarge,
                color = when {
                    p.text.startsWith("Bought") -> TradeColors.buy()
                    p.text.startsWith("Sold") -> TradeColors.sell()
                    else -> MaterialTheme.colorScheme.onSurface
                })
            if (p.ticker.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onStock)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(Brush.linearGradient(Brand.story), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Text(p.ticker.take(4), color = Color.White, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$" + p.ticker, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (p.company.isNotBlank()) Text(p.company, style = MaterialTheme.typography.bodySmall, color = muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Chart ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onLike) {
                    Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like",
                        tint = if (liked) Brand.like else muted)
                }
                if (p.ticker.isNotEmpty()) IconButton(onClick = onWatch) {
                    Icon(if (watching) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = if (watching) "On your watchlist" else "Add to watchlist",
                        tint = if (watching) MaterialTheme.colorScheme.primary else muted)
                }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share", tint = muted) }
                if (p.link.isNotEmpty()) TextButton(onClick = onSource) { Text("Source", color = muted) }
                else Spacer(Modifier.width(48.dp))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Funds show a ticker (or "13F") instead of initials. */
private fun fundBadge(name: String): String {
    val n = name.lowercase(java.util.Locale.US)
    return when {
        "berkshire" in n -> "BRK.B"
        "pershing square" in n -> "PSH"
        else -> "13F"
    }
}

private fun isFund(f: Followed) = Following.FUNDS.any { it.name == f.name }

@Composable
private fun Avatar(name: String, size: Int = 44, fund: Boolean = false) {
    Following.FLAGS[name]?.let { flag ->
        Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center) {
            Text(flag, fontSize = (size * 0.55f).sp)
        }
        return
    }
    if (fund) {
        Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Text(fundBadge(name), color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold,
                style = if (size >= 50) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        return
    }
    val palette = listOf(0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFF4511E, 0xFF00897B, 0xFF3949AB, 0xFFD81B60, 0xFF6D4C41)
    val color = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(size.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold,
            style = if (size >= 44) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium)
    }
}

private val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
private fun dayMs(d: String): Long = try { synchronized(dateFmt) { dateFmt.parse(d)?.time } ?: 0L } catch (_: Exception) { 0L }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    store: Store, tick: Int, refreshing: Boolean, onRefresh: () -> Unit,
    onSetup: () -> Unit, onOpen: (StockTarget) -> Unit,
) {
    val openPerson = LocalOpenPerson.current
    val highlights = remember(tick) { Highlights.compute(store.moves(), store.picks()) }
    var showFollowed by remember { mutableStateOf<Followed?>(null) }
    var followedPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(FEED_FILTERS.first()) }
    var liked by remember { mutableStateOf(store.prefs.getStringSet("liked_posts", emptySet())!!.toSet()) }
    var watchlist by remember { mutableStateOf(store.watchlist) }

    // People followed from their profile: their filings count as "Following" posts.
    val byPerson = remember(tick, reload) { Following.load(store).filter { it.person.isNotEmpty() }.associateBy { it.person } }
    // Posts from the app's tracked filings (Congress, insiders, funds).
    val alertPosts = remember(tick, byPerson) {
        store.alerts().filter { it.category != Category.CHARGES }.take(150).map { a -> postOf(a, byPerson[a.person]) }
    }

    // Posts from people you follow, loaded live.
    LaunchedEffect(reload) {
        val list = Following.load(store)
        val out = java.util.Collections.synchronizedList(mutableListOf<Post>())
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                list.forEach { f ->
                    launch {
                        val about = f.businesses.firstOrNull().orEmpty()
                        if (f.insider.isNotEmpty()) runCatching { Following.insiderFilings(f, store, 3) }.getOrNull()?.forEach { x ->
                            out.add(Post(f.name, about, null, dayMs(x.date), x.lines.joinToString(" · ") +
                                if (x.company.isNotBlank()) " · ${x.company}" else "", x.ticker, x.company, x.link, null, f))
                        }
                        if (f.cik.isNotEmpty()) runCatching { Following.fundSnapshot(f.cik, store) }.getOrNull()?.let { s ->
                            val text = buildString {
                                append("Filed holdings as of ${s.period}.")
                                if (s.newBuys.isNotEmpty()) append(" New: " + s.newBuys.take(3).joinToString(", ") + ".")
                                if (s.soldOut.isNotEmpty()) append(" Sold out of: " + s.soldOut.take(3).joinToString(", ") + ".")
                                s.top.firstOrNull()?.let { append(" Biggest holding: ${it.name}.") }
                            }
                            val names = store.cachedTickerMap() ?: runCatching { Edgar.companyTickers(store.userAgent).also { store.saveTickerMap(it) } }.getOrNull()
                            val pick = (s.newBuys + s.top.map { it.name }).firstNotNullOfOrNull { n -> names?.get(Edgar.normalizeName(n))?.let { it to n } }
                            out.add(Post(f.name, about, null, dayMs(s.period) + 45L * Store.DAY_MS, text,
                                pick?.first ?: "", pick?.second ?: "", "", null, f))
                        }
                        if (f.youtube.isNotBlank()) runCatching { Following.videos(f, store) }.getOrNull()?.take(2)?.forEach { v ->
                            out.add(Post(f.name, about, null, v.time, "Posted a new video: \"${v.title}\"", "", "", v.url, null, f))
                        }
                    }
                }
            }
        }
        followedPosts = out.toList()
    }

    val market = LocalMarket.current
    val posts = (alertPosts + followedPosts).distinctBy { it.link.ifEmpty { it.key } }.sortedByDescending { it.time }.filter { p ->
        when (filter) {
            "Following" -> p.followed != null
            "Countries" -> Following.FLAGS.containsKey(p.who)
            "Congress" -> p.category == Category.SENATE || p.category == Category.HOUSE
            "Insiders" -> p.category == Category.INSIDER || (p.followed?.insider?.isNotEmpty() ?: false)
            "Funds" -> p.category == Category.FUND || (p.followed?.let { isFund(it) } ?: false)
            "Liked" -> p.key in liked
            else -> true
        }
    }
    val context = LocalContext.current

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { onRefresh(); reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            market?.let { q ->
                item(key = "market") { MarketPill(q, on = Appearance.marketBg(store)) }
            }
            item(key = "today") {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (!store.isSetUp) SetupCard(onSetup) else TodayCard(highlights, onOpen)
                }
            }

            // "Stories" row of people you follow.
            item(key = "stories") {
                val follows = remember(reload) { Following.load(store) }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    follows.forEach { f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(72.dp).clickable { showFollowed = f }) {
                            Box(Modifier.size(68.dp).border(3.dp, Brush.linearGradient(Brand.story), CircleShape).padding(5.dp),
                                contentAlignment = Alignment.Center) {
                                Avatar(f.name, 58, fund = isFund(f))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(f.name.substringBefore(" ("), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            // Feed tabs, like "For you" / "Following" on social apps.
            item(key = "filters") {
                PillTabs(FEED_FILTERS, FEED_FILTERS.indexOf(filter), { filter = FEED_FILTERS[it] },
                    Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }

            if (posts.isEmpty()) item {
                Box(Modifier.padding(16.dp)) {
                    if (filter == "Liked") EmptyState("No liked posts yet", "Tap the heart on a post to keep it here.")
                    else EmptyState("Your feed is warming up", "Pull down to check for new filings.")
                }
            }
            items(posts.take(200), key = { it.key }) { p ->
                PostItem(
                    p, liked = p.key in liked, watching = p.ticker.isNotEmpty() && p.ticker.uppercase() in watchlist,
                    onAuthor = {
                        when {
                            p.person != null -> openPerson(p.person)
                            p.followed != null -> showFollowed = p.followed
                        }
                    },
                    onStock = { onOpen(StockTarget(p.ticker, p.company, "", "", emptyList(), "", 0.0)) },
                    onLike = {
                        liked = if (p.key in liked) liked - p.key else liked + p.key
                        store.prefs.edit().putStringSet("liked_posts", liked).apply()
                    },
                    onWatch = {
                        val t = p.ticker.uppercase()
                        watchlist = if (t in watchlist) watchlist - t else watchlist + t
                        store.watchlist = watchlist
                    },
                    onShare = {
                        val text = "${p.who}: ${p.text}" + (if (p.ticker.isNotEmpty()) " \$${p.ticker}" else "") +
                            (if (p.link.isNotEmpty()) "\n${p.link}" else "")
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text), null))
                    },
                    onSource = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.link))) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    showFollowed?.let { f ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFollowed = null }, sheetState = sheet) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 32.dp)) {
                FollowedCard(f, store, onRemove = {
                    Following.save(store, Following.load(store).filter { it.name != f.name })
                    showFollowed = null
                    reload++
                })
            }
        }
    }
}

// ============================================================ Markets: Movers, Picks, WSB together

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketsScreen(
    store: Store, tick: Int, refreshing: Boolean, onRefresh: () -> Unit,
    onOpen: (StockTarget) -> Unit, onMessage: (String) -> Unit,
) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(listOf("Movers", "Common picks", "WSB"), view, { view = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        when (view) {
            0 -> MoversScreen(store, onOpen = onOpen, onMessage = onMessage)
            1 -> PicksScreen(store, tick, refreshing, onRefresh = onRefresh, onOpen = onOpen)
            else -> WsbScreen(store, onOpen = onOpen)
        }
    }
}


private fun shareCount(v: Double): String = when {
    v >= 1e9 -> String.format(java.util.Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(java.util.Locale.US, "%.1fM", v / 1e6)
    else -> String.format(java.util.Locale.US, "%,.0f", v)
}


// ============================================================ Watchlist

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchlistScreen(store: Store, tick: Int, onOpen: (StockTarget) -> Unit) {
    var list by remember { mutableStateOf(store.watchlist) }
    var groups by remember { mutableStateOf(store.loadGroups()) }
    val followed = remember { Following.load(store) }
    val followedNames = remember(followed) { followed.map { it.name }.toSet() }
    var whoMap by remember { mutableStateOf(WhoTraded.cached(store, followed)) }
    LaunchedEffect(followed) {
        if (whoMap == null) whoMap = withContext(Dispatchers.IO) { runCatching { WhoTraded.build(store, followed) }.getOrNull() ?: emptyMap() }
    }
    var sel by rememberSaveable { mutableStateOf("") } // "" = All
    var newGroup by remember { mutableStateOf<String?>(null) }
    var editGroup by remember { mutableStateOf<String?>(null) }
    val group = groups.firstOrNull { it.name == sel }
    val shown = group?.tickers?.filter { it in list } ?: list
    fun setGroups(g: List<WatchGroup>) { groups = g; store.saveGroups(g) }
    var input by rememberSaveable { mutableStateOf("") }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var live by remember { mutableStateOf<Map<String, LiveQuote>>(emptyMap()) }
    var pricesLoaded by remember { mutableStateOf(false) }
    val hasLive = Live.key(store).isNotBlank()
    val picks = remember(tick) { store.picks() }
    val moves = remember(tick) { store.moves() }

    LaunchedEffect(shown) {
        while (true) {
            if (hasLive) {
                // Live: refresh every 15 seconds while open, staying inside Finnhub's free 60 requests a minute.
                live = live + withContext(Dispatchers.IO) {
                    shown.mapNotNull { t -> runCatching { Live.quote(t, store) }.getOrNull()?.let { t to it } }.toMap()
                }
                pricesLoaded = true
                delay(maxOf(15_000L, shown.size * 1_100L))
            } else {
                prices = prices + withContext(Dispatchers.IO) {
                    shown.associateWith { t -> StockInfo.price(t).first }.filterValues { it != null }.mapValues { it.value!! }
                }
                pricesLoaded = true
                delay(5 * 60_000)
            }
        }
    }

    fun add() {
        val t = input.trim().uppercase().filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        if (t.isNotEmpty() && t !in list) { list = list + t; store.watchlist = list }
        if (t.isNotEmpty() && group != null && t !in group.tickers)
            setGroups(groups.map { if (it.name == group.name) it.copy(tickers = it.tickers + t) else it })
        input = ""
    }

    newGroup?.let { name ->
        AlertDialog(onDismissRequest = { newGroup = null },
            title = { Text("New group") },
            text = { OutlinedTextField(value = name, onValueChange = { newGroup = it.take(24) }, singleLine = true,
                placeholder = { Text("e.g. Long term, Tech, Dividends") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && groups.none { it.name == name.trim() }, onClick = {
                setGroups(groups + WatchGroup(name.trim(), emptyList())); sel = name.trim(); newGroup = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newGroup = null }) { Text("Cancel") } })
    }
    editGroup?.let { name ->
        AlertDialog(onDismissRequest = { editGroup = null },
            title = { Text("Rename or delete \"${sel}\"") },
            text = { OutlinedTextField(value = name, onValueChange = { editGroup = it.take(24) }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                setGroups(groups.map { if (it.name == sel) it.copy(name = name.trim()) else it }); sel = name.trim(); editGroup = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { setGroups(groups.filter { it.name != sel }); sel = ""; editGroup = null }) {
                Text("Delete group", color = TradeColors.sell()) } })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it.uppercase().take(10) },
                    placeholder = { Text("Add a stock or fund, e.g. NVDA or VOO") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }), modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { add() }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(shape = RoundedCornerShape(50), selected = sel == "", onClick = { sel = "" }, label = { Text("All (${list.size})") })
                groups.forEach { g ->
                    FilterChip(shape = RoundedCornerShape(50), selected = sel == g.name, onClick = { if (sel == g.name) editGroup = g.name else sel = g.name },
                        label = { Text("${g.name} (${g.tickers.count { it in list }})") })
                }
                AssistChipLike("+ New group") { newGroup = "" }
            }
            val ctx = LocalContext.current
            val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Text("App version $ver", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (group != null) Text("Tap the selected group again to rename or delete it. New tickers you add here go into this group.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (hasLive) "Live prices refresh every 15 seconds. You'll also get news and SEC filing alerts for these."
                else "Showing latest closing prices. Add a free Finnhub key in Settings → Market data for live prices.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.isEmpty()) item { EmptyState("Your watchlist is empty", "Add tickers above, or tap \"Add to watchlist\" on any stock summary.") }
        else if (shown.isEmpty()) item { EmptyState("\"$sel\" is empty", "Add tickers above, or use the list button on a stock in All to put it in this group.") }
        if (shown.isNotEmpty()) item(key = "checkup_$sel") {
            GroupCheckup(if (group == null) "Whole watchlist" else group.name, shown, live, store, tick,
                notes = group?.notes, onNotes = { n -> setGroups(groups.map { if (it.name == sel) it.copy(notes = n) else it }) },
                onOpen = { onOpen(StockTarget(it, "", "", "", emptyList(), "", 0.0)) })
        }
        items(shown, key = { it }) { t ->
            ModernCard(onClick = { onOpen(StockTarget(t, "", "", "", emptyList(), "", 0.0)) },
                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TickerBadge(t, TradeColors.buyContainer(), TradeColors.buy())
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val q = live[t]
                            if (q != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(String.format(java.util.Locale.US, "\$%,.2f", q.price), style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(String.format(java.util.Locale.US, "%s%.2f (%+.2f%%)", if (q.change >= 0) "▲ +" else "▼ ", q.change, q.changePct),
                                        color = if (q.change >= 0) TradeColors.buy() else TradeColors.sell(),
                                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Box(Modifier.size(6.dp).background(TradeColors.sell(), CircleShape))
                                    Text(" LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(prices[t]?.let { String.format(java.util.Locale.US, "\$%,.2f latest close", it) }
                                    ?: if (pricesLoaded) "Price unavailable" else "Price loading…",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (groups.isNotEmpty()) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.List, contentDescription = "Groups for $t") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    groups.forEach { g ->
                                        val inIt = t in g.tickers
                                        DropdownMenuItem(text = { Text(g.name) }, leadingIcon = { Checkbox(checked = inIt, onCheckedChange = null) },
                                            onClick = { setGroups(groups.map { if (it.name == g.name) it.copy(tickers = if (inIt) it.tickers - t else it.tickers + t) else it }) })
                                    }
                                }
                            }
                        }
                        IconButton(onClick = {
                            if (group != null) setGroups(groups.map { if (it.name == group.name) it.copy(tickers = it.tickers - t) else it })
                            else { list = list - t; store.watchlist = list; setGroups(groups.map { it.copy(tickers = it.tickers - t) }) }
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = if (group != null) "Remove $t from ${group.name}" else "Remove $t")
                        }
                    }
                    var info by remember(t) { mutableStateOf<StockSummary?>(null) }
                    LaunchedEffect(t) { info = withContext(Dispatchers.IO) { runCatching { StockInfo.load(t, "", store) }.getOrNull() } }
                    info?.let { i ->
                        val line = listOf(i.name, i.industry, i.exchange).filter { it.isNotBlank() }.joinToString(" · ")
                        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (i.about.isNotBlank()) Text(i.about, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    PriceChartBlock(t, store, live[t])
                    var looked by remember(t) { mutableStateOf<List<WhoEntry>?>(null) }
                    LaunchedEffect(t) { looked = withContext(Dispatchers.IO) { runCatching { WhoTraded.lookup(store, t) }.getOrNull() ?: emptyList() } }
                    val others = remember(t, tick, looked) {
                        (WhoTraded.others(store, t, followedNames) + looked.orEmpty().filter { it.who !in followedNames })
                            .distinctBy { it.who.lowercase() + it.action + it.date }.sortedByDescending { it.date }
                    }
                    WhoBlock(whoMap?.get(t), others, loading = whoMap == null, othersLoading = looked == null)
                    Broker.link(store, t)?.let { url ->
                        val context = LocalContext.current
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                            contentPadding = PaddingValues(0.dp)) { Text("Open in ${Broker.name(store)}") }
                    }
                }
            }
        }
    }
}


/** "Who's in it": people you follow, then others the app tracks (Congress, insiders), with what they did. */
@Composable
private fun WhoBlock(following: List<WhoEntry>?, others: List<WhoEntry>, loading: Boolean, othersLoading: Boolean) {
    val context = LocalContext.current
    var all by remember { mutableStateOf(false) }
    @Composable
    fun row(e: WhoEntry) {
        val color = when (e.side) { "buy" -> TradeColors.buy(); "sell" -> TradeColors.sell(); else -> MaterialTheme.colorScheme.primary }
        Row(Modifier.fillMaxWidth().clickable(enabled = e.link.isNotBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.link))) } }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top) {
            Avatar(e.who, 28, fund = Following.FUNDS.any { it.name == e.who })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.who, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text(e.action, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
                    if (e.date.isNotBlank()) Text(" · ${e.date}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val f = following.orEmpty()
    Text("People you follow", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    when {
        f.isNotEmpty() -> (if (all) f else f.take(4)).forEach { row(it) }
        loading -> Text("Checking SEC filings for everyone you follow… (first time takes a minute)", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text("No one you follow has bought, sold or reported holding it.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Others who bought or sold (Congress, company insiders)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    when {
        others.isNotEmpty() -> (if (all) others else others.take(3)).forEach { row(it) }
        othersLoading -> Text("Looking up Congress trades and SEC insider filings…", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text("No Congress trades or insider buys/sells reported recently.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (f.size > 4 || others.size > 3) TextButton(onClick = { all = !all }, contentPadding = PaddingValues(0.dp)) {
        Text(if (all) "Show less" else "Show all ${f.size + others.size}")
    }
}

@Composable
private fun AssistChipLike(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Summary card at the top of each watchlist group: what's in it, how spread out it is, notes and ideas. */
@Composable
private fun GroupCheckup(title: String, tickers: List<String>, live: Map<String, LiveQuote>, store: Store, tick: Int,
                         notes: String?, onNotes: (String) -> Unit, onOpen: (String) -> Unit) {
    val picks = remember(tick) { store.picks() }
    var info by remember(tickers) { mutableStateOf<Map<String, StockSummary?>>(emptyMap()) }
    LaunchedEffect(tickers, picks) {
        val want = tickers + Portfolio.ideaCandidates(tickers, picks)
        for (t in want) {
            if (t in info) continue
            val s = withContext(Dispatchers.IO) { runCatching { StockInfo.load(t, "", store) }.getOrNull() }
            info = info + (t to s)
        }
    }
    val c = remember(tickers, info, live, picks) { Portfolio.checkup(tickers, info, live, picks) }
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    var draft by remember(title, notes) { mutableStateOf(notes ?: "") }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = !expanded }) {
                Column(Modifier.weight(1f)) {
                    Text("$title checkup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${tickers.size} ${if (tickers.size == 1) "holding" else "holdings"} · ${c.sectors.size} ${if (c.sectors.size == 1) "area" else "areas"}" +
                        (c.dayMove?.let { String.format(java.util.Locale.US, " · today %+.2f%% avg", it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            if (!expanded) return@Column

            Text("What's in it", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            tickers.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(t) }) {
                    Text(t, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                    Text(Portfolio.describe(t, info[t]), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (c.sectors.isNotEmpty()) {
                Text("Mix by area", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                c.sectors.forEach { (name, n) ->
                    val frac = n.toFloat() / tickers.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.weight(1f).height(6.dp))
                        Text(" ${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                }
                Text("Counted by number of stocks, not dollars.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (c.notes.isNotEmpty()) {
                Text("Diversification notes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                c.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            if (c.ideas.isNotEmpty()) {
                Text("Ideas to research (areas this group doesn't cover)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                c.ideas.forEach { i ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(i.ticker) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(i.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        Text("${i.sector} · ${i.why}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (notes != null) {
                Text("Your notes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = draft, onValueChange = { draft = it; onNotes(it) }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Why you're watching these, price targets, reminders…") }, minLines = 2)
            }
            Text("General info from SEC industry codes and the people you track, not personal financial advice.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


// ============================================================ Price chart (Robinhood-style)

@Composable
private fun PriceChartBlock(ticker: String, store: Store, live: LiveQuote?) {
    var range by rememberSaveable(ticker) { mutableStateOf(ChartRange.D1) }
    var pts by remember(ticker, range) { mutableStateOf<List<Pt>?>(null) }
    var error by remember(ticker, range) { mutableStateOf<String?>(null) }
    var selected by remember(ticker, range) { mutableStateOf<Int?>(null) }

    LaunchedEffect(ticker, range, live?.price) {
        runCatching { withContext(Dispatchers.IO) { Charts.history(ticker, range, store) } }
            .onSuccess { pts = it; error = null }
            .onFailure { error = it.message; if (pts == null) pts = emptyList() }
    }

    val data = pts.orEmpty()
    val base = if (range == ChartRange.D1) (live?.prevClose?.takeIf { it > 0 } ?: data.firstOrNull()?.p) else data.firstOrNull()?.p
    val shown = selected?.let { data.getOrNull(it) } ?: data.lastOrNull()
    val up = shown != null && base != null && shown.p >= base
    val color = if (up) TradeColors.buy() else TradeColors.sell()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (shown != null && base != null && base > 0) {
            val ch = shown.p - base
            Text(String.format(java.util.Locale.US, "\$%,.2f", shown.p), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(String.format(java.util.Locale.US, "%s%.2f (%+.2f%%) %s", if (ch >= 0) "▲ +" else "▼ ", ch, ch / base * 100,
                if (selected != null) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(shown.t)) else if (range == ChartRange.D1) "today" else "past ${range.label}"),
                color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            when {
                pts == null -> LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.Center))
                data.size < 2 -> Text(
                    error ?: if (range == ChartRange.D1) "Today's chart draws live while the app is open during market hours."
                    else "No chart data.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                else -> {
                    val min = minOf(data.minOf { it.p }, base ?: Double.MAX_VALUE)
                    val max = maxOf(data.maxOf { it.p }, base ?: -Double.MAX_VALUE)
                    val dash = MaterialTheme.colorScheme.outline
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxSize().pointerInput(data) {
                            detectDragGesturesAfterLongPressOrNot(
                                onMove = { x -> selected = ((x / size.width) * (data.size - 1)).toInt().coerceIn(0, data.size - 1) },
                                onEnd = { selected = null },
                            )
                        }
                    ) {
                        val span = (max - min).takeIf { it > 0 } ?: 1.0
                        fun x(i: Int) = size.width * i / (data.size - 1)
                        fun y(p: Double) = (size.height * (1 - (p - min) / span)).toFloat()
                        base?.let {
                            drawLine(dash, androidx.compose.ui.geometry.Offset(0f, y(it)), androidx.compose.ui.geometry.Offset(size.width, y(it)),
                                strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                        }
                        val path = androidx.compose.ui.graphics.Path()
                        data.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(i), y(pt.p)) else path.lineTo(x(i), y(pt.p)) }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        selected?.let { i ->
                            drawLine(dash, androidx.compose.ui.geometry.Offset(x(i), 0f), androidx.compose.ui.geometry.Offset(x(i), size.height), strokeWidth = 2f)
                            drawCircle(color, 10f, androidx.compose.ui.geometry.Offset(x(i), y(data[i].p)))
                        } ?: drawCircle(color, 9f, androidx.compose.ui.geometry.Offset(x(data.size - 1), y(data.last().p)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ChartRange.entries.forEach { r ->
                TextButton(onClick = { range = r }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(r.label, fontWeight = if (r == range) FontWeight.Bold else FontWeight.Normal,
                        color = if (r == range) color else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (range == ChartRange.D1) {
            Text("1D draws live from today's open while the app is open. Dashed line = yesterday's close.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Touch-and-drag (or tap) across the chart to read prices. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGesturesAfterLongPressOrNot(
    onMove: (Float) -> Unit, onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onMove(down.position.x)
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { if (it.pressed) { onMove(it.position.x); it.consume() } }
        } while (event.changes.any { it.pressed })
        onEnd()
    }
}
