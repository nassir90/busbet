package iompar.mpts.ie

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One end of a journey, as the user chose it. Resolved to coordinates only when planning. */
sealed class PlaceRef {
    abstract val label: String

    data object Here : PlaceRef() {
        override val label = "Your location"
    }

    data class Saved(val place: SavedPlace) : PlaceRef() {
        override val label get() = place.label
    }

    data class AtStop(val code: String, val name: String, val lat: Double, val lon: Double) : PlaceRef() {
        override val label get() = name
    }

    data class Pin(val lat: Double, val lon: Double) : PlaceRef() {
        override val label get() = "Point on map"
    }
}

/**
 * The journey being planned. Owned by [App] rather than the screen, so opening a trip from an
 * itinerary and coming back finds the same results instead of an empty form.
 */
class JourneyState {
    var from by mutableStateOf<PlaceRef?>(PlaceRef.Here)
    var to by mutableStateOf<PlaceRef?>(null)
    /** Epoch seconds; null means leave now. */
    var departAt by mutableStateOf<Long?>(null)
    var result by mutableStateOf<PlanResponse?>(null)
    /** The (from, to, departAt) that [result] answers. */
    var resultKey: Any? = null
    var selected by mutableStateOf<Int?>(null)
}

/**
 * A place being saved or edited. Hoisted out of the dialog because the dialog steps aside while
 * the user picks the location, and must come back with the name they had typed.
 */
private class SaveDraft(val initial: SavedPlace?, initialKind: PlaceKind) {
    var kind by mutableStateOf(initial?.kind ?: initialKind)
    var label by mutableStateOf(initial?.label ?: if (initialKind == PlaceKind.OTHER) "" else initialKind.label)
    var point by mutableStateOf(initial?.let { LatLon(it.lat, it.lon) })
    var pointLabel by mutableStateOf(initial?.let { "Saved location" })
    var stopCode by mutableStateOf(initial?.stopCode)
    var locating by mutableStateOf(false)
}

private enum class PickFor { FROM, TO, SAVE }

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(epochSec: Long): String = Instant.ofEpochSecond(epochSec).atZone(SERVICE_ZONE).format(CLOCK)
private fun minutes(seconds: Int): Int = maxOf(1, (seconds + 30) / 60)
private fun nowSec(): Long = AppClock.millis() / 1000

