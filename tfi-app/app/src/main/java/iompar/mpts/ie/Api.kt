package iompar.mpts.ie

import iompar.mpts.ie.BuildConfig
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class Stop(
    @SerializedName("stop_id") val stopId: String,
    @SerializedName("stop_code") val stopCode: String,
    @SerializedName("stop_name") val stopName: String,
    @SerializedName("stop_lat") val stopLat: Double? = null,
    @SerializedName("stop_lon") val stopLon: Double? = null,
    @SerializedName("routes") val routes: List<String>? = null,
)

data class Departure(
    @SerializedName("trip_id") val tripId: String,
    @SerializedName("stop_sequence") val stopSequence: Int,
    @SerializedName("route_short_name") val routeShortName: String,
    @SerializedName("direction_id") val directionId: Int,
    @SerializedName("trip_headsign") val tripHeadsign: String,
    @SerializedName("scheduled_departure") val scheduledDeparture: String,
    @SerializedName("estimated_departure") val estimatedDeparture: String?,
    @SerializedName("delay_seconds") val delaySeconds: Int?,
    val realtime: Boolean,
)

data class DeparturesResponse(
    val stop: Stop,
    val departures: List<Departure>,
    /**
     * Header timestamp (epoch seconds) of the GTFS-R snapshot the realtime overlay came from —
     * the instant these times are actually true for. The collector polls on a fixed cadence, so
     * it lags the request by up to one poll interval; the fetch time overstates freshness.
     *
     * Nullable: older deployments omit it, and so does a snapshot-less (schedule-only) answer.
     */
    @SerializedName("feed_timestamp") val feedTimestamp: Long? = null,
)

/**
 * Response of the batch departures endpoint, keyed by the stop code that was asked for.
 * Codes the server didn't recognise come back in [missing] rather than being silently dropped.
 */
data class BatchDeparturesResponse(
    val results: Map<String, DeparturesResponse> = emptyMap(),
    val missing: List<String> = emptyList(),
)

data class RouteDirection(
    @SerializedName("route_short_name") val routeShortName: String,
    @SerializedName("direction_id") val directionId: Int,
    @SerializedName("from_stop") val fromStop: String,
    @SerializedName("to_stop") val toStop: String,
)

data class RouteStop(
    @SerializedName("stop_sequence") val stopSequence: Int,
    @SerializedName("stop_code") val stopCode: String,
    @SerializedName("stop_name") val stopName: String,
)

data class TripStop(
    @SerializedName("stop_sequence") val stopSequence: Int,
    @SerializedName("stop_id") val stopId: String,
    @SerializedName("stop_code") val stopCode: String,
    @SerializedName("stop_name") val stopName: String,
    @SerializedName("stop_lat") val stopLat: Double? = null,
    @SerializedName("stop_lon") val stopLon: Double? = null,
    @SerializedName("scheduled_arrival") val scheduledArrival: String,
    @SerializedName("scheduled_departure") val scheduledDeparture: String,
    @SerializedName("estimated_arrival") val estimatedArrival: String?,
    @SerializedName("estimated_departure") val estimatedDeparture: String?,
    @SerializedName("delay_seconds") val delaySeconds: Int?,
    val realtime: Boolean,
)

data class VehiclePosition(
    @SerializedName("trip_id") val tripId: String,
    @SerializedName("route_short_name") val routeShortName: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,          // null until second poll gives us a heading
    @SerializedName("delay_seconds") val delaySeconds: Int?,
    /** Scheduled first departure / last arrival of the whole trip, "HH:MM:SS". */
    @SerializedName("first_departure") val firstDeparture: String? = null,
    @SerializedName("last_arrival") val lastArrival: String? = null,
)

/** One point of a route's road geometry. Already decimated server-side. */
data class ShapePoint(
    val lat: Double,
    val lon: Double,
)

data class TripDetail(
    @SerializedName("trip_id") val tripId: String,
    @SerializedName("route_short_name") val routeShortName: String,
    @SerializedName("trip_headsign") val tripHeadsign: String,
    @SerializedName("direction_id") val directionId: Int,
    val stops: List<TripStop>,
)

interface GtfsApi {
    @GET("stops")
    suspend fun searchStops(@Query("q") q: String): List<Stop>

    // 404 means a favourite points at a stop code the current timetable no longer has. The only
    // caller is the coordinate backfill, which skips it and leaves that favourite unsorted — the
    // stop board reports the same condition where the user can actually see it.
    @Headers("$EXPECTED_STATUS_HEADER: 404")
    @GET("stops/{code}")
    suspend fun stop(@Path("code") code: String): Stop

    @GET("departures/{code}")
    suspend fun departures(
        @Path("code") code: String,
        @Query("time") time: Long? = null,
    ): DeparturesResponse

    /**
     * Departures for several stops in one round trip. [codes] is comma-separated.
     *
     * The home screen used to issue one request per favourite card simultaneously, which OkHttp
     * then queued at 5 concurrent per host, so a long favourites list needed a second wave before
     * the last card filled in.
     */
    // 404 means the configured deployment predates this endpoint; [Boards] catches it and falls
    // back to per-stop calls, so it isn't an error worth reporting.
    @Headers("$EXPECTED_STATUS_HEADER: 404")
    @GET("departures")
    suspend fun departuresBatch(
        @Query("codes") codes: String,
        @Query("time") time: Long? = null,
    ): BatchDeparturesResponse

    @GET("routes")
    suspend fun searchRoutes(@Query("q") q: String): List<RouteDirection>

    // 404 means this route has no stops in this direction — which the flip-direction button on the
    // route screen reaches for any route that only runs one way. An empty result, not a failure.
    @Headers("$EXPECTED_STATUS_HEADER: 404")
    @GET("route-stops")
    suspend fun routeStops(
        @Query("route") route: String,
        @Query("direction") direction: Int,
    ): List<RouteStop>

