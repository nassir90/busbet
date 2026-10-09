package iompar.mpts.ie

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import java.io.File
import java.util.WeakHashMap
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/**
 * Shared map machinery: tiles, polyline geometry, the faked gradient, and overlay ordering.
 *
 * Extracted from StopMap when the trip screen needed the same rendering. The geometry here is
 * the part worth not duplicating — it was arrived at by measuring real data, and the reasons are
 * recorded on each function.
 */

/**
 * OpenStreetMap's own raster tiles, through osmdroid's built-in source for them.
 *
 * CARTO stopped serving keyless tiles in September 2026: every tile now comes back stamped "API
 * KEY REQUIRED". OSM's tile servers need no key, and their usage policy admits a published app
 * on conditions MAPNIK's own TileSourcePolicy already enforces — a distinct User-Agent (osmdroid
 * sends `<package>/<versionCode>`), honouring cache headers, at most two connections, and no
 * bulk or preventive downloading (https://operations.osmfoundation.org/policies/tiles/). So no
 * "download this area for offline" on top of it: the policy forbids exactly that.
 *
 * OpenFreeMap is keyless too, but vector-only, which osmdroid can't draw.
 */
val BASE_TILES: OnlineTileSourceBase = TileSourceFactory.MAPNIK

private fun linear(scale: Float, offset: Float) = ColorMatrix(floatArrayOf(
    scale, 0f, 0f, 0f, offset,
    0f, scale, 0f, 0f, offset,
    0f, 0f, scale, 0f, offset,
    0f, 0f, 0f, 1f, 0f,
))

/*
 * OSM has a single style, so both themes are derived from it with a colour filter. Light mode is
 * muted because the standard style is busy, and its blue bus-stop dots read as the app's own
 * markers. Dark mode inverts, rotates the hue back round so water stays blue and parks green, then
 * mutes and dims. Both were tuned by rendering these matrices over real tiles of Aston Quay, to
 * land near the CARTO Positron / Dark Matter look the app had before.
 *
 * A filter rather than a second tile set also means a theme switch repaints from tiles already
 * cached instead of downloading the city again.
 */
private val LIGHT_TILE_FILTER = ColorMatrixColorFilter(ColorMatrix().apply {
    setSaturation(0.2f)
    postConcat(linear(0.9f, 26f))
})

private val DARK_TILE_FILTER = ColorMatrixColorFilter(linear(-1f, 255f).apply {
    // A 180° hue rotation: the CSS hue-rotate() matrix with cos = -1, sin = 0.
    postConcat(ColorMatrix(floatArrayOf(
        -0.574f, 1.430f,  0.144f, 0f, 0f,
         0.426f, 0.430f,  0.144f, 0f, 0f,
         0.426f, 1.430f, -0.856f, 0f, 0f,
         0f,     0f,      0f,     1f, 0f,
    )))
    postConcat(ColorMatrix().apply { setSaturation(0.2f) })
    postConcat(linear(0.72f, 6f))
})

private val styledDark = WeakHashMap<MapView, Boolean>()

/** Puts [map] on [BASE_TILES], styled for the theme. Cheap enough to call on every update. */
fun styleBaseMap(map: MapView, isDark: Boolean) {
    // Setting the source unconditionally triggers a full tile reload — a visible flash.
    if (map.tileProvider.tileSource !== BASE_TILES) map.setTileSource(BASE_TILES)
    if (styledDark[map] == isDark) return
    styledDark[map] = isDark
    map.overlayManager.tilesOverlay.setColorFilter(if (isDark) DARK_TILE_FILTER else LIGHT_TILE_FILTER)
    map.overlays.filterIsInstance<CopyrightOverlay>().forEach {
        it.setTextColor(if (isDark) 0xB3FFFFFF.toInt() else 0xB3000000.toInt())
    }
    map.invalidate()
}

/**
 * osmdroid was being given none of the three things it asks for: a tile cache it can find on disk,
 * onResume/onPause in step with the host lifecycle, and a MapView that survives recomposition.
 *
 * Without the cache configuration it falls back to a location it may not be able to write, so every
 * return to the app repaints from an empty cache; without the lifecycle calls its tile downloader
 * is never told to stand down and come back. Either way the map arrives blank and fills in — the
 * flicker when navigating to and from the app (TFI-115).
 *
 * Call [rememberMapView] instead of constructing MapView inside AndroidView's factory, and drop
 * the onRelease hook: disposal is handled here.
 */
