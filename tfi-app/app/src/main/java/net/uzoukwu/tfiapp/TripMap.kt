package net.uzoukwu.tfiapp

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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The route of a single trip, drawn solid up to [anchorLat]/[anchorLon] — the stop you entered
 * from — and fading out beyond it.
 *
 * The mirror of the stop map, which anchors at a bus and fades forward. Only one trip is drawn,
 * so the overlapping-lines problem that makes route lines opt-in on the stop map doesn't apply.
 */
@Composable
fun TripMap(
    shape: List<ShapePoint>,
    anchorLat: Double?,
    anchorLon: Double?,
    anchorLabel: String? = null,
    /** Live position of this trip's bus, if it has one running right now. */
    vehicle: VehiclePosition? = null,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val tint = primary.copy(alpha = 0.14f)
    val lineArgb = primary.toArgb()
    val onPinArgb = MaterialTheme.colorScheme.onPrimary.toArgb()
    val tiles = if (isDark) CARTO_DARK else CARTO_LIGHT

    AndroidView(
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(tiles)
                setMultiTouchControls(true)
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                )
            }
        },
        update = { map ->
            if (map.tileProvider.tileSource !== tiles) map.setTileSource(tiles)
            if (shape.size < 2) return@AndroidView

            val density = map.context.resources.displayMetrics.density

            // The bus, the line from it to the stop, and a fade — nothing before the bus and
            // nothing past the stop. The route-line length setting deliberately doesn't apply
            // here: it exists to control crowding where several routes are drawn at once.
            val nowMins = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            val state = vehicle?.let { runStateOf(it, nowMins) }
            val departed = state == RunState.RUNNING
            val run = if (departed && anchorLat != null && anchorLon != null) {
                routeBetween(shape, vehicle!!.lat, vehicle.lon, anchorLat, anchorLon)
            } else {
                emptyList()
            }

            val existing = map.overlays
                .filterIsInstance<Polyline>()
                .filter { it.id?.startsWith("$SHAPE_TAG:") == true }
                .associateBy { it.id!! }
            val wanted = HashSet<String>()
            val toAdd = mutableListOf<Polyline>()

            if (run.size >= 2) {
                renderFadedLine(
                    map, "$SHAPE_TAG:run", run, lineArgb,
                    solidFraction = SOLID_FRACTION, existing = existing, wantedIds = wanted, toAdd = toAdd,
                )
            }
            existing.forEach { (id, line) -> if (id !in wanted) map.overlays.remove(line) }
            map.overlays.addAll(toAdd)

            // The anchor stop, drawn the same way as on the stop map.
            if (anchorLat != null && anchorLon != null) {
                val marker = map.overlays.filterIsInstance<Marker>()
                    .firstOrNull { it.id == STOP_MARKER_TAG }
                    ?: Marker(map).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        id = STOP_MARKER_TAG
                        map.overlays.add(this)
                    }
                marker.position = GeoPoint(anchorLat, anchorLon)
                marker.icon = StopSquareDrawable(lineArgb, onPinArgb, density, anchorLabel)
            }

            // This trip's bus. Same marker as the stop map, mutated in place so it moves
            // rather than being torn down and re-added on each poll.
            val busId = "$VEHICLE_MARKER_TAG:trip"
            val existingBus = map.overlays.filterIsInstance<Marker>().firstOrNull { it.id == busId }
            if (vehicle == null) {
                existingBus?.let { map.overlays.remove(it) }
            } else {
                val bus = existingBus ?: Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    id = busId
                    map.overlays.add(this)
                }
                bus.position = GeoPoint(vehicle.lat, vehicle.lon)
                bus.icon = BusMarkerDrawable(
                    vehicle.routeShortName,
                    if (departed) lineArgb else dimmed(lineArgb),
                    onPinArgb, vehicle.bearing, density,
                )
            }

            applyOverlayOrder(map)

            // Frame the drawn route rather than centring on a point — a trip spans the city, so
            // a fixed zoom would either crop it or bury it. Done once: re-framing on every
            // update would fight the user's own panning.
            if (map.tag != shape.hashCode()) {
                map.tag = shape.hashCode()
                val drawn = if (run.size >= 2) run else shape
                val box = BoundingBox(
                    drawn.maxOf { it.lat }, drawn.maxOf { it.lon },
                    drawn.minOf { it.lat }, drawn.minOf { it.lon },
                )
                map.post { map.zoomToBoundingBox(box.increaseByScale(1.15f), false) }
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
