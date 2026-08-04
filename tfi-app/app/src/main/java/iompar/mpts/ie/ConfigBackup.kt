package iompar.mpts.ie

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

/**
 * A restored setting that only works once the user grants something.
 *
 * A backup carries the *preference*, never the grant — permissions are per-install, so a reinstall
 * or a move to another device restores "location sorting: on" with no location permission behind
 * it. The setting then reads as enabled and silently does nothing, which is worse than it having
 * been off, because nothing on screen suggests anything is wrong.
 */
data class RestoredGrant(
    /** The setting as it is named in Settings, so the prompt matches what the user turned on. */
    val setting: String,
    /** What stops working without it. */
    val reason: String,
    val permission: String,
    /** Turns the setting back off when the grant is refused, so state matches reality. */
    val disable: suspend (SettingsStore) -> Unit,
)

/**
 * The grants [config] implies, whether or not they are already held.
 *
 * Deliberately not filtered here: what is already granted depends on the live install, which is a
 * caller's concern, and keeping this pure makes it testable.
 */
fun AppConfig.requiredGrants(): List<RestoredGrant> = buildList {
    if (locationAware || hideFarStops) {
        add(
            RestoredGrant(
                setting = "Location-aware mode",
                reason = "Sorts your favourite stops by distance, and lets the widget show the nearest one.",
                permission = android.Manifest.permission.ACCESS_COARSE_LOCATION,
                disable = { it.setLocationAware(false) },
            ),
        )
    }
    // Only from API 33 — before that, posting a notification needs no runtime grant, so asking
    // would be a dialog the system has no way to answer.
    if (notificationWindows.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 33) {
        add(
            RestoredGrant(
                setting = "Bus notifications",
                reason = "You have ${notificationWindows.size} saved notification window(s); without this they never fire.",
                permission = android.Manifest.permission.POST_NOTIFICATIONS,
                // The windows themselves are still worth keeping — they are user data, and the
                // grant can be given later from the notifications screen.
                disable = { },
            ),
        )
    }
}

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
