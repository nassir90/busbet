package iompar.mpts.ie

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
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

@Composable
fun StopMap(
    lat: Double,
    lon: Double,
    stopCode: String? = null,
    /** How far ahead of each bus to draw its route line, in metres. */
    lineAheadM: Int = DEFAULT_ROUTE_LINE_DISTANCE_M,
    vehicles: List<VehiclePosition> = emptyList(),
    /** Road geometry per trip id. Empty when route lines are off. */
    shapes: Map<String, List<ShapePoint>> = emptyMap(),
    /** Minutes since midnight, ticking. Drives the dimming of buses outside their window. */
    nowMins: Int,
    modifier: Modifier = Modifier,
) {
    val isDark  = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val tint    = primary.copy(alpha = 0.14f)
    val pinArgb = primary.toArgb()
    // Text on the marker fill. The palette already names the right colour for this: the default
    // theme's dark mode uses a light lavender primary (0xFFD0BCFF), where hardcoded white is
    // unreadable, and onPinArgb is 0xFF381E72.
    val onPinArgb = MaterialTheme.colorScheme.onPrimary.toArgb()
    // Buses outside their scheduled window are dimmed rather than hidden: still there, visibly
    // not running.
    val busFill = { v: VehiclePosition ->
        if (runStateOf(v, nowMins) == RunState.RUNNING) pinArgb else dimmed(pinArgb)
    }
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
                    icon      = StopSquareDrawable(pinArgb, onPinArgb, density, stopCode)
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

            // Route lines: only the stretch the bus is about to cover, fading out ahead.
            // Rendering lives in MapCommon so the trip screen draws lines the same way.
            val shapePrefix = "$SHAPE_TAG:"
            val existingShapes = map.overlays
                .filterIsInstance<Polyline>()
                .filter { it.id?.startsWith(shapePrefix) == true }
                .associateBy { it.id!! }
            val wantedShapeIds = HashSet<String>()
            val toAdd = mutableListOf<Polyline>()

            for ((tripId, points) in shapes) {
                if (points.isEmpty()) continue
                // Without a vehicle position there's nothing to anchor the run to, so skip it
                // rather than drawing the whole cross-city trip.
                val v = vehicles.firstOrNull { it.tripId == tripId } ?: continue
                // Only the stretch between the bus and this stop — the part of the journey the
                // user is actually waiting on. Nothing before the bus, nothing past the stop.
                val run = routeBetween(points, v.lat, v.lon, lat, lon)
                if (run.size < 2) continue
                renderFadedLine(
                    map = map,
                    idPrefix = "$shapePrefix$tripId",
                    points = run,
                    colorArgb = if (runStateOf(v, nowMins) == RunState.RUNNING) pinArgb else dimmed(pinArgb),
                    // Fades as it approaches the stop. Flat 1f here removes not just the fade
                    // but the width taper and soft cap with it, which reads as a blunt slab.
                    solidFraction = SOLID_FRACTION,
                    existing = existingShapes,
                    wantedIds = wantedShapeIds,
                    toAdd = toAdd,
                )
            }

            existingShapes.forEach { (id, line) ->
                if (id !in wantedShapeIds) map.overlays.remove(line)
            }
            map.overlays.addAll(toAdd)

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

            // Add new vehicles, update existing ones in place. The icon is only rebuilt when its
            // inputs change — a bus that merely moved needs the position set, nothing more.
            for (v in vehicles) {
                val id     = "$prefix${v.tripId}"
                val point  = GeoPoint(v.lat, v.lon)
                val fill   = busFill(v)
                val iconKey = busIconKey(v.routeShortName, fill, onPinArgb, v.bearing)
                val marker = existing[id] ?: Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title      = null
                    infoWindow = null
                    this.id    = id
                    map.overlays.add(this)
                }
                marker.position = point
                setMarkerIcon(marker, iconKey) {
                    BusMarkerDrawable(v.routeShortName, fill, onPinArgb, v.bearing, density)
                }
            }

            applyOverlayOrder(map)

            map.invalidate()
        },
        // osmdroid holds a tile cache and downloader threads per MapView and expects onDetach() to
        // release them. Nothing here ever called it, and every stop or trip screen opened built a
        // fresh MapView, so the cost accumulated for the life of the process.
        onRelease = { it.onDetach() },
        modifier = modifier
            .clipToBounds()
            .graphicsLayer { }
            .drawWithContent {
                drawContent()
                drawRect(color = tint, blendMode = BlendMode.SrcOver)
            },
    )
}

