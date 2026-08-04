package iompar.mpts.ie

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** A request (from a home-screen widget tap) to jump straight to a stop's board. */
data class WidgetStopRequest(val stopCode: String, val seq: Int)

/** A request (from a stop screen's notification bell) to open the create sheet pre-filled with a stop. */
data class NotifStopRequest(val code: String, val name: String, val seq: Int)

class MainActivity : ComponentActivity() {
    private var widgetRequestSeq = 0
    private val widgetStopRequest = mutableStateOf<WidgetStopRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge with transparent bars and light (white) icons so the
        // app background fills the entire screen including the curved bottom corners.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        captureWidgetStop(intent)
        // Read synchronously, before the first frame: the real palette lives in DataStore and can
        // only be reached from a coroutine, so seeding from it asynchronously meant every cold
        // start painted the default purple and then re-themed. See PaletteCache.
        val seedPalette = PaletteCache.cached(this)
        setContent {
            val context = LocalContext.current
            val paletteStore = remember { PaletteStore(context) }
            val palette by paletteStore.palette.collectAsState(initial = seedPalette)
            val dark = isSystemInDarkTheme()
            // Remembered: building a scheme is ~20 blends and allocations, and neither input
            // changes on a typical recomposition.
            val colors = remember(palette, dark) { buildColorScheme(palette, dark) }

            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(paletteStore = paletteStore, widgetStopRequest = widgetStopRequest)
                }
            }
        }
    }

    // android:launchMode="singleTop" means a widget tap while the app is already on top
    // reuses this instance via onNewIntent rather than recreating it, so without this
    // override the new stopCode extra would be silently dropped and the tap would just
    // resume whatever screen was already showing.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureWidgetStop(intent)
    }

    private fun captureWidgetStop(intent: Intent) {
        val code = intent.getStringExtra("stopCode") ?: return
        widgetStopRequest.value = WidgetStopRequest(code, widgetRequestSeq++)
    }
}

sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data object Privacy : Screen()
    data class StopBoard(val code: String) : Screen()
    data class RouteView(val route: String, val direction: Int) : Screen()
    data class TripView(val tripId: String, val fromStopCode: String? = null) : Screen()
    data class Report(
        val routeShortName: String,
        val stopCode: String,
        val stopName: String,
        val tripId: String,
        val scheduledDeparture: String,
        val estimatedDeparture: String?,
    ) : Screen()
}

/**
 * Stable, argument-free name for telemetry (TFI-54). Every case is spelled out rather than derived
 * from the class's simple name so a rename can't silently change a screen's identity — and, more
 * importantly, so it stays obvious at review time that the stop codes, route numbers and trip ids
 * these screens carry are never part of what gets reported.
 */
val Screen.screenName: String
    get() = when (this) {
        is Screen.Home -> "Home"
        is Screen.Settings -> "Settings"
        is Screen.Privacy -> "Privacy"
        is Screen.StopBoard -> "StopBoard"
        is Screen.RouteView -> "RouteView"
        is Screen.TripView -> "TripView"
        is Screen.Report -> "Report"
    }