private var osmdroidConfigured = false

private fun configureOsmdroid(context: Context) {
    if (osmdroidConfigured) return
    val ctx = context.applicationContext
    Configuration.getInstance().apply {
        // Any SharedPreferences will do; osmdroid only uses it to persist its own settings. Using
        // a named file rather than the default keeps it out of the app's own preferences.
        load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        userAgentValue = ctx.packageName
        // Inside the app's own storage: no permissions needed, and it is removed on uninstall,
        // which the privacy policy's "uninstalling removes them" claim depends on.
        osmdroidBasePath = File(ctx.filesDir, "osmdroid")
        osmdroidTileCache = File(ctx.filesDir, "osmdroid/tiles")
    }
    osmdroidConfigured = true
}

/**
 * A MapView that lives as long as the composable does, with its lifecycle forwarded from the host.
 * [onCreate] runs once, for setup that must happen before the first draw.
 */
@Composable
fun rememberMapView(onCreate: MapView.() -> Unit = {}): MapView {
    val context = LocalContext.current
    val map = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // OSM's licence requires attribution visible on the map itself. The text is the tile
            // source's own copyright notice; styleBaseMap colours it for the theme.
            overlays.add(CopyrightOverlay(context).apply { setTextSize(9) })
            onCreate()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE  -> map.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // osmdroid holds a tile cache and downloader threads per MapView and expects this to
            // release them. Previously AndroidView's onRelease did it; it belongs with the rest of
            // the lifecycle now.
            map.onDetach()
        }
    }
    return map
}

const val STOP_MARKER_TAG = "stop"
const val VEHICLE_MARKER_TAG = "vehicle"
const val SHAPE_TAG = "shape"

/**
 * Fraction of a run drawn at full strength before the fade begins. Fading across the whole
 * length makes a line look uniformly washed out rather than fading.
 */
const val SOLID_FRACTION = 0.55f

/** Number of decreasing-alpha strokes used to fake a gradient. */
const val FADE_STEPS = 28
const val LINE_ALPHA = 170f
const val LINE_WIDTH_START = 6f
const val LINE_WIDTH_END = 1.5f

const val M_PER_DEG_LAT = 111_320.0

/** Equirectangular metres — accurate well past the distances involved here, and cheap. */
fun metresBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val kx = Math.cos(Math.toRadians((aLat + bLat) / 2)) * M_PER_DEG_LAT
    return Math.hypot((aLon - bLon) * kx, (aLat - bLat) * M_PER_DEG_LAT)
}

/** Where a point falls on a polyline: which segment, and the interpolated position on it. */
data class Projection(val segment: Int, val lat: Double, val lon: Double, val distanceM: Double)

/**
 * Nearest point on the polyline itself, not the nearest vertex.
 *
 * Vertices average ~225m apart after decimation, so snapping to one put a bus's line up to 155m
 * from the bus, and always at or behind it (measured against live data at Pearse Street).
 */
fun projectOnto(points: List<ShapePoint>, lat: Double, lon: Double): Projection {
    var best = Projection(0, points.first().lat, points.first().lon, Double.MAX_VALUE)
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val kx = Math.cos(Math.toRadians((a.lat + b.lat) / 2)) * M_PER_DEG_LAT
        val ax = a.lon * kx; val ay = a.lat * M_PER_DEG_LAT
        val bx = b.lon * kx; val by = b.lat * M_PER_DEG_LAT
        val px = lon * kx;   val py = lat * M_PER_DEG_LAT
        val dx = bx - ax;    val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
        if (d < best.distanceM) {
            best = Projection(i, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, d)
        }
    }
    return best
}

/** The stretch of route immediately ahead of [lat]/[lon], starting exactly there. */
fun routeAhead(points: List<ShapePoint>, lat: Double, lon: Double, metresAhead: Double): List<ShapePoint> {
    if (points.size < 2) return points
    val p = projectOnto(points, lat, lon)
    val out = ArrayList<ShapePoint>()
    out.add(ShapePoint(p.lat, p.lon))
    var acc = 0.0
    var prevLat = p.lat
    var prevLon = p.lon
    var i = p.segment + 1
    while (i < points.size) {
        val d = metresBetween(prevLat, prevLon, points[i].lat, points[i].lon)
        if (acc + d >= metresAhead) {
            val f = if (d <= 0.0) 0.0 else (metresAhead - acc) / d
            out.add(ShapePoint(prevLat + (points[i].lat - prevLat) * f, prevLon + (points[i].lon - prevLon) * f))
            return out
        }
        acc += d
        out.add(points[i])
        prevLat = points[i].lat
        prevLon = points[i].lon
        i++
    }
    return out
}

