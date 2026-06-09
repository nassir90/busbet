package com.example.tfiapp

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

private const val GRID_START_MIN  = 6 * 60    // 06:00
private const val GRID_END_MIN    = 21 * 60   // 21:00
private const val TIME_LABEL_W_DP = 44

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

enum class CalendarViewMode { WEEK, DAY }

// Persists across open/close cycles of the create sheet for the lifetime of the screen.
class NotificationDraft {
    var name      by mutableStateOf("")
    var days      by mutableStateOf(setOf(1, 2, 3, 4, 5))
    var startMin  by mutableIntStateOf(8 * 60)
    var endMin    by mutableIntStateOf(9 * 60)
    var stopCode  by mutableStateOf("")
    var stopName  by mutableStateOf("")
    var routes    by mutableStateOf<List<String>>(emptyList())
    var enabled   by mutableStateOf(true)

    fun prefill(code: String, name: String) { stopCode = code; stopName = name }

    fun loadFrom(w: NotificationWindow) {
        name = w.name; days = w.days; startMin = w.startMinute; endMin = w.endMinute
        stopCode = w.stopCode; stopName = w.stopName; routes = w.routes; enabled = w.enabled
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    prefill: Pair<String, String>? = null,
    onPrefillConsumed: () -> Unit = {},
) {
    val context   = LocalContext.current
    val store     = remember { NotificationWindowStore(context) }
    val windows   by store.flow.collectAsState(initial = emptyList())
    val scope     = rememberCoroutineScope()

    var viewMode    by remember { mutableStateOf(CalendarViewMode.WEEK) }
    var selectedDay by remember { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var editTarget  by remember { mutableStateOf<NotificationWindow?>(null) }
    var showCreate  by remember { mutableStateOf(false) }
    val createDraft = remember { NotificationDraft() }

    // Open create sheet pre-filled when navigated from a stop's bell icon
    LaunchedEffect(prefill) {
        if (prefill != null) {
            createDraft.prefill(prefill.first, prefill.second)
            showCreate = true
            onPrefillConsumed()
        }
    }

    val visibleDays = remember(viewMode, selectedDay) {
        if (viewMode == CalendarViewMode.WEEK) (1..7).toList() else listOf(selectedDay)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        BusNotificationService.start(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 2.dp),
                    ) {
                        listOf(CalendarViewMode.WEEK to "Week", CalendarViewMode.DAY to "Day").forEach { (mode, label) ->
                            TextButton(
                                onClick = { viewMode = mode },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (viewMode == mode) MaterialTheme.colorScheme.onPrimary
                                                   else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            DayHeader(
                visibleDays = visibleDays,
                viewMode = viewMode,
                onDayClick = { day -> selectedDay = day; viewMode = CalendarViewMode.DAY },
            )
            HorizontalDivider()

            if (windows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No notification windows yet.\nTap + to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                CalendarGrid(
                    windows = windows,
                    visibleDays = visibleDays,
                    onWindowMoved = { id, start, end, days ->
                        windows.firstOrNull { it.id == id }?.let { w ->
                            scope.launch { store.save(w.copy(startMinute = start, endMinute = end, days = days)) }
                        }
                    },
                    onWindowClick = { editTarget = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showCreate || editTarget != null) {
        NotificationWindowSheet(
            window = editTarget,
            draft = if (editTarget == null) createDraft else null,
            onSave = { w ->
                scope.launch { store.save(w) }
                BusNotificationService.refresh(context)
                showCreate = false; editTarget = null
            },
            onDelete = { id ->
                scope.launch { store.delete(id) }
                editTarget = null
            },
            onDismiss = { showCreate = false; editTarget = null },
        )
    }
}

@Composable
private fun DayHeader(
    visibleDays: List<Int>,
    viewMode: CalendarViewMode,
    onDayClick: (Int) -> Unit,
) {
    val today = LocalDate.now().dayOfWeek.value
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        Spacer(Modifier.width(TIME_LABEL_W_DP.dp))
        visibleDays.forEach { day ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (viewMode == CalendarViewMode.WEEK)
                            Modifier.clickable { onDayClick(day) }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    DAY_LABELS[day - 1],
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                    color = if (day == today) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    windows: List<NotificationWindow>,
    visibleDays: List<Int>,
    onWindowMoved: (id: String, startMinute: Int, endMinute: Int, days: Set<Int>) -> Unit,
    onWindowClick: (NotificationWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density      = LocalDensity.current
    val timeLabelWDp = TIME_LABEL_W_DP.dp
    val totalHours   = (GRID_END_MIN - GRID_START_MIN) / 60

    var nowMins by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowMins = LocalTime.now().let { it.hour * 60 + it.minute }
        }
    }

    val dragOffsets = remember { mutableStateMapOf<String, Offset>() }

    val outlineColor   = MaterialTheme.colorScheme.outlineVariant
    val errorColor     = MaterialTheme.colorScheme.error
    val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(modifier) {
        // Divide by totalHours+1 so one hour-slot of space remains below the 21 line
        val hourHeightDp  = maxHeight / (totalHours + 1)
        val hourHeightPx  = with(density) { hourHeightDp.toPx() }
        val dayColWidth   = (maxWidth - timeLabelWDp) / visibleDays.size
        val dayColWidthPx = with(density) { dayColWidth.toPx() }
        val timeLabelWPx  = with(density) { timeLabelWDp.toPx() }

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Grid lines + now indicator
                Canvas(Modifier.matchParentSize()) {
                    // Hour and half-hour lines
                    for (h in 0..totalHours) {
                        val y = h * hourHeightPx
                        drawLine(outlineColor, Offset(timeLabelWPx, y), Offset(size.width, y), strokeWidth = 1f)
                        if (h < totalHours) {
                            drawLine(
                                outlineColor.copy(alpha = 0.35f),
                                Offset(timeLabelWPx, y + hourHeightPx / 2f),
                                Offset(size.width, y + hourHeightPx / 2f),
                                strokeWidth = 0.5f,
                            )
                        }
                    }
                    // Day column dividers
                    for (i in 1..visibleDays.size) {
                        val x = timeLabelWPx + i * dayColWidthPx
                        drawLine(outlineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                    // Current time line
                    if (nowMins in GRID_START_MIN..GRID_END_MIN) {
                        val y = (nowMins - GRID_START_MIN) / 60f * hourHeightPx
                        drawCircle(errorColor, radius = 5f, center = Offset(timeLabelWPx, y))
                        drawLine(errorColor, Offset(timeLabelWPx, y), Offset(size.width, y), strokeWidth = 2f)
                    }
                }

                // Time labels
                for (h in 1..totalHours) {
                    val hour = (GRID_START_MIN / 60 + h) % 24
                    Text(
                        "%02d".format(hour),
                        modifier = Modifier
                            .absoluteOffset(x = 0.dp, y = hourHeightDp * h - 8.dp)
                            .width(timeLabelWDp)
                            .padding(end = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceColor,
                        textAlign = TextAlign.End,
                    )
                }

                // Window blobs
                windows.forEach { window ->
                    window.days.forEach dayLoop@{ day ->
                        val colIndex = visibleDays.indexOf(day)
                        if (colIndex < 0) return@dayLoop

                        val blobLeftDp   = timeLabelWDp + dayColWidth * colIndex
                        val blobTopDp    = hourHeightDp * (window.startMinute - GRID_START_MIN) / 60f
                        val blobHeightDp = (hourHeightDp * (window.endMinute - window.startMinute) / 60f)
                            .coerceAtLeast(24.dp)

                        val dragOffset = dragOffsets[window.id] ?: Offset.Zero
                        val primary    = MaterialTheme.colorScheme.primary
                        val onPrimary  = MaterialTheme.colorScheme.onPrimary

                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        x = (blobLeftDp.toPx() + dragOffset.x).roundToInt(),
                                        y = (blobTopDp.toPx()  + dragOffset.y).roundToInt(),
                                    )
                                }
                                .width(dayColWidth - 2.dp)
                                .height(blobHeightDp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (window.enabled) primary else primary.copy(alpha = 0.4f))
                                .pointerInput(window.id) {
                                    detectTapGestures(onTap = { onWindowClick(window) })
                                }
                                .pointerInput("drag_${window.id}") {
                                    detectDragGestures(
                                        onDragStart = { dragOffsets[window.id] = Offset.Zero },
                                        onDrag = { _, amount ->
                                            dragOffsets[window.id] =
                                                (dragOffsets[window.id] ?: Offset.Zero) + amount
                                        },
                                        onDragEnd = {
                                            val delta = dragOffsets[window.id] ?: Offset.Zero
                                            val minsDelta = (delta.y / hourHeightPx * 60).roundToInt()
                                            val colDelta  = (delta.x / dayColWidthPx).roundToInt()

                                            val duration = window.endMinute - window.startMinute
                                            val rawStart = window.startMinute + minsDelta
                                            val snapped  = (rawStart / 15) * 15
                                            val newStart = snapped.coerceIn(GRID_START_MIN, GRID_END_MIN - 15)
                                            val newEnd   = (newStart + duration).coerceAtMost(GRID_END_MIN)

                                            val newDays = if (colDelta != 0) {
                                                val origIdx  = visibleDays.indexOf(day)
                                                val newIdx   = (origIdx + colDelta).coerceIn(0, visibleDays.size - 1)
                                                val dayShift = visibleDays[newIdx] - day
                                                window.days.map { d -> ((d - 1 + dayShift).mod(7)) + 1 }.toSet()
                                            } else window.days

                                            onWindowMoved(window.id, newStart, newEnd, newDays)
                                            dragOffsets.remove(window.id)
                                        },
                                        onDragCancel = { dragOffsets.remove(window.id) },
                                    )
                                },
                            contentAlignment = Alignment.TopStart,
                        ) {
                            Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Text(
                                    window.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = onPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (blobHeightDp >= 38.dp) {
                                    Text(
                                        window.stopName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = onPrimary.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