    @GET("trips/{tripId}")
    suspend fun trip(@Path("tripId") tripId: String): TripDetail

    // Purely decorative — markers on the stop map. A 404 here always accompanies a 404 on this
    // stop's departures, which is the call that reports it, so leaving this one on would only
    // double up the same signal.
    @Headers("$EXPECTED_STATUS_HEADER: 404")
    @GET("vehicles/{stopCode}")
    suspend fun vehicles(
        @Path("stopCode") stopCode: String,
        @Query("time") time: Long? = null,
    ): List<VehiclePosition>

    // 404 means this trip has no live position right now — scheduled-only, finished, or not yet
    // started. An ordinary answer, not a failure.
    @Headers("$EXPECTED_STATUS_HEADER: 404")
    @GET("vehicles/trip/{tripId}")
    suspend fun tripVehicle(@Path("tripId") tripId: String): VehiclePosition

    // 404 means the feed carries no shape for this trip, which is normal for plenty of them.
    @Headers("$EXPECTED_STATUS_HEADER: 404")
    @GET("shapes/trip/{tripId}")
    suspend fun tripShape(@Path("tripId") tripId: String): List<ShapePoint>

    @GET("stop-routes/{stopCode}")
    suspend fun stopRoutes(@Path("stopCode") stopCode: String): List<String>
}

/** Stateful write API (tfi-tenant-api) — separate host from the read-only GTFS proxy. */
interface TenantApi {
    @POST("report")
    suspend fun report(@Body body: ArrivalReport): ReportResponse

    @POST("reports/{id}/undo")
    suspend fun undoReport(@Path("id") id: Long): UndoReportResponse
}

data class ArrivalReport(
    @SerializedName("trip_id") val tripId: String,
    @SerializedName("stop_code") val stopCode: String,
    @SerializedName("route_short_name") val routeShortName: String,
    @SerializedName("service_date") val serviceDate: String,   // "YYYYMMDD" local
    // "boarded" (the user actually got on) | "arrived" (the bus turned up) | "cancelled".
    // Server-side `kind` is an open TEXT domain, so adding a kind needs no schema migration.
    val kind: String,
    @SerializedName("actual_time") val actualTime: String?,    // "HH:MM" local, null when cancelled
    @SerializedName("reported_at") val reportedAt: Long,       // epoch seconds
)

data class ReportResponse(
    val ok: Boolean,
    val id: Long,
)

data class UndoReportResponse(
    val ok: Boolean,
    val id: Long,
    val undone: Boolean,
)

object Api {
    /** On-disk response cache. Small: only the static GTFS endpoints are cacheable. */
    private const val HTTP_CACHE_BYTES = 8L * 1024 * 1024

    @Volatile private var httpCacheDir: java.io.File? = null

    /**
     * Call once from [TfiApp.onCreate], before anything issues a request. Only records a path —
     * OkHttp opens the cache lazily on first use, so this does no disk I/O.
     */
    fun init(context: android.content.Context) {
        httpCacheDir = java.io.File(context.applicationContext.cacheDir, "http")
    }

    // Lazy, unlike the Retrofit clients below: the cache directory is fixed for the process, so
    // there's nothing here that can go stale the way a user-editable base URL can.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(SentryErrorInterceptor())
            .apply {
                // Honours the Cache-Control the GTFS proxy now sets on stop-routes, route-stops
                // and shapes — static timetable data the app was refetching on every screen open.
                httpCacheDir?.let { cache(okhttp3.Cache(it, HTTP_CACHE_BYTES)) }
                // Everything the app talks to lives behind one host, so OkHttp's default of 5
                // concurrent requests per host is the app's real ceiling, not the 64 global one.
                dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 10 })
            }
            .build()
    }

    private fun <T> build(baseUrl: String, api: Class<T>): T =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(SentryConverterFactory(GsonConverterFactory.create()))
            .build()
            .create(api)

    // Retrofit clients are rebuilt whenever the configured URL changes, rather than being pinned
    // for the process lifetime. `by lazy` would resolve once and then quietly keep talking to the
    // old backend after the user edited it in Settings. The OkHttp client is deliberately shared
    // across rebuilds so the connection pool and interceptors survive a repoint.
    @Volatile private var gtfsUrl: String? = null
    @Volatile private var gtfsApi: GtfsApi? = null

    @Volatile private var tenantUrl: String? = null
    @Volatile private var tenantApi: TenantApi? = null

    /**
     * Read-only GTFS proxy (gtfsr-stop-times).
     *
     * Suspending because resolving the configured URL may have to wait for the first DataStore
     * read. That wait used to happen on the main thread in `Application.onCreate` instead. Once
     * the config has landed this returns without suspending.
     */
    suspend fun service(): GtfsApi {
        val url = BackendConfigHolder.current().urlFor(BackendService.GTFS)
        gtfsApi?.let { if (url == gtfsUrl) return it }
        return synchronized(this) {
            gtfsApi?.let { if (url == gtfsUrl) return@synchronized it }
            build(url, GtfsApi::class.java).also { gtfsApi = it; gtfsUrl = url }
        }
    }

    /** Stateful write API (tfi-tenant-api). */
    suspend fun tenant(): TenantApi {
        val url = BackendConfigHolder.current().urlFor(BackendService.TENANT)
        tenantApi?.let { if (url == tenantUrl) return it }
        return synchronized(this) {
            tenantApi?.let { if (url == tenantUrl) return@synchronized it }
            build(url, TenantApi::class.java).also { tenantApi = it; tenantUrl = url }
        }
    }
}
