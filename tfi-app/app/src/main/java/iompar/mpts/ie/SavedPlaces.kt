package iompar.mpts.ie

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class PlaceKind(val label: String) {
    HOME("Home"),
    WORK("Work"),
    OTHER("Other");

    val icon: ImageVector
        get() = when (this) {
            HOME -> Icons.Filled.Home
            WORK -> Icons.Filled.Work
            OTHER -> Icons.Filled.Place
        }
}

/**
 * A destination the user goes to often: home, work, a friend's house. Kept on the device only,
 * like favourites; a point leaves the phone only as the start or end of a journey being planned.
 */
data class SavedPlace(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: PlaceKind = PlaceKind.OTHER,
    val lat: Double,
    val lon: Double,
    /** Set when the place was chosen as a stop, so it can open that stop's board. */
    val stopCode: String? = null,
)

private val KEY = stringPreferencesKey("saved_places")
private val gson = Gson()
private val type = object : TypeToken<List<SavedPlace>>() {}.type

class SavedPlacesStore(private val context: Context) {
    val flow: Flow<List<SavedPlace>> = context.dataStore.data
        .map { it[KEY] }
        .distinctUntilChanged()
        .map { json -> json?.let { gson.fromJson<List<SavedPlace>>(it, type) } ?: emptyList() }
        .map { ordered(it) }

    /** Adds or replaces by id. There is one Home and one Work: saving a new one replaces the old. */
    suspend fun save(place: SavedPlace) = update { current ->
        val others = current.filterNot {
            it.id == place.id || (place.kind != PlaceKind.OTHER && it.kind == place.kind)
        }
        others + place
    }

    suspend fun remove(id: String) = update { it.filterNot { p -> p.id == id } }

    /** Overwrites the whole list, e.g. when restoring from a config backup. */
    suspend fun replaceAll(places: List<SavedPlace>) = update { places }

    private suspend fun update(transform: (List<SavedPlace>) -> List<SavedPlace>) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { gson.fromJson<List<SavedPlace>>(it, type) } ?: emptyList()
            prefs[KEY] = gson.toJson(transform(current))
        }
    }

    private fun ordered(list: List<SavedPlace>): List<SavedPlace> =
        list.sortedWith(compareBy<SavedPlace> { it.kind.ordinal }.thenBy { it.label.lowercase() })
}
