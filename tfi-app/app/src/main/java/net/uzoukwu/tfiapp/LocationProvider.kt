package net.uzoukwu.tfiapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Lightweight location access via the framework LocationManager (no Play Services). */
object LocationProvider {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns the best available location: the freshest last-known fix across providers,
     * falling back to a single live update if none is cached. Null if unavailable.
     */
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        lastKnown(lm)?.let { return it }
        return requestSingle(lm)
    }

    private fun lastKnown(lm: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { p ->
            runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun requestSingle(lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        return suspendCancellableCoroutine { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            runCatching {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
    }

    /** Straight-line distance in metres between two coordinates. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }
}
