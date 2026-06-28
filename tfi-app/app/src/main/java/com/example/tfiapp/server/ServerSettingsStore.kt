package com.example.tfiapp.server

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.tfiapp.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.Inet4Address
import java.net.NetworkInterface

const val LOOPBACK_HOST = "127.0.0.1"
const val ALL_INTERFACES_HOST = "0.0.0.0"
const val DEFAULT_SERVER_PORT = 8080
const val MIN_SERVER_PORT = 1024
const val MAX_SERVER_PORT = 65535

/** Which interface(s) the on-device server binds to. */
enum class BindMode {
    /** 127.0.0.1 — reachable only from this device. */
    LOOPBACK,

    /** The device's Tailscale address only — reachable over the tailnet, not the local LAN. */
    TAILSCALE,

    /** 0.0.0.0 — every interface (LAN, cellular, tailnet, …). */
    ALL,
}

private val SERVER_ENABLED_KEY = booleanPreferencesKey("server_enabled")
private val SERVER_BIND_MODE_KEY = stringPreferencesKey("server_bind_mode")
private val SERVER_BIND_LAN_KEY = booleanPreferencesKey("server_bind_lan") // legacy, pre-BindMode
private val SERVER_PORT_KEY = intPreferencesKey("server_port")

/**
 * Persisted config for the on-device server: whether it should run, which interface(s) it binds to
 * ([BindMode]), and the port. Shares the app's single DataStore ("tfi"), matching the idiom of
 * [com.example.tfiapp.SettingsStore].
 */
class ServerSettingsStore(private val context: Context) {
    val enabled: Flow<Boolean> = context.dataStore.data.map { it[SERVER_ENABLED_KEY] ?: false }

    val bindMode: Flow<BindMode> = context.dataStore.data.map { prefs ->
        prefs[SERVER_BIND_MODE_KEY]?.let { runCatching { BindMode.valueOf(it) }.getOrNull() }
        // Fall back to the legacy boolean so existing installs keep their choice.
            ?: if (prefs[SERVER_BIND_LAN_KEY] == true) BindMode.ALL else BindMode.LOOPBACK
    }

    val port: Flow<Int> = context.dataStore.data.map { it[SERVER_PORT_KEY] ?: DEFAULT_SERVER_PORT }

    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { it[SERVER_ENABLED_KEY] = value }
    }

    suspend fun setBindMode(value: BindMode) {
        context.dataStore.edit { it[SERVER_BIND_MODE_KEY] = value.name }
    }

    suspend fun setPort(value: Int) {
        context.dataStore.edit { it[SERVER_PORT_KEY] = value }
    }
}

/**
 * The host Ktor should bind to for [mode]. Returns null when the mode can't be satisfied right now
 * (e.g. [BindMode.TAILSCALE] but the device isn't on a tailnet), so callers can refuse to start.
 */
fun bindHost(mode: BindMode): String? = when (mode) {
    BindMode.LOOPBACK -> LOOPBACK_HOST
    BindMode.ALL -> ALL_INTERFACES_HOST
    BindMode.TAILSCALE -> tailscaleIpv4()
}

/** The address other machines actually reach the server on for [mode] (for display). */
fun reachableHost(mode: BindMode): String? = when (mode) {
    BindMode.LOOPBACK -> LOOPBACK_HOST
    BindMode.TAILSCALE -> tailscaleIpv4()
    BindMode.ALL -> tailscaleIpv4() ?: lanIpv4()
}

/** A port is valid if it sits in the unprivileged, in-range band. */
fun isValidPort(port: Int): Boolean = port in MIN_SERVER_PORT..MAX_SERVER_PORT

/**
 * The device's first site-local IPv4 address (e.g. 192.168.x.x), used to render a reachable URL on
 * the local LAN. Returns null if the device isn't on a LAN.
 */
fun lanIpv4(): String? = firstIpv4 { it.isSiteLocalAddress }

/**
 * The device's Tailscale IPv4 address, if connected. Tailscale assigns addresses from the CGNAT
 * range 100.64.0.0/10, which is neither site-local nor loopback, so we match that range directly.
 */
fun tailscaleIpv4(): String? = firstIpv4 { it.isInCgnatRange() }

private fun firstIpv4(predicate: (Inet4Address) -> Boolean): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull(predicate)
        ?.hostAddress
}.getOrNull()

/** 100.64.0.0/10 — the shared-address space Tailscale draws its IPv4 addresses from. */
private fun Inet4Address.isInCgnatRange(): Boolean {
    val b = address // 4 bytes, signed
    return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
}