@Composable
fun App(paletteStore: PaletteStore, widgetStopRequest: State<WidgetStopRequest?>) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val serverSettingsStore = remember { iompar.mpts.ie.server.ServerSettingsStore(context) }
    val backendConfigStore = remember { BackendConfigStore(context) }
    val timeController = remember { TimeController() }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    val stack = remember { mutableStateListOf<Screen>() }

    // Only the top screen is composed (see below), so each one's rememberSaveable state is parked
    // here while it's off screen and restored when it comes back.
    val stateHolder = rememberSaveableStateHolder()

    /** The SaveableStateHolder key for the entry at [index]; null means the home screen. */
    fun slotKey(index: Int): String =
        if (index < 0) "home" else "$index:${stack[index]}"

    val pop = {
        if (stack.isNotEmpty()) {
            // Drop the saved state with the screen: a popped entry is gone for good, and without
            // this the holder would accumulate a bundle per screen ever visited.
            stateHolder.removeState(slotKey(stack.lastIndex))
            stack.removeAt(stack.lastIndex)
            Unit
        } else Unit
    }

    // Set when a stop screen's bell is tapped; consumed by NotificationScreen to open a pre-filled
    // create sheet. The seq makes re-tapping the same stop a distinct request.
    var notifStopSeq by remember { mutableStateOf(0) }
    var notifStopRequest by remember { mutableStateOf<NotifStopRequest?>(null) }
    val openNotificationsForStop: (String, String) -> Unit = { code, name ->
        notifStopRequest = NotifStopRequest(code, name, notifStopSeq++)
        scope.launch { pagerState.animateScrollToPage(1) }
    }

    // A widget tap should always land on that stop's board, replacing whatever was on
    // screen — including re-firing when the same stop is tapped twice in a row, hence
    // the incrementing seq making each request distinct for LaunchedEffect's key.
    LaunchedEffect(widgetStopRequest.value) {
        val req = widgetStopRequest.value ?: return@LaunchedEffect
        stack.clear()
        stack.add(Screen.StopBoard(req.stopCode))
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    // What the user is actually looking at: the notifications page wins because the pager draws
    // over the whole stack, otherwise it's the topmost pushed screen — Home when nothing is
    // pushed, since Home is the base of the pager's first page. Derived from the nav state rather
    // than reported from each onOpenX callback so that back presses, and the widget's
    // stack.clear(), are covered without a call at every site (TFI-54).
    val currentScreen = if (pagerState.currentPage == 1) "Notifications"
        else stack.lastOrNull()?.screenName ?: "Home"
    LaunchedEffect(currentScreen) { Telemetry.trackScreen(currentScreen) }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    BackHandler(enabled = stack.isEmpty() && pagerState.currentPage == 1) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    // Debounce time-travel selection so spinning the drums doesn't refetch the board
    // on every detent — only the settled instant reaches the screens.
    LaunchedEffect(timeController.querySec) {
        val q = timeController.querySec
        if (q == null) timeController.committedSec = null
        else { delay(300); timeController.committedSec = q }
    }

    // Swipe-to-switch is disabled: the pager is driven only by explicit navigation
    // actions, so a horizontal drag on a map (or anything else) on page 0 can never
    // be mistaken for a page transition to the notifications screen.
    HorizontalPager(state = pagerState, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 1) {
            NotificationScreen(
                stopRequest = notifStopRequest,
                onStopRequestHandled = { notifStopRequest = null },
            )
            return@HorizontalPager
        }

    // Only the screen actually in front of the user is composed.
    //
    // Every layer used to stay in the composition, stacked in a Box. That meant each one kept its
    // 30s poll loops running behind whatever you were looking at — opening a favourite fetched
    // that stop's departures twice over, once from the home card still alive underneath and once
    // from the stop board on top — and every layer was still laid out and drawn, so a three-deep
    // stack painted the full screen four times over.
    //
    // SaveableStateHolder keeps each screen's rememberSaveable state (scroll positions, entered
    // text) keyed by its stack slot, so going back still restores the screen rather than
    // rebuilding it blank; DeparturesCache covers the data side.
    val top = stack.lastOrNull()
    Box(Modifier.fillMaxSize()) {
        // Keyed on depth as well as identity: two boards for the same stop at different depths
        // are different screens and must not share saved state.
        stateHolder.SaveableStateProvider(slotKey(stack.lastIndex)) {
            when (top) {
                null, is Screen.Home -> HomeScreen(
                    settingsStore = settingsStore,
                    timeController = timeController,
                    onOpenStop = { stack.add(Screen.StopBoard(it)) },
                    onOpenRoute = { route, dir -> stack.add(Screen.RouteView(route, dir)) },
                    onOpenTrip = { tripId, fromCode -> stack.add(Screen.TripView(tripId, fromCode)) },
                    onReport = { d, stopCode, stopName ->
                        stack.add(
                            Screen.Report(
                                routeShortName = d.routeShortName,
                                stopCode = stopCode,
                                stopName = stopName,
                                tripId = d.tripId,
                                scheduledDeparture = d.scheduledDeparture,
                                estimatedDeparture = d.estimatedDeparture,
                            )
                        )
                    },
                    onOpenSettings = { stack.add(Screen.Settings) },
                    onOpenNotifications = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                is Screen.Settings -> SettingsScreen(
                    paletteStore = paletteStore,
                    settingsStore = settingsStore,
                    serverSettingsStore = serverSettingsStore,
                    backendConfigStore = backendConfigStore,
                    onOpenPrivacy = { stack.add(Screen.Privacy) },
                    onBack = { pop() },
                )
                is Screen.Privacy -> PrivacyPolicyScreen(onBack = { pop() })
                is Screen.StopBoard -> StopScreen(
                    code = top.code,
                    timeController = timeController,
                    settingsStore = settingsStore,
                    onBack = { pop() },
                    onOpenTrip = { tripId, fromCode -> stack.add(Screen.TripView(tripId, fromCode)) },
                    onOpenRoute = { route, dir -> stack.add(Screen.RouteView(route, dir)) },
                    onOpenNotifications = openNotificationsForStop,
                    onReport = { d, stopCode, stopName ->
                        stack.add(
                            Screen.Report(
                                routeShortName = d.routeShortName,
                                stopCode = stopCode,
                                stopName = stopName,
                                tripId = d.tripId,
                                scheduledDeparture = d.scheduledDeparture,
                                estimatedDeparture = d.estimatedDeparture,
                            )
                        )
                    },
                )
                is Screen.RouteView -> RouteScreen(
                    route = top.route,
                    direction = top.direction,
                    onBack = { pop() },
                    onOpenStop = { stack.add(Screen.StopBoard(it)) },
                    onFlip = {
                        stack[stack.lastIndex] =
                            Screen.RouteView(top.route, if (top.direction == 0) 1 else 0)
                    },
                )
                is Screen.TripView -> TripScreen(
                    tripId = top.tripId,
                    fromStopCode = top.fromStopCode,
                    onBack = { pop() },
                    onOpenStop = { stack.add(Screen.StopBoard(it)) },
                )
                is Screen.Report -> ReportScreen(
                    routeShortName = top.routeShortName,
                    stopCode = top.stopCode,
                    stopName = top.stopName,
                    tripId = top.tripId,
                    scheduledDeparture = top.scheduledDeparture,
                    estimatedDeparture = top.estimatedDeparture,
                    onBack = { pop() },
                )
            }
        }
    }
    } // end HorizontalPager
}
