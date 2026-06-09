package com.example.tfiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    themeStore: ThemeStore,
    currentTheme: AppTheme,
    onOpenStop: (String) -> Unit,
    onOpenRoute: (route: String, direction: Int) -> Unit,
    onOpenTrip: (tripId: String, fromStopCode: String?) -> Unit,
) {
    val context = LocalContext.current
    val favStore = remember { FavouritesStore(context) }
    val favourites by favStore.flow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var query by rememberSaveable { mutableStateOf("") }
    var stopResults by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var routeResults by remember { mutableStateOf<List<RouteDirection>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

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

    val staleCodes = remember { mutableStateMapOf<String, Boolean>() }
    val anyStale = localOrder.any { staleCodes[it.code] == true }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("TFI Live Departures") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            actions = {
                if (anyStale) StaleBadge()
                TextButton(
                    onClick = { refreshKey++ },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("↻") }
                Box {
                    TextButton(
                        onClick = { menuOpen = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) { Text("⋮") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Text(
                            "Theme",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        AppTheme.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                trailingIcon = { if (t == currentTheme) Text("✓") },
                                onClick = {
                                    scope.launch { themeStore.set(t) }
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            },
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 0.dp, bottom = 16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a stop or route") },
                placeholder = { Text("Stop name or number e.g. 3368") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
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
                                    modifier = Modifier.clickable { onOpenRoute(r.routeShortName, r.directionId) },
                                )
                                HorizontalDivider()
                            }
                        }
                        if (stopResults.isNotEmpty()) {
                            item { SectionLabel("Stops") }
                            items(stopResults) { s ->
                                ListItem(
                                    headlineContent = { Text("${s.stopCode}  ${s.stopName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable { onOpenStop(s.stopCode) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (localOrder.isNotEmpty()) {
                Text("Favourites", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(localOrder, key = { it.code }) { fav ->
                        ReorderableItem(reorderState, key = fav.code) { isDragging ->
                            val handleModifier = Modifier
                                .padding(horizontal = 8.dp)
                                .draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = {
                                        scope.launch { favStore.reorder(localOrder.map { it.code }) }
                                    },
                                )
                            FavouriteCard(
                                favourite = fav,
                                isDragging = isDragging,
                                onOpen = { onOpenStop(fav.code) },
                                onOpenTrip = onOpenTrip,
                                onRemove = { scope.launch { favStore.remove(fav.code) } },
                                handleModifier = handleModifier,
                                onStaleChanged = { staleCodes[fav.code] = it },
                                refreshKey = refreshKey,
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

@Composable
fun StaleBadge() {
    val amber = Color(0xFFFDE68A)
    Row(
        Modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = amber, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(4.dp))
        Text("stale", color = amber, style = MaterialTheme.typography.labelMedium)
    }
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
    onRemove: () -> Unit,
    handleModifier: Modifier,
    onStaleChanged: (Boolean) -> Unit,
    refreshKey: Int = 0,
) {
    var deps by remember(favourite.code) {
        mutableStateOf(DeparturesCache.get(favourite.code)?.departures?.take(3))
    }
    var initialError by remember(favourite.code) { mutableStateOf<String?>(null) }

    LaunchedEffect(favourite.code, refreshKey) {
        while (true) {
            runCatching { Api.service.departures(favourite.code) }
                .onSuccess {
                    deps = it.departures.take(3)
                    DeparturesCache.put(favourite.code, it)
                    initialError = null
                    onStaleChanged(false)
                }
                .onFailure {
                    android.util.Log.e("tfi", "departures ${favourite.code} failed", it)
                    if (deps != null) onStaleChanged(true)
                    else initialError = "${it::class.simpleName}: ${it.message}"
                }
            delay(30_000)
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isDragging) 8.dp else 1.dp,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                    Text(favourite.name, style = MaterialTheme.typography.titleMedium)
                    Text(favourite.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "⠿",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = handleModifier,
                )
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            Spacer(Modifier.height(4.dp))
            when {
                deps == null && initialError != null ->
                    Text("Could not load", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                deps == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                deps!!.isEmpty() -> Text("No upcoming departures", style = MaterialTheme.typography.bodySmall)
                else -> deps!!.forEach { d ->
                    DepartureRow(d, onOpenRoute = { onOpenTrip(d.tripId, favourite.code) })
                }
            }
        }
    }
}
