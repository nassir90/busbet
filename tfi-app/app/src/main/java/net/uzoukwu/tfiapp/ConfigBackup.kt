package net.uzoukwu.tfiapp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.flow.first

/**
 * A full snapshot of the user-configurable state: favourite stops, theme, settings, search
 * history, and notification windows. Exported/imported as JSON so an upgrade or reinstall can't
 * lose it.
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

private val configGson = Gson()

class ConfigBackupStore(
    private val context: Context,
    private val favouritesStore: FavouritesStore = FavouritesStore(context),
    private val paletteStore: PaletteStore = PaletteStore(context),
    private val settingsStore: SettingsStore = SettingsStore(context),
    private val searchHistoryStore: SearchHistoryStore = SearchHistoryStore(context),
    private val notificationWindowStore: NotificationWindowStore = NotificationWindowStore(context),
) {
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
}
