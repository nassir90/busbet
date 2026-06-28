package com.example.tfiapp.server

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.tfiapp.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.Inet4Address
import java.net.NetworkInterface

const val LOOPBACK_HOST = "127.0.0.1"
const val LAN_HOST = "0.0.0.0"
const val DEFAULT_SERVER_PORT = 8080
const val MIN_SERVER_PORT = 1024
const val MAX_SERVER_PORT = 65535

private val SERVER_ENABLED_KEY = booleanPreferencesKey("server_enabled")
private val SERVER_BIND_LAN_KEY = booleanPreferencesKey("server_bind_lan")
private val SERVER_PORT_KEY = intPreferencesKey("server_port")

/**
 * Persisted config for the on-device server: whether it should run, which interface it binds to
 * (loopback vs. all interfaces / LAN), and the port. Shares the app's single DataStore ("tfi"),
 * matching the idiom of [com.example.tfiapp.SettingsStore].
 */
class ServerSettingsStore(private val context: Context) {
    val enabled: Flow<Boolean> = context.dataStore.data.map { it[SERVER_ENABLED_KEY] ?: false }

    /** false = loopback only (127.0.0.1); true = bound to all interfaces (LAN-reachable). */
    val bindLan: Flow<Boolean> = context.dataStore.data.map { it[SERVER_BIND_LAN_KEY] ?: false }

    val port: Flow<Int> = context.dataStore.data.map { it[SERVER_PORT_KEY] ?: DEFAULT_SERVER_PORT }

    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { it[SERVER_ENABLED_KEY] = value }
    }

    suspend fun setBindLan(value: Boolean) {
        context.dataStore.edit { it[SERVER_BIND_LAN_KEY] = value }
    }

    suspend fun setPort(value: Int) {
        context.dataStore.edit { it[SERVER_PORT_KEY] = value }
    }
}

/** The host string passed to Ktor for a given bind choice. */
fun bindHost(bindLan: Boolean): String = if (bindLan) LAN_HOST else LOOPBACK_HOST

/** A port is valid if it sits in the unprivileged, in-range band. */
fun isValidPort(port: Int): Boolean = port in MIN_SERVER_PORT..MAX_SERVER_PORT

/**
 * The device's first site-local IPv4 address (e.g. 192.168.x.x), used to render a reachable URL
 * when bound to the LAN. Loopback/bind-all addresses aren't reachable names, so we resolve the
 * actual interface address here. Returns null if the device isn't on a LAN.
 */
fun lanIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
