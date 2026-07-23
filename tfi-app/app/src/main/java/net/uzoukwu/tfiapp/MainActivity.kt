package com.example.tfiapp

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** A request (from a home-screen widget tap) to jump straight to a stop's board. */
data class WidgetStopRequest(val stopCode: String, val seq: Int)

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
        setContent {
            val context = LocalContext.current
            val paletteStore = remember { PaletteStore(context) }
            val selectedId by paletteStore.selectedId.collectAsState(initial = DEFAULT_PALETTE.id)
            val customPalettes by paletteStore.customPalettes.collectAsState(initial = emptyList())
            val colors = buildColorScheme(resolvePalette(selectedId, customPalettes), isSystemInDarkTheme())

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

@Composable
fun App(paletteStore: PaletteStore, widgetStopRequest: State<WidgetStopRequest?>) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val serverSettingsStore = remember { com.example.tfiapp.server.ServerSettingsStore(context) }
    val backendConfigStore = remember { BackendConfigStore(context) }
    val timeController = remember { TimeController() }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    val stack = remember { mutableStateListOf<Screen>() }
    val pop = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else Unit }

    // A widget tap should always land on that stop's board, replacing whatever was on
    // screen — including re-firing when the same stop is tapped twice in a row, hence
    // the incrementing seq making each request distinct for LaunchedEffect's key.
    LaunchedEffect(widgetStopRequest.value) {
        val req = widgetStopRequest.value ?: return@LaunchedEffect
        stack.clear()
        stack.add(Screen.StopBoard(req.stopCode))
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

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
            NotificationScreen()
            return@HorizontalPager
        }

    // Home always composed underneath; every stack entry rendered above the previous one,
    // so back navigation reveals the still-alive parent screen.
    Box(Modifier.fillMaxSize()) {
        HomeScreen(
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
        stack.forEachIndexed { i, layer ->
            when (layer) {
                is Screen.Home -> Unit
                is Screen.Settings -> SettingsScreen(
                    paletteStore = paletteStore,
                    settingsStore = settingsStore,
                    serverSettingsStore = serverSettingsStore,
                    backendConfigStore = backendConfigStore,
                    onBack = { pop() },
                )
                is Screen.StopBoard -> StopScreen(
                    code = layer.code,
                    timeController = timeController,
                    settingsStore = settingsStore,
                    onBack = { pop() },
                    onOpenTrip = { tripId, fromCode -> stack.add(Screen.TripView(tripId, fromCode)) },
                    onOpenRoute = { route, dir -> stack.add(Screen.RouteView(route, dir)) },
                    onOpenNotifications = { scope.launch { pagerState.animateScrollToPage(1) } },
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
                    route = layer.route,
                    direction = layer.direction,
                    onBack = { pop() },
                    onOpenStop = { stack.add(Screen.StopBoard(it)) },
                    onFlip = {
                        stack[i] = Screen.RouteView(layer.route, if (layer.direction == 0) 1 else 0)
                    },
                )
                is Screen.TripView -> TripScreen(
                    tripId = layer.tripId,
                    fromStopCode = layer.fromStopCode,
                    onBack = { pop() },
                    onOpenStop = { stack.add(Screen.StopBoard(it)) },
                )
                is Screen.Report -> ReportScreen(
                    routeShortName = layer.routeShortName,
                    stopCode = layer.stopCode,
                    stopName = layer.stopName,
                    tripId = layer.tripId,
                    scheduledDeparture = layer.scheduledDeparture,
                    estimatedDeparture = layer.estimatedDeparture,
                    onBack = { pop() },
                )
            }
        }
    }
    } // end HorizontalPager
}
