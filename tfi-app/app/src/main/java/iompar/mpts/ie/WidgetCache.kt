package iompar.mpts.ie

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * Last-good departures per stop, so a failed fetch shows stale data instead of blanking a widget.
 *
 * [DeparturesCache] can't serve this: it's a `mutableStateMapOf` in memory, and a widget update
 * runs in a short-lived process, so it is always empty there.
 *
 * Deliberately stored in the app's own DataStore rather than the widget's Glance state. Writing
 * Glance state from inside `provideGlance` triggers a re-render, which re-runs `provideGlance`,
 * which writes again — a feedback loop that also raced with the config activity's write of the
 * stop code and could strand a widget on "Tap to choose a stop". Keying by stop code rather than
 * by widget also means two widgets on the same stop share a fallback.
 */
object WidgetCache {
    private val gson = Gson()

    private fun departuresKey(code: String) = stringPreferencesKey("widgetCache:departures:$code")
    private fun fetchedAtKey(code: String) = longPreferencesKey("widgetCache:at:$code")

    /** Age beyond which the data is worth flagging rather than showing silently. */
    private val STALE_AFTER: Duration = Duration.ofMinutes(2)

    data class Cached(val departures: List<Departure>, val ageMinutes: Long)

    suspend fun save(context: Context, code: String, departures: List<Departure>) {
        context.dataStore.edit { prefs ->
            prefs[departuresKey(code)] = gson.toJson(departures)
            prefs[fetchedAtKey(code)] = Instant.now().epochSecond
        }
    }

    /** The last good result for [code], or null if there has never been one. */
    suspend fun load(context: Context, code: String): Cached? {
        val prefs = context.dataStore.data.first()
        val json = prefs[departuresKey(code)] ?: return null
        val at = prefs[fetchedAtKey(code)] ?: return null
        val type = object : TypeToken<List<Departure>>() {}.type
        val deps: List<Departure> = runCatching { gson.fromJson<List<Departure>>(json, type) }
            .getOrNull() ?: return null
        return Cached(deps, Duration.ofSeconds(Instant.now().epochSecond - at).toMinutes())
    }

    fun isStale(ageMinutes: Long): Boolean = ageMinutes >= STALE_AFTER.toMinutes()

    fun ageLabel(ageMinutes: Long): String = when {
        ageMinutes < 1 -> "just now"
        ageMinutes == 1L -> "1 min ago"
        ageMinutes < 60 -> "$ageMinutes mins ago"
        ageMinutes < 120 -> "1 hr ago"
        else -> "${ageMinutes / 60} hrs ago"
    }
}
