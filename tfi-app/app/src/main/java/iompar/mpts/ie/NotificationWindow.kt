package iompar.mpts.ie

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDateTime
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

/**
 * Whether the window covers [dt]. Half-open: the start minute counts, the end minute does not, so
 * an 08:00-09:00 window is over at 09:00 rather than running one minute past it.
 *
 * This is the same rule [NotificationScheduler] arms its alarm against, kept here so the editor's
 * status line and the thing that actually fires cannot disagree about what "active" means.
 */
fun NotificationWindow.isActiveAt(dt: LocalDateTime): Boolean {
    val mins = dt.hour * 60 + dt.minute
    return dt.dayOfWeek.value in days && mins in startMinute until endMinute
}

/**
 * Earliest start strictly after [from], scanning the next 8 days, or null if the window has no days
 * and so never starts. Eight days rather than seven because a window earlier today has to roll to
 * the same weekday next week.
 */
fun NotificationWindow.nextStartAfter(from: LocalDateTime): LocalDateTime? {
    if (days.isEmpty()) return null
    var best: LocalDateTime? = null
    for (offset in 0..7) {
        val day = from.toLocalDate().plusDays(offset.toLong())
        if (day.dayOfWeek.value !in days) continue
        val candidate = day.atStartOfDay().plusMinutes(startMinute.toLong())
        if (candidate.isAfter(from) && (best == null || candidate.isBefore(best))) best = candidate
    }
    return best
}

/**
 * One-line answer to "is this thing on?", for the window editor. Written for a right-aligned label
 * beside the Time heading, so it stays short.
 */
fun NotificationWindow.statusLabel(now: LocalDateTime = LocalDateTime.now(AppClock.clock)): String {
    if (!enabled) return "Disabled"
    if (days.isEmpty()) return "No days selected"
    if (endMinute <= startMinute) return "End is not after start"
    if (isActiveAt(now)) return "Currently active"

    val next = nextStartAfter(now) ?: return "Never active"
    val total = Duration.between(now, next).toMinutes()
    val d = total / (24 * 60)
    val h = (total % (24 * 60)) / 60
    val m = total % 60
    return when {
        d > 0L -> "Active in ${d}d ${h}h"
        h > 0L -> "Active in ${h}h ${m}m"
        else   -> "Active in ${m}m"
    }
}

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
