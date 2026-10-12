package iompar.mpts.ie

import android.graphics.DashPathEffect
import android.graphics.drawable.GradientDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val JOURNEY_TAG = "journey"

/**
 * One itinerary on the map: vehicle legs solid in the palette's primary, walks dashed, a dot at
 * each end. Framed once per itinerary, after which the map is the user's to pan.
 */
@Composable
fun JourneyMap(itinerary: Itinerary, modifier: Modifier = Modifier) {
    val isDark = isAppInDarkTheme()
    val lineArgb = MaterialTheme.colorScheme.primary.toArgb()
    val walkArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val endArgb = MaterialTheme.colorScheme.tertiary.toArgb()
    val mapView = rememberMapView {
        styleBaseMap(this, isDark)
        // Open over the journey: osmdroid's default view is 0°N 0°E, whose tiles would otherwise
        // be fetched for the frame before the itinerary is framed.
        val start = itinerary.legs.first().from
        controller.setZoom(12.0)
        controller.setCenter(GeoPoint(start.lat, start.lon))
    }

    AndroidView(
        factory = { mapView },
        update = { map ->
            styleBaseMap(map, isDark)
            val density = map.context.resources.displayMetrics.density
            map.overlays.removeAll { (it as? Polyline)?.id == JOURNEY_TAG || (it as? Marker)?.id == JOURNEY_TAG }

            val all = mutableListOf<GeoPoint>()
            for (leg in itinerary.legs) {
                val pts = leg.geometry.mapNotNull { p -> if (p.size >= 2) GeoPoint(p[0], p[1]) else null }
                    .ifEmpty { listOf(GeoPoint(leg.from.lat, leg.from.lon), GeoPoint(leg.to.lat, leg.to.lon)) }
                all += pts
                map.overlays.add(Polyline(map).apply {
                    id = JOURNEY_TAG
                    setPoints(pts)
                    infoWindow = null
                    outlinePaint.isAntiAlias = true
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    if (leg.isWalk) {
                        outlinePaint.color = walkArgb
                        outlinePaint.strokeWidth = 3f * density
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
                    } else {
                        outlinePaint.color = lineArgb
                        outlinePaint.strokeWidth = 5f * density
                    }
                })
            }
            val first = itinerary.legs.first().from
            val last = itinerary.legs.last().to
            for ((p, fill) in listOf(first to endArgb, last to lineArgb)) {
                map.overlays.add(Marker(map).apply {
                    id = JOURNEY_TAG
                    position = GeoPoint(p.lat, p.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(fill)
                        setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                        setSize((14 * density).toInt(), (14 * density).toInt())
                    }
                })
            }
            applyOverlayOrder(map)

            val key = itinerary.hashCode()
            if (map.tag != key && all.size >= 2) {
                map.tag = key
                val box = BoundingBox(
                    all.maxOf { it.latitude }, all.maxOf { it.longitude },
                    all.minOf { it.latitude }, all.minOf { it.longitude },
                )
                map.post {
                    map.zoomToBoundingBox(box.increaseByScale(1.25f), false)
                    // Whole levels only: see rememberMapView on why fractional zoom looks blurry.
                    map.controller.setZoom(kotlin.math.floor(map.zoomLevelDouble))
                }
            }
            map.invalidate()
        },
        modifier = modifier.clipToBounds(),
    )
}

/**
 * Full-width map with a pin fixed at its centre: the user pans the map under the pin and confirms.
 * Used to save a place that isn't a stop, like a friend's house.
 */
@Composable
fun PinPickerMap(startLat: Double, startLon: Double, onCentreChanged: (Double, Double) -> Unit, modifier: Modifier = Modifier) {
    val isDark = isAppInDarkTheme()
    val mapView = rememberMapView {
        styleBaseMap(this, isDark)
        controller.setZoom(16.0)
        controller.setCenter(GeoPoint(startLat, startLon))
        addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                mapCenter.let { onCentreChanged(it.latitude, it.longitude) }
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
        })
    }
    AndroidView(
        factory = { mapView },
        update = { map -> styleBaseMap(map, isDark) },
        modifier = modifier.clipToBounds(),
    )
}
