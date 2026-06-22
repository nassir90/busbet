package com.example.tfiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settingsStore: SettingsStore,
    timeController: TimeController,
    onOpenStop: (String) -> Unit,
    onOpenRoute: (route: String, direction: Int) -> Unit,
    onOpenTrip: (tripId: String, fromStopCode: String?) -> Unit,
    onReport: (d: Departure, stopCode: String, stopName: String) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit = {},
) {
    val context = LocalContext.current
    val favStore = remember { FavouritesStore(context) }
    val favourites by favStore.flow.collectAsState(initial = emptyList())
    val historyStore = remember { SearchHistoryStore(context) }
    val history by historyStore.flow.collectAsState(initial = emptyList())
    val locationAware by settingsStore.locationAware.collectAsState(initial = false)
    val hideFarStops by settingsStore.hideFarStops.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Bumped on pull-to-refresh; drives both per-card departure reloads and the location fix.
    var refreshKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Current location for distance sorting. Re-acquired whenever location-aware mode is
    // turned on and on every pull-to-refresh, so the fix never silently goes stale.
    var userLocation by remember { mutableStateOf<android.location.Location?>(null) }
    LaunchedEffect(locationAware, refreshKey) {
        userLocation = if (locationAware) LocationProvider.current(context) else null
    }

    var query by rememberSaveable { mutableStateOf("") }
    var stopResults by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var routeResults by remember { mutableStateOf<List<RouteDirection>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.length < 2) {
            stopResults = emptyList(); routeResults = emptyList(); searchError = null
            return@LaunchedEffect
        }
        delay(150)
        runCatching {
            stopResults = Api.service.searchStops(query)
            routeResults = Api.service.searchRoutes(query)
            searchError = null
        }.onFailure { e ->
            android.util.Log.e("tfi", "search failed", e)
            stopResults = emptyList(); routeResults = emptyList()
            searchError = "${e::class.simpleName}: ${e.message}"
        }
    }

    // Local mutable copy so the drag updates immediately, then we persist on drop.
    var localOrder by remember { mutableStateOf(favourites) }
    LaunchedEffect(favourites) { localOrder = favourites }

    // Backfill coordinates for favourites added before location-aware mode existed.
    LaunchedEffect(locationAware, favourites) {
        if (!locationAware) return@LaunchedEffect
        favourites.filter { it.lat == null || it.lon == null }.forEach { fav ->
            runCatching { Api.service.stop(fav.code) }
                .onSuccess { s ->
                    if (s.stopLat != null && s.stopLon != null) favStore.setCoords(fav.code, s.stopLat, s.stopLon)
                }
        }
    }

    // When location-aware and we have a fix, present favourites sorted by distance
    // (stops without known coordinates sink to the bottom); otherwise manual drag order.
    val sortByDistance = locationAware && userLocation != null
    val displayList = if (sortByDistance) {
        favourites.sortedBy { f ->
            if (f.lat != null && f.lon != null)
                LocationProvider.distanceMeters(userLocation!!.latitude, userLocation!!.longitude, f.lat, f.lon)
            else Float.MAX_VALUE
        }
    } else localOrder

    val staleCodes = remember { mutableStateMapOf<String, Boolean>() }
    val anyStale = displayList.any { staleCodes[it.code] == true }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    var showTimePanel by remember { mutableStateOf(false) }

    Scaffold(
        // Let the favourites list draw under the nav bar; we add it back as content padding below.
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
        TopAppBar(
            title = { Text("TFI Live Departures") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            actions = {
                if (anyStale) StaleBadge()
                TimeTravelChip(timeController, onClick = { showTimePanel = !showTimePanel })
                IconButton(onClick = onOpenNotifications) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
        )
    }) { padding ->
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.padding(padding).fillMaxSize()) {
          androidx.compose.animation.AnimatedVisibility(
              visible = showTimePanel,
              enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(),
              exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(),
          ) {
              TimeTravelPanel(timeController)
          }
          PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    refreshKey++
                    delay(900)
                    isRefreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
          ) {
          Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a stop or route") },
                placeholder = { Text("Stop name or number e.g. 3368") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
            )
            Spacer(Modifier.height(8.dp))

            searchError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            if (routeResults.isNotEmpty() || stopResults.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        if (routeResults.isNotEmpty()) {
                            item { SectionLabel("Routes") }
                            items(routeResults) { r ->
                                ListItem(
                                    headlineContent = { Text("${r.routeShortName}  ${r.fromStop} → ${r.toStop}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            historyStore.record(
                                                SearchHistoryEntry(
                                                    isRoute = true,
                                                    id = "${r.routeShortName}|${r.directionId}",
                                                    label = "${r.routeShortName}  ${r.fromStop} → ${r.toStop}",
                                                    routeShortName = r.routeShortName,
                                                    directionId = r.directionId,
                                                )
                                            )
                                        }
                                        onOpenRoute(r.routeShortName, r.directionId)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                        if (stopResults.isNotEmpty()) {
                            item { SectionLabel("Stops") }
                            items(stopResults) { s ->
                                ListItem(
                                    headlineContent = { Text("${s.stopCode}  ${s.stopName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            historyStore.record(
                                                SearchHistoryEntry(
                                                    isRoute = false,
                                                    id = s.stopCode,
                                                    label = "${s.stopCode}  ${s.stopName}",
                                                )
                                            )
                                        }
                                        onOpenStop(s.stopCode)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else if (searchFocused && query.isEmpty() && history.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionLabel("Recent searches")
                                TextButton(onClick = { scope.launch { historyStore.clear() } }) { Text("Clear") }
                            }
                        }
                        items(history, key = { "${it.isRoute}:${it.id}" }) { h ->
                            ListItem(
                                leadingContent = {
                                    Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                headlineContent = { Text(h.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clickable {
                                    scope.launch { historyStore.record(h) }
                                    if (h.isRoute && h.routeShortName != null && h.directionId != null) {
                                        onOpenRoute(h.routeShortName, h.directionId)
                                    } else {
                                        onOpenStop(h.id)
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (displayList.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Favourites", style = MaterialTheme.typography.titleMedium)
                    if (sortByDistance) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "· by distance",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = navBottom + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(displayList, key = { it.code }) { fav ->
                        ReorderableItem(reorderState, key = fav.code) { isDragging ->
                            // Manual reordering only applies when not sorting by distance.
                            val handleModifier = if (sortByDistance) Modifier
                                else Modifier
                                    .padding(horizontal = 8.dp)
                                    .draggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragStopped = {
                                            scope.launch { favStore.reorder(localOrder.map { it.code }) }
                                        },
                                    )
                            val distanceMeters = if (sortByDistance && fav.lat != null && fav.lon != null) {
                                LocationProvider.distanceMeters(
                                    userLocation!!.latitude, userLocation!!.longitude, fav.lat, fav.lon,
                                )
                            } else null
                            val distanceLabel = distanceMeters?.let { m ->
                                if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000f)
                            }
                            val isFar = hideFarStops && distanceMeters != null &&
                                distanceMeters > FAR_STOP_THRESHOLD_M
                            FavouriteCard(
                                favourite = fav,
                                isDragging = isDragging,
                                showHandle = !sortByDistance,
                                distanceLabel = distanceLabel,
                                dimmed = isFar,
                                autoCollapsed = isFar,
                                onOpen = { onOpenStop(fav.code) },
                                onOpenTrip = onOpenTrip,
                                onOpenRoute = onOpenRoute,
                                onReport = onReport,
                                onRemove = { scope.launch { favStore.remove(fav.code) } },
                                onSetCustomName = { name -> scope.launch { favStore.setCustomName(fav.code, name) } },
                                onToggleCollapsed = { scope.launch { favStore.setCollapsed(fav.code, !fav.collapsed) } },
                                handleModifier = handleModifier,
                                onStaleChanged = { staleCodes[fav.code] = it },
                                refreshKey = refreshKey,
                                timeSec = timeController.committedSec,
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Search for a stop and add it as a favourite to see live departures here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
          }
          }
        }
    }
}

@Composable
fun StaleBadge() {
    val amber = Color(0xFFFDE68A)
    Row(
        Modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("stale", color = amber, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RenameFavouriteDialog(
    favourite: Favourite,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(favourite.customName ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename stop") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Custom name") },
                    placeholder = { Text(favourite.name) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave blank to use the real stop name (${favourite.name}).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun FavouriteCard(
    favourite: Favourite,
    isDragging: Boolean,
    onOpen: () -> Unit,
    onOpenTrip: (tripId: String, fromStopCode: String?) -> Unit,
    onOpenRoute: (route: String, direction: Int) -> Unit = { _, _ -> },
    onReport: (d: Departure, stopCode: String, stopName: String) -> Unit = { _, _, _ -> },
    onRemove: () -> Unit,
    onSetCustomName: (String?) -> Unit,
    onToggleCollapsed: () -> Unit,
    handleModifier: Modifier,
    onStaleChanged: (Boolean) -> Unit,
    showHandle: Boolean = true,
    distanceLabel: String? = null,
    dimmed: Boolean = false,
    autoCollapsed: Boolean = false,
    refreshKey: Int = 0,
    timeSec: Long? = null,
) {
    var showRename by remember { mutableStateOf(false) }
    if (showRename) {
        RenameFavouriteDialog(
            favourite = favourite,
            onDismiss = { showRename = false },
            onConfirm = { name -> onSetCustomName(name); showRename = false },
        )
    }

    // A far stop is auto-collapsed, but the user can tap to peek without changing the
    // persisted collapsed state. Peek resets whenever the stop moves in/out of "far".
    var peek by remember(favourite.code, autoCollapsed) { mutableStateOf(false) }
    val collapsed = if (autoCollapsed) !peek else favourite.collapsed

    var deps by remember(favourite.code, timeSec) {
        mutableStateOf(if (timeSec == null) DeparturesCache.get(favourite.code)?.departures?.take(3) else null)
    }
    var initialError by remember(favourite.code, timeSec) { mutableStateOf<String?>(null) }

    LaunchedEffect(favourite.code, refreshKey, timeSec) {
        while (true) {
            runCatching { Api.service.departures(favourite.code, timeSec) }
                .onSuccess {
                    deps = it.departures.take(3)
                    if (timeSec == null) DeparturesCache.put(favourite.code, it)
                    initialError = null
                    onStaleChanged(false)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.e("tfi", "departures ${favourite.code} failed", it)
                    if (deps != null) onStaleChanged(true)
                    else initialError = "${it::class.simpleName}: ${it.message}"
                }
            // Pinned to a historical instant: data is static, don't poll.
            if (timeSec != null) break
            delay(30_000)
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) 0.45f else 1f),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isDragging) 8.dp else 1.dp,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                    Text(favourite.displayName, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (favourite.customName.isNullOrBlank()) favourite.code
                            else "${favourite.name} · ${favourite.code}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (distanceLabel != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "· $distanceLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (showHandle) {
                    Text(
                        "⠿",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = handleModifier,
                    )
                }
                TextButton(
                    onClick = { if (autoCollapsed) peek = !peek else onToggleCollapsed() },
                ) { Text(if (collapsed) "Expand" else "Collapse") }
                TextButton(onClick = { showRename = true }) { Text("Rename") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            if (!collapsed) {
                Spacer(Modifier.height(4.dp))
                when {
                    deps == null && initialError != null ->
                        Text("Could not load", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    deps == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    deps!!.isEmpty() -> Text("No upcoming departures", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val refNowMins = timeSec?.let {
                            java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
                                .let { z -> z.hour * 60 + z.minute }
                        } ?: java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                        deps!!.forEach { d ->
                            DepartureRow(
                                d,
                                nowMins = refNowMins,
                                onOpenRoute = { onOpenTrip(d.tripId, favourite.code) },
                                onOpenService = { onOpenRoute(d.routeShortName, d.directionId) },
                                onReport = { onReport(d, favourite.code, favourite.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}
