package iompar.mpts.ie.screenshots

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import iompar.mpts.ie.BatchDeparturesResponse
import iompar.mpts.ie.DeparturesResponse
import iompar.mpts.ie.GtfsApi
import iompar.mpts.ie.RouteDirection
import iompar.mpts.ie.RouteStop
import iompar.mpts.ie.ShapePoint
import iompar.mpts.ie.Stop
import iompar.mpts.ie.TripDetail
import iompar.mpts.ie.VehiclePosition
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.lang.reflect.Type

/**
 * The instant every fixture is true for: 08:15 Irish time on Fri 9 Oct 2026, morning rush, after
 * the timetable published on 8 Oct went live (so every realtime trip matches the static data).
 * Screens see this as "now" through AppClock, and the recorder queries the server's time-travel
 * endpoints at it.
 */
const val FIXTURE_AT_SEC = 1_791_530_100L

/**
 * [GtfsApi] answered from responses recorded off the production backend at [FIXTURE_AT_SEC].
 *
 * Each call maps to a key such as `departures/7392`; the recording lives at
 * `src/test/resources/screenshot-fixtures/<key>.json`, or `<key>.404` for an answer that was a 404.
 * A call with no recording behaves like a 404 and is appended to [MISSES], which
 * `scripts/record_screenshot_fixtures.py` reads to fetch exactly what the screens asked for.
 */
class FixtureGtfsApi : GtfsApi {
    private val gson = Gson()

    private fun <T> load(key: String, type: Type): T {
        val loader = javaClass.classLoader!!
        loader.getResource("$ROOT/$key.json")?.let { return gson.fromJson(it.readText(), type) }
        if (loader.getResource("$ROOT/$key.404") == null) recordMiss(key)
        throw HttpException(Response.error<Any>(404, "".toResponseBody()))
    }

    private inline fun <reified T> load(key: String): T = load(key, object : TypeToken<T>() {}.type)

    override suspend fun searchStops(q: String): List<Stop> = load("stops-search/$q")
    override suspend fun stop(code: String): Stop = load("stops/$code")
    override suspend fun departures(code: String, time: Long?): DeparturesResponse = load("departures/$code")
    override suspend fun departuresBatch(codes: String, time: Long?): BatchDeparturesResponse =
        load("departures-batch/$codes")
    override suspend fun searchRoutes(q: String): List<RouteDirection> = load("routes-search/$q")
    override suspend fun routeStops(route: String, direction: Int): List<RouteStop> =
        load("route-stops/$route/$direction")
    override suspend fun trip(tripId: String): TripDetail = load("trips/$tripId")
    override suspend fun vehicles(stopCode: String, time: Long?): List<VehiclePosition> = load("vehicles/$stopCode")
    override suspend fun tripVehicle(tripId: String): VehiclePosition = load("vehicles-trip/$tripId")
    override suspend fun tripShape(tripId: String): List<ShapePoint> = load("shapes-trip/$tripId")
    override suspend fun stopRoutes(stopCode: String): List<String> = load("stop-routes/$stopCode")

    /** Test-side reads, for picking a real trip or route out of a recorded board. */
    fun recordedDepartures(code: String): DeparturesResponse = gson.fromJson(
        javaClass.classLoader!!.getResource("$ROOT/departures/$code.json")!!.readText(),
        DeparturesResponse::class.java,
    )

    companion object {
        const val ROOT = "screenshot-fixtures"
        /** Relative to the module directory, which is the test JVM's working directory. */
        val MISSES = File("build/screenshot-fixture-misses.txt")

        @Synchronized
        private fun recordMiss(key: String) {
            MISSES.parentFile?.mkdirs()
            val seen = if (MISSES.exists()) MISSES.readLines().toSet() else emptySet()
            if (key !in seen) MISSES.appendText("$key\n")
        }
    }
}
