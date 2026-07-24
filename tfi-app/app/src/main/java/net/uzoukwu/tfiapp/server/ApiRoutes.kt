package net.uzoukwu.tfiapp.server

import net.uzoukwu.tfiapp.NotificationWindow
import net.uzoukwu.tfiapp.service.AppServices
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val apiGson = Gson()

/** Partial settings update — only the non-null fields are applied. */
data class SettingsPatch(
    val locationAware: Boolean? = null,
    val hideFarStops: Boolean? = null,
    val busDisplayThresholdMin: Int? = null,
    val farStopThresholdM: Int? = null,
    val routeLines: Boolean? = null,
    val routeLineDistanceM: Int? = null,
)

/** Partial backend-endpoint update. `overrides` is keyed by [net.uzoukwu.tfiapp.BackendService] name. */
data class BackendConfigPatch(
    val root: String? = null,
    val deriveFromRoot: Boolean? = null,
    val overrides: Map<String, String>? = null,
)

/** Body for adding a favourite stop. */
data class AddFavouriteRequest(
    val code: String,
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * The REST face over [AppServices]. Every handler is a thin adapter: read the request, call one
 * service method, respond with the result. No business logic lives here — it's the same surface
 * as the MCP tools, just spoken over HTTP.
 *
 * We serialize with Gson by hand (not Ktor ContentNegotiation): the server-wide ContentNegotiation
 * is configured with kotlinx's McpJson for the MCP transport, and the app's domain classes use
 * Gson `@SerializedName` annotations rather than `@Serializable`.
 */
fun Route.apiRoutes(services: AppServices) {
    route("/backend-config") {
        get { call.respondJson(services.backend.get()) }
        patch {
            val patch = call.receiveJson(BackendConfigPatch::class.java)
            // Validation lives in the service; a bad URL surfaces as 400 rather than silently
            // repointing the app at something unreachable.
            patch.root?.let { services.backend.setRoot(it) }
            patch.deriveFromRoot?.let { services.backend.setDeriveFromRoot(it) }
            patch.overrides?.forEach { (service, url) -> services.backend.setOverride(service, url) }
            call.respondJson(services.backend.get())
        }
    }

    route("/settings") {
        get { call.respondJson(services.settings.get()) }
        patch {
            val patch = call.receiveJson(SettingsPatch::class.java)
            patch.locationAware?.let { services.settings.setLocationAware(it) }
            patch.hideFarStops?.let { services.settings.setHideFarStops(it) }
            patch.busDisplayThresholdMin?.let { services.settings.setBusDisplayThresholdMin(it) }
            patch.farStopThresholdM?.let { services.settings.setFarStopThresholdM(it) }
            patch.routeLines?.let { services.settings.setRouteLines(it) }
            patch.routeLineDistanceM?.let { services.settings.setRouteLineDistanceM(it) }
            call.respondJson(services.settings.get())
        }
    }

    route("/favourites") {
        get { call.respondJson(services.favourites.list()) }
        post {
            val req = call.receiveJson(AddFavouriteRequest::class.java)
            services.favourites.add(req.code, req.name, req.lat, req.lon)
            call.respondJson(services.favourites.list(), HttpStatusCode.Created)
        }
        delete("/{code}") {
            val code = call.parameters["code"]
                ?: return@delete call.respondJson(mapOf("error" to "missing code"), HttpStatusCode.BadRequest)
            services.favourites.remove(code)
            call.respondJson(services.favourites.list())
        }
    }

    route("/notification-windows") {
        get { call.respondJson(services.notificationWindows.list()) }
        post {
            val window = call.receiveJson(NotificationWindow::class.java)
            services.notificationWindows.save(window)
            call.respondJson(services.notificationWindows.list(), HttpStatusCode.Created)
        }
        delete("/{id}") {
            val id = call.parameters["id"]
                ?: return@delete call.respondJson(mapOf("error" to "missing id"), HttpStatusCode.BadRequest)
            services.notificationWindows.delete(id)
            call.respondJson(services.notificationWindows.list())
        }
    }

    get("/stops") {
        val q = call.parameters["q"]
            ?: return@get call.respondJson(mapOf("error" to "missing q"), HttpStatusCode.BadRequest)
        call.respondJson(services.transit.searchStops(q))
    }

    get("/departures/{code}") {
        val code = call.parameters["code"]
            ?: return@get call.respondJson(mapOf("error" to "missing code"), HttpStatusCode.BadRequest)
        val time = call.parameters["time"]?.toLongOrNull()
        call.respondJson(services.transit.departures(code, time))
    }
}

private suspend fun ApplicationCall.respondJson(value: Any?, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(apiGson.toJson(value), ContentType.Application.Json, status)

private suspend fun <T> ApplicationCall.receiveJson(type: Class<T>): T =
    apiGson.fromJson(receiveText(), type)
