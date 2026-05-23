package com.example.tfiapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        runCatching { Api.service.routeStops(route, direction) }
            .onSuccess { stops = it; error = null }
            .onFailure { error = it.message ?: "error"; stops = emptyList() }
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
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("←") }
            },
            actions = {
                TextButton(
                    onClick = onFlip,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("⇄") }
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
                s.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stops") }
                else -> {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.first().stopName} → ${s.last().stopName} · ${s.size} stops",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s) { stop ->
                            ListItem(
                                leadingContent = { Text("${stop.stopSequence}", style = MaterialTheme.typography.labelSmall) },
                                headlineContent = { Text(stop.stopName) },
                                supportingContent = { Text(stop.stopCode) },
                                modifier = Modifier.clickable { onOpenStop(stop.stopCode) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
