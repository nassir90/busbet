package iompar.mpts.ie

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

data class NotificationWindow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val days: Set<Int>,        // 1=Mon … 7=Sun (ISO DayOfWeek values)
    val startMinute: Int,      // minutes since midnight, e.g. 480 = 08:00
    val endMinute: Int,
    val stopCode: String,
    val stopName: String,
    val routes: List<String>,  // empty = all routes
    val enabled: Boolean = true,
)

private val NW_KEY  = stringPreferencesKey("notification_windows")
private val nwGson  = Gson()
private val nwType  = object : TypeToken<List<NotificationWindow>>() {}.type

class NotificationWindowStore(private val context: Context) {
    // See FavouritesStore: narrowed to this key and deduped before parsing, so a write anywhere
    // else in the shared DataStore doesn't re-run Gson here.
    val flow: Flow<List<NotificationWindow>> = context.dataStore.data
        .map { it[NW_KEY] }
        .distinctUntilChanged()
        .map { json -> json?.let { nwGson.fromJson<List<NotificationWindow>>(it, nwType) } ?: emptyList() }

    suspend fun save(window: NotificationWindow) = update { cur ->
        cur.filterNot { it.id == window.id } + window
    }

    suspend fun delete(id: String) = update { it.filterNot { w -> w.id == id } }

    /** Overwrites the whole list, e.g. when restoring from a config backup. */
    suspend fun replaceAll(windows: List<NotificationWindow>) = update { windows }

    private suspend fun update(fn: (List<NotificationWindow>) -> List<NotificationWindow>) {
        context.dataStore.edit { prefs ->
            val cur = prefs[NW_KEY]?.let { nwGson.fromJson<List<NotificationWindow>>(it, nwType) } ?: emptyList()
            prefs[NW_KEY] = nwGson.toJson(fn(cur))
        }
    }
}
