package net.uzoukwu.tfiapp

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private fun cartoTiles(style: String) = XYTileSource(
    "CartoDB.$style",
    1, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/${style}/",
        "https://b.basemaps.cartocdn.com/${style}/",
        "https://c.basemaps.cartocdn.com/${style}/",
        "https://d.basemaps.cartocdn.com/${style}/",
    ),
    "© CartoDB © OpenStreetMap contributors",
)

private val CARTO_DARK  = cartoTiles("dark_all")
private val CARTO_LIGHT = cartoTiles("light_all")

private const val STOP_MARKER_TAG    = "stop"
private const val VEHICLE_MARKER_TAG = "vehicle"

@Composable
fun StopMap(
    lat: Double,
    lon: Double,
    vehicles: List<VehiclePosition> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val isDark  = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val tint    = primary.copy(alpha = 0.14f)
    val pinArgb = primary.toArgb()
    val tiles   = if (isDark) CARTO_DARK else CARTO_LIGHT

    AndroidView(
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(tiles)
                setMultiTouchControls(true)
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                )
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(lat, lon))

                // Stop marker
                val density = context.resources.displayMetrics.density
                overlays.add(Marker(this).apply {
                    position  = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon      = StopPinDrawable(pinArgb, density)
                    title     = null
                    infoWindow = null
                    id = STOP_MARKER_TAG
                })
            }
        },
        update = { map ->
            val density = map.context.resources.displayMetrics.density

            // Only swap the tile source when the style actually changed; calling this
            // unconditionally triggers a full tile reload (a visible flash) every update.
            if (map.tileProvider.tileSource !== tiles) {
                map.setTileSource(tiles)
            }

            // Stop marker: move it in place rather than rebuilding the drawable each frame.
            map.overlays
                .filterIsInstance<Marker>()
                .firstOrNull { it.id == STOP_MARKER_TAG }
                ?.let {
                    val p = GeoPoint(lat, lon)
                    if (it.position != p) it.position = p
                }

            // Vehicle markers: diff by id and mutate in place instead of clearing and
            // re-adding every poll. Tearing them all down is what makes the buses blink.
            val prefix   = "$VEHICLE_MARKER_TAG:"
            val existing = map.overlays
                .filterIsInstance<Marker>()
                .filter { it.id?.startsWith(prefix) == true }
                .associateBy { it.id!! }
            val wantedIds = vehicles.map { "$prefix${it.tripId}" }.toHashSet()

            // Drop markers for vehicles that are gone.
            existing.forEach { (id, marker) ->
                if (id !in wantedIds) map.overlays.remove(marker)
            }

            // Add new vehicles, update existing ones in place.
            for (v in vehicles) {
                val id     = "$prefix${v.tripId}"
                val point  = GeoPoint(v.lat, v.lon)
                val marker = existing[id]
                if (marker == null) {
                    map.overlays.add(Marker(map).apply {
                        position  = point
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon      = BusMarkerDrawable(v.routeShortName, pinArgb, v.bearing, density)
                        title     = null
                        infoWindow = null
                        this.id   = id
                    })
                } else {
                    marker.position = point
                    marker.icon     = BusMarkerDrawable(v.routeShortName, pinArgb, v.bearing, density)
                }
            }

            map.invalidate()
        },
        modifier = modifier
            .clipToBounds()
            .graphicsLayer { }
            .drawWithContent {
                drawContent()
                drawRect(color = tint, blendMode = BlendMode.SrcOver)
            },
    )
}

// Stop pin: same radius and border as bus markers, no label.
private class StopPinDrawable(private val fillColor: Int, density: Float) : Drawable() {
    private val radius = 10f * density
    private val fill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = 0x66000000
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val intrinsic = (radius * 2f + 4f).toInt()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, stroke)
    }

    override fun setAlpha(alpha: Int)             { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth()  = intrinsic
    override fun getIntrinsicHeight() = intrinsic
}
