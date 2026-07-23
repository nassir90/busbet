package net.uzoukwu.tfiapp.server

import net.uzoukwu.tfiapp.service.AppServices
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.McpJson

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
            // McpJson so the MCP transport's call.respond(JSONRPCMessage) serializes correctly.
            // The /api routes don't rely on this — they serialize their Gson domain types by hand.
            install(ContentNegotiation) { json(McpJson) }
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
