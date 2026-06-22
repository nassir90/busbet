package com.example.tfiapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val MAX_ENTRIES = 10

data class SearchHistoryEntry(
    val isRoute: Boolean,
    /** Stop code for a stop entry, or "routeShortName|directionId" for a route entry. */
    val id: String,
    val label: String,
    val routeShortName: String? = null,
    val directionId: Int? = null,
)

private val HISTORY_KEY = stringPreferencesKey("search_history")
private val gson = Gson()
private val type = object : TypeToken<List<SearchHistoryEntry>>() {}.type

class SearchHistoryStore(private val context: Context) {
    val flow: Flow<List<SearchHistoryEntry>> = context.dataStore.data.map { prefs ->
        prefs[HISTORY_KEY]?.let { gson.fromJson<List<SearchHistoryEntry>>(it, type) } ?: emptyList()
    }

    /** Records a visit, moving it to the front if already present. */
    suspend fun record(entry: SearchHistoryEntry) = update { current ->
        (listOf(entry) + current.filterNot { it.id == entry.id && it.isRoute == entry.isRoute }).take(MAX_ENTRIES)
    }

    suspend fun clear() = update { emptyList() }

    /** Overwrites the whole list, e.g. when restoring from a config backup. */
    suspend fun replaceAll(entries: List<SearchHistoryEntry>) = update { entries }

    private suspend fun update(transform: (List<SearchHistoryEntry>) -> List<SearchHistoryEntry>) {
        context.dataStore.edit { prefs ->
            val current = prefs[HISTORY_KEY]?.let { gson.fromJson<List<SearchHistoryEntry>>(it, type) } ?: emptyList()
            prefs[HISTORY_KEY] = gson.toJson(transform(current))
        }
    }
}
