package iompar.mpts.ie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    route: String,
    direction: Int,
    onBack: () -> Unit,
    onOpenStop: (String) -> Unit,
    onFlip: () -> Unit,
) {
    var stops by remember(route, direction) { mutableStateOf<List<RouteStop>?>(null) }
    var error by remember(route, direction) { mutableStateOf<String?>(null) }

    LaunchedEffect(route, direction) {
        runCatching { Api.service().routeStops(route, direction) }
            .onSuccess { stops = it; error = null }
            .onFailure {
                // Leaving the screen cancels this; that isn't an error to show the user.
                if (it is kotlinx.coroutines.CancellationException) throw it
                // 404 is the server saying this route has no stops in this direction, which the
                // flip button reaches on any one-way route. That's an empty result — showing
                // "Could not load route" made a normal answer look like a failure.
                if (it is retrofit2.HttpException && it.code() == 404) {
                    stops = emptyList(); error = null
                } else {
                    error = it.message ?: "error"; stops = emptyList()
                }
            }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Route $route") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
            actions = {
                IconButton(onClick = onFlip) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = "Flip direction",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val s = stops
            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Could not load route.", color = MaterialTheme.colorScheme.error)
                }
                s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
                s.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "This route has no stops in this direction.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.first().stopName} → ${s.last().stopName} · ${s.size} stops",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s) { stop ->
                            RouteStopRow(stop = stop, onClick = { onOpenStop(stop.stopCode) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * A stop in the route list.
 *
 * Deliberately the same shape as TripScreen's TripStopRow rather than a Material3 ListItem: the
 * two screens list the same thing and sat at different type scales and insets, ListItem's
 * headline being bodyLarge against the trip view's bodyMedium (TFI-116). The trip row is the one
 * with the sequence gutter and the tighter rhythm, so the route list follows it. What is missing
 * here is only what a route has no answer for: a departure time.
 */
@Composable
private fun RouteStopRow(stop: RouteStop, onClick: () -> Unit) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp, horizontal = 8.dp),
        ) {
            Text(
                stop.stopName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                stop.stopCode,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
