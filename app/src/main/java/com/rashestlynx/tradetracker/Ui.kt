package com.rashestlynx.tradetracker

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.DateUtils
import java.util.Locale
import kotlin.math.abs
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
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
private const val TAB_PRED = 11

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
                                TAB_WATCH -> "Portfolio"
                                TAB_FEED -> "Feed"
                                TAB_PRED -> "Predictions"
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
            // Five tabs, each with one row of views at most:
            // Markets (Top 30, Movers, Common picks, WSB, Crypto), Predictions, Feed (Filings, News),
            // Portfolio (Watchlist, My accounts, Track record). Sources is in Settings.
            NavigationBar {
                NavigationBarItem(selected = tab == TAB_HOME, onClick = { tab = TAB_HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == TAB_MARKETS || tab == TAB_MOVERS || tab == TAB_PICKS || tab == TAB_WSB || tab == TAB_CRYPTO,
                    onClick = { tab = TAB_MARKETS },
                    icon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }, label = { Text("Markets") })
                NavigationBarItem(selected = tab == TAB_PRED, onClick = { tab = TAB_PRED },
                    icon = { Text("%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    label = { Text("Predictions") })
                NavigationBarItem(selected = tab == TAB_FEED || tab == TAB_NEWS, onClick = { tab = TAB_FEED },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) }, label = { Text("Feed") })
                NavigationBarItem(selected = tab == TAB_WATCH, onClick = { tab = TAB_WATCH },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) }, label = { Text("Portfolio") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                TAB_HOME -> HomeScreen(store, tick, refreshing, onRefresh = { checkNow() },
                    onSetup = { tab = TAB_SETTINGS }, onOpen = { stock = it })
                TAB_WATCH -> WatchlistTab(store, tick, onOpen = { stock = it })
                TAB_MARKETS, TAB_MOVERS, TAB_PICKS, TAB_WSB -> MarketsScreen(store, tick, refreshing,
                    onRefresh = { checkNow() }, onOpen = { stock = it }, onMessage = { message(it) })
                TAB_PRED -> PredictionsTab(store)
                TAB_FEED -> FeedTab(store, tick, refreshing, onRefresh = { checkNow() },
                    onSetup = { tab = TAB_SETTINGS }, onOpen = { stock = it })
                TAB_CRYPTO -> CryptoScreen(store, onOpen = { stock = it })
                TAB_NEWS -> NewsScreen(store, onOpen = { stock = it })
                TAB_SOURCES -> SourcesScreen(store)
                TAB_WSB -> WsbScreen(store, onOpen = { stock = it })
                TAB_MOVERS -> MoversScreen(store, onOpen = { stock = it }, onMessage = { message(it) })
                TAB_PICKS -> PicksScreen(store, tick, refreshing, onRefresh = { checkNow() },
                    onOpen = { stock = it })
                else -> SettingsScreen(store, onMessage = { message(it) }, onSources = { tab = TAB_SOURCES }, onReady = {
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
private fun SettingsScreen(store: Store, onMessage: (String) -> Unit, onSources: () -> Unit, onReady: () -> Unit) {
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
                    LiveService.start(context)
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

        var oddsKey by rememberSaveable { mutableStateOf(BookOdds.key(context)) }
        Section("Sportsbook odds", "FanDuel and DraftKings moneylines next to Kalshi and Polymarket on every game, from The Odds API " +
            "(free: 500 lookups a month; the app uses one per league every 30 minutes). Saved encrypted on this phone.") {
            OutlinedTextField(value = oddsKey, onValueChange = { oddsKey = it }, label = { Text("The Odds API key") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { BookOdds.setKey(context, oddsKey); onMessage("Saved") }) { Text("Save key") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BookOdds.SIGNUP))) }) {
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
        var appLock by remember { mutableStateOf(store.appLock) }
        Section("Privacy and security",
            "Your keys, holdings and settings stay on this phone. They're never put in backups or sent anywhere " +
                "except the service they belong to.") {
            if (AppLock.available(context)) {
                SwitchRow("App lock",
                    "Ask for your fingerprint, face or PIN to open the app, and again after a minute away. " +
                        "Also hides the app in recent apps and blocks screenshots.", appLock) {
                    appLock = it
                    store.appLock = it
                    (context as? android.app.Activity)?.let { a -> AppLock.applySecureWindow(a, it) }
                }
            } else {
                Text("Set a screen lock on your phone (Settings → Security) to lock this app.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

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
        var liveOn by remember { mutableStateOf(store.liveOn) }
        var liveSeconds by remember { mutableStateOf(store.liveSeconds) }
        Section("Live alerts",
            "Checks the SEC for new insider trades every few seconds, even with the app closed, and notifies you " +
                "right away. Android shows a small \"Live alerts on\" notification while it runs. Checking every second " +
                "uses more battery and data; outside SEC filing hours (weekdays 6 a.m. to 10 p.m. Eastern) it checks once a minute.") {
            SwitchRow("Live alerts", "Keep checking in the background for new filings.", liveOn) {
                liveOn = it
                store.liveOn = it
                if (it) LiveService.start(context) else LiveService.stop(context)
            }
            if (liveOn) {
                Text("Check every", style = MaterialTheme.typography.bodyMedium)
                val choices = listOf(1 to "1 sec", 5 to "5 sec", 15 to "15 sec", 60 to "1 min")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { i, (secs, label) ->
                        SegmentedButton(
                            selected = liveSeconds == secs,
                            onClick = {
                                liveSeconds = secs
                                store.liveSeconds = secs
                                LiveService.restart(context)
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                        ) { Text(label) }
                    }
                }
            }
            var priceOn by remember { mutableStateOf(store.priceAlertsOn) }
            SwitchRow("Price alerts", "While the market is open: your watchlist and last night's strongest patterns breaking " +
                "a 20-day or 52-week high or low, or moving 5% in a day. About every 5 minutes with live alerts on, else every 15.", priceOn) {
                priceOn = it; store.priceAlertsOn = it
            }
            SwitchRow("Notifications from this app", "Turn off to stop all filing notifications from the app.", appNotify) {
                appNotify = it; store.appNotify = it
            }
        }

        Section("Sources", "Every data feed the app uses, and the article or filing behind each person's holdings.") {
            OutlinedButton(onClick = onSources) { Text("See all sources") }
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

            BuyButton(store, t.ticker, prominent = true)
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
            AnalystCard(t.ticker, liveQ?.price, store)
            GradeCard(t.ticker)
            NewsCard(t.ticker, store)
            NightlyBlock(t.ticker)
            PatternsBlock(t.ticker, store)
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
        PillTabs(listOf("Top 30", "Grades", "Patterns", "Movers", "Common picks", "WSB", "Crypto"), view, { view = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        when (view) {
            0 -> ScoresScreen(store, onOpen = onOpen)
            1 -> GradesScreen(onOpen = onOpen)
            2 -> NightlyPatternsScreen(store, onOpen = onOpen)
            3 -> MoversScreen(store, onOpen = onOpen, onMessage = onMessage)
            4 -> PicksScreen(store, tick, refreshing, onRefresh = onRefresh, onOpen = onOpen)
            5 -> WsbScreen(store, onOpen = onOpen)
            else -> CryptoScreen(store, onOpen = onOpen)
        }
    }
}

// ============================================================ Prediction markets (Kalshi, Polymarket)

@Composable
private fun PredictionsTab(store: Store) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(listOf("Start here", "All markets", "Sports", "My picks"), view, { view = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        when (view) {
            0 -> PredGuideScreen()
            1 -> PredictionsScreen()
            2 -> SportsScreen(store)
            else -> MyPredPicksScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NflOddsScreen(store: Store) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<OddsResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { Odds.load(store, reloadToken > 0) } }
        r.onSuccess { result = it }.onFailure { error = it.message ?: "Couldn't load NFL odds" }
        loading = false
    }

    fun pct(v: Double?) = if (v == null) "–" else String.format(java.util.Locale.US, "%.0f%%", v * 100)
    var loggingGame by remember { mutableStateOf<OddsGame?>(null) }
    var gameLogged by remember { mutableStateOf("") }
    var gameBet by remember { mutableStateOf<BetTarget?>(null) }
    gameBet?.let { t -> BetSheet(t, onDone = { gameLogged = it; gameBet = null }, onCancel = { gameBet = null }) }
    loggingGame?.let { g ->
        AlertDialog(
            onDismissRequest = { loggingGame = null },
            title = { Text("${g.away} at ${g.home}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Who wins? It's logged at that site's price today and followed under My picks. Nothing is bought.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOfNotNull(g.kalshiPick?.let { "Kalshi" to it }, g.polymarketPick?.let { "Polymarket" to it })
                        .forEach { (site, m) ->
                            val (ev, homeO, awayO) = m
                            Text(site, style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(Triple(g.away, awayO, 1 - g.best), Triple(g.home, homeO, g.best)).forEach { (team, o, est) ->
                                    OutlinedButton(onClick = {
                                        loggingGame = null
                                        gameBet = BetTarget(ev, o, "yes", est, String.format(java.util.Locale.US,
                                            "Best estimate for the %s: %.0f%%, from the sportsbooks, Kalshi, Polymarket and the math model combined.",
                                            team, est * 100))
                                    }) { Text(String.format(java.util.Locale.US, "%s %.0f¢", team, o.prob * 100)) }
                                }
                            }
                        }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { loggingGame = null }) { Text("Cancel") } },
        )
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reloadToken++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (gameLogged.isNotEmpty()) {
                item { Text("$gameLogged. See it under My picks.", color = TradeColors.buy(), style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                Text("Each game's chance of the home team winning, from four sources. Sportsbooks are usually the " +
                    "sharpest estimate. When Kalshi or Polymarket is several points away from them, one side of " +
                    "that game is cheaper there. Gaps under about 3 points are mostly fees and noise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { e ->
                item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            val r = result
            if (r != null && r.games.isEmpty()) {
                item { Text("No NFL games in the next week.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (r != null) items(r.games.size) { i ->
                val g = r.games[i]
                ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${g.away} at ${g.home}", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(g.kickoff, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Chance the ${g.home} win", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("Sportsbooks" to g.books, "Kalshi" to g.kalshi,
                                "Polymarket" to g.polymarket, "Math model" to g.model).forEach { (name, v) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(pct(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(name, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(String.format(java.util.Locale.US, "Best estimate: %s %.0f%%",
                            if (g.best >= 0.5) g.home else g.away, maxOf(g.best, 1 - g.best) * 100),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        oddsNote(g)?.let { note ->
                            Text(note, style = MaterialTheme.typography.bodySmall,
                                color = if (g.gap >= 3) TradeColors.buy() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (g.kalshiPick != null || g.polymarketPick != null) {
                            TextButton(onClick = { loggingGame = g }, contentPadding = PaddingValues(0.dp)) { Text("Log my pick") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (g.kalshiUrl.isNotEmpty()) OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.kalshiUrl)))
                            }) { Text("Kalshi") }
                            if (g.polymarketUrl.isNotEmpty()) OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.polymarketUrl)))
                            }) { Text("Polymarket") }
                        }
                    }
                }
            }
            if (r != null && r.tested > 0) {
                item {
                    Text(String.format(java.util.Locale.US,
                        "How the math model has done: since 2023 it picked the winner in %.0f%% of %,d games. " +
                            "The sportsbook favorite won %.0f%% of the same games, so trust the sportsbooks more " +
                            "when the two disagree.", r.modelRight * 100, r.tested, r.booksRight * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text("Game results and sportsbook lines from nflverse (free, open data). Odds are estimates, not " +
                    "sure things. Only bet money you can afford to lose. Not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyPredPicksScreen() {
    val context = LocalContext.current
    var record by remember { mutableStateOf<PredRecord?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadToken) {
        loading = true
        record = withContext(Dispatchers.IO) { PredJournal.grade(context) }
        loading = false
    }
    fun cents(v: Double) = String.format(java.util.Locale.US, "%s\$%.2f", if (v < 0) "-" else "+", kotlin.math.abs(v))
    val dayFmt = remember { java.text.SimpleDateFormat("MMM d", java.util.Locale.US) }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reloadToken++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = record
            item {
                ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your picks vs the market", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (r == null || r.picks.isEmpty()) {
                            Text("Nothing logged yet. Tap \"Log a pick\" on any market under All markets.",
                                style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val n = r.settled.size
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("You won" to "${r.wins} of $n",
                                    "Market expected" to String.format(java.util.Locale.US, "%.1f", r.expected),
                                    "\$10 on each" to cents(r.profit * 10)).forEach { (label, v) ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(v, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text(when {
                                n < 20 -> "Too early to judge: $n settled. You need about 20 or more before luck stops dominating."
                                r.wins > r.expected + 2 -> "You're winning more than the market's odds predicted. That's what an edge looks like. Keep logging to see if it holds."
                                r.wins < r.expected - 2 -> "You're winning fewer than the market's odds predicted, so the market has been the better forecaster so far."
                                else -> "You're winning about as often as the market's odds predicted, which means no edge yet. Most people land here."
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (r != null) items(r.picks.size) { i ->
                val s = r.picks[i]
                val p = s.pick
                ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.label} – ${if (p.side == "yes") "Yes" else "No"} at " +
                            String.format(java.util.Locale.US, "%.0f¢", p.price * 100) + " · ${p.source} · ${dayFmt.format(java.util.Date(p.id))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when (s.won) {
                                true -> "Won"
                                false -> "Lost"
                                null -> s.now?.let { String.format(java.util.Locale.US, "Open · now %.0f¢", it * 100) } ?: "Open"
                            }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                                color = when (s.won) { true -> TradeColors.buy(); false -> TradeColors.sell(); null -> MaterialTheme.colorScheme.onSurface })
                            s.profit?.let {
                                val st = p.stake ?: 10.0
                                Text(cents(it * st) + " on " + String.format(java.util.Locale.US, "\$%.0f", st),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (it >= 0) TradeColors.buy() else TradeColors.sell())
                            }
                        }
                        TextButton(onClick = { PredJournal.delete(context, p.id); reloadToken++ }, contentPadding = PaddingValues(0.dp)) {
                            Text("Remove")
                        }
                    }
                }
            }
            item {
                Text("\"\$10 on each\" is what you'd have made or lost betting \$10 on every pick at the logged price, " +
                    "before fees. Open picks are valued at today's price. Nothing is ever bought for you.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Sports: pick a league from the menu. NFL adds sportsbook odds and the math model; the rest use Kalshi and Polymarket. */
@Composable
private fun SportsScreen(store: Store) {
    var leagueName by rememberSaveable { mutableStateOf("NFL") }
    var open by remember { mutableStateOf(false) }
    val league = SportsData.LEAGUES.firstOrNull { it.name == leagueName } ?: SportsData.LEAGUES.first()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(onClick = { open = true }) { Text("League: ${league.name}  ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SportsData.LEAGUES.forEach { l ->
                    DropdownMenuItem(text = { Text(l.name) }, onClick = { leagueName = l.name; open = false })
                }
            }
        }
        if (league.name == "NFL") NflOddsScreen(store) else LeagueScreen(league)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeagueScreen(league: League) {
    val context = LocalContext.current
    var games by remember(league.name) { mutableStateOf<List<SportGame>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember(league.name) { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<SportGame?>(null) }
    var comparing by remember { mutableStateOf<SportGame?>(null) }
    var bet by remember { mutableStateOf<BetTarget?>(null) }
    var logged by remember { mutableStateOf("") }
    var books by remember(league.name) { mutableStateOf<List<BookGame>>(emptyList()) }
    var booksError by remember(league.name) { mutableStateOf<String?>(null) }
    comparing?.let { g -> CompareSheet(league, g) { comparing = null } }
    LaunchedEffect(league.name, reloadToken) {
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { SportsData.load(league, reloadToken > 0) } }
        r.onSuccess { games = it }.onFailure { error = it.message ?: "Couldn't load ${league.name}" }
        loading = false
        withContext(Dispatchers.IO) { runCatching { BookOdds.league(context, league.polyTag, reloadToken > 0) } }
            .onSuccess { books = it; booksError = null }.onFailure { booksError = it.message }
    }
    fun pct(v: Double?) = if (v == null) "–" else String.format(java.util.Locale.US, "%.0f%%", v * 100)

    bet?.let { t -> BetSheet(t, onDone = { logged = it; bet = null }, onCancel = { bet = null }) }
    picking?.let { g ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(g.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Who wins? Pick a side and a site. It's logged at that price and followed under My picks. Nothing is bought.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOfNotNull("Kalshi" to g.kalshiEvent, g.polyEvent?.let { "Polymarket" to it }).forEach { (site, ev) ->
                        Text(site, style = MaterialTheme.typography.labelLarge)
                        g.sides.forEach { side ->
                            val o = if (site == "Kalshi") side.kalshi else side.poly
                            if (o != null && o.ref.isNotEmpty()) OutlinedButton(onClick = {
                                picking = null
                                bet = BetTarget(ev, o, "yes", side.best, String.format(java.util.Locale.US,
                                    "Best estimate for %s: %.0f%%, from %s.", side.label, side.best * 100,
                                    if (side.polyP != null) "Kalshi and Polymarket combined" else "Kalshi's price"))
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(String.format(java.util.Locale.US, "%s  %.0f¢", side.label, o.prob * 100))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reloadToken++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Each side's chance to win from Kalshi and Polymarket, with the best estimate combining them. " +
                    (if (league.hasDraw) "Soccer games can end in a draw, so it's listed too. " else "") +
                    "When the two sites differ by 3 points or more, one side is cheaper on one of them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (logged.isNotEmpty()) item { Text("$logged. See it under My picks.", color = TradeColors.buy()) }
            booksError?.let { e -> item { Text("Sportsbook odds: $e", style = MaterialTheme.typography.bodySmall, color = TradeColors.sell()) } }
            if (books.isNotEmpty()) item {
                Text("FanDuel and DraftKings odds from The Odds API, with each one's chance after removing the book's cut" +
                    (if (BookOdds.remaining.isNotEmpty()) ". ${BookOdds.remaining} free lookups left this month." else "."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            val list = games
            if (list != null && list.isEmpty()) {
                item { Text("No ${league.name} games open right now. The season may be over or not started yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (list != null) items(list.size) { i ->
                val g = list[i]
                ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (g.date.isNotEmpty()) Text(g.date, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            Text("", modifier = Modifier.weight(1f))
                            listOf("Best", "Kalshi", "Poly").forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                            }
                        }
                        g.sides.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pct(s.best), fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                                Text(pct(s.kalshiP), modifier = Modifier.width(56.dp), textAlign = TextAlign.End,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(pct(s.polyP), modifier = Modifier.width(56.dp), textAlign = TextAlign.End,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val bg0 = BookOdds.find(books, g.sides.map { it.label })
                        g.sides.filterNot { SportsData.isDraw(it.label) }.mapNotNull { s -> Coach.verdict(s, bg0?.sideLines(s.label).orEmpty()) }
                            .let { vs -> vs.firstOrNull { it.good } ?: vs.firstOrNull() }?.let { v ->
                                Text(v.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                                    color = if (v.good) TradeColors.buy() else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        bg0?.let { bg ->
                            g.sides.filterNot { SportsData.isDraw(it.label) }.forEach { s ->
                                val lines = bg.sideLines(s.label)
                                if (lines.isNotEmpty()) {
                                    val best = bg.best(s.label)
                                    Text("${s.label}: " + lines.joinToString(" · ") { "${it.book} ${it.oddsText} (${pct(it.fair)})" } +
                                        (if (lines.size > 1 && best != null) " · pays most: ${best.book}" else ""),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (g.gap >= 3) {
                            val s = g.sides.maxByOrNull { if (it.kalshiP != null && it.polyP != null) abs(it.kalshiP - it.polyP) else 0.0 }!!
                            val cheaper = if ((s.kalshiP ?: 0.0) < (s.polyP ?: 0.0)) "Kalshi" else "Polymarket"
                            Text(String.format(java.util.Locale.US, "The sites differ by %.0f points on %s. It's cheaper on %s.",
                                g.gap, s.label, cheaper), style = MaterialTheme.typography.bodySmall, color = TradeColors.buy())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { comparing = g }, contentPadding = PaddingValues(0.dp)) { Text("Compare") }
                            TextButton(onClick = { picking = g }, contentPadding = PaddingValues(0.dp)) { Text("Log my pick") }
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.kalshiEvent.url))) },
                                contentPadding = PaddingValues(0.dp)) { Text("Kalshi") }
                            g.polyEvent?.let { pe ->
                                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pe.url))) },
                                    contentPadding = PaddingValues(0.dp)) { Text("Polymarket") }
                            }
                        }
                    }
                }
            }
            item {
                Text("Prices are each site's current chance, adjusted so a game's sides add up to 100%. " +
                    "Only bet money you can afford to lose. Not financial advice.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A pick being sized: [estimate] is the chance the app (or you) gives [side] of [outcome]. */
private data class BetTarget(val event: PredEvent, val outcome: PredOutcome, val side: String,
                             val estimate: Double, val estimateNote: String)

/** How much to bet, and what you'd get back if it wins. Logs the pick with the amount. Never places a bet. */
@Composable
private fun BetSheet(t: BetTarget, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val price = if (t.side == "yes") t.outcome.prob else 1 - t.outcome.prob
    val sideText = "${t.outcome.label}${if (t.side == "no") " – No" else if (t.outcome.label == "Yes") "" else " – Yes"}"
    var budgetText by remember { mutableStateOf(if (store.betBudget > 0) String.format(java.util.Locale.US, "%.0f", store.betBudget) else "") }
    var estText by remember { mutableStateOf(String.format(java.util.Locale.US, "%.0f", t.estimate * 100)) }
    var amountText by remember { mutableStateOf("") }
    var amountEdited by remember { mutableStateOf(false) }
    fun money(v: Double) = String.format(java.util.Locale.US, "\$%,.2f", v)

    val budget = budgetText.replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0
    val est = ((estText.toDoubleOrNull() ?: (t.estimate * 100)) / 100).coerceIn(0.0, 1.0)
    val sug = Bets.suggest(budget, est, price, t.event.source, t.event.category)
    val shownAmount = if (amountEdited) amountText else if (sug.amount > 0) String.format(java.util.Locale.US, "%.0f", sug.amount) else ""
    val amount = shownAmount.replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0
    val plan = Bets.plan(amount, price, t.event.source, t.event.category)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("How much?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(String.format(java.util.Locale.US, "%s at %.0f¢ on %s", sideText, price * 100, t.event.source),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(budgetText, { budgetText = it }, label = { Text("Your betting budget (\$)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(estText, { estText = it }, label = { Text("Chance it happens (%)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
                Text(t.estimateNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sug.why, style = MaterialTheme.typography.bodySmall,
                    color = if (sug.amount > 0) TradeColors.buy() else MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(shownAmount, { amountText = it; amountEdited = true }, label = { Text("Amount to bet (\$)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth())
                if (plan.contracts > 0) {
                    Text("${plan.contracts} contracts for ${money(plan.cost)} (includes ${money(plan.fee)} fee)",
                        style = MaterialTheme.typography.bodySmall)
                    Text("If it wins: you get ${money(plan.payout)} back, ${money(plan.profit)} profit",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TradeColors.buy())
                    Text("If it loses: you lose ${money(plan.cost)}", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = TradeColors.sell())
                } else if (amount > 0) {
                    Text("That's not enough for one contract at this price.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Only bet money you can afford to lose. The app never places bets; it just logs your pick.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (budget > 0) store.betBudget = budget
                PredJournal.log(context, t.event, t.outcome, t.side, plan.cost.takeIf { it > 0 })
                onDone("Logged: $sideText" + if (plan.cost > 0) " (${money(plan.cost)} planned)" else "")
            }) { Text("Log pick") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** One plain-English line about the biggest disagreement, e.g. "Kalshi has the Giants 4 points higher…". */
private fun oddsNote(g: OddsGame): String? {
    val ref = g.books ?: return null
    val (site, v) = listOfNotNull(g.kalshi?.let { "Kalshi" to it }, g.polymarket?.let { "Polymarket" to it })
        .maxByOrNull { abs(it.second - ref) } ?: return "Not listed on Kalshi or Polymarket yet."
    val pts = (v - ref) * 100
    if (abs(pts) < 3) return "$site is in line with the sportsbooks."
    val cheaper = if (pts > 0) g.away else g.home
    return String.format(java.util.Locale.US, "%s has the %s %.0f points %s than the sportsbooks, so the %s are the cheaper side there.",
        site, g.home, abs(pts), if (pts > 0) "higher" else "lower", cheaper)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PredictionsScreen() {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<PredEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 sports, 2 politics, 3 economics
    var sort by rememberSaveable { mutableIntStateOf(0) }     // 0 biggest moves, 1 most traded
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { Predictions.load(reloadToken > 0) } }
        r.onSuccess { events = it }.onFailure { error = it.message ?: "Couldn't load prediction markets" }
        loading = false
    }

    var logging by remember { mutableStateOf<PredEvent?>(null) }
    var logged by remember { mutableStateOf("") }
    var betting by remember { mutableStateOf<BetTarget?>(null) }
    betting?.let { t -> BetSheet(t, onDone = { logged = it; betting = null }, onCancel = { betting = null }) }
    logging?.let { ev ->
        AlertDialog(
            onDismissRequest = { logging = null },
            title = { Text("Log a pick") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(ev.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Pick what you think will happen. It's logged at today's price, and My picks follows it until it " +
                        "settles. Nothing is bought.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ev.outcomes.filter { it.ref.isNotEmpty() }.forEach { o ->
                        Text(o.label, style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                logging = null
                                betting = BetTarget(ev, o, "yes", o.prob, "This is the market's own chance. Change it if you think differently.")
                            }) { Text(String.format(java.util.Locale.US, "Yes %.0f¢", o.prob * 100)) }
                            OutlinedButton(onClick = {
                                logging = null
                                betting = BetTarget(ev, o, "no", 1 - o.prob, "This is the market's own chance. Change it if you think differently.")
                            }) { Text(String.format(java.util.Locale.US, "No %.0f¢", (1 - o.prob) * 100)) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { logging = null }) { Text("Cancel") } },
        )
    }

    val cats = listOf("All", "Sports", "Politics", "Economics")
    val shown = events
        .filter { category == 0 || it.category == cats[category] }
        .sortedByDescending { if (sort == 0) kotlin.math.abs(it.move) else it.volume24 }
        .take(25)

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reloadToken++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PillTabs(cats, category, { category = it }) }
            item { PillTabs(listOf("Biggest moves today", "Most traded"), sort, { sort = it }) }
            item {
                Text("Live odds from Kalshi and Polymarket, both open in Illinois. " +
                    "A price of 62¢ means traders put the chance at about 62%. Big moves often mean news just broke. " +
                    "FanDuel and DraftKings don't share their odds, so they can't be shown here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { e ->
                item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (logged.isNotEmpty()) {
                item { Text("$logged. See it under My picks.", color = TradeColors.buy(), style = MaterialTheme.typography.bodyMedium) }
            }
            if (!loading && error == null && shown.isEmpty()) {
                item { Text("Nothing to show yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(shown.size) { i ->
                val ev = shown[i]
                ModernCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.url))) },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ev.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ev.outcomes.forEach { o ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                o.change?.takeIf { kotlin.math.abs(it) >= 0.01 }?.let { c ->
                                    Text(String.format(java.util.Locale.US, "%+.0f today", c * 100),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (c > 0) TradeColors.buy() else TradeColors.sell(),
                                        modifier = Modifier.padding(end = 8.dp))
                                }
                                Text(String.format(java.util.Locale.US, "%.0f%%", o.prob * 100),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (ev.outcomes.any { it.ref.isNotEmpty() }) {
                            TextButton(onClick = { logging = ev }, contentPadding = PaddingValues(0.dp)) { Text("Log a pick") }
                        }
                        Text("${ev.source} · ${ev.category} · " + if (ev.source == "Polymarket")
                                String.format(java.util.Locale.US, "\$%,.0f traded today", ev.volume24)
                            else String.format(java.util.Locale.US, "%,.0f contracts traded today", ev.volume24),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("Odds are the crowd's estimate, not a sure thing. Prediction markets are risky; only trade money you can afford to lose. Not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ============================================================ Feed: filings and news together

@Composable
private fun FeedTab(
    store: Store, tick: Int, refreshing: Boolean, onRefresh: () -> Unit,
    onSetup: () -> Unit, onOpen: (StockTarget) -> Unit,
) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(listOf("Filings", "News"), view, { view = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        when (view) {
            0 -> FeedScreen(store, tick, refreshing, onRefresh = onRefresh, onSetup = onSetup, onOpen = onOpen)
            else -> NewsScreen(store, onOpen = onOpen)
        }
    }
}

// ============================================================ Top 30: best-scoring stocks for growth, income and wealth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoresScreen(store: Store, onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    var hasKey by remember { mutableStateOf(store.massiveKey.isNotBlank()) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    if (!hasKey) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Section("One-time setup",
                "Top 30 scores every U.S. stock using SEC filings and daily prices from Massive (formerly Polygon.io). " +
                    "Their free plan works: sign up, copy your API key and paste it here. It's saved on this phone only.") {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://massive.com/pricing")))
                }) { Text("Get a free key") }
                OutlinedTextField(keyInput, { keyInput = it }, label = { Text("Paste API key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = keyInput.isNotBlank(), onClick = { store.massiveKey = keyInput; hasKey = true }) {
                    Text("Save and score stocks")
                }
            }
        }
        return
    }
    var result by remember { mutableStateOf(Scores.cached(store)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 growth, 1 income, 2 wealth
    var reloadToken by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { Scores.load(store, force) { progress = it } } }
        r.onSuccess { result = it }.onFailure { error = it.message ?: "Couldn't load the scores" }
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
            item { PillTabs(listOf("Growth", "Income", "Wealth"), view, { view = it }) }
            item {
                Text(
                    when (view) {
                        0 -> "Growth: sales and profits rising fast, with the share price trending up."
                        1 -> "Income: a solid dividend paid for 10 years, covered by cash, and still growing."
                        else -> "Wealth: steady compounders. Very profitable, low debt, reliable growth year after year."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (loading) {
                item {
                    Text(
                        if (result == null) "Scoring every U.S. stock. The first time takes a minute or two. $progress"
                        else "Updating… $progress",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let { e ->
                item {
                    Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val r = result
            if (r != null) {
                val list = when (view) { 0 -> r.growth; 1 -> r.income; else -> r.wealth }
                val title = when (view) { 0 -> "Top 10 for growth"; 1 -> "Top 10 for income"; else -> "Top 10 for wealth" }
                item { ScoresList(title, list, onOpen) }
                item {
                    Text(
                        "Scored ${String.format(java.util.Locale.US, "%,d", r.screened)} U.S. companies using their " +
                            "${r.fiscalYear} annual reports (SEC) and prices from ${r.priceDate} (Massive). " +
                            "Each score is 0 to 100. A stock appears on one list only, with at most 3 per industry. " +
                            "Pull down to refresh.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text("Scores are made automatically from public filings and can contain errors or be out of date. " +
                    "They are a starting point for research, not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScoresList(title: String, list: List<ScoredStock>, onOpen: (StockTarget) -> Unit) {
    val accent = TradeColors.buy()
    val container = TradeColors.buyContainer()
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (list.isEmpty()) {
                Text("No stocks passed every check this time.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
            list.forEachIndexed { i, s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(StockTarget(s.ticker, s.name, "", "", emptyList(), "", 0.0)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.ticker, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(if (s.sector.isBlank()) s.name else "${s.name} · ${s.sector}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        Surface(color = container, shape = RoundedCornerShape(50)) {
                            Text("${s.score}", color = accent, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        Text(String.format(java.util.Locale.US, "\$%,.2f", s.price),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


private fun shareText(v: Double): String =
    if (v == Math.floor(v)) String.format(java.util.Locale.US, "%,.0f", v)
    else String.format(java.util.Locale.US, "%,.4f", v).trimEnd('0').trimEnd('.')

private fun shareCount(v: Double): String = when {
    v >= 1e9 -> String.format(java.util.Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(java.util.Locale.US, "%.1fM", v / 1e6)
    else -> String.format(java.util.Locale.US, "%,.0f", v)
}


// ============================================================ Watchlist and My accounts

@Composable
private fun WatchlistTab(store: Store, tick: Int, onOpen: (StockTarget) -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    var accountsTick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(listOf("Watchlist", "My accounts", "Track record"), view, { view = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        when (view) {
            0 -> WatchlistScreen(store, tick + accountsTick, onOpen)
            1 -> MyAccountsScreen(store, onOpen, onChanged = { accountsTick++ })
            else -> TrackRecordScreen(store, onOpen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyAccountsScreen(store: Store, onOpen: (StockTarget) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var hasKey by remember { mutableStateOf(Accounts.hasKey(context)) }
    var portfolio by remember { mutableStateOf<MyMoney?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var clientInput by rememberSaveable { mutableStateOf("") }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var manual by remember { mutableStateOf(Accounts.manual(context)) }
    var mAccount by rememberSaveable { mutableStateOf("T. Rowe Price") }
    var mName by rememberSaveable { mutableStateOf("") }
    var mTicker by rememberSaveable { mutableStateOf("") }
    var mValue by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(reloadToken, hasKey) {
        loading = true
        portfolio = withContext(Dispatchers.IO) { Accounts.load(context, reloadToken > 0) }
        loading = false
        onChanged()
    }
    val p = portfolio
    val notes = remember(p) { if (p == null) emptyList() else Accounts.checkup(p, store.moves(), Scores.cached(store)) }
    fun money(v: Double) = String.format(java.util.Locale.US, "\$%,.2f", v)

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reloadToken++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (p != null && p.holdings.isNotEmpty()) {
                item {
                    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(p.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            p.holdings.groupBy { it.account }.forEach { (acct, list) ->
                                Text("$acct: ${money(list.sumOf { it.value })}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            p?.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            if (notes.isNotEmpty()) {
                item {
                    Section("Checkup", "Not advice: things worth knowing about what you own.") {
                        notes.forEach { n ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = n.ticker.isNotEmpty()) {
                                onOpen(StockTarget(n.ticker, "", "", "", emptyList(), "", 0.0))
                            }) {
                                Text(if (n.good) "✓ " else "! ", color = if (n.good) TradeColors.buy() else TradeColors.sell(),
                                    fontWeight = FontWeight.Bold)
                                Text(n.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (p != null && p.holdings.isNotEmpty()) {
                val sorted = p.holdings.sortedByDescending { it.value }
                items(sorted.size) { i ->
                    val h = sorted[i]
                    ModernCard(
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                        onClick = if (h.ticker.isNotEmpty()) ({ onOpen(StockTarget(h.ticker, h.name, "", "", emptyList(), "", 0.0)) }) else null,
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.ticker.ifEmpty { h.name }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(listOfNotNull(h.name.takeIf { h.ticker.isNotEmpty() }, h.account,
                                    h.units?.let { "${shareText(it)} shares" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(h.value), fontWeight = FontWeight.Bold)
                                Text(String.format(java.util.Locale.US, "%.1f%% of total", h.value / p.total * 100),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val c = h.cost; val u = h.units
                                if (c != null && u != null && c > 0) {
                                    val gain = h.value - c * u
                                    Text(String.format(java.util.Locale.US, "%s%s (%+.0f%%)", if (gain >= 0) "+" else "-",
                                        money(kotlin.math.abs(gain)), gain / (c * u) * 100),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (gain >= 0) TradeColors.buy() else TradeColors.sell())
                                }
                            }
                        }
                    }
                }
            }
            item {
                Section("Robinhood", if (hasKey) "Linked, read-only. Pull down to refresh." else
                    "Link it read-only through SnapTrade Personal, which is free for personal use. The app can see " +
                        "what you own but can't trade or move money.") {
                    if (!hasKey) {
                        Text("1. Make a free SnapTrade Personal account and link Robinhood there.\n" +
                            "2. In SnapTrade's dashboard, create a Personal API key.\n" +
                            "3. Paste the Client ID and Consumer Key below. They're encrypted on this phone.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Accounts.SIGNUP)))
                        }) { Text("Open SnapTrade") }
                        OutlinedTextField(clientInput, { clientInput = it }, label = { Text("Client ID") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(keyInput, { keyInput = it }, label = { Text("Consumer Key") }, singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                        Button(enabled = clientInput.isNotBlank() && keyInput.isNotBlank(), onClick = {
                            Accounts.saveKey(context, clientInput, keyInput)
                            clientInput = ""; keyInput = ""
                            hasKey = true
                        }) { Text("Link") }
                    } else {
                        OutlinedButton(onClick = { Accounts.forgetKey(context); hasKey = false }) { Text("Unlink") }
                    }
                }
            }
            item {
                Section("T. Rowe Price and other accounts",
                    "T. Rowe Price can't be linked automatically, so type in each fund and its current value. " +
                        "Update the values now and then from your statement.") {
                    manual.forEachIndexed { i, m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name.ifBlank { m.ticker }, fontWeight = FontWeight.SemiBold)
                                Text("${m.account}${if (m.ticker.isNotBlank()) " · ${m.ticker}" else ""} · ${money(m.value)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                manual = manual.filterIndexed { k, _ -> k != i }
                                Accounts.saveManual(context, manual)
                                reloadToken++
                            }) { Text("Remove") }
                        }
                    }
                    OutlinedTextField(mAccount, { mAccount = it }, label = { Text("Account") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mName, { mName = it }, label = { Text("Fund name, e.g. Retirement 2055") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mTicker, { mTicker = it.uppercase() }, label = { Text("Ticker, if it has one") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mValue, { mValue = it }, label = { Text("Current value in dollars") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    val v = mValue.replace(",", "").replace("$", "").toDoubleOrNull()
                    Button(enabled = (mName.isNotBlank() || mTicker.isNotBlank()) && v != null && v > 0, onClick = {
                        manual = manual + ManualHolding(mAccount.ifBlank { "Other" }, mName.trim(), mTicker.trim(), v!!, System.currentTimeMillis())
                        Accounts.saveManual(context, manual)
                        mName = ""; mTicker = ""; mValue = ""
                        reloadToken++
                    }) { Text("Add") }
                }
            }
        }
    }
}

// ============================================================ Before you buy, and your track record

/**
 * "Open in Robinhood", with a quick look first: how much you already own, Top 30 score, and what insiders did.
 * You can log the buy (with your reason) so Track record can grade it against the S&P 500.
 */
@Composable
private fun BuyButton(store: Store, ticker: String, prominent: Boolean) {
    val context = LocalContext.current
    val url = Broker.link(store, ticker) ?: return
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var reason by remember(ticker) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun open() = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    if (prominent) {
        Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("Open $ticker in ${Broker.name(store)}") }
    } else {
        TextButton(onClick = { show = true }, contentPadding = PaddingValues(0.dp)) { Text("Open in ${Broker.name(store)}") }
    }
    if (show) {
        val check = remember(ticker) { Journal.check(context, store, ticker) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Before you buy $ticker") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    check.lines.forEach { (good, line) ->
                        Row {
                            Text(if (good) "✓ " else "! ", color = if (good) TradeColors.buy() else TradeColors.sell(), fontWeight = FontWeight.Bold)
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(reason, { reason = it }, label = { Text("Why are you buying? (optional)") },
                        modifier = Modifier.fillMaxWidth())
                    if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Journal.logBuy(context, store, ticker, reason) }
                        saving = false
                        if (ok) { show = false; reason = ""; note = ""; open() } else note = "Couldn't get today's price to log it. Try again, or just open."
                    }
                }) { Text(if (saving) "Logging…" else "Log it & open") }
            },
            dismissButton = { TextButton(onClick = { show = false; open() }) { Text("Just open") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackRecordScreen(store: Store, onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<TrackRecord?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadToken) {
        loading = true
        record = withContext(Dispatchers.IO) { Journal.grade(context, store) }
        loading = false
    }
    fun pct(v: Double?) = if (v == null) "–" else String.format(java.util.Locale.US, "%+.1f%%", v * 100)
    val dayFmt = remember { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US) }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reloadToken++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = record
            item {
                ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your picks vs the S&P 500", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (r == null || r.count == 0) {
                            Text("Nothing logged yet. When you tap \"Open in ${Broker.name(store)}\" on a stock, choose " +
                                "\"Log it & open\" and it shows up here, graded against the S&P 500 over the same days.",
                                style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("Your average" to pct(r.avg), "S&P 500" to pct(r.spyAvg),
                                    "Beat it" to "${r.wins} of ${r.count}").forEach { (label, v) ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(v, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text(when {
                                r.count < 10 -> "Too early to judge. A fair test needs 10 or more picks held for several months."
                                r.avg > r.spyAvg -> "So far you're ahead of simply buying an S&P 500 index fund. Keep logging to see if it lasts."
                                else -> "So far an S&P 500 index fund would have done better. That's true for most stock pickers, " +
                                    "including professionals, which is why index funds are a strong default."
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (r != null) items(r.graded.size) { i ->
                val g = r.graded[i]
                ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpen(StockTarget(g.pick.ticker, "", "", "", emptyList(), "", 0.0)) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.pick.ticker, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            Text(pct(g.ret), fontWeight = FontWeight.Bold,
                                color = if ((g.ret ?: 0.0) >= 0) TradeColors.buy() else TradeColors.sell())
                        }
                        Text("Logged ${dayFmt.format(java.util.Date(g.pick.id))} at " +
                            String.format(java.util.Locale.US, "\$%,.2f", g.pick.price) +
                            (g.pick.soldAt?.let { " · sold ${dayFmt.format(java.util.Date(it))}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("S&P 500 over the same time: ${pct(g.spyRet)}" + when (g.beat) {
                            true -> " · you beat it"; false -> " · it beat you"; null -> "" },
                            style = MaterialTheme.typography.bodySmall)
                        if (g.pick.reason.isNotBlank()) Text("Why: ${g.pick.reason}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (g.pick.soldAt == null) TextButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { Journal.markSold(context, store, g.pick.id) }; reloadToken++ }
                            }) { Text("I sold it") }
                            TextButton(onClick = { Journal.delete(context, g.pick.id); reloadToken++ }) { Text("Remove") }
                        }
                    }
                }
            }
            item {
                Text("Prices from Finnhub (live) or Stooq (last close). The S&P 500 is measured with the SPY fund. " +
                    "Dividends aren't included. Your log is encrypted on this phone.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ============================================================ Watchlist

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchlistScreen(store: Store, tick: Int, onOpen: (StockTarget) -> Unit) {
    val ownedCtx = LocalContext.current
    val owned = remember(tick) { Accounts.owned(ownedCtx) }
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
                            owned[t]?.let { (units, value) ->
                                // Shares from your linked accounts; value at today's live price when there is one.
                                val now = if (units != null && q != null) units * q.price else value
                                Text(
                                    (if (units != null) String.format(java.util.Locale.US, "You own %s shares · ", shareText(units)) else "You own ") +
                                        String.format(java.util.Locale.US, "\$%,.2f", now),
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary)
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
                    BuyButton(store, t, prominent = false)
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


// ============================================================ Chart patterns

@Composable
private fun PatternsBlock(ticker: String, store: Store) {
    var report by remember(ticker) { mutableStateOf<PatternReport?>(null) }
    var error by remember(ticker) { mutableStateOf<String?>(null) }
    var open by remember(ticker) { mutableStateOf(false) }
    LaunchedEffect(ticker) {
        withContext(Dispatchers.IO) { runCatching { Patterns.load(ticker, store) } }
            .onSuccess { report = it }.onFailure { error = it.message ?: "Couldn't check patterns" }
    }
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Patterns", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val r = report
                if (r != null) {
                    val up = r.hits.count { it.mood > 0 }
                    val down = r.hits.count { it.mood < 0 }
                    Text("$up good · $down bad", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val r = report
            when {
                error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r == null -> Text("Checking the last 2 years of prices…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val shown = if (open) r.hits else r.hits.take(3)
                    shown.forEach { h ->
                        Column {
                            Row {
                                Text(when { h.mood > 0 -> "▲ "; h.mood < 0 -> "▼ "; else -> "• " },
                                    color = when { h.mood > 0 -> TradeColors.buy(); h.mood < 0 -> TradeColors.sell()
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant }, fontWeight = FontWeight.Bold)
                                Text(h.name, fontWeight = FontWeight.SemiBold)
                            }
                            Text(h.what, style = MaterialTheme.typography.bodySmall)
                            if (h.history.isNotEmpty()) Text(h.history, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (r.support != null || r.resistance != null) {
                        Text(listOfNotNull(r.support?.let { String.format(java.util.Locale.US, "Support near \$%.2f", it) },
                            r.resistance?.let { String.format(java.util.Locale.US, "resistance near \$%.2f", it) }).joinToString(" · ") +
                            ": prices it has bounced off more than once in the past year.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.hits.size > 3) TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (open) "Show less" else "Show all ${r.hits.size}")
                    }
                    Text("Patterns are hints, not predictions. Research finds trend and momentum signals hold up best; " +
                        "classic chart shapes are weaker. The history line shows how each one has actually worked for this stock.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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

// ============================================================ Patterns: last night's scan of every stock + live price alerts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightlyPatternsScreen(store: Store, onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    var view by rememberSaveable { mutableIntStateOf(0) }   // 0 = signals, 1 = track records
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var dir by rememberSaveable { mutableIntStateOf(0) }    // 0 = both, 1 = up, 2 = down
    var result by remember { mutableStateOf<NightlyResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var showN by rememberSaveable { mutableIntStateOf(60) }
    val signalsCache = remember { HashMap<Triple<NightlyResult, Int, Int>, List<Pair<NightlyStock, NightlyHit>>>() }
    val alerts = remember(reload) { store.priceAlertLog().filter { System.currentTimeMillis() - it.time < 3 * 86_400_000L } }
    val kinds = listOf("All", "Candlesticks", "Chart shapes", "Indicators", "Smart money")
    val kindName = listOf("", "Candlestick", "Chart shape", "Indicator", "Smart money")

    LaunchedEffect(reload) {
        loading = true
        withContext(Dispatchers.IO) { runCatching { Nightly.load(context, force = reload > 0) } }
            .onSuccess { result = it; error = null }.onFailure { error = it.message ?: "Couldn't load the scan" }
        loading = false
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (alerts.isNotEmpty()) {
                item { Text("Live price alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                itemsIndexed(alerts.take(8), key = { i, a -> "alert|${a.key}|$i" }) { _, a ->
                    ModernCard(onClick = { onOpen(StockTarget(a.ticker, "", "", "", emptyList(), "", 0.0)) },
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row {
                                Text(if (a.up) "▲ " else "▼ ", color = if (a.up) TradeColors.buy() else TradeColors.sell(), fontWeight = FontWeight.Bold)
                                Text(a.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(DateUtils.getRelativeTimeSpanString(a.time).toString(), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (a.text.isNotEmpty()) Text(a.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { PillTabs(listOf("Signals", "Track records"), view, { view = it }) }
            item { PillTabs(kinds, kind, { kind = it; showN = 60 }) }
            if (view == 0) item { PillTabs(listOf("Up and down", "Up signals", "Down signals"), dir, { dir = it; showN = 60 }) }
            val r = result
            item {
                Text(
                    if (r != null) "Last night's scan of ${String.format(Locale.US, "%,d", r.scanned)} U.S. stocks (prices from ${r.date}). " +
                        "The track record is every time each pattern showed up in any stock over the last 2 years. " +
                        "Live alerts check your watchlist and today's strongest setups while the market is open."
                    else "Every U.S. stock is scanned each night for about 70 patterns.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { e -> item { EmptyState("No scan yet", e) } }
            if (r != null && view == 0) {
                val shown = signalsCache.getOrPut(Triple(r, kind, dir)) { r.signals().filter { (_, h) ->
                    (kind == 0 || h.pattern.kind == kindName[kind]) &&
                        (dir == 0 || (dir == 1 && h.pattern.mood > 0) || (dir == 2 && h.pattern.mood < 0))
                } }
                if (shown.isEmpty()) item { EmptyState("No signals", "Nothing matches these filters today.") }
                itemsIndexed(shown.take(showN), key = { i, (s, h) -> "sig|${s.ticker}|${h.pattern.id}|$i" }) { _, (s, h) ->
                    NightlySignalRow(s, h) { onOpen(StockTarget(s.ticker, "", "", "", emptyList(), "", 0.0)) }
                }
                if (shown.size > showN) item {
                    TextButton(onClick = { showN += 60 }, modifier = Modifier.fillMaxWidth()) { Text("Show more (${shown.size - showN} left)") }
                }
            }
            if (r != null && view == 1) {
                val list = r.patterns.filter { (kind == 0 || it.kind == kindName[kind]) && it.n > 0 }.sortedByDescending { it.edge }
                if (list.isEmpty()) item { EmptyState("No track records", "Smart-money signals come from filings, not prices, so they have no price history here.") }
                itemsIndexed(list, key = { i, p -> "rec|${p.id}|$i" }) { _, p ->
                    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MoodMark(p.mood)
                                Text(p.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(if (p.edge > 0) "Worked" else "Didn't work", style = MaterialTheme.typography.labelMedium,
                                    color = if (p.edge > 0) TradeColors.buy() else TradeColors.sell())
                            }
                            Text(p.desc, style = MaterialTheme.typography.bodySmall)
                            Text(p.record, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text("Patterns are hints, not predictions. \"Worked\" means it beat the S&P 500 on average in the direction it points; " +
                    "past results don't guarantee future ones.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MoodMark(mood: Int) {
    Text(when { mood > 0 -> "▲ "; mood < 0 -> "▼ "; else -> "• " },
        color = when { mood > 0 -> TradeColors.buy(); mood < 0 -> TradeColors.sell(); else -> MaterialTheme.colorScheme.onSurfaceVariant },
        fontWeight = FontWeight.Bold)
}

@Composable
private fun NightlySignalRow(s: NightlyStock, h: NightlyHit, onClick: () -> Unit) {
    ModernCard(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(String.format(Locale.US, "\$%,.2f  %+.1f%%", s.price, s.chg * 100), style = MaterialTheme.typography.labelMedium,
                    color = if (s.chg >= 0) TradeColors.buy() else TradeColors.sell(), modifier = Modifier.weight(1f))
                Text(h.whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row { MoodMark(h.pattern.mood); Text(h.pattern.name, fontWeight = FontWeight.SemiBold) }
            if (h.note.isNotEmpty()) Text(h.note, style = MaterialTheme.typography.bodySmall)
            if (h.pattern.record.isNotEmpty()) Text(h.pattern.record, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (h.ownRecord.isNotEmpty()) Text(h.ownRecord, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** On a stock's page: what last night's scan found for it (uses the saved copy; loads it if missing). */
@Composable
private fun NightlyBlock(ticker: String) {
    val context = LocalContext.current
    var stock by remember(ticker) { mutableStateOf<NightlyStock?>(null) }
    var date by remember(ticker) { mutableStateOf("") }
    LaunchedEffect(ticker) {
        withContext(Dispatchers.IO) { runCatching { Nightly.cached(context) ?: Nightly.load(context) } }.getOrNull()?.let {
            stock = it.stocks[ticker.uppercase()]; date = it.date
        }
    }
    val s = stock ?: return
    if (s.hits.isEmpty()) return
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last night's scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            s.hits.forEach { h ->
                Column {
                    Row { MoodMark(h.pattern.mood); Text(h.pattern.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(h.whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(h.note.ifEmpty { h.pattern.desc }, style = MaterialTheme.typography.bodySmall)
                    if (h.pattern.record.isNotEmpty()) Text(h.pattern.record, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (h.ownRecord.isNotEmpty()) Text(h.ownRecord, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(String.format(Locale.US, "Live alerts fire if it breaks \$%,.2f (20-day high) or \$%,.2f (20-day low). Prices from %s.",
                s.hi20, s.lo20, date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================ Grades: every stock graded for growth, income and wealth

@Composable
private fun gradeColor(g: String): Color = when (g.firstOrNull()) {
    'A' -> TradeColors.buy()
    'B' -> Color(0xFF7CB342)
    'C' -> Color(0xFFF9A825)
    'D' -> Color(0xFFEF6C00)
    'F' -> TradeColors.sell()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun GradeBadge(g: String, label: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(12.dp), color = gradeColor(g).copy(alpha = 0.15f)) {
            Text(g.ifEmpty { "–" }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = gradeColor(g),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val GOALS = listOf("Overall", "Growth", "Income", "Wealth", "Moat", "Next big thing")
private val TAB_GOALS = listOf(5, 0, 1, 2, 3, 4)   // tab position -> goal number
private val GOAL_ABOUT = listOf(
    "Sales and profit rising fast (and speeding up), margins improving, and the stock beating the S&P 500.",
    "A dividend that's paid every year, covered by the cash the business makes, growing, and a fair yield. Non-payers get an F.",
    "Wealth preservation: steady, profitable, low-debt companies whose stock holds up when the market drops.",
    "Our own moat score from SEC filings: high gross profit vs assets, high return on equity, profits backed by cash, low debt, and a steady growth record. Tested like the others.",
    "Smaller companies (\$300M to \$15B) with sales up 20%+, growth speeding up, margins widening, and the stock already beating the market.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradesScreen(onOpen: (StockTarget) -> Unit) {
    val context = LocalContext.current
    var goal by rememberSaveable { mutableIntStateOf(5) }
    var query by rememberSaveable { mutableStateOf("") }
    var showN by rememberSaveable { mutableIntStateOf(50) }
    var result by remember { mutableStateOf<GradesResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        withContext(Dispatchers.IO) { runCatching { Grades.load(context, force = reload > 0) } }
            .onSuccess { result = it; error = null }.onFailure { error = it.message }
        loading = false
    }
    PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PillTabs(GOALS, TAB_GOALS.indexOf(goal), { goal = TAB_GOALS[it]; showN = 50 }) }
            item { Text(if (goal == 5) "Overall: growth and wealth together. Stocks that tend to beat the market and also hold up when it falls. The best single grade if you're not sure which to use." else GOAL_ABOUT[goal], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { e -> if (result == null) item { EmptyState("No grades yet", e) } }
            val r = result
            if (r != null) {
                item { GradeRecordCard(r, goal) }
                if (goal != 4) {
                    item {
                        OutlinedTextField(query, { query = it.uppercase(Locale.US).take(6) }, Modifier.fillMaxWidth(),
                            singleLine = true, label = { Text("Find a ticker") })
                    }
                    val list = r.ranked(goal).filter { query.isBlank() || it.ticker.startsWith(query) }
                    itemsIndexed(list.take(showN), key = { i, g -> "gr|${g.ticker}|$i" }) { _, g ->
                        ModernCard(onClick = { onOpen(StockTarget(g.ticker, g.name, "", "", emptyList(), "", 0.0)) },
                            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(g.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.width(6.dp))
                                        Text(Grades.money(g.capM), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(g.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    g.why(goal).firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                                }
                                GradeBadge(g.grade(goal), g.odds.getOrNull(g.slot(goal))?.let { String.format(Locale.US, "%.0f%% odds", it * 100) })
                            }
                        }
                    }
                    if (list.size > showN) item {
                        TextButton(onClick = { showN += 50 }, modifier = Modifier.fillMaxWidth()) { Text("Show more (${list.size - showN} left)") }
                    }
                } else {
                    if (r.stars.isEmpty()) item { EmptyState("Nothing qualifies today", "No company passed every test.") }
                    itemsIndexed(r.stars, key = { i, s -> "st|${s.ticker}|$i" }) { _, s ->
                        ModernCard(onClick = { onOpen(StockTarget(s.ticker, s.name, "", "", emptyList(), "", 0.0)) },
                            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(6.dp))
                                    Text(listOf(Grades.money(s.capM), s.industry).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f), maxLines = 1)
                                    if (s.growth.isNotEmpty()) GradeBadge(s.growth, "growth")
                                }
                                Text(s.name, style = MaterialTheme.typography.bodySmall)
                                Text(s.why.replaceFirstChar { it.uppercase() } + ".", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Text("Grades are from ${r.date} using fiscal ${r.fiscalYear} SEC filings and daily prices, ranked against " +
                        "${String.format(Locale.US, "%,d", r.graded)} U.S. stocks. They're a starting point for research, not advice.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** "Did the grades work?": the same rules run a year ago, and how each grade did since. */
@Composable
private fun GradeRecordCard(r: GradesResult, goal: Int) {
    val spy = r.spy ?: return
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Did it work?", fontWeight = FontWeight.Bold)
            Text(r.note.ifEmpty { "Graded by the same rules on ${r.recordFrom}, then held ${if (r.months == 12) "a year" else "${r.months} months"}. S&P 500: " +
                String.format(Locale.US, "%+.0f%%", spy * 100) + (if (goal == 2 && r.spyDrop != null)
                    String.format(Locale.US, ", worst drop %.0f%%", r.spyDrop * 100) else "") + "." },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (goal == 4) {
                val ret = r.starsRet
                val beat = r.starsBeat
                if (ret != null && beat != null) Text(String.format(Locale.US,
                    if (r.learned) "Past lists: %2\$.0f%% of picks beat the S&P 500, median %1\$+.1f%% vs it."
                    else "That day's list: median %+.0f%%, %.0f%% of them beat the S&P 500.", ret * 100, beat * 100),
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                val buckets = r.record[mapOf(0 to "g", 1 to "i", 2 to "w", 3 to "m", 5 to "b")[goal]].orEmpty()
                buckets.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.letter + "s", fontWeight = FontWeight.Bold, color = gradeColor(b.letter), modifier = Modifier.width(36.dp))
                        Text(when {
                            goal == 2 && b.drop != null && r.learned -> String.format(Locale.US,
                                "typical worst drop %.0f%% · beat the S&P 500 %.0f%%", b.drop * 100, b.beat * 100)
                            goal == 2 && b.drop != null -> String.format(Locale.US, "worst drop %.0f%% · return %+.0f%%", b.drop * 100, b.ret * 100)
                            r.learned -> String.format(Locale.US, "beat the S&P 500 %.0f%% · median %+.1f%% vs it", b.beat * 100, b.ret * 100)
                            else -> String.format(Locale.US, "median %+.0f%% · %.0f%% beat the S&P 500", b.ret * 100, b.beat * 100)
                        },
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("${b.n}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Only stocks still trading today are counted, which flatters every grade a little. Past results don't guarantee future ones.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** On a stock's page: its three grades, with the reasons behind each. */
@Composable
private fun GradeCard(ticker: String) {
    val context = LocalContext.current
    var g by remember(ticker) { mutableStateOf<StockGrade?>(null) }
    var open by remember(ticker) { mutableIntStateOf(-1) }
    LaunchedEffect(ticker) {
        g = withContext(Dispatchers.IO) { runCatching { (Grades.cached(context) ?: Grades.load(context)).grades[ticker.uppercase(Locale.US)] }.getOrNull() }
    }
    val s = g ?: return
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Grades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(5 to "Overall", 0 to "Growth", 1 to "Income", 2 to "Wealth", 3 to "Moat").forEach { (i, label) ->
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = i }.padding(2.dp)) {
                        GradeBadge(s.grade(i), label)
                    }
                }
            }
            val shown = if (open >= 0) open else 5
            Text(mapOf(5 to "Overall", 0 to "Growth", 1 to "Income", 2 to "Wealth preservation", 3 to "Moat")[shown] + ":",
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            s.odds.getOrNull(s.slot(shown))?.let { o ->
                Text(String.format(Locale.US, "Measured odds: %.0f%% chance to beat the S&P 500 over 3 months", o * 100) +
                    (s.drops.getOrNull(s.slot(shown))?.let { String.format(Locale.US, ", typical worst drop %.0f%%", it * 100) } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            s.why(shown).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("Tap a grade to see why. Weights are learned from history and the odds are measured on months the model " +
                "never saw. Odds aren't promises.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================ Analyst ratings and price targets (like Robinhood's)

@Composable
private fun AnalystCard(ticker: String, price: Double?, store: Store) {
    var view by remember(ticker) { mutableStateOf<AnalystView?>(null) }
    LaunchedEffect(ticker) {
        view = withContext(Dispatchers.IO) { runCatching { Analysts.get(ticker, Live.key(store)) }.getOrNull() }
    }
    val v = view ?: return
    val buyC = TradeColors.buy(); val sellC = TradeColors.sell(); val holdC = Color(0xFFF9A825)
    val track = MaterialTheme.colorScheme.surfaceVariant
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${v.total} analyst ratings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (v.total > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                val top = maxOf(v.buy, v.hold, v.sell)
                val (topPct, topColor) = when (top) { v.buy -> v.pct(v.buy) to buyC; v.hold -> v.pct(v.hold) to holdC; else -> v.pct(v.sell) to sellC }
                Box(Modifier.size(64.dp).clip(CircleShape).background(topColor), contentAlignment = Alignment.Center) {
                    Text("$topPct%", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Triple("Buy", v.buy, buyC), Triple("Hold", v.hold, holdC), Triple("Sell", v.sell, sellC)).forEach { (label, n, col) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                                Box(Modifier.fillMaxWidth(v.pct(n) / 100f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(col))
                            }
                            Text("${v.pct(n)}% $label", style = MaterialTheme.typography.labelMedium, color = col,
                                modifier = Modifier.width(76.dp).padding(start = 8.dp))
                        }
                    }
                }
            }
            val low = v.low; val avg = v.avg; val high = v.high
            if (low != null && avg != null && high != null && high > low) {
                Text("Price targets", fontWeight = FontWeight.SemiBold)
                val lo = minOf(low, price ?: low); val hi = maxOf(high, price ?: high)
                fun at(x: Double) = ((x - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
                    val w = maxWidth
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(track))
                    // the analysts' range
                    Box(Modifier.align(Alignment.CenterStart).offset(x = w * at(low)).width(w * (at(high) - at(low))).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(buyC.copy(alpha = 0.35f)))
                    Box(Modifier.align(Alignment.CenterStart).offset(x = (w * at(avg) - 1.5.dp).coerceAtLeast(0.dp)).width(3.dp).height(18.dp)
                        .background(MaterialTheme.colorScheme.onSurface))
                    price?.let { p ->
                        Box(Modifier.align(Alignment.CenterStart).offset(x = (w * at(p) - 6.dp).coerceIn(0.dp, w - 12.dp)).size(12.dp)
                            .clip(CircleShape).background(if (p < avg) sellC else buyC))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    fun m(x: Double) = String.format(Locale.US, "\$%,.2f", x)
                    Column(Modifier.weight(1f)) { Text("Lowest", style = MaterialTheme.typography.labelSmall); Text(m(low), fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Average", style = MaterialTheme.typography.labelSmall); Text(m(avg), fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("Highest", style = MaterialTheme.typography.labelSmall); Text(m(high), fontWeight = FontWeight.SemiBold) }
                }
                price?.let { p ->
                    val diff = (p / avg - 1) * 100
                    Text(String.format(Locale.US, "Current price \$%,.2f is %.1f%% %s the average 12-month target of \$%,.2f.",
                        p, kotlin.math.abs(diff), if (diff < 0) "below" else "above", avg),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            val src = buildString {
                append("Ratings from ${v.ratings?.name ?: "–"}")
                v.targets?.let { tg -> append(if (tg.name == v.ratings?.name) " (targets too)" else "; targets from ${tg.name}" +
                    (if (tg.targetAnalysts > 0) " (${tg.targetAnalysts} analysts)" else "")) }
                append(".")
            }
            Text(src, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.all.filter { it !== v.ratings && it.total > 0 }.forEach { o ->
                Text("${o.name}: ${o.total} analysts, ${o.pct(o.buy)}% buy, ${o.pct(o.hold)}% hold, ${o.pct(o.sell)}% sell",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Analysts lean optimistic and targets are often missed; use this as one input.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================ Sports: two teams side by side, with the tested rating model

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareSheet(league: League, game: SportGame, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var model by remember { mutableStateOf<SportsModel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { SportsModels.load(context) } }
            .onSuccess { model = it }.onFailure { error = it.message }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val lm = model?.leagues?.get(league.polyTag)
            if (model != null && lm == null) Text("No ratings for ${league.name} yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lm != null) {
                val sides = game.sides.filterNot { SportsData.isDraw(it.label) }
                val a = sides.getOrNull(0)?.let { lm.find(it.label) }
                val b = sides.getOrNull(1)?.let { lm.find(it.label) }
                if (a == null || b == null) {
                    Text("Couldn't match ${sides.joinToString(" and ") { it.label }} to the ratings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val homeLabel = game.title.substringAfter(" at ", "")
                    val aHome: Boolean? = when {
                        homeLabel.isEmpty() -> null
                        SportsData.same(sides[0].label, homeLabel) -> true
                        SportsData.same(sides[1].label, homeLabel) -> false
                        else -> null
                    }
                    val up = lm.upcomingFor(a, b)
                    val (pa0, pd, pb0) = lm.chance(a, b, aHome)
                    // the matchup-and-weather chance replaces the plain rating where it proved better on unseen seasons
                    val full = up?.full
                    val aIsHome = up?.home == a.name
                    val eA = full?.let { if (aIsHome) it else 1 - it }
                    val pa = if (eA != null) eA - pd / 2 else pa0
                    val pb = if (eA != null) 1 - eA - pd / 2 else pb0
                    // the model's chance next to the markets'
                    ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Who's more likely to win", fontWeight = FontWeight.Bold)
                            Row { Text("", Modifier.weight(1f)); listOf("Ratings", "Markets").forEach {
                                Text(it, Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                            listOf(Triple(sides[0], a, pa), Triple(sides[1], b, pb)).forEach { (side, team, p) ->
                                Row { Text(team.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(SportsModels.pct(p), Modifier.width(72.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                                    Text(SportsModels.pct(side.best), Modifier.width(72.dp), textAlign = TextAlign.End) }
                            }
                            if (league.hasDraw) game.sides.firstOrNull { SportsData.isDraw(it.label) }?.let { dr ->
                                Row { Text("Draw", Modifier.weight(1f))
                                    Text(SportsModels.pct(pd), Modifier.width(72.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                                    Text(SportsModels.pct(dr.best), Modifier.width(72.dp), textAlign = TextAlign.End) }
                            }
                            Text((if (aHome == null) "No home edge used (neutral or unknown site)."
                                else "Includes a home edge of ${lm.homeEdge} rating points for ${if (aHome) a.name else b.name}.") +
                                (if (full != null) " Adjusted for rest, recent form, this season's margins and head-to-head" +
                                    (if (lm.matchupUsed == "matchup+weather") ", and the weather forecast" else "") + "." else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            up?.let { u ->
                                val w = when {
                                    u.indoor -> "Indoors${if (u.venue.isNotEmpty()) " at ${u.venue}" else ""}: weather doesn't matter."
                                    u.temp != null -> "Forecast${if (u.venue.isNotEmpty()) " at ${u.venue}" else ""}: ${u.temp}°F, wind ${u.wind} mph" +
                                        (if ((u.rain ?: 0.0) > 0.1) ", rain ${u.rain} mm" else "") + "."
                                    else -> null
                                }
                                w?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (u.temp != null && !lm.weatherHelped) Text("In testing, weather didn't improve ${league.name} predictions, so it's shown but not used.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // side by side
                    ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row { Text("", Modifier.weight(1f))
                                Text(a.abbr.ifEmpty { a.name.take(10) }, Modifier.width(96.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                                Text(b.abbr.ifEmpty { b.name.take(10) }, Modifier.width(96.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End) }
                            val rows = buildList {
                                add(Triple("Rating", "${a.elo}${a.rank?.let { " (#$it)" } ?: ""}", "${b.elo}${b.rank?.let { " (#$it)" } ?: ""}"))
                                if (a.w + a.l + b.w + b.l > 0) {
                                    add(Triple("Record", a.record, b.record))
                                    add(Triple("Points for (per game)", a.pf?.toString() ?: "–", b.pf?.toString() ?: "–"))
                                    add(Triple("Points against", a.pa?.toString() ?: "–", b.pa?.toString() ?: "–"))
                                    add(Triple("Last 10", a.l10, b.l10))
                                    add(Triple("Home", a.home, b.home))
                                    add(Triple("Away", a.away, b.away))
                                    add(Triple("Streak", a.streak, b.streak))
                                    add(Triple("Opponents' avg rating", a.sos?.toString() ?: "–", b.sos?.toString() ?: "–"))
                                }
                                add(Triple("Rating change, 30 days", String.format(Locale.US, "%+d", a.trend), String.format(Locale.US, "%+d", b.trend)))
                            }
                            rows.forEach { (label, x, y) ->
                                Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(x, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                                    Text(y, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End) }
                            }
                        }
                    }
                    // head to head
                    val h2h = a.games.filter { SportsData.same(it.opp, b.name) || it.opp == b.name }.take(6)
                    if (h2h.isNotEmpty()) ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Recent meetings", fontWeight = FontWeight.Bold)
                            h2h.forEach { g ->
                                val res = if (g.pf > g.pa) "won" else if (g.pf < g.pa) "lost" else "tied"
                                Text("${g.date}: ${a.name} $res ${g.pf}-${g.pa} ${if (g.home) "at home" else "away"}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    // UFC: fight check
                    if (up != null && up.fighters.isNotEmpty()) ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Fight check" + if (up.weightClass.isNotEmpty()) " (${up.weightClass})" else "", fontWeight = FontWeight.Bold)
                            val fa = up.fighters[a.name] ?: up.fighters.values.firstOrNull()
                            val fb = up.fighters[b.name] ?: up.fighters.values.drop(1).firstOrNull()
                            listOf(
                                Triple("Age", fa?.age?.toString() ?: "–", fb?.age?.toString() ?: "–"),
                                Triple("Reach (in)", fa?.reach?.let { String.format(Locale.US, "%.0f", it) } ?: "–", fb?.reach?.let { String.format(Locale.US, "%.0f", it) } ?: "–"),
                                Triple("Height (in)", fa?.height?.let { String.format(Locale.US, "%.0f", it) } ?: "–", fb?.height?.let { String.format(Locale.US, "%.0f", it) } ?: "–"),
                                Triple("Stance", fa?.stance ?: "–", fb?.stance ?: "–"),
                                Triple("UFC record (7 yrs)", fa?.record ?: "–", fb?.record ?: "–"),
                                Triple("Days since last fight", fa?.layoffDays?.toString() ?: "–", fb?.layoffDays?.toString() ?: "–"),
                                Triple("Weight class move", fa?.classChange?.let { if (it > 0) "Up" else if (it < 0) "Down" else "Same" } ?: "–",
                                    fb?.classChange?.let { if (it > 0) "Up" else if (it < 0) "Down" else "Same" } ?: "–"),
                            ).forEach { (label, x, y) ->
                                Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(x, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                                    Text(y, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End) }
                            }
                            listOf(a.name to fa, b.name to fb).forEach { (n, f) ->
                                if (f != null && f.last.isNotEmpty()) Text("$n last fight: ${f.last}", style = MaterialTheme.typography.bodySmall)
                            }
                            val news = listOf(a.name to fa, b.name to fb).flatMap { (n, f) -> f?.news.orEmpty().map { n to it } }
                            if (news.isEmpty()) Text("No injury, pull-out or weight-cut headlines in the last 3 weeks.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                Text("Red flags in the news (last 3 weeks)", fontWeight = FontWeight.SemiBold, color = TradeColors.sell())
                                news.forEach { (n, x) ->
                                    Text("• ${x.kinds.joinToString("/")}: ${x.title} (${x.date})", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(x.link))) } })
                                }
                            }
                            Text("Age, reach, layoffs, knockout losses and weight-class moves are tested on past fights and used in the chance above " +
                                "only if they helped. News can't be tested the same way, so it's shown as flags for you to weigh, not in the percentage.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // weather record
                    if (a.weather.isNotEmpty() || b.weather.isNotEmpty()) ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Weather record (last 7 seasons)", fontWeight = FontWeight.Bold)
                            listOf(a, b).forEach { t ->
                                if (t.weather.isNotEmpty()) {
                                    Text(t.name, style = MaterialTheme.typography.labelLarge)
                                    t.weather.sortedBy { it.z }.forEach { w ->
                                        Text(String.format(Locale.US, "• %s: %d-%d, ratings expected %.1f wins: %s", w.label, w.wins, w.n - w.wins,
                                            w.expected, w.verdict), style = MaterialTheme.typography.bodySmall,
                                            color = when { w.z <= -2 -> TradeColors.sell(); w.z >= 2 -> TradeColors.buy(); else -> MaterialTheme.colorScheme.onSurface })
                                    }
                                }
                            }
                            Text("Compared with what each team's rating expected before those games, so a strong team winning in the cold " +
                                "doesn't count as a cold-weather team. With many teams and conditions, a few 'could be luck' results are expected by chance.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // injuries
                    if (a.injuries.isNotEmpty() || b.injuries.isNotEmpty()) ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Injuries", fontWeight = FontWeight.Bold)
                            listOf(a, b).forEach { t ->
                                if (t.injuries.isNotEmpty()) {
                                    Text(t.name, style = MaterialTheme.typography.labelLarge)
                                    t.injuries.take(8).forEach { i ->
                                        Text("• ${i.name}${if (i.pos.isNotEmpty()) " (${i.pos})" else ""}: ${i.status}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text("The rating doesn't adjust for injuries yet; that's coming next.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // how good the ratings have been
                ModernCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How accurate are these ratings?", fontWeight = FontWeight.Bold)
                        lm.acc?.let { acc ->
                            Text(String.format(Locale.US, "Tested on %d %s seasons it never saw (%s to %s, %,d games): the team it favored won %.0f%% of the time.",
                                lm.seasons, league.name, lm.from, lm.to, lm.n, acc * 100), style = MaterialTheme.typography.bodySmall)
                        }
                        if (lm.booksAcc != null && lm.modelSameAcc != null) Text(String.format(Locale.US,
                            "On the same games, the sportsbooks' favorite won %.0f%% (ratings: %.0f%%). Lower Brier score is better: books %.3f, ratings %.3f.",
                            lm.booksAcc * 100, lm.modelSameAcc * 100, lm.booksBrier ?: 0.0, lm.modelSameBrier ?: 0.0), style = MaterialTheme.typography.bodySmall)
                        val mt = lm.matchupTests.associateBy { it.version }
                        val e = mt["elo"]
                        if (e != null) {
                            val bestV = mt[lm.matchupUsed]
                            Text(if (lm.matchupUsed == "elo") "Adding rest, form, head-to-head" + (if (mt.containsKey("matchup+weather")) " and weather" else "") +
                                " didn't beat the plain ratings on unseen seasons, so they're not used."
                                else String.format(Locale.US, "The matchup adjustments%s beat the plain ratings on unseen seasons (favorite won %.1f%% vs %.1f%%).",
                                    if (lm.matchupUsed == "matchup+weather") " and weather" else "", (bestV?.acc ?: 0.0) * 100, e.acc * 100),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        lm.calibration.filter { it.n >= 50 && it.from >= 50 }.takeLast(3).forEach { c ->
                            Text(String.format(Locale.US, "When it said %.0f%%, that team won %.0f%% (%,d games).", c.said * 100, c.won * 100, c.n),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val kb = model?.kalshiBins.orEmpty().filter { it.from <= 20 }
                if (kb.isNotEmpty()) Text("Kalshi check: " + kb.joinToString("; ") {
                    String.format(Locale.US, "sides priced %d-%d¢ won %.0f%% of the time (%d)", it.from, it.to, it.won * 100, it.n) } + ".",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ============================================================ Headlines for one stock

@Composable
private fun NewsCard(ticker: String, store: Store) {
    val context = LocalContext.current
    var list by remember(ticker) { mutableStateOf<List<Headline>?>(null) }
    LaunchedEffect(ticker) {
        list = withContext(Dispatchers.IO) { runCatching { StockNews.get(ticker, store.massiveKey, Live.key(store)) }.getOrDefault(emptyList()) }
    }
    val l = list ?: return
    ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Headlines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (l.isEmpty()) Text(if (store.massiveKey.isBlank() && Live.key(store).isBlank())
                "Add a Massive or Finnhub key in Settings to see headlines." else "No recent headlines.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            l.forEach { h ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) } }) {
                    Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.source + if (h.time > 0) " · " + DateUtils.getRelativeTimeSpanString(h.time) else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        if (h.sentiment.isNotEmpty()) Text(h.sentiment.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                            color = when (h.sentiment) { "positive" -> TradeColors.buy(); "negative" -> TradeColors.sell()
                                else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }
                }
            }
            if (l.any { it.sentiment.isNotEmpty() }) Text("Positive/negative labels are Massive's automatic read of each article, not advice.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


// ============================================================ Prediction markets for beginners

@Composable
private fun PredGuideScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("New to prediction markets? Read this first.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The simple routine", fontWeight = FontWeight.Bold)
                    listOf("1. Open Sports and pick a league.",
                        "2. Read the green or gray line on each game. Gray means no edge: skip it.",
                        "3. Only on a green 'possible value' line, tap the side and use 'How much?'.",
                        "4. Log it under My picks, then check your record after 50 picks.").forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { Text("How each sport works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(Coach.SPORTS) { (title, body) ->
            ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Text("How betting works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(Coach.GUIDE) { (title, body) ->
            ModernCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
