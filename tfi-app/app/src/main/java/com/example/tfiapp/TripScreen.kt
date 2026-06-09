package com.example.tfiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(
    tripId: String,
    fromStopCode: String?,
    onBack: () -> Unit,
    onOpenStop: (String) -> Unit,
) {
    var detail by remember(tripId) { mutableStateOf<TripDetail?>(null) }
    var initialError by remember(tripId) { mutableStateOf<String?>(null) }
    var stale by remember(tripId) { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        while (true) {
            runCatching { Api.service.trip(tripId) }
                .onSuccess { detail = it; stale = false; initialError = null }
                .onFailure {
                    if (detail != null) stale = true
                    else initialError = it.message ?: "error"
                }
            delay(30_000)
        }
    }

    val d = detail
    val title = if (d != null) "${d.routeShortName} → ${d.tripHeadsign}" else "Trip"

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
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
            actions = { if (stale) StaleBadge() },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                d == null && initialError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Could not load trip.\n$initialError", color = MaterialTheme.colorScheme.error)
                }
                d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> TripStopsList(d, fromStopCode, onOpenStop)
            }
        }
    }
}

@Composable
private fun TripStopsList(d: TripDetail, fromStopCode: String?, onOpenStop: (String) -> Unit) {
    val nowMins = remember { LocalTime.now().let { it.hour * 60 + it.minute } }
    val listState = rememberLazyListState()

    LaunchedEffect(fromStopCode) {
        if (fromStopCode != null) {
            val idx = d.stops.indexOfFirst { it.stopCode == fromStopCode }
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(d.stops, key = { it.stopSequence }) { stop ->
            val effective = stop.estimatedDeparture ?: stop.scheduledDeparture
            val effectiveMins = toMin(effective)
            val isPassed = effectiveMins < nowMins
            val isOrigin = fromStopCode != null && stop.stopCode == fromStopCode

            TripStopRow(
                stop = stop,
                isPassed = isPassed,
                isOrigin = isOrigin,
                nowMins = nowMins,
                onClick = { onOpenStop(stop.stopCode) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TripStopRow(
    stop: TripStop,
    isPassed: Boolean,
    isOrigin: Boolean,
    nowMins: Int,
    onClick: () -> Unit,
) {
    val passedAlpha = if (isPassed && !isOrigin) 0.4f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
                .width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${stop.stopSequence}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = passedAlpha),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stop.stopName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = passedAlpha),
                    fontWeight = if (isOrigin) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
                Text(
                    stop.stopCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = passedAlpha),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    timeLabel(stop, nowMins),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = (if (isPassed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        .copy(alpha = passedAlpha),
                )
                Text(
                    secondaryLabel(stop),
                    style = MaterialTheme.typography.labelSmall,
                    color = driftColor(stop).copy(alpha = passedAlpha),
                )
            }
        }
    }
}

private fun toMin(hhmm: String): Int {
    val parts = hhmm.split(":").map { it.toInt() }
    return parts[0] * 60 + parts[1]
}

private fun timeLabel(stop: TripStop, nowMins: Int): String {
    val effective = stop.estimatedDeparture ?: stop.scheduledDeparture
    val diff = toMin(effective) - nowMins
    return when {
        diff < -1 -> "${-diff}m ago"
        diff <= 0 -> "Due"
        diff == 1 -> "1 min"
        else -> "$diff min"
    }
}

private fun secondaryLabel(stop: TripStop): String {
    val est = stop.estimatedDeparture
    if (est == null) return stop.scheduledDeparture
    val drift = toMin(est) - toMin(stop.scheduledDeparture)
    return when {
        drift > 0 -> "${stop.scheduledDeparture} · +${drift}m"
        drift < 0 -> "${stop.scheduledDeparture} · ${drift}m"
        else -> "${stop.scheduledDeparture} · on time"
    }
}

@Composable
private fun driftColor(stop: TripStop): Color {
    val est = stop.estimatedDeparture ?: return MaterialTheme.colorScheme.onSurfaceVariant
    val drift = toMin(est) - toMin(stop.scheduledDeparture)
    return when {
        drift > 0 -> MaterialTheme.colorScheme.error
        drift < 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
