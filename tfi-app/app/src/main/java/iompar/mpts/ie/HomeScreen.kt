package iompar.mpts.ie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val MAX_HISTORY_SUGGESTIONS = 4

/**
 * Debounce before a search leaves the device. Matches the notification sheet's stop search, which
 * used a different value despite being the same interaction.
 */
const val SEARCH_DEBOUNCE_MS = 200L

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
    val farStopThresholdM by settingsStore.farStopThresholdM.collectAsState(initial = DEFAULT_FAR_STOP_THRESHOLD_M)
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
    // Fallback for history entries recorded before routes were persisted (empty h.routes).
    val legacyStopServices = remember { mutableStateMapOf<String, List<String>>() }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.length < 2) {
            stopResults = emptyList(); routeResults = emptyList(); searchError = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        runCatching {
            // Concurrently: these are independent lookups, and run in sequence the route results
            // couldn't land until the stop results had, roughly doubling the wait on a slow link.
            coroutineScope {
                val stops = async { Api.service().searchStops(query) }
                val routes = async { Api.service().searchRoutes(query) }
                stopResults = stops.await()
                routeResults = routes.await()
            }
            searchError = null
        }.onFailure { e ->
            // Typing fast cancels the previous search. runCatching catches CancellationException
            // like any other failure, so without this the cancellation was rendered to the user
            // as "LeftCompositionCancellationException" and the results were cleared — a normal
            // keystroke looked like a crash. Rethrowing also keeps the coroutine's cancellation
            // honest instead of swallowing it.
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("tfi", "search failed", e)
            stopResults = emptyList(); routeResults = emptyList()
            searchError = "${e::class.simpleName}: ${e.message}"
        }
    }

    // Local mutable copy so the drag updates immediately, then we persist on drop.
    var localOrder by remember { mutableStateOf(favourites) }
    LaunchedEffect(favourites) { localOrder = favourites }

    // Backfill coordinates for favourites added before location-aware mode existed.
    //
    // This effect writes coordinates back through favStore, which re-emits `favourites`. Keyed on
    // the favourites list it therefore restarted itself on every success, cancelling the lookup
    // already in flight — N stops became a cascade of started-then-abandoned requests. Keyed on
    // `locationAware` alone it survives its own writes, and the conflated snapshotFlow inside
    // still picks up newly added favourites. `attempted` makes each code a one-shot, so a stop
    // whose lookup fails isn't retried on every subsequent list change.
    val attemptedCoords = remember { mutableSetOf<String>() }
    LaunchedEffect(locationAware) {
        if (!locationAware) return@LaunchedEffect
        snapshotFlow { favourites }
            .map { list -> list.filter { it.lat == null || it.lon == null }.map { f -> f.code } }
            .collect { codes ->
                codes.forEach { code ->
                    if (!attemptedCoords.add(code)) return@forEach
                    runCatching { Api.service().stop(code) }
                        .onSuccess { s ->
                            if (s.stopLat != null && s.stopLon != null) {
                                favStore.setCoords(code, s.stopLat, s.stopLon)
                            }
                        }
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

    // One request for the whole favourites list, not one per card.
    //
    // Each FavouriteCard used to own a poll loop, so opening the app fired N simultaneous
    // /departures/{code} calls that OkHttp then queued at 5 concurrent per host — the tail of a
    // long list waited on a second wave. It also meant the stale flag had to be gathered back up
    // from the cards through a snapshot map; one shared request makes it a single flag.
    val boardCodes = remember(displayList) { displayList.map { it.code } }
    val pinnedSec = timeController.committedSec
    // Ticks on the minute, so "3 mins" counts down on its own instead of sitting frozen until
    // some unrelated recomposition happens along.
    val boardNowMins = rememberBoardMinutes(pinnedSec)
    var boards by remember { mutableStateOf<Map<String, DeparturesResponse>>(emptyMap()) }
    var missingCodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var boardsFailed by remember { mutableStateOf(false) }
    var anyStale by remember { mutableStateOf(false) }

    PollEffect(
        boardCodes, pinnedSec, refreshKey,
        // Pinned to a historical instant the data is static, so fetch once and stop.
        intervalMs = if (pinnedSec == null) POLL_INTERVAL_MS else null,
    ) {
        if (boardCodes.isEmpty()) {
            boards = emptyMap(); missingCodes = emptySet(); boardsFailed = false; anyStale = false
            return@PollEffect
        }
        runCatching { Boards.fetch(boardCodes, pinnedSec) }
            .onSuccess { result ->
                boards = result.boards
                missingCodes = result.missing
                if (pinnedSec == null) result.boards.forEach { (c, d) -> DeparturesCache.put(c, d) }
                boardsFailed = false
                anyStale = false
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.e("tfi", "batch departures failed", it)
                // Keep whatever is on screen and mark it stale; only report an outright failure
                // when there was nothing to fall back on.
                if (boards.isEmpty()) boardsFailed = true else anyStale = true
            }
    }

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
            // The title slot names where you are, not what app you're running — on the home
            // screen that's the favourites list. Lives here rather than above the list so it
            // doesn't cost a row of content.
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Unconditional: an empty title slot reads as a broken bar, unlike the
                    // heading this replaced, which sat above the list and had to be hidden.
                    Text("Favourites", style = MaterialTheme.typography.titleLarge)
                    // The qualifier describes an ordering, so it means nothing with no list.
                    if (sortByDistance && displayList.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "· by distance",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
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

            // History entries matching the in-progress query, clock icon first (YouTube-style),
            // capped at 4 even if more match. Live results are filtered server-side already.
            val matchingHistory = remember(history, query) {
                if (query.isEmpty()) emptyList()
                else history.filter { it.label.contains(query, ignoreCase = true) }.take(MAX_HISTORY_SUGGESTIONS)
            }
            val showDropdown = searchFocused && query.isNotEmpty() &&
                (matchingHistory.isNotEmpty() || routeResults.isNotEmpty() || stopResults.isNotEmpty())

            // Hoisted out of the LazyColumn item it used to live in. Inside the item the effect
            // re-launched every time a row re-entered composition — scrolling the dropdown, or
            // editing the query — and because it only recorded successes, a stop whose lookup
            // failed was retried every single time. StopRoutesCache caches failures too, and is
            // shared with the stop board and the notification sheet, which ask the same question.
            val legacyCodes = remember(matchingHistory) {
                matchingHistory.filter { !it.isRoute && it.routes.isNullOrEmpty() }.map { it.id }
            }
            LaunchedEffect(legacyCodes) {
                legacyCodes.forEach { code ->
                    if (code !in legacyStopServices) legacyStopServices[code] = StopRoutesCache.get(code)
                }
            }

            // Drives the filled/outlined star on stop rows. A set so the lookup stays O(1) per
            // row rather than scanning the favourites list once for every result.
            val favouriteCodes = remember(favourites) { favourites.map { it.code }.toSet() }

            if (showDropdown) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        if (matchingHistory.isNotEmpty()) {
                            items(matchingHistory, key = { "history:${it.isRoute}:${it.id}" }) { h ->
                                CompactSearchRow(
                                    label = h.label,
                                    services = when {
                                        h.isRoute -> null
                                        !h.routes.isNullOrEmpty() -> h.routes
                                        else -> legacyStopServices[h.id].orEmpty()
                                    },
                                    leading = {
                                        Icon(
                                            Icons.Filled.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    trailing = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove from history",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { scope.launch { historyStore.remove(h.id, h.isRoute) } },
                                        )
                                    },
                                    onClick = {
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
                        if (routeResults.isNotEmpty()) {
                            item { SectionLabel("Routes") }
                            items(routeResults) { r ->
                                CompactSearchRow(
                                    label = "${r.routeShortName}  ${r.fromStop} → ${r.toStop}",
                                    onClick = {
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
                                val isFavourite = s.stopCode in favouriteCodes
                                CompactSearchRow(
                                    label = "${s.stopCode}  ${s.stopName}",
                                    services = s.routes,
                                    // Favouriting is what most searches are for, so it happens on
                                    // the result row itself. Onboarding was otherwise search →
                                    // open the stop → star → back, once per stop.
                                    leading = {
                                        Icon(
                                            if (isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                            contentDescription =
                                                if (isFavourite) "Remove ${s.stopName} from favourites"
                                                else "Add ${s.stopName} to favourites",
                                            tint = if (isFavourite) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            // Sized for the touch target, then padded back in so the
                                            // glyph still matches the 20dp run of icons in the dropdown.
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clickable {
                                                    scope.launch {
                                                        if (isFavourite) favStore.remove(s.stopCode)
                                                        else favStore.add(s.stopCode, s.stopName, s.stopLat, s.stopLon)
                                                    }
                                                }
                                                .padding(3.dp),
                                        )
                                    },
                                    onClick = {
                                        scope.launch {
                                            historyStore.record(
                                                SearchHistoryEntry(
                                                    isRoute = false,
                                                    id = s.stopCode,
                                                    label = "${s.stopCode}  ${s.stopName}",
                                                    routes = s.routes,
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
            }

            if (displayList.isNotEmpty()) {
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
                                distanceMeters > farStopThresholdM
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
                                // Served from the one shared batch poll. Falls back to the
                                // last-seen board so a card paints instantly on return rather
                                // than flashing "Loading…" until the first response lands.
                                board = boards[fav.code]
                                    ?: if (pinnedSec == null) DeparturesCache.get(fav.code) else null,
                                loadFailed = boardsFailed,
                                notInTimetable = fav.code in missingCodes,
                                nowMins = boardNowMins,
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No favourites added yet. Use search to see stop times.",
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
    /** This stop's board, from the screen-level batch poll. Null while nothing has loaded yet. */
    board: DeparturesResponse?,
    /** True when the batch poll failed with nothing cached to fall back on. */
    loadFailed: Boolean,
    /** True when the server says this stop code isn't in the timetable at all. */
    notInTimetable: Boolean,
    nowMins: Int,
    showHandle: Boolean = true,
    distanceLabel: String? = null,
    dimmed: Boolean = false,
    autoCollapsed: Boolean = false,
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

    // The route filter is per-favourite config, so it's applied here rather than at the shared
    // fetch: two cards can hide different routes from the same batch response.
    val hiddenRoutes = favourite.hiddenRoutes?.toSet() ?: emptySet()
    val deps = remember(board, hiddenRoutes) {
        board?.departures?.filter { it.routeShortName !in hiddenRoutes }?.take(3)
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
                    if (!favourite.customName.isNullOrBlank()) {
                        Text(
                            favourite.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            favourite.code,
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
                    // Distinct from a failed load: this stop is gone from the timetable, so
                    // waiting won't help. It used to sit on "Loading…" indefinitely.
                    deps == null && notInTimetable ->
                        Text(
                            "This stop is no longer in the timetable",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    deps == null && loadFailed ->
                        Text("Could not load", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    deps == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    deps.isEmpty() -> Text("No upcoming departures", style = MaterialTheme.typography.bodySmall)
                    else -> deps.forEach { d ->
                        DepartureRow(
                            d,
                            nowMins = nowMins,
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
