package com.example.tfiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialStop = intent.getStringExtra("stopCode")
        setContent {
            val context = LocalContext.current
            val themeStore = remember { ThemeStore(context) }
            val theme by themeStore.flow.collectAsState(initial = AppTheme.DEFAULT)
            val colors = colorsFor(theme, isSystemInDarkTheme())
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
    val stack = remember {
        mutableStateListOf<Screen>().apply {
            if (initialStop != null) add(Screen.StopBoard(initialStop))
        }
    }
    val pop = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else Unit }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

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
}
