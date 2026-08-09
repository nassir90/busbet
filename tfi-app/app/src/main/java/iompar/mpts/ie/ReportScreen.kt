package iompar.mpts.ie

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    routeShortName: String,
    stopCode: String,
    stopName: String,
    tripId: String,
    scheduledDeparture: String,   // "HH:MM"
    estimatedDeparture: String?,  // "HH:MM" or null
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var submitting by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }

    LaunchedEffect(tripId, stopCode) {
        watching = BusWatchStore(context).has(tripId, stopCode)
    }

    fun toggleWatch() {
        scope.launch {
            if (watching) {
                BusWatchScheduler.cancel(context, tripId, stopCode)
                watching = false
                Toast.makeText(context, "Notification cancelled", Toast.LENGTH_SHORT).show()
            } else {
                val effective = estimatedDeparture ?: scheduledDeparture
                val (h, m) = effective.split(":").map { it.toInt() }
                // The departure time is a service-zone wall clock, so resolve it to an instant
                // there; anchoring it to the device's zone would fire the alarm hours out.
                val now = ZonedDateTime.now(SERVICE_ZONE)
                var target = now.withHour(h).withMinute(m).withSecond(0).withNano(0)
                if (target.isBefore(now.minusHours(12))) target = target.plusDays(1)
                BusWatchScheduler.schedule(
                    context,
                    BusWatch(
                        tripId = tripId,
                        stopCode = stopCode,
                        stopName = stopName,
                        routeShortName = routeShortName,
                        triggerAtMillis = target.toInstant().toEpochMilli(),
                    ),
                )
                watching = true
                Toast.makeText(context, "We'll notify you when it's due", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun submit(kind: String, actualTime: String?) {
        if (submitting) return
        scope.launch {
            submitting = true
            val report = ArrivalReport(
                tripId = tripId,
                stopCode = stopCode,
                routeShortName = routeShortName,
                // GTFS service date, which is the operator's calendar day, not the device's.
                serviceDate = serviceToday().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                kind = kind,
                actualTime = actualTime,
                reportedAt = System.currentTimeMillis() / 1000,
            )
            runCatching { Api.tenant().report(report) }
                .onSuccess { response ->
                    val result = snackbarHostState.showSnackbar(
                        message = "Reported",
                        actionLabel = "Undo",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        runCatching { Api.tenant().undoReport(response.id) }
                            .onSuccess { Toast.makeText(context, "Report undone", Toast.LENGTH_SHORT).show() }
                            .onFailure {
                                Toast.makeText(context, "Undo failed: ${it.message ?: "error"}", Toast.LENGTH_LONG).show()
                            }
                    }
                    onBack()
                }
                .onFailure {
                    Toast.makeText(context, "Failed: ${it.message ?: "error"}", Toast.LENGTH_LONG).show()
                }
            submitting = false
        }
    }

    fun pickArrivalTime() {
        // The picked time is submitted as a service-clock time, so pre-fill it from that clock.
        val now = serviceNow()
        TimePickerDialog(
            context,
            { _, hour, minute -> submit("arrived", "%02d:%02d".format(hour, minute)) },
            now.hour,
            now.minute,
            true,
        ).show()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Report") },
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
                    IconButton(onClick = { toggleWatch() }) {
                        Icon(
                            if (watching) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                            contentDescription = if (watching) "Cancel notification" else "Notify me when due",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    routeShortName,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "$stopName ($stopCode)",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(Modifier.width(120.dp))
            Spacer(Modifier.height(12.dp))

            val effective = estimatedDeparture ?: scheduledDeparture
            val due = minutesUntil(toMins(effective), serviceNowMinutes())
            Text(
                when {
                    due <= 0 -> "Due now"
                    due == 1 -> "in 1 min"
                    else -> "in $due min"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (estimatedDeparture != null) "live $estimatedDeparture · scheduled $scheduledDeparture"
                else "scheduled $scheduledDeparture",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            // The highest-value observation is "I am on this bus, now": it is ground truth
            // about the user rather than an inference about the vehicle, and it is filed at
            // the moment it happens, so it needs no time entry. One tap, before they sit down.
            Button(
                onClick = { submit("boarded", serviceNow().format(HHMM)) },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("I got on this bus") }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // "Arrived" stays time-picked: it covers the bus turning up when you did not
                // board it, and gets filed after the fact.
                OutlinedButton(
                    onClick = { pickArrivalTime() },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Arrived") }
                OutlinedButton(
                    onClick = { submit("cancelled", null) },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Cancelled") }
            }
        }
    }
}

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun toMins(hhmm: String): Int {
    val (h, m) = hhmm.split(":").map { it.toInt() }
    return h * 60 + m
}
