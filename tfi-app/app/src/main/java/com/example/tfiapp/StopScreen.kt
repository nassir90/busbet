package com.example.tfiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopScreen(
    code: String,
    onBack: () -> Unit,
    onOpenRoute: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    val favStore = remember { FavouritesStore(context) }
    val favourites by favStore.flow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var data by remember(code) { mutableStateOf<DeparturesResponse?>(null) }
    var initialError by remember(code) { mutableStateOf<String?>(null) }
    var lastFetched by remember(code) { mutableStateOf<LocalTime?>(null) }
    var stale by remember(code) { mutableStateOf(false) }

    LaunchedEffect(code) {
        while (true) {
            runCatching { Api.service.departures(code) }
                .onSuccess {
                    data = it
                    initialError = null
                    stale = false
                    lastFetched = LocalTime.now()
                }
                .onFailure {
                    android.util.Log.e("tfi", "stop $code fetch failed", it)
                    if (data != null) stale = true
                    else initialError = it.message ?: "error"
                }
            delay(30_000)
        }
    }

    val isFavourite = favourites.any { it.code == code }
    val title = data?.stop?.stopName ?: "Stop $code"

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("←") }
            },
            actions = {
                if (stale) StaleBadge()
                val stop = data?.stop
                TextButton(onClick = {
                    scope.launch {
                        if (isFavourite) favStore.remove(code)
                        else favStore.add(code, stop?.stopName ?: code)
                    }
                }) { Text(if (isFavourite) "★ Saved" else "☆ Save") }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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

            when {
                initialError != null && data == null -> EmptyMessage("Could not load departures.\n$initialError")
                data == null -> EmptyMessage("Loading…")
                data!!.departures.isEmpty() -> EmptyMessage("No departures in the next 105 minutes.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(data!!.departures) { d ->
                        DepartureRow(d, onOpenRoute = { onOpenRoute(d.routeShortName, d.directionId) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DepartureRow(d: Departure, onOpenRoute: () -> Unit) {
    val nowMins = LocalTime.now().let { it.hour * 60 + it.minute }
    val effective = d.estimatedDeparture ?: d.scheduledDeparture
    val effectiveMins = toMinutes(effective)
    val due = effectiveMins - nowMins

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
