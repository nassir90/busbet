package com.example.tfiapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Favourite(val code: String, val name: String)

val Context.dataStore by preferencesDataStore(name = "tfi")
private val KEY = stringPreferencesKey("favourites")
private val gson = Gson()
private val type = object : TypeToken<List<Favourite>>() {}.type

class FavouritesStore(private val context: Context) {
    val flow: Flow<List<Favourite>> = context.dataStore.data.map { prefs ->
        prefs[KEY]?.let { gson.fromJson<List<Favourite>>(it, type) } ?: emptyList()
    }

    suspend fun add(code: String, name: String) = update { current ->
        current.filterNot { it.code == code } + Favourite(code, name)
    }

    suspend fun remove(code: String) = update { it.filterNot { f -> f.code == code } }

    suspend fun rename(code: String, name: String) = update { current ->
        current.map { if (it.code == code) it.copy(name = name) else it }
    }

    suspend fun reorder(codes: List<String>) = update { current ->
        val byCode = current.associateBy { it.code }
        codes.mapNotNull { byCode[it] }
    }

    private suspend fun update(transform: (List<Favourite>) -> List<Favourite>) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { gson.fromJson<List<Favourite>>(it, type) } ?: emptyList()
            prefs[KEY] = gson.toJson(transform(current))
        }
    }
}
