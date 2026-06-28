package com.example.tfiapp.server

import com.example.tfiapp.NotificationWindow
import com.example.tfiapp.service.AppServices
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Partial settings update — only the non-null fields are applied. */
data class SettingsPatch(
    val locationAware: Boolean? = null,
    val hideFarStops: Boolean? = null,
    val busDisplayThresholdMin: Int? = null,
    val farStopThresholdM: Int? = null,
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
 * as the MCP tools, just spoken over HTTP. Serialization is handled by the Gson ContentNegotiation
 * installed on the server, so the existing domain data classes are used as-is.
 */
fun Route.apiRoutes(services: AppServices) {
    route("/settings") {
        get { call.respond(services.settings.get()) }
        patch {
            val patch = call.receive<SettingsPatch>()
            patch.locationAware?.let { services.settings.setLocationAware(it) }
            patch.hideFarStops?.let { services.settings.setHideFarStops(it) }
            patch.busDisplayThresholdMin?.let { services.settings.setBusDisplayThresholdMin(it) }
            patch.farStopThresholdM?.let { services.settings.setFarStopThresholdM(it) }
            call.respond(services.settings.get())
        }
    }

    route("/favourites") {
        get { call.respond(services.favourites.list()) }
        post {
            val req = call.receive<AddFavouriteRequest>()
            services.favourites.add(req.code, req.name, req.lat, req.lon)
            call.respond(HttpStatusCode.Created, services.favourites.list())
        }
        delete("/{code}") {
            val code = call.parameters["code"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing code"))
            services.favourites.remove(code)
            call.respond(services.favourites.list())
        }
    }

    route("/notification-windows") {
        get { call.respond(services.notificationWindows.list()) }
        post {
            val window = call.receive<NotificationWindow>()
            services.notificationWindows.save(window)
            call.respond(HttpStatusCode.Created, services.notificationWindows.list())
        }
        delete("/{id}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            services.notificationWindows.delete(id)
            call.respond(services.notificationWindows.list())
        }
    }

    get("/stops") {
        val q = call.parameters["q"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing q"))
        call.respond(services.transit.searchStops(q))
    }

    get("/departures/{code}") {
        val code = call.parameters["code"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing code"))
        val time = call.parameters["time"]?.toLongOrNull()
        call.respond(services.transit.departures(code, time))
    }
}
