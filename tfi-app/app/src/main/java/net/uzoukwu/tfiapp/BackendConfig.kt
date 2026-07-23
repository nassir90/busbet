package net.uzoukwu.tfiapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * Where the backends live. Runtime configuration, not a build constant — the whole point is that a
 * user can repoint the app at a different deployment without a rebuild.
 */

/**
 * Arrival reporting ships in debug builds only. The feature is incomplete and the write API it
 * posts to has no authentication or rate limiting, so it has no business being reachable in a
 * published build.
 */
val REPORTS_ENABLED: Boolean get() = BuildConfig.DEBUG

/** Default root. Served over the Cloudflare tunnel, hence https. */
const val DEFAULT_BACKEND_ROOT = "https://pet.uzoukwu.net"

/**
 * A backend the app talks to. [slug] is the path segment appended to the root in derive mode, and
 * matches the reverse-proxy layout the origin already serves (`<root>/gtfsr-stop-times/`).
 */
enum class BackendService(val slug: String, val label: String) {
    GTFS("gtfsr-stop-times", "Departures (read)"),
    TENANT("tfi-tenant-api", "Reports (write)"),
}

/**
 * The resolved backend configuration.
 *
 * In derive mode every service URL is built from [root]. With derive off, a service uses its
 * [overrides] entry if it has a usable one and otherwise still falls back to the derived URL, so
 * clearing a single override can never leave a service with no address at all.
 */
data class BackendConfig(
    val root: String = DEFAULT_BACKEND_ROOT,
    val deriveFromRoot: Boolean = true,
    val overrides: Map<BackendService, String> = emptyMap(),
) {
    fun urlFor(service: BackendService): String {
        if (!deriveFromRoot) {
            overrides[service]?.trim()?.takeIf { it.isNotEmpty() && isValidBaseUrl(it) }
                ?.let { return withTrailingSlash(it) }
        }
        return deriveUrl(root, service)
    }

    /** Services currently reachable over plain HTTP — surfaced as a warning in Settings. */
    fun cleartextServices(): List<BackendService> =
        BackendService.entries.filter { urlFor(it).startsWith("http://", ignoreCase = true) }
}

fun withTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

fun deriveUrl(root: String, service: BackendService): String =
    withTrailingSlash(root.trim().trimEnd('/') + "/" + service.slug)

/** A usable Retrofit base URL: absolute, http(s), with a host. */
fun isValidBaseUrl(url: String): Boolean = runCatching {
    val u = URI(url.trim())
    (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrBlank()
}.getOrDefault(false)

// --- Persistence -----------------------------------------------------------------------------

private val BACKEND_ROOT_KEY = stringPreferencesKey("backend_root")
private val BACKEND_DERIVE_KEY = booleanPreferencesKey("backend_derive_from_root")
private fun overrideKey(service: BackendService) =
    stringPreferencesKey("backend_override_${service.name}")

/** DataStore-backed backend config, sharing the app's single "tfi" store like the other stores. */
class BackendConfigStore(private val context: Context) {
    val config: Flow<BackendConfig> = context.dataStore.data.map { prefs ->
        BackendConfig(
            root = prefs[BACKEND_ROOT_KEY] ?: DEFAULT_BACKEND_ROOT,
            deriveFromRoot = prefs[BACKEND_DERIVE_KEY] ?: true,
            overrides = BackendService.entries.mapNotNull { service ->
                prefs[overrideKey(service)]?.let { service to it }
            }.toMap(),
        )
    }

    suspend fun setRoot(value: String) {
        context.dataStore.edit { it[BACKEND_ROOT_KEY] = value.trim() }
    }

    suspend fun setDeriveFromRoot(value: Boolean) {
        context.dataStore.edit { it[BACKEND_DERIVE_KEY] = value }
    }

    suspend fun setOverride(service: BackendService, value: String) {
        context.dataStore.edit { it[overrideKey(service)] = value.trim() }
    }
}

/**
 * The live view of [BackendConfig] for non-suspending callers — chiefly [Api], which is reached
 * from widgets, workers and the on-device server as well as the UI.
 *
 * This exists to kill the staleness bug: [Api]'s clients used to be `by lazy` over build constants,
 * so they'd pin whatever URL they saw first for the life of the process. Here the snapshot is
 * refreshed by a collector for as long as the process lives, and [Api] rebuilds its Retrofit
 * clients whenever the resolved URL changes, so a Settings edit takes effect on the next request
 * with no restart.
 */
object BackendConfigHolder {
    @Volatile
    private var snapshot: BackendConfig = BackendConfig()

    /** The current config. Cheap — a volatile read. */
    val current: BackendConfig get() = snapshot

    /**
     * Call once from [TfiApp.onCreate]. The first read is blocking (a single DataStore read, low
     * milliseconds) so that a widget or worker running before the collector's first emission still
     * sees persisted config rather than the default root.
     */
    fun init(context: Context) {
        val store = BackendConfigStore(context.applicationContext)
        runCatching { snapshot = runBlocking { store.config.first() } }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            store.config.collect { snapshot = it }
        }
    }
}
