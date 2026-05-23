package com.example.tfiapp

import com.example.tfiapp.BuildConfig
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class Stop(
    @SerializedName("stop_id") val stopId: String,
    @SerializedName("stop_code") val stopCode: String,
    @SerializedName("stop_name") val stopName: String,
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

interface GtfsApi {
    @GET("stops")
    suspend fun searchStops(@Query("q") q: String): List<Stop>

    @GET("departures/{code}")
    suspend fun departures(@Path("code") code: String): DeparturesResponse

    @GET("routes")
    suspend fun searchRoutes(@Query("q") q: String): List<RouteDirection>

    @GET("route-stops")
    suspend fun routeStops(
        @Query("route") route: String,
        @Query("direction") direction: Int,
    ): List<RouteStop>
}

object Api {
    val service: GtfsApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GtfsApi::class.java)
    }
}
