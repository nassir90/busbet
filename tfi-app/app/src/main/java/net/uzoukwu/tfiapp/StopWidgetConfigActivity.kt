package net.uzoukwu.tfiapp

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class StopWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }

        setContent {
            val paletteStore = remember { PaletteStore(this) }
            val selectedId by paletteStore.selectedId.collectAsState(initial = DEFAULT_PALETTE.id)
            val customPalettes by paletteStore.customPalettes.collectAsState(initial = emptyList())
            val colors = buildColorScheme(resolvePalette(selectedId, customPalettes), isSystemInDarkTheme())
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PickerScreen(
                        onPicked = { fav -> savePick(appWidgetId, fav) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun savePick(appWidgetId: Int, fav: Favourite) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                val updated: MutablePreferences = prefs.toMutablePreferences()
                updated[STOP_CODE_KEY] = fav.code
                updated[STOP_NAME_KEY] = fav.displayName
                updated
            }
            StopWidget().update(applicationContext, glanceId)

            val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }
}

@androidx.compose.runtime.Composable
private fun PickerScreen(onPicked: (Favourite) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val favStore = remember { FavouritesStore(context) }
    val favourites by favStore.flow.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pick a stop for this widget", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (favourites.isEmpty()) {
            Text(
                "You don't have any favourites yet. Open the app, add a stop as a favourite, then add the widget again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favourites, key = { it.code }) { fav ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(fav) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(fav.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                fav.code,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
