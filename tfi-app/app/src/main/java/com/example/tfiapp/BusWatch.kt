package com.example.tfiapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A one-shot request to be notified once when this specific bus is due, then forgotten. */
data class BusWatch(
    val tripId: String,
    val stopCode: String,
    val stopName: String,
    val routeShortName: String,
    val triggerAtMillis: Long,
)

/** Stable id for this watch's alarm/notification, shared between the scheduler and the receiver. */
fun BusWatch.requestCode(): Int = tripId.hashCode() xor stopCode.hashCode()

private val BW_KEY  = stringPreferencesKey("bus_watches")
private val bwGson  = Gson()
private val bwType  = object : TypeToken<List<BusWatch>>() {}.type

class BusWatchStore(private val context: Context) {
    val flow: Flow<List<BusWatch>> = context.dataStore.data.map { prefs ->
        prefs[BW_KEY]?.let { bwGson.fromJson<List<BusWatch>>(it, bwType) } ?: emptyList()
    }

    suspend fun all(): List<BusWatch> = flow.first()

    suspend fun has(tripId: String, stopCode: String): Boolean =
        all().any { it.tripId == tripId && it.stopCode == stopCode }

    suspend fun save(watch: BusWatch) = update { cur ->
        cur.filterNot { it.tripId == watch.tripId && it.stopCode == watch.stopCode } + watch
    }

    suspend fun remove(tripId: String, stopCode: String) = update {
        it.filterNot { w -> w.tripId == tripId && w.stopCode == stopCode }
    }

    private suspend fun update(fn: (List<BusWatch>) -> List<BusWatch>) {
        context.dataStore.edit { prefs ->
            val cur = prefs[BW_KEY]?.let { bwGson.fromJson<List<BusWatch>>(it, bwType) } ?: emptyList()
            prefs[BW_KEY] = bwGson.toJson(fn(cur))
        }
    }
}
