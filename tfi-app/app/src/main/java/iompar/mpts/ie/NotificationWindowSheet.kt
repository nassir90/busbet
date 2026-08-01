package iompar.mpts.ie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationWindowSheet(
    window: NotificationWindow?,
    draft: NotificationDraft?,        // non-null for create mode — state persists across open/close
    onSave: (NotificationWindow) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isNew   = window == null

    // For edit mode use local vars; for create mode proxy through draft so state survives dismissal.
    var name     by remember { mutableStateOf(window?.name     ?: draft?.name     ?: "") }
    var days     by remember { mutableStateOf(window?.days     ?: draft?.days     ?: setOf(1, 2, 3, 4, 5)) }
    var startMin by remember { mutableIntStateOf(window?.startMinute ?: draft?.startMin ?: 8 * 60) }
    var endMin   by remember { mutableIntStateOf(window?.endMinute   ?: draft?.endMin   ?: 9 * 60) }
    var stopCode by remember { mutableStateOf(window?.stopCode ?: draft?.stopCode ?: "") }
    var stopName by remember { mutableStateOf(window?.stopName ?: draft?.stopName ?: "") }
    var routes   by remember { mutableStateOf(window?.routes   ?: draft?.routes   ?: emptyList()) }
    var enabled  by remember { mutableStateOf(window?.enabled  ?: draft?.enabled  ?: true) }

    // Sync local state back into draft whenever it changes (create mode only)
    LaunchedEffect(name, days, startMin, endMin, stopCode, stopName, routes, enabled) {
        if (draft != null) {
            draft.name = name; draft.days = days; draft.startMin = startMin; draft.endMin = endMin
            draft.stopCode = stopCode; draft.stopName = stopName; draft.routes = routes; draft.enabled = enabled
        }
    }

    var stopQuery     by remember { mutableStateOf(window?.stopName ?: draft?.stopName ?: "") }
    var stopResults   by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var availRoutes   by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingRoutes by remember { mutableStateOf(false) }

    // Debounced stop search
    LaunchedEffect(stopQuery) {
        if (stopQuery.length >= 2 && stopQuery != stopName) {
            delay(200)
            runCatching { Api.service.searchStops(stopQuery) }
                .onSuccess { stopResults = it.take(6) }
        } else if (stopQuery.isEmpty()) {
            stopResults = emptyList()
        }
    }

    // Fetch ALL routes for the stop from static GTFS (not just current departures)
    LaunchedEffect(stopCode) {
        if (stopCode.isEmpty()) { availRoutes = emptyList(); return@LaunchedEffect }
        loadingRoutes = true
        runCatching { Api.service.stopRoutes(stopCode) }
            .onSuccess { availRoutes = it.sorted() }
        loadingRoutes = false
    }

    fun timeStr(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    val canSave = name.isNotBlank() && stopCode.isNotEmpty() && days.isNotEmpty() && endMin > startMin

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (isNew) "New notification window" else "Edit notification window",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // ── Name ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // ── Days ─────────────────────────────────────────────────────────
            Column {
                Text("Days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
                        .forEach { (d, label) ->
                            FilterChip(
                                selected = d in days,
                                onClick = { days = if (d in days) days - d else days + d },
                                label = { Text(label) },
                            )
                        }
                }
            }

            // ── Time range ───────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = timeStr(startMin),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Start") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.matchParentSize().clickable {
                        android.app.TimePickerDialog(
                            context, { _, h, m -> startMin = h * 60 + m },
                            startMin / 60, startMin % 60, true,
                        ).show()
                    })
                }
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = timeStr(endMin),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("End") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.matchParentSize().clickable {
                        android.app.TimePickerDialog(
                            context, { _, h, m -> endMin = h * 60 + m },
                            endMin / 60, endMin % 60, true,
                        ).show()
                    })
                }
            }

            // ── Stop search ──────────────────────────────────────────────────
            Column {
                OutlinedTextField(
                    value = stopQuery,
                    onValueChange = { stopQuery = it; if (it != stopName) stopCode = "" },
                    label = { Text("Bus stop") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = if (stopCode.isNotEmpty()) {
                        { Text(stopCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
                if (stopResults.isNotEmpty()) {
                    // Same presentation as the home-screen stop search: a compact row with the
                    // stop's route chips trailing the "code  name" label.
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            stopResults.forEachIndexed { i, stop ->
                                CompactSearchRow(
                                    label = "${stop.stopCode}  ${stop.stopName}",
                                    services = stop.routes,
                                    onClick = {
                                        stopCode    = stop.stopCode
                                        stopName    = stop.stopName
                                        stopQuery   = stop.stopName
                                        stopResults = emptyList()
                                        routes      = emptyList()
                                        availRoutes = emptyList()
                                    },
                                )
                                if (i < stopResults.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // ── Routes ───────────────────────────────────────────────────────
            if (stopCode.isNotEmpty()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Routes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (routes.isEmpty()) "(all)" else "${routes.size} selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (loadingRoutes) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else if (availRoutes.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            availRoutes.forEach { route ->
                                FilterChip(
                                    selected = route in routes,
                                    onClick = { routes = if (route in routes) routes - route else routes + route },
                                    label = { Text(route) },
                                )
                            }
                        }
                    }
                }
            }

            // ── Enable toggle ────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            // ── Action buttons ───────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew) {
                    OutlinedButton(
                        onClick = { onDelete(window!!.id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Button(
                    onClick = {
                        if (canSave) {
                            onSave(NotificationWindow(
                                id          = window?.id ?: UUID.randomUUID().toString(),
                                name        = name.trim(),
                                days        = days,
                                startMinute = startMin,
                                endMinute   = endMin,
                                stopCode    = stopCode,
                                stopName    = stopName,
                                routes      = routes,
                                enabled     = enabled,
                            ))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                ) { Text(if (isNew) "Create" else "Save") }
            }
        }
    }
}
