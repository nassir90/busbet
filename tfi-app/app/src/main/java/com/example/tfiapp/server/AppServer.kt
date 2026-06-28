package com.example.tfiapp.server

import com.example.tfiapp.service.AppServices
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * The single Ktor server that hosts both transports over the shared [AppServices]:
 * the MCP server at `/mcp` (Streamable HTTP) and the REST API under `/api`. It owns no behaviour
 * itself — it just wires the two thin adapters onto one engine and manages start/stop. Lifecycle is
 * driven by [OnDeviceServerService].
 */
class AppServer(private val services: AppServices) {
    private var engine: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = engine != null

    /** Starts the server bound to [host]:[port]. No-op if already running. */
    fun start(host: String, port: Int) {
        if (engine != null) return
        engine = embeddedServer(CIO, host = host, port = port) {
            install(ContentNegotiation) { gson() }
            mountMcp(services)
            routing {
                route("/api") { apiRoutes(services) }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }
}