/** Dublin city centre: where a map opens when there is no better idea. */
private const val CENTRE_LAT = 53.3498
private const val CENTRE_LON = -6.2603

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyScreen(
    state: JourneyState,
    onBack: () -> Unit,
    onOpenTrip: (tripId: String, fromStopCode: String?) -> Unit,
    onOpenStop: (code: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedStore = remember { SavedPlacesStore(context) }
    val saved by savedStore.flow.collectAsState(initial = emptyList())

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var needsLocation by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<PickFor?>(null) }
    var draft by remember { mutableStateOf<SaveDraft?>(null) }
    var choosingTime by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        attempt++
    }

    LaunchedEffect(state.from, state.to, state.departAt, attempt) {
        val from = state.from ?: return@LaunchedEffect
        val to = state.to ?: return@LaunchedEffect
        error = null
        needsLocation = false
        val key = Triple(from, to, state.departAt)
        // Results for these exact inputs are already on screen (coming back from a trip).
        if (state.result != null && state.resultKey == key) return@LaunchedEffect
        loading = true
        try {
            val a = resolve(context, from)
            val b = resolve(context, to)
            if (a == null || b == null) {
                needsLocation = true
                state.result = null
                return@LaunchedEffect
            }
            state.result = Api.service().plan(PlanRequest(a, b, departAt = state.departAt))
            state.resultKey = key
            state.selected = null
            unavailable = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) unavailable = true else error = "Couldn't plan this journey (HTTP ${e.code()})"
            state.result = null
        } catch (e: Exception) {
            error = "Couldn't reach the server"
            state.result = null
        } finally {
            loading = false
        }
    }

    BackHandler(enabled = state.selected != null) { state.selected = null }

    val selected = state.selected?.let { state.result?.itineraries?.getOrNull(it) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Journey") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            navigationIcon = {
                IconButton(onClick = { if (state.selected != null) state.selected = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onPrimary)
                }
            },
        )
    }) { padding ->
        if (selected != null) {
            ItineraryDetail(
                itinerary = selected,
                onOpenTrip = onOpenTrip,
                onOpenStop = onOpenStop,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                EndsCard(
                    from = state.from,
                    to = state.to,
                    onPickFrom = { picking = PickFor.FROM },
                    onPickTo = { picking = PickFor.TO },
                    onSwap = {
                        val f = state.from
                        state.from = state.to
                        state.to = f
                        state.result = null
                    },
                )
            }
            item {
                SavedPlacesRow(
                    places = saved,
                    onGo = { state.to = PlaceRef.Saved(it); state.result = null },
                    onEdit = { draft = SaveDraft(it, it.kind) },
                    onAdd = { draft = SaveDraft(null, it) },
                )
            }
            item {
                DepartRow(
                    departAt = state.departAt,
                    onNow = { state.departAt = null; state.result = null },
                    onChoose = { choosingTime = true },
                )
            }
            item { HorizontalDivider() }
            when {
                loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                unavailable -> item { Notice("This server doesn't plan journeys yet.") }
                needsLocation -> item {
                    Column(Modifier.padding(16.dp)) {
                        Notice("Starting from your location needs location access.")
                        Button(onClick = {
                            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text("Allow location") }
                    }
                }
                error != null -> item {
                    Column(Modifier.padding(16.dp)) {
                        Notice(error!!)
                        TextButton(onClick = { state.result = null; attempt++ }) { Text("Try again") }
                    }
                }
                state.to == null -> Unit
                else -> {
                    val list = state.result?.itineraries.orEmpty()
                    if (state.result != null && list.isEmpty()) item { Notice("No journeys found in the next two hours.") }
                    items(list.size) { i ->
                        ItineraryCard(list[i], onClick = { state.selected = i })
                    }
                }
            }
        }
    }

    picking?.let { mode ->
        PlacePickerSheet(
            saved = if (mode == PickFor.SAVE) emptyList() else saved,
            onDismiss = { picking = null },
            onPick = { ref ->
                when (mode) {
                    PickFor.FROM -> { state.from = ref; state.result = null }
                    PickFor.TO -> { state.to = ref; state.result = null }
                    PickFor.SAVE -> draft?.let { d -> applyPick(d, ref, context, scope) }
                }
                picking = null
            },
        )
    }

    // Out of the way while its location is being picked; the draft keeps what was typed.
    val d = draft
    if (d != null && picking != PickFor.SAVE) {
        SavePlaceDialog(
            draft = d,
            onPickLocation = { picking = PickFor.SAVE },
            onSave = { place -> scope.launch { savedStore.save(place) }; draft = null },
            onDelete = d.initial?.let { e -> { scope.launch { savedStore.remove(e.id) }; draft = null } },
            onDismiss = { draft = null },
        )
    }

    if (choosingTime) {
        DepartTimeDialog(
            initial = state.departAt,
            onPicked = { state.departAt = it; state.result = null; choosingTime = false },
            onDismiss = { choosingTime = false },
        )
    }
}

/** Coordinates for a place, or null when it needs a location fix the app may not take. */
private suspend fun resolve(context: android.content.Context, ref: PlaceRef): LatLon? = when (ref) {
    is PlaceRef.Here -> LocationProvider.current(context)?.let { LatLon(it.latitude, it.longitude) }
    is PlaceRef.Saved -> LatLon(ref.place.lat, ref.place.lon)
    is PlaceRef.AtStop -> LatLon(ref.lat, ref.lon)
    is PlaceRef.Pin -> LatLon(ref.lat, ref.lon)
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun EndsCard(from: PlaceRef?, to: PlaceRef?, onPickFrom: () -> Unit, onPickTo: () -> Unit, onSwap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EndRow(Icons.Filled.TripOrigin, from?.label ?: "Choose start", from == null, onPickFrom)
                HorizontalDivider(Modifier.padding(start = 48.dp))
                EndRow(Icons.Filled.Place, to?.label ?: "Where to?", to == null, onPickTo)
            }
            IconButton(onClick = onSwap) { Icon(Icons.Filled.SwapVert, contentDescription = "Swap start and destination") }
        }
    }
}

