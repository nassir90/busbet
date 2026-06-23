package com.example.tfiapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * A full snapshot of the user-configurable state: favourite stops, theme, settings, search
 * history, and notification windows. Exported/imported as JSON and also used for in-app named
 * save/reload, e.g. switching a kiosk between stop setups without re-entering everything.
 */
data class AppConfig(
    val favourites: List<Favourite> = emptyList(),
    val paletteSelectedId: String = DEFAULT_PALETTE.id,
    val customPalettes: List<AppPalette> = emptyList(),
    val locationAware: Boolean = false,
    val hideFarStops: Boolean = false,
    val busDisplayThresholdMin: Int = DEFAULT_BUS_DISPLAY_THRESHOLD_MIN,
    val farStopThresholdM: Int = DEFAULT_FAR_STOP_THRESHOLD_M,
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
    val notificationWindows: List<NotificationWindow> = emptyList(),
)

/** An [AppConfig] saved in-app under a name, for quick reload without going through a file. */
data class NamedConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val savedAt: Long,
    val config: AppConfig,
)

private val NAMED_CONFIGS_KEY = stringPreferencesKey("named_configs")
private val configGson = Gson()
private val namedConfigListType = object : TypeToken<List<NamedConfig>>() {}.type

class ConfigBackupStore(
    private val context: Context,
    private val favouritesStore: FavouritesStore = FavouritesStore(context),
    private val paletteStore: PaletteStore = PaletteStore(context),
    private val settingsStore: SettingsStore = SettingsStore(context),
    private val searchHistoryStore: SearchHistoryStore = SearchHistoryStore(context),
    private val notificationWindowStore: NotificationWindowStore = NotificationWindowStore(context),
) {
    val namedConfigs: Flow<List<NamedConfig>> = context.dataStore.data.map { prefs ->
        prefs[NAMED_CONFIGS_KEY]?.let { configGson.fromJson<List<NamedConfig>>(it, namedConfigListType) } ?: emptyList()
    }

    /** Reads every store's current value into one [AppConfig]. */
    suspend fun snapshot(): AppConfig = AppConfig(
        favourites = favouritesStore.flow.first(),
        paletteSelectedId = paletteStore.selectedId.first(),
        customPalettes = paletteStore.customPalettes.first(),
        locationAware = settingsStore.locationAware.first(),
        hideFarStops = settingsStore.hideFarStops.first(),
        busDisplayThresholdMin = settingsStore.busDisplayThresholdMin.first(),
        farStopThresholdM = settingsStore.farStopThresholdM.first(),
        searchHistory = searchHistoryStore.flow.first(),
        notificationWindows = notificationWindowStore.flow.first(),
    )

    fun toJson(config: AppConfig): String = configGson.toJson(config)

    /** @throws JsonSyntaxException if [json] isn't a valid config. */
    fun fromJson(json: String): AppConfig =
        configGson.fromJson(json, AppConfig::class.java) ?: throw JsonSyntaxException("Empty config")

    /** Overwrites every store's contents with [config]. */
    suspend fun restore(config: AppConfig) {
        favouritesStore.replaceAll(config.favourites)
        paletteStore.restore(config.paletteSelectedId, config.customPalettes)
        settingsStore.restore(config.locationAware, config.hideFarStops, config.busDisplayThresholdMin, config.farStopThresholdM)
        searchHistoryStore.replaceAll(config.searchHistory)
        notificationWindowStore.replaceAll(config.notificationWindows)
    }

    /** Snapshots the current state and saves it under [name], replacing any existing save with that name. */
    suspend fun saveNamed(name: String) {
        val saved = NamedConfig(name = name, savedAt = System.currentTimeMillis(), config = snapshot())
        update { current -> current.filterNot { it.name == name } + saved }
    }

    /** Restores the named save with the given [id], if it still exists. */
    suspend fun reload(id: String) {
        namedConfigs.first().firstOrNull { it.id == id }?.let { restore(it.config) }
    }

    suspend fun deleteNamed(id: String) = update { it.filterNot { c -> c.id == id } }

    private suspend fun update(transform: (List<NamedConfig>) -> List<NamedConfig>) {
        context.dataStore.edit { prefs ->
            val current = prefs[NAMED_CONFIGS_KEY]?.let { configGson.fromJson<List<NamedConfig>>(it, namedConfigListType) } ?: emptyList()
            prefs[NAMED_CONFIGS_KEY] = configGson.toJson(transform(current))
        }
    }
}