/**
 * Whether a bus is between its scheduled first departure and last arrival.
 *
 * The realtime feed cannot answer this — NTA reports currentStatus IN_TRANSIT_TO and
 * currentStopSequence 0 for every vehicle, so both are constants. The schedule can.
 */
enum class RunState { NOT_DEPARTED, RUNNING, FINISHED }

fun runStateOf(v: VehiclePosition, nowMins: Int): RunState {
    val first = v.firstDeparture?.let { hhmmToMins(it) }
    val last = v.lastArrival?.let { hhmmToMins(it) }
    // Compared the short way round the clock rather than by magnitude: a trip's span is raw
    // GTFS, so an after-midnight service starts at "25:10" (1510) while `nowMins` is 30, and a
    // straight `nowMins < first` would call every such bus NOT_DEPARTED for its whole run.
    return when {
        first != null && minutesUntil(first, nowMins) > 0 -> RunState.NOT_DEPARTED
        last != null && minutesUntil(last, nowMins) < 0 -> RunState.FINISHED
        else -> RunState.RUNNING
    }
}

/** GTFS times run past 24:00 for after-midnight services, so this can exceed 1440. */
private fun hhmmToMins(t: String): Int? {
    val parts = t.split(":")
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

/** Washed-out version of [argb], for buses that aren't currently running. */
fun dimmed(argb: Int): Int = (argb and 0x00FFFFFF) or (0x66 shl 24)

/**
 * The stretch of route between two points on it, starting and ending exactly at each.
 *
 * Returns empty when [toLat]/[toLon] falls before the start point — a bus that has already
 * passed the stop has no "between" left to draw.
 */
fun routeBetween(
    points: List<ShapePoint>,
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
): List<ShapePoint> {
    if (points.size < 2) return emptyList()
    val a = projectOnto(points, fromLat, fromLon)
    val b = projectOnto(points, toLat, toLon)
    if (b.segment < a.segment) return emptyList()

    val out = ArrayList<ShapePoint>()
    out.add(ShapePoint(a.lat, a.lon))
    for (i in (a.segment + 1)..b.segment) out.add(points[i])
    out.add(ShapePoint(b.lat, b.lon))
    return out
}

/** Everything from the start of the route up to [lat]/[lon], ending exactly there. */
fun routeUpTo(points: List<ShapePoint>, lat: Double, lon: Double): List<ShapePoint> {
    if (points.size < 2) return points
    val p = projectOnto(points, lat, lon)
    val out = ArrayList<ShapePoint>(points.subList(0, p.segment + 1))
    out.add(ShapePoint(p.lat, p.lon))
    return out
}

/**
 * Re-spaces a run of points evenly by distance, interpolating along the segments.
 *
 * This is what makes the fade work at all. Shape vertices average ~225m apart after
 * Douglas-Peucker decimation, and can be ~2km apart on a straight road, so fading per-vertex
 * gave two or three steps — or one, which is just a line that stops.
 */
fun resampleEvenly(points: List<ShapePoint>, count: Int): List<ShapePoint> {
    if (points.size < 2 || count < 2) return points
    val cum = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cum[i] = cum[i - 1] + metresBetween(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
    }
    val total = cum.last()
    if (total <= 0.0) return points

    val out = ArrayList<ShapePoint>(count + 1)
    var seg = 0
    for (k in 0..count) {
        val target = total * k / count
        while (seg < points.size - 2 && cum[seg + 1] < target) seg++
        val segLen = cum[seg + 1] - cum[seg]
        val f = if (segLen <= 0.0) 0.0 else ((target - cum[seg]) / segLen).coerceIn(0.0, 1.0)
        out.add(
            ShapePoint(
                lat = points[seg].lat + (points[seg + 1].lat - points[seg].lat) * f,
                lon = points[seg].lon + (points[seg + 1].lon - points[seg].lon) * f,
            )
        )
    }
    return out
}

/**
 * Draws [points] as a run of strokes, optionally fading out over the tail.
 *
 * osmdroid gives a Polyline a single paint, so a gradient has to be approximated by consecutive
 * strokes of decreasing alpha and width. Existing strokes are mutated in place rather than
 * skipped — the ids are position-independent, so skipping them left lines behind when the thing
 * they were anchored to moved.
 *
 * @param solidFraction how much of the run stays at full strength before the fade begins
 */
fun renderFadedLine(
    map: MapView,
    idPrefix: String,
    points: List<ShapePoint>,
    colorArgb: Int,
    solidFraction: Float,
    existing: Map<String, Polyline>,
    wantedIds: MutableSet<String>,
    toAdd: MutableList<Polyline>,
) {
    if (points.size < 2) return
    val even = resampleEvenly(points, FADE_STEPS)
    if (even.size < 2) return
    val steps = even.size - 1

    for (step in 0 until steps) {
        val id = "$idPrefix:$step"
        wantedIds.add(id)
        val pts = even.subList(step, step + 2).map { GeoPoint(it.lat, it.lon) }

        val existingLine = existing[id]
        if (existingLine != null) {
            existingLine.setPoints(pts)
            continue
        }

        val t = step.toFloat() / steps
        val falloff = if (t <= solidFraction) {
            1f
        } else {
            val u = ((t - solidFraction) / (1f - solidFraction)).coerceIn(0f, 1f)
            val e = 1f - u
            e * e * (3f - 2f * e) // smoothstep: no visible kink where the fade starts
        }

        toAdd.add(
            Polyline(map).apply {
                setPoints(pts)
                outlinePaint.color = colorArgb
                outlinePaint.alpha = (LINE_ALPHA * falloff).toInt().coerceIn(0, 255)
                outlinePaint.strokeWidth = LINE_WIDTH_END + (LINE_WIDTH_START - LINE_WIDTH_END) * falloff
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                this.id = id
                infoWindow = null
            }
        )
    }
}

/**
 * Sets [marker]'s icon only when something about it actually changed.
 *
 * The update block reruns on every poll, and it used to allocate a fresh [BusMarkerDrawable] for
 * every vehicle each time — including the common case where the bus had only moved, which the
 * position assignment already handles. The identity is stashed on the marker's related-object slot
 * (unused here) rather than in a side map, so it can't drift out of sync with the overlay list.
 */
fun setMarkerIcon(marker: Marker, key: String, build: () -> android.graphics.drawable.Drawable) {
    if (marker.relatedObject == key) return
    marker.icon = build()
    marker.relatedObject = key
}

/** Identity of a bus marker's appearance — everything [BusMarkerDrawable] renders from. */
fun busIconKey(route: String, fillArgb: Int, textArgb: Int, bearing: Float?): String =
    "$route|$fillArgb|$textArgb|${bearing?.let { Math.round(it / 5f) * 5 }}"

/**
 * osmdroid draws overlays in list order and every diff appends, so without this the z-order
 * depends on whatever sequence things happened to be added in — lines could end up over the
 * buses one frame and under them the next. Lines, then vehicles, then stops, then the
 * attribution, which nothing may cover.
 */
fun applyOverlayOrder(map: MapView) {
    val rank = { o: Overlay ->
        when {
            o is Polyline -> 0
            o is Marker && o.id == STOP_MARKER_TAG -> 2
            o is CopyrightOverlay -> 3
            else -> 1
        }
    }
    val ordered = map.overlays.sortedBy(rank)
    if (ordered != map.overlays.toList()) {
        map.overlays.clear()
        map.overlays.addAll(ordered)
    }
}

/**
 * The stop, drawn as a square carrying its stop number. Square rather than round so it reads as
 * a different kind of thing from the circular bus markers without relying on colour alone.
 */
class StopSquareDrawable(
    private val fillColor: Int,
    textColor: Int,
    private val density: Float,
    private val label: String? = null,
) : Drawable() {
    private val half   = 11f * density
    private val fill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = 0x66000000
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = textColor
        textSize  = 9f * density
        typeface  = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val intrinsic = (half * 2f + 4f).toInt()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, fill)
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, stroke)
        if (!label.isNullOrEmpty()) {
            // Shrink to fit rather than overflow the square — stop codes vary in length.
            textPaint.textSize = 9f * density
            while (textPaint.measureText(label) > half * 1.8f && textPaint.textSize > 5f) {
                textPaint.textSize -= 0.5f
            }
            val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, cx, baseline, textPaint)
        }
    }

    override fun setAlpha(alpha: Int)             { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth()  = intrinsic
    override fun getIntrinsicHeight() = intrinsic
}
