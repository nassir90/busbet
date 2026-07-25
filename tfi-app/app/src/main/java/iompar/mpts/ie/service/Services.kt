package iompar.mpts.ie.service

import android.content.Context
import iompar.mpts.ie.Api
import iompar.mpts.ie.BackendConfigStore
import iompar.mpts.ie.BackendService
import iompar.mpts.ie.isValidBaseUrl
import iompar.mpts.ie.DeparturesResponse
import iompar.mpts.ie.Favourite
import iompar.mpts.ie.FavouritesStore
import iompar.mpts.ie.NotificationWindow
import iompar.mpts.ie.NotificationWindowStore
import iompar.mpts.ie.SettingsStore
import iompar.mpts.ie.Stop
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
    val routeLines: Boolean,
    val routeLineDistanceM: Int,
)

interface SettingsService {
    suspend fun get(): SettingsSnapshot
    suspend fun setLocationAware(enabled: Boolean)
    suspend fun setHideFarStops(enabled: Boolean)
    suspend fun setBusDisplayThresholdMin(minutes: Int)
    suspend fun setFarStopThresholdM(metres: Int)
    suspend fun setRouteLines(enabled: Boolean)
    suspend fun setRouteLineDistanceM(metres: Int)
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

/** Where the app points its backends, and what each service currently resolves to. */
data class BackendConfigSnapshot(
    val root: String,
    val deriveFromRoot: Boolean,
    /** Service name (`GTFS`, `TENANT`) to the base URL actually in use right now. */
    val resolved: Map<String, String>,
    /** Names of services currently reached over plain http. */
    val cleartext: List<String>,
)

/**
 * Backend endpoint configuration. Lives behind the services layer on purpose: [TransitQueryService]
 * and everything else reach the backends through [Api], so resolving URLs here means the MCP tools
 * and the REST routes inherit one implementation instead of growing two that drift.
 */
interface BackendConfigService {
    suspend fun get(): BackendConfigSnapshot
    suspend fun setRoot(root: String)
    suspend fun setDeriveFromRoot(derive: Boolean)
    suspend fun setOverride(service: String, url: String)
}

/** Everything the transports are allowed to reach. Inject this; don't reach past it. */
interface AppServices {
    val settings: SettingsService
    val favourites: FavouritesService
    val notificationWindows: NotificationWindowService
    val transit: TransitQueryService
    val backend: BackendConfigService
}

// --- Default implementations: thin wrappers over the existing stores / Api ------------------

private class DefaultSettingsService(private val store: SettingsStore) : SettingsService {
    override suspend fun get() = SettingsSnapshot(
        locationAware = store.locationAware.first(),
        hideFarStops = store.hideFarStops.first(),
        busDisplayThresholdMin = store.busDisplayThresholdMin.first(),
        farStopThresholdM = store.farStopThresholdM.first(),
        routeLines = store.routeLines.first(),
        routeLineDistanceM = store.routeLineDistanceM.first(),
    )
    override suspend fun setLocationAware(enabled: Boolean) = store.setLocationAware(enabled)
    override suspend fun setHideFarStops(enabled: Boolean) = store.setHideFarStops(enabled)
    override suspend fun setBusDisplayThresholdMin(minutes: Int) = store.setBusDisplayThresholdMin(minutes)
    override suspend fun setFarStopThresholdM(metres: Int) = store.setFarStopThresholdM(metres)
    override suspend fun setRouteLines(enabled: Boolean) = store.setRouteLines(enabled)
    override suspend fun setRouteLineDistanceM(metres: Int) = store.setRouteLineDistanceM(metres)
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

private class DefaultBackendConfigService(
    private val store: BackendConfigStore,
) : BackendConfigService {
    override suspend fun get(): BackendConfigSnapshot {
        val config = store.config.first()
        return BackendConfigSnapshot(
            root = config.root,
            deriveFromRoot = config.deriveFromRoot,
            resolved = BackendService.entries.associate { it.name to config.urlFor(it) },
            cleartext = config.cleartextServices().map { it.name },
        )
    }

    override suspend fun setRoot(root: String) {
        require(isValidBaseUrl(root)) { "root must be an absolute http(s) URL" }
        store.setRoot(root)
    }

    override suspend fun setDeriveFromRoot(derive: Boolean) = store.setDeriveFromRoot(derive)

    override suspend fun setOverride(service: String, url: String) {
        val target = BackendService.entries.firstOrNull { it.name.equals(service, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "unknown service '$service' — expected one of ${BackendService.entries.joinToString { it.name }}",
            )
        require(isValidBaseUrl(url)) { "url must be an absolute http(s) URL" }
        store.setOverride(target, url)
    }
}

/** Production wiring: builds every service over the real stores for the given [context]. */
class DefaultAppServices(context: Context) : AppServices {
    private val appContext = context.applicationContext
    override val settings: SettingsService = DefaultSettingsService(SettingsStore(appContext))
    override val favourites: FavouritesService = DefaultFavouritesService(FavouritesStore(appContext))
    override val notificationWindows: NotificationWindowService =
        DefaultNotificationWindowService(NotificationWindowStore(appContext))
    override val transit: TransitQueryService = DefaultTransitQueryService()
    override val backend: BackendConfigService =
        DefaultBackendConfigService(BackendConfigStore(appContext))
}