@Composable
private fun EndRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, placeholder: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedPlacesRow(
    places: List<SavedPlace>,
    onGo: (SavedPlace) -> Unit,
    onEdit: (SavedPlace) -> Unit,
    onAdd: (PlaceKind) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (p in places) {
            // A chip with a long press: tap to go there, hold to edit or delete.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.combinedClickable(onClick = { onGo(p) }, onLongClick = { onEdit(p) }),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(p.kind.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (places.none { it.kind == PlaceKind.HOME }) {
            AssistChip(onClick = { onAdd(PlaceKind.HOME) }, label = { Text("Set home") },
                leadingIcon = { Icon(PlaceKind.HOME.icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
        }
        if (places.none { it.kind == PlaceKind.WORK }) {
            AssistChip(onClick = { onAdd(PlaceKind.WORK) }, label = { Text("Set work") },
                leadingIcon = { Icon(PlaceKind.WORK.icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
        }
        AssistChip(onClick = { onAdd(PlaceKind.OTHER) }, label = { Text("Add place") },
            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(AssistChipDefaults.IconSize)) })
    }
}

@Composable
private fun DepartRow(departAt: Long?, onNow: () -> Unit, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = departAt == null, onClick = onNow, label = { Text("Leave now") })
        FilterChip(
            selected = departAt != null,
            onClick = onChoose,
            label = { Text(departAt?.let { "Leave at ${clock(it)}" } ?: "Leave later") },
            leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartTimeDialog(initial: Long?, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val start = Instant.ofEpochSecond(initial ?: nowSec()).atZone(SERVICE_ZONE)
    val picker = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val now = Instant.ofEpochSecond(nowSec()).atZone(SERVICE_ZONE)
                var at: ZonedDateTime = now.withHour(picker.hour).withMinute(picker.minute).withSecond(0)
                // A time earlier than now means that time tomorrow.
                if (at.isBefore(now.minusMinutes(1))) at = at.plusDays(1)
                onPicked(at.toEpochSecond())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = picker) },
    )
}

/** The departure board's colours for "late" and "early": amber and green read the same everywhere. */
private val LATE = Color(0xFFB26A00)
private val EARLY = Color(0xFF2E7D32)

@Composable
private fun DelayText(leg: PlanLeg) {
    if (!leg.realtime) return
    val d = leg.delayS ?: return
    val m = (d + if (d >= 0) 30 else -30) / 60
    val (text, colour) = when {
        m > 0 -> "+$m min" to LATE
        m < 0 -> "${m} min" to EARLY
        else -> "on time" to EARLY
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = colour)
}

/**
 * Route numbers fit a badge; Irish Rail's line names ("Dublin - Waterford") don't, and the leg
 * already says where it goes, so those show the mode instead.
 */
private fun badgeText(leg: PlanLeg): String {
    val route = leg.route.orEmpty()
    return if (route.length <= 8) route else TransitMode.of(leg.routeType).label
}

@Composable
private fun RouteBadge(leg: PlanLeg) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServiceModeIcon(leg.routeType, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            badgeText(leg),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ItineraryCard(itinerary: Itinerary, onClick: () -> Unit) {
    val firstRide = itinerary.legs.firstOrNull { !it.isWalk }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${clock(itinerary.departure)} – ${clock(itinerary.arrival)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text("${minutes(itinerary.durationS)} min", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itinerary.legs.forEachIndexed { i, leg ->
                if (i > 0) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (leg.isWalk) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Walk", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${minutes(leg.durationS)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    RouteBadge(leg)
                }
            }
        }
        if (firstRide != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val inMin = ((firstRide.start - nowSec()) / 60).toInt()
                Text(
                    buildString {
                        append(clock(firstRide.start))
                        if (inMin in 0..90) append(if (inMin == 0) " (now)" else " (in $inMin min)")
                        append(" from ")
                        append(firstRide.from.name ?: "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                DelayText(firstRide)
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ItineraryDetail(
    itinerary: Itinerary,
    onOpenTrip: (String, String?) -> Unit,
    onOpenStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        JourneyMap(itinerary, Modifier.fillMaxWidth().height(260.dp))
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${clock(itinerary.departure)} – ${clock(itinerary.arrival)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${minutes(itinerary.durationS)} min · ${if (itinerary.transfers == 0) "direct" else "${itinerary.transfers} change${if (itinerary.transfers > 1) "s" else ""}"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        for (leg in itinerary.legs) {
            if (leg.isWalk) WalkStep(leg) else RideStep(leg, onOpenTrip, onOpenStop)
            HorizontalDivider(Modifier.padding(start = 56.dp))
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text("Arrive ${clock(itinerary.arrival)}", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun WalkStep(leg: PlanLeg) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                "Walk ${minutes(leg.durationS)} min" + (leg.distanceM?.let { " · ${if (it >= 1000) "%.1f km".format(it / 1000.0) else "$it m"}" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
            leg.to.name?.let {
                Text("to $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RideStep(leg: PlanLeg, onOpenTrip: (String, String?) -> Unit, onOpenStop: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = leg.tripId != null) { onOpenTrip(leg.tripId!!, leg.from.stopCode) }
            .padding(16.dp),
    ) {
        ServiceModeIcon(leg.routeType, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(leg)
                Spacer(Modifier.width(8.dp))
                Text(
                    leg.headsign?.let { "to $it" } ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(leg.start)}  ", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                StopName(leg.from, onOpenStop, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                DelayText(leg)
            }
            leg.intermediateStops?.let { n ->
                Text(
                    "${minutes(leg.durationS)} min · ${n + 1} stop${if (n == 0) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(leg.end)}  ", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                StopName(leg.to, onOpenStop, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun StopName(place: PlanPlace, onOpenStop: (String) -> Unit, modifier: Modifier = Modifier) {
    val code = place.stopCode?.takeIf { it.isNotBlank() && it != "0" } ?: place.stopId
    Text(
        place.name ?: "",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(enabled = code != null) { onOpenStop(code!!) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacePickerSheet(saved: List<SavedPlace>, onDismiss: () -> Unit, onPick: (PlaceRef) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var stops by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var pinning by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { stops = emptyList(); return@LaunchedEffect }
        delay(300)
        stops = runCatching { Api.service().searchStops(query.trim()) }
            .getOrElse { if (it is CancellationException) throw it; emptyList() }
            .filter { it.stopLat != null && it.stopLon != null }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search stops") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (query.isBlank()) {
                ListItem(
                    headlineContent = { Text(PlaceRef.Here.label) },
                    leadingContent = { Icon(Icons.Filled.MyLocation, null) },
                    modifier = Modifier.clickable { onPick(PlaceRef.Here) },
                )
                ListItem(
                    headlineContent = { Text("Choose on map") },
                    leadingContent = { Icon(Icons.Filled.Map, null) },
                    modifier = Modifier.clickable { pinning = true },
                )
                for (p in saved) {
                    ListItem(
                        headlineContent = { Text(p.label) },
                        leadingContent = { Icon(p.kind.icon, null) },
                        modifier = Modifier.clickable { onPick(PlaceRef.Saved(p)) },
                    )
                }
            }
            for (s in stops.take(8)) {
                ListItem(
                    headlineContent = { Text(s.stopName) },
                    supportingContent = { Text("Stop ${s.addressId}") },
                    leadingContent = { StopModeIcon(s.routeTypes) },
                    modifier = Modifier.clickable { onPick(PlaceRef.AtStop(s.addressId, s.stopName, s.stopLat!!, s.stopLon!!)) },
                )
            }
        }
    }
    if (pinning) {
        PinDialog(
            onPicked = { lat, lon -> pinning = false; onPick(PlaceRef.Pin(lat, lon)) },
            onDismiss = { pinning = false },
        )
    }
}

@Composable
private fun PinDialog(onPicked: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var start by remember { mutableStateOf<LatLon?>(null) }
    LaunchedEffect(Unit) {
        start = LocationProvider.current(context)?.let { LatLon(it.latitude, it.longitude) } ?: LatLon(CENTRE_LAT, CENTRE_LON)
    }
    var centre by remember { mutableStateOf<LatLon?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val s = start
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Surface
            }
            Box(Modifier.fillMaxSize()) {
                PinPickerMap(s.lat, s.lon, onCentreChanged = { lat, lon -> centre = LatLon(lat, lon) }, modifier = Modifier.fillMaxSize())
                // The pin's point sits on the map's centre: lift the icon by half its height.
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp),
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { val c = centre ?: s; onPicked(c.lat, c.lon) }) { Text("Use this point") }
                }
            }
        }
    }
}

private fun applyPick(d: SaveDraft, ref: PlaceRef, context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    when (ref) {
        is PlaceRef.Here -> {
            d.locating = true
            scope.launch {
                d.point = LocationProvider.current(context)?.let { LatLon(it.latitude, it.longitude) }
                d.pointLabel = if (d.point != null) "Where you are now" else "Location unavailable"
                d.stopCode = null
                d.locating = false
            }
        }
        is PlaceRef.AtStop -> { d.point = LatLon(ref.lat, ref.lon); d.pointLabel = ref.name; d.stopCode = ref.code }
        is PlaceRef.Pin -> { d.point = LatLon(ref.lat, ref.lon); d.pointLabel = "Point on map"; d.stopCode = null }
        is PlaceRef.Saved -> { d.point = LatLon(ref.place.lat, ref.place.lon); d.pointLabel = ref.place.label; d.stopCode = ref.place.stopCode }
    }
}

@Composable
private fun SavePlaceDialog(
    draft: SaveDraft,
    onPickLocation: () -> Unit,
    onSave: (SavedPlace) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.initial == null) "Save a place" else "Edit place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PlaceKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = draft.kind == k,
                            onClick = {
                                if (draft.label.isBlank() || draft.label == draft.kind.label) {
                                    draft.label = if (k == PlaceKind.OTHER) "" else k.label
                                }
                                draft.kind = k
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, PlaceKind.entries.size),
                        ) { Text(k.label) }
                    }
                }
                OutlinedTextField(value = draft.label, onValueChange = { draft.label = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (draft.locating) "Finding you…" else draft.pointLabel ?: "No location yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPickLocation) { Text("Choose") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.label.isNotBlank() && draft.point != null,
                onClick = {
                    val p = draft.point ?: return@TextButton
                    onSave(
                        SavedPlace(
                            id = draft.initial?.id ?: java.util.UUID.randomUUID().toString(),
                            label = draft.label.trim(),
                            kind = draft.kind,
                            lat = p.lat,
                            lon = p.lon,
                            stopCode = draft.stopCode,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
