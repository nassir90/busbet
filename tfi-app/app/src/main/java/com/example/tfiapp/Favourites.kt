package com.example.tfiapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Favourite(
    val code: String,
    val name: String,
    val customName: String? = null,
    val collapsed: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    /** Custom name if the user set one, otherwise the real stop name. */
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: name
}

val Context.dataStore by preferencesDataStore(name = "tfi")
private val KEY = stringPreferencesKey("favourites")
private val gson = Gson()
private val type = object : TypeToken<List<Favourite>>() {}.type

class FavouritesStore(private val context: Context) {
    val flow: Flow<List<Favourite>> = context.dataStore.data.map { prefs ->
        prefs[KEY]?.let { gson.fromJson<List<Favourite>>(it, type) } ?: emptyList()
    }

    suspend fun add(code: String, name: String, lat: Double? = null, lon: Double? = null) = update { current ->
        current.filterNot { it.code == code } + Favourite(code, name, lat = lat, lon = lon)
    }

    /** Backfills coordinates for distance sorting once we learn them. */
    suspend fun setCoords(code: String, lat: Double, lon: Double) = update { current ->
        current.map { if (it.code == code) it.copy(lat = lat, lon = lon) else it }
    }

    suspend fun remove(code: String) = update { it.filterNot { f -> f.code == code } }

    /** Sets a custom display name; pass null/blank to clear and fall back to the real stop name. */
    suspend fun setCustomName(code: String, customName: String?) = update { current ->
        current.map { if (it.code == code) it.copy(customName = customName?.takeIf { n -> n.isNotBlank() }) else it }
    }

    suspend fun setCollapsed(code: String, collapsed: Boolean) = update { current ->
        current.map { if (it.code == code) it.copy(collapsed = collapsed) else it }
    }

    suspend fun reorder(codes: List<String>) = update { current ->
        val byCode = current.associateBy { it.code }
        codes.mapNotNull { byCode[it] }
    }

    /** Overwrites the whole list, e.g. when restoring from a config backup. */
    suspend fun replaceAll(favourites: List<Favourite>) = update { favourites }

    private suspend fun update(transform: (List<Favourite>) -> List<Favourite>) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { gson.fromJson<List<Favourite>>(it, type) } ?: emptyList()
            prefs[KEY] = gson.toJson(transform(current))
        }
    }
}
