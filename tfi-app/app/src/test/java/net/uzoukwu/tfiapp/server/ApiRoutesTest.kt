package com.example.tfiapp.server

import com.example.tfiapp.DeparturesResponse
import com.example.tfiapp.Favourite
import com.example.tfiapp.NotificationWindow
import com.example.tfiapp.Stop
import com.example.tfiapp.service.AppServices
import com.example.tfiapp.service.FavouritesService
import com.example.tfiapp.service.NotificationWindowService
import com.example.tfiapp.service.SettingsService
import com.example.tfiapp.service.SettingsSnapshot
import com.example.tfiapp.service.TransitQueryService
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the HTTP adapter has no logic of its own: every route just delegates to [AppServices].
 * We back the routes with a fake services implementation and assert the calls pass straight
 * through (GET returns what the service returns; PATCH forwards each field to the service).
 */
class ApiRoutesTest {

    @Test
    fun `GET settings returns the service snapshot`() = testApplication {
        val fake = FakeServices(
            settings = FakeSettings(SettingsSnapshot(true, false, 30, 5000)),
        )
        application {
            routing { route("/api") { apiRoutes(fake) } }
        }
        val body = client.get("/api/settings").bodyAsText()
        assertTrue(body.contains("\"locationAware\":true"))
        assertTrue(body.contains("\"busDisplayThresholdMin\":30"))
    }

    @Test
    fun `PATCH settings forwards each provided field to the service`() = testApplication {
        val settings = FakeSettings(SettingsSnapshot(false, false, 10, 1000))
        application {
            routing { route("/api") { apiRoutes(FakeServices(settings = settings)) } }
        }
        val resp = client.patch("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"hideFarStops":true,"busDisplayThresholdMin":45}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(true, settings.snapshot.hideFarStops)
        assertEquals(45, settings.snapshot.busDisplayThresholdMin)
        // Untouched fields stay as they were.
        assertEquals(false, settings.snapshot.locationAware)
    }
}

// --- fakes -----------------------------------------------------------------------------------

private class FakeSettings(var snapshot: SettingsSnapshot) : SettingsService {
    override suspend fun get() = snapshot
    override suspend fun setLocationAware(enabled: Boolean) {
        snapshot = snapshot.copy(locationAware = enabled)
    }
    override suspend fun setHideFarStops(enabled: Boolean) {
        snapshot = snapshot.copy(hideFarStops = enabled)
    }
    override suspend fun setBusDisplayThresholdMin(minutes: Int) {
        snapshot = snapshot.copy(busDisplayThresholdMin = minutes)
    }
    override suspend fun setFarStopThresholdM(metres: Int) {
        snapshot = snapshot.copy(farStopThresholdM = metres)
    }
}

private class FakeFavourites : FavouritesService {
    override suspend fun list(): List<Favourite> = emptyList()
    override suspend fun add(code: String, name: String, lat: Double?, lon: Double?) = Unit
    override suspend fun remove(code: String) = Unit
}

private class FakeNotificationWindows : NotificationWindowService {
    override suspend fun list(): List<NotificationWindow> = emptyList()
    override suspend fun save(window: NotificationWindow) = Unit
    override suspend fun delete(id: String) = Unit
}

private class FakeTransit : TransitQueryService {
    override suspend fun searchStops(query: String): List<Stop> = emptyList()
    override suspend fun departures(stopCode: String, time: Long?): DeparturesResponse =
        DeparturesResponse(Stop("x", "x", "x"), emptyList())
}

private class FakeServices(
    override val settings: SettingsService = FakeSettings(SettingsSnapshot(false, false, 10, 1000)),
    override val favourites: FavouritesService = FakeFavourites(),
    override val notificationWindows: NotificationWindowService = FakeNotificationWindows(),
    override val transit: TransitQueryService = FakeTransit(),
) : AppServices
