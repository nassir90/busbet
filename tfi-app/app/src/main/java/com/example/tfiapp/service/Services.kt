package com.example.tfiapp.service

import android.content.Context
import com.example.tfiapp.Api
import com.example.tfiapp.DeparturesResponse
import com.example.tfiapp.Favourite
import com.example.tfiapp.FavouritesStore
import com.example.tfiapp.NotificationWindow
import com.example.tfiapp.NotificationWindowStore
import com.example.tfiapp.SettingsStore
import com.example.tfiapp.Stop
import kotlinx.coroutines.flow.first

/**
 * The services layer: the single source of truth for everything both transports (the MCP server
 * and the HTTP API) can do. Each service exposes plain Kotlin functions over domain types and
 * knows nothing about MCP or HTTP — the adapters are thin faces over these interfaces.
 *
 * Implementations wrap the existing DataStore-backed stores and [Api], so the on-device server and
 * the Compose UI share exactly the same state. Services are injected into the transports (rather
 * than constructed inside them) so the adapters can be exercised with fakes in tests.
 */

/** Plain snapshot of the user-tweakable settings (mirrors the fields on [SettingsStore]). */
data class SettingsSnapshot(
    val locationAware: Boolean,
    val hideFarStops: Boolean,
    val busDisplayThresholdMin: Int,
    val farStopThresholdM: Int,
)

interface SettingsService {
    suspend fun get(): SettingsSnapshot
    suspend fun setLocationAware(enabled: Boolean)
    suspend fun setHideFarStops(enabled: Boolean)
    suspend fun setBusDisplayThresholdMin(minutes: Int)
    suspend fun setFarStopThresholdM(metres: Int)
}

interface FavouritesService {
    suspend fun list(): List<Favourite>
    suspend fun add(code: String, name: String, lat: Double? = null, lon: Double? = null)
    suspend fun remove(code: String)
}

interface NotificationWindowService {
    suspend fun list(): List<NotificationWindow>
    suspend fun save(window: NotificationWindow)
    suspend fun delete(id: String)
}

/** Read-only window onto the GTFS proxy. Must stay read-only — it talks to the shared feed. */
interface TransitQueryService {
    suspend fun searchStops(query: String): List<Stop>
    suspend fun departures(stopCode: String, time: Long? = null): DeparturesResponse
}

/** Everything the transports are allowed to reach. Inject this; don't reach past it. */
interface AppServices {
    val settings: SettingsService
    val favourites: FavouritesService
    val notificationWindows: NotificationWindowService
    val transit: TransitQueryService
}

// --- Default implementations: thin wrappers over the existing stores / Api ------------------

private class DefaultSettingsService(private val store: SettingsStore) : SettingsService {
    override suspend fun get() = SettingsSnapshot(
        locationAware = store.locationAware.first(),
        hideFarStops = store.hideFarStops.first(),
        busDisplayThresholdMin = store.busDisplayThresholdMin.first(),
        farStopThresholdM = store.farStopThresholdM.first(),
    )
    override suspend fun setLocationAware(enabled: Boolean) = store.setLocationAware(enabled)
    override suspend fun setHideFarStops(enabled: Boolean) = store.setHideFarStops(enabled)
    override suspend fun setBusDisplayThresholdMin(minutes: Int) = store.setBusDisplayThresholdMin(minutes)
    override suspend fun setFarStopThresholdM(metres: Int) = store.setFarStopThresholdM(metres)
}

private class DefaultFavouritesService(private val store: FavouritesStore) : FavouritesService {
    override suspend fun list() = store.flow.first()
    override suspend fun add(code: String, name: String, lat: Double?, lon: Double?) =
        store.add(code, name, lat, lon)
    override suspend fun remove(code: String) = store.remove(code)
}

private class DefaultNotificationWindowService(
    private val store: NotificationWindowStore,
) : NotificationWindowService {
    override suspend fun list() = store.flow.first()
    override suspend fun save(window: NotificationWindow) = store.save(window)
    override suspend fun delete(id: String) = store.delete(id)
}

private class DefaultTransitQueryService : TransitQueryService {
    override suspend fun searchStops(query: String) = Api.service.searchStops(query)
    override suspend fun departures(stopCode: String, time: Long?) =
        Api.service.departures(stopCode, time)
}

/** Production wiring: builds every service over the real stores for the given [context]. */
class DefaultAppServices(context: Context) : AppServices {
    private val appContext = context.applicationContext
    override val settings: SettingsService = DefaultSettingsService(SettingsStore(appContext))
    override val favourites: FavouritesService = DefaultFavouritesService(FavouritesStore(appContext))
    override val notificationWindows: NotificationWindowService =
        DefaultNotificationWindowService(NotificationWindowStore(appContext))
    override val transit: TransitQueryService = DefaultTransitQueryService()
}
