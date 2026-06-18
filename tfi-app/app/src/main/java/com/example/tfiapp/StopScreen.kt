package com.example.tfiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopScreen(
    code: String,
    onBack: () -> Unit,
    onOpenTrip: (tripId: String, fromStopCode: String?) -> Unit,
    onAddNotification: (stopCode: String, stopName: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val favStore = remember { FavouritesStore(context) }
    val favourites by favStore.flow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var data by remember(code) { mutableStateOf(DeparturesCache.get(code)) }
    var initialError by remember(code) { mutableStateOf<String?>(null) }
    var lastFetched by remember(code) { mutableStateOf<LocalTime?>(null) }
    var stale by remember(code) { mutableStateOf(false) }
    var vehicles by remember(code) { mutableStateOf<List<VehiclePosition>>(emptyList()) }
    var refreshKey by remember(code) { mutableStateOf(0) }
    var isRefreshing by remember(code) { mutableStateOf(false) }

    LaunchedEffect(code, refreshKey) {
        while (true) {
            runCatching { Api.service.departures(code) }
                .onSuccess {
                    data = it
                    DeparturesCache.put(code, it)
                    initialError = null
                    stale = false
                    lastFetched = LocalTime.now()
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    android.util.Log.e("tfi", "stop $code fetch failed", it)
                    if (data != null) stale = true
                    else initialError = it.message ?: "error"
                }
            runCatching { Api.service.vehicles(code) }
                .onSuccess { vehicles = it }
                .onFailure {
                    if (it is CancellationException) throw it
                    android.util.Log.w("tfi", "vehicles $code fetch failed", it)
                }
            isRefreshing = false
            delay(30_000)
        }
    }

    val isFavourite = favourites.any { it.code == code }
    val title = data?.stop?.stopName ?: "Stop $code"

    val stop = data?.stop
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (isFavourite) favStore.remove(code)
                                else favStore.add(code, stop?.stopName ?: code, stop?.stopLat, stop?.stopLon)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                    ) { Text(if (isFavourite) "★" else "☆") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            navigationIcon = {
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("←") }
            },
            actions = {
                if (stale) StaleBadge()
                TextButton(
                    onClick = { onAddNotification(code, stop?.stopName ?: code) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("🔔") }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Map sits at the top, just below the app bar
            if (stop?.stopLat != null && stop.stopLon != null) {
                StopMap(
                    lat = stop.stopLat,
                    lon = stop.stopLon,
                    vehicles = vehicles,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Stop $code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                lastFetched?.let {
                    Text("as of ${it.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true; refreshKey++ },
                modifier = Modifier.weight(1f),
            ) {
                when {
                    initialError != null && data == null -> EmptyMessage("Could not load departures.\n$initialError")
                    data == null -> EmptyMessage("Loading…")
                    data!!.departures.isEmpty() -> EmptyMessage("No departures in the next 105 minutes.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(data!!.departures) { d ->
                            DepartureRow(d, onOpenRoute = { onOpenTrip(d.tripId, code) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    // Scrollable so pull-to-refresh registers even when there's no list content.
    Box(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DepartureRow(d: Departure, onOpenRoute: () -> Unit) {
    val nowMins = LocalTime.now().let { it.hour * 60 + it.minute }
    val effective = d.estimatedDeparture ?: d.scheduledDeparture
    val effectiveMins = toMinutes(effective)
    val due = (effectiveMins - nowMins).let { if (it < -720) it + 1440 else it }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.widthIn(min = 48.dp),
        ) {
            Text(
                d.routeShortName,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onOpenRoute)) {
            Text(d.tripHeadsign, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(buildDriftLabel(d), style = MaterialTheme.typography.bodySmall, color = driftColor(d))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(dueLabel(due), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (d.realtime) "Live" else "Sched",
                style = MaterialTheme.typography.labelSmall,
                color = if (d.realtime) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun toMinutes(hhmm: String): Int {
    val (h, m) = hhmm.split(":").map { it.toInt() }
    return h * 60 + m
}

private fun dueLabel(diff: Int): String = when {
    diff <= 0 -> "Due"
    diff == 1 -> "1 min"
    else -> "$diff mins"
}

private fun buildDriftLabel(d: Departure): String {
    val est = d.estimatedDeparture ?: return "Sched ${d.scheduledDeparture}"
    val drift = toMinutes(est) - toMinutes(d.scheduledDeparture)
    return when {
        drift > 0 -> "Sched ${d.scheduledDeparture} · +${drift}m"
        drift < 0 -> "Sched ${d.scheduledDeparture} · ${drift}m"
        else -> "Sched ${d.scheduledDeparture} · on time"
    }
}

@Composable
private fun driftColor(d: Departure): androidx.compose.ui.graphics.Color {
    val est = d.estimatedDeparture ?: return MaterialTheme.colorScheme.onSurfaceVariant
    val drift = toMinutes(est) - toMinutes(d.scheduledDeparture)
    return when {
        drift > 0 -> MaterialTheme.colorScheme.error
        drift < 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
