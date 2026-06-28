package com.example.tfiapp.server

import com.example.tfiapp.NotificationWindow
import com.example.tfiapp.service.AppServices
import com.google.gson.Gson
import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val mcpGson = Gson()

/**
 * The MCP face over [AppServices]. Each tool is a thin wrapper: deserialise the arguments, call one
 * service method, serialise the result back as JSON text. There is no business logic here — it is
 * the same capability surface as [apiRoutes], just spoken over MCP. Mounted on the same Ktor server
 * via the SDK's Streamable HTTP transport at `/mcp`.
 */
fun Application.mountMcp(services: AppServices) {
    mcpStreamableHttp(path = "/mcp") { buildMcpServer(services) }
}

private fun buildMcpServer(services: AppServices): Server {
    val server = Server(
        serverInfo = Implementation(name = "tfi-app", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
        ),
    )

    server.addTool(
        name = "get_settings",
        description = "Get the current app settings (location-aware mode, hide-far-stops, display thresholds).",
    ) { _ -> json(services.settings.get()) }

    server.addTool(
        name = "set_settings",
        description = "Update one or more app settings. Only the fields you provide are changed; returns the new settings.",
        inputSchema = schema(
            "locationAware" to prop("boolean", "Sort/filter stops by current location"),
            "hideFarStops" to prop("boolean", "Hide stops beyond the far-stop threshold"),
            "busDisplayThresholdMin" to prop("integer", "Only show buses due within this many minutes"),
            "farStopThresholdM" to prop("integer", "Distance in metres beyond which a stop is 'far'"),
        ),
    ) { req ->
        val args = req.arguments
        args.bool("locationAware")?.let { services.settings.setLocationAware(it) }
        args.bool("hideFarStops")?.let { services.settings.setHideFarStops(it) }
        args.int("busDisplayThresholdMin")?.let { services.settings.setBusDisplayThresholdMin(it) }
        args.int("farStopThresholdM")?.let { services.settings.setFarStopThresholdM(it) }
        json(services.settings.get())
    }

    server.addTool(
        name = "list_favourites",
        description = "List the user's favourite stops.",
    ) { _ -> json(services.favourites.list()) }

    server.addTool(
        name = "add_favourite",
        description = "Add a favourite stop.",
        inputSchema = schema(
            "code" to prop("string", "Stop code"),
            "name" to prop("string", "Stop name"),
            "lat" to prop("number", "Optional latitude"),
            "lon" to prop("number", "Optional longitude"),
            required = listOf("code", "name"),
        ),
    ) { req ->
        val args = req.arguments
        val code = args.str("code") ?: return@addTool error("code is required")
        val name = args.str("name") ?: return@addTool error("name is required")
        services.favourites.add(code, name, args.dbl("lat"), args.dbl("lon"))
        json(services.favourites.list())
    }

    server.addTool(
        name = "remove_favourite",
        description = "Remove a favourite stop by its code.",
        inputSchema = schema("code" to prop("string", "Stop code"), required = listOf("code")),
    ) { req ->
        val code = req.arguments.str("code") ?: return@addTool error("code is required")
        services.favourites.remove(code)
        json(services.favourites.list())
    }

    server.addTool(
        name = "list_notification_windows",
        description = "List the configured bus-notification windows.",
    ) { _ -> json(services.notificationWindows.list()) }

    server.addTool(
        name = "save_notification_window",
        description = "Create or update a notification window. Provide the full window object; an existing id updates in place.",
        inputSchema = schema(
            "window" to prop("object", "A NotificationWindow object (id,name,days,startMinute,endMinute,stopCode,stopName,routes,enabled)"),
            required = listOf("window"),
        ),
    ) { req ->
        val windowJson = req.arguments?.get("window")?.toString()
            ?: return@addTool error("window is required")
        val window = mcpGson.fromJson(windowJson, NotificationWindow::class.java)
        services.notificationWindows.save(window)
        json(services.notificationWindows.list())
    }

    server.addTool(
        name = "delete_notification_window",
        description = "Delete a notification window by id.",
        inputSchema = schema("id" to prop("string", "Window id"), required = listOf("id")),
    ) { req ->
        val id = req.arguments.str("id") ?: return@addTool error("id is required")
        services.notificationWindows.delete(id)
        json(services.notificationWindows.list())
    }

    server.addTool(
        name = "search_stops",
        description = "Search stops by name or code (read-only).",
        inputSchema = schema("q" to prop("string", "Search query"), required = listOf("q")),
    ) { req ->
        val q = req.arguments.str("q") ?: return@addTool error("q is required")
        json(services.transit.searchStops(q))
    }

    server.addTool(
        name = "get_departures",
        description = "Get live departures for a stop code (read-only).",
        inputSchema = schema(
            "code" to prop("string", "Stop code"),
            "time" to prop("integer", "Optional epoch-seconds time to query (defaults to now)"),
            required = listOf("code"),
        ),
    ) { req ->
        val code = req.arguments.str("code") ?: return@addTool error("code is required")
        val time = req.arguments?.get("time")?.jsonPrimitive?.longOrNull()
        json(services.transit.departures(code, time))
    }

    return server
}

// --- helpers ---------------------------------------------------------------------------------

/** Serialise any domain result to a JSON text content block. */
private fun json(value: Any?): CallToolResult =
    CallToolResult(content = listOf(TextContent(mcpGson.toJson(value))))

/** A tool-level error result (visible to the model, not a protocol error). */
private fun error(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Build a JSON-Schema property fragment, e.g. {"type":"string","description":"..."}. */
private fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

/** Assemble a [ToolSchema] from named property fragments. */
private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String>? = null): ToolSchema =
    ToolSchema(
        properties = buildJsonObject { properties.forEach { (k, v) -> put(k, v) } },
        required = required,
    )

private fun JsonObject?.str(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject?.bool(key: String): Boolean? = this?.get(key)?.jsonPrimitive?.booleanOrNull
private fun JsonObject?.int(key: String): Int? = this?.get(key)?.jsonPrimitive?.intOrNull
private fun JsonObject?.dbl(key: String): Double? = this?.get(key)?.jsonPrimitive?.doubleOrNull
private fun JsonPrimitive.longOrNull(): Long? = contentOrNull?.toLongOrNull()
