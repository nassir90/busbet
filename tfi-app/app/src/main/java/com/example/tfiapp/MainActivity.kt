package com.example.tfiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val themeStore = remember { ThemeStore(context) }
            val theme by themeStore.flow.collectAsState(initial = AppTheme.DEFAULT)
            val colors = if (theme == AppTheme.TFI) TfiColorScheme else DefaultColorScheme
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(themeStore = themeStore, currentTheme = theme)
                }
            }
        }
    }
}

sealed class Screen {
    data object Home : Screen()
    data class StopBoard(val code: String) : Screen()
    data class RouteView(val route: String, val direction: Int) : Screen()
}

@Composable
fun App(themeStore: ThemeStore, currentTheme: AppTheme) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen !is Screen.Home) {
        screen = Screen.Home
    }

    when (val s = screen) {
        is Screen.Home -> HomeScreen(
            themeStore = themeStore,
            currentTheme = currentTheme,
            onOpenStop = { screen = Screen.StopBoard(it) },
            onOpenRoute = { route, dir -> screen = Screen.RouteView(route, dir) },
        )
        is Screen.StopBoard -> StopScreen(
            code = s.code,
            onBack = { screen = Screen.Home },
            onOpenRoute = { route, dir -> screen = Screen.RouteView(route, dir) },
        )
        is Screen.RouteView -> RouteScreen(
            route = s.route,
            direction = s.direction,
            onBack = { screen = Screen.Home },
            onOpenStop = { screen = Screen.StopBoard(it) },
            onFlip = { screen = Screen.RouteView(s.route, if (s.direction == 0) 1 else 0) },
        )
    }
}
