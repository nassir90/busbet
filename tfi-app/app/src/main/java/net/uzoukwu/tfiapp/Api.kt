package com.example.tfiapp

import com.example.tfiapp.BuildConfig
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
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

    @GET("stops/{code}")
    suspend fun stop(@Path("code") code: String): Stop

    @GET("departures/{code}")
    suspend fun departures(
        @Path("code") code: String,
        @Query("time") time: Long? = null,
    ): DeparturesResponse

    @GET("routes")
    suspend fun searchRoutes(@Query("q") q: String): List<RouteDirection>

    @GET("route-stops")
    suspend fun routeStops(
        @Query("route") route: String,
        @Query("direction") direction: Int,
    ): List<RouteStop>

    @GET("trips/{tripId}")
    suspend fun trip(@Path("tripId") tripId: String): TripDetail

    @GET("vehicles/{stopCode}")
    suspend fun vehicles(
        @Path("stopCode") stopCode: String,
        @Query("time") time: Long? = null,
    ): List<VehiclePosition>

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
    val kind: String,                                          // "arrived" | "cancelled"
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(SentryErrorInterceptor())
        .build()

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

    /** Read-only GTFS proxy (gtfsr-stop-times). */
    val service: GtfsApi
        get() {
            val url = BackendConfigHolder.current.urlFor(BackendService.GTFS)
            gtfsApi?.let { if (url == gtfsUrl) return it }
            return synchronized(this) {
                gtfsApi?.let { if (url == gtfsUrl) return@synchronized it }
                build(url, GtfsApi::class.java).also { gtfsApi = it; gtfsUrl = url }
            }
        }

    /** Stateful write API (tfi-tenant-api). */
    val tenant: TenantApi
        get() {
            val url = BackendConfigHolder.current.urlFor(BackendService.TENANT)
            tenantApi?.let { if (url == tenantUrl) return it }
            return synchronized(this) {
                tenantApi?.let { if (url == tenantUrl) return@synchronized it }
                build(url, TenantApi::class.java).also { tenantApi = it; tenantUrl = url }
            }
        }
}
