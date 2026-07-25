package iompar.mpts.ie

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
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

val CARTO_DARK: XYTileSource = cartoTiles("dark_all")
val CARTO_LIGHT: XYTileSource = cartoTiles("light_all")

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
    return when {
        first != null && nowMins < first -> RunState.NOT_DEPARTED
        last != null && nowMins > last -> RunState.FINISHED
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
 * osmdroid draws overlays in list order and every diff appends, so without this the z-order
 * depends on whatever sequence things happened to be added in — lines could end up over the
 * buses one frame and under them the next. Lines, then vehicles, then stops on top.
 */
fun applyOverlayOrder(map: MapView) {
    val rank = { o: Overlay ->
        when {
            o is Polyline -> 0
            o is Marker && o.id == STOP_MARKER_TAG -> 2
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
