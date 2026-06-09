package com.example.tfiapp

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialStop = intent.getStringExtra("stopCode")
        setContent {
            val context = LocalContext.current
            val themeStore = remember { ThemeStore(context) }
            val theme by themeStore.flow.collectAsState(initial = AppTheme.DEFAULT)
            val colors = colorsFor(theme, isSystemInDarkTheme())

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colors.primary.toArgb()
                    WindowCompat.getInsetsController(window, view)
                        .isAppearanceLightStatusBars = false
                }
            }

            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(themeStore = themeStore, currentTheme = theme, initialStop = initialStop)
                }
            }
        }
    }
}

sealed class Screen {
    data object Home : Screen()
    data class StopBoard(val code: String) : Screen()
    data class RouteView(val route: String, val direction: Int) : Screen()
    data class TripView(val tripId: String, val fromStopCode: String? = null) : Screen()
}

@Composable
fun App(themeStore: ThemeStore, currentTheme: AppTheme, initialStop: String? = null) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    val stack = remember {
        mutableStateListOf<Screen>().apply {
            if (initialStop != null) add(Screen.StopBoard(initialStop))
        }
    }
    val pop = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else Unit }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    BackHandler(enabled = stack.isEmpty() && pagerState.currentPage == 1) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    var notifPrefill by remember { mutableStateOf<Pair<String, String>?>(null) }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 1) {
            NotificationScreen(prefill = notifPrefill, onPrefillConsumed = { notifPrefill = null })
            return@HorizontalPager
        }

    // Home always composed underneath; every stack entry rendered above the previous one,
    // so back navigation reveals the still-alive parent screen.
    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            themeStore = themeStore,
            currentTheme = currentTheme,
            onOpenStop = { stack.add(Screen.StopBoard(it)) },
            onOpenRoute = { route, dir -> stack.add(Screen.RouteView(route, dir)) },
            onOpenTrip = { tripId, fromCode -> stack.add(Screen.TripView(tripId, fromCode)) },
        )
        stack.forEachIndexed { i, layer ->
            when (layer) {
                is Screen.Home -> Unit
                is Screen.StopBoard -> StopScreen(
                    code = layer.code,
                    onBack = { pop() },
                    onOpenTrip = { tripId, fromCode -> stack.add(Screen.TripView(tripId, fromCode)) },
                    onAddNotification = { stopCode, stopName ->
                        notifPrefill = Pair(stopCode, stopName)
                        scope.launch { pagerState.animateScrollToPage(1) }
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
            }
        }
    }
    } // end HorizontalPager
}
