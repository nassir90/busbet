package iompar.mpts.ie.plan

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The on-device half of the route-planning experiment (gtfsr-stop-times/docs/ROUTE_PLANNING.md): the backend's
 * RAPTOR (gtfsr-stop-times/src/plan/raptor.ts) ported line for line, over the binary timetable
 * scripts/export-ondevice.ts writes. It lives in the test sources because it is a measurement,
 * not a feature: the app plans on the server.
 */
class OnDeviceTimetable private constructor(buf: ByteBuffer) {
    val epochZero: Long
    val stopLat: IntArray
    val stopLon: IntArray
    val patternStopsStart: IntArray
    val patternStops: IntArray
    val patternTripsStart: IntArray
    val patternRoute: IntArray
    val tripTimesStart: IntArray
    val arr: IntArray
    val dep: IntArray
    val stopPatternsStart: IntArray
    val stopPatterns: IntArray
    val stopPatternPos: IntArray
    val transfersStart: IntArray
    val transferTo: IntArray
    val transferSecs: IntArray
    val routeType: IntArray
    val stopName: List<String>

    val nStops get() = stopLat.size
    val nPatterns get() = patternRoute.size

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.ISO_8859_1) == "IOMP") { "not an Iompar timetable" }
        require(buf.int == 1) { "unknown version" }
        epochZero = buf.long
        fun ints(): IntArray = IntArray(buf.int).also { buf.asIntBuffer().get(it); buf.position(buf.position() + it.size * 4) }
        fun strings(): List<String> {
            val bytes = ByteArray(buf.int).also { buf.get(it) }
            return String(bytes, Charsets.UTF_8).split('\u0000')
        }
        stopLat = ints(); stopLon = ints()
        patternStopsStart = ints(); patternStops = ints(); patternTripsStart = ints(); patternRoute = ints()
        tripTimesStart = ints(); arr = ints(); dep = ints()
        stopPatternsStart = ints(); stopPatterns = ints(); stopPatternPos = ints()
        transfersStart = ints(); transferTo = ints(); transferSecs = ints()
        routeType = ints()
        stopName = strings()
    }

    companion object {
        /** Memory-maps the file, as a phone would after downloading it. */
        fun load(file: File): OnDeviceTimetable =
            FileChannel.open(file.toPath()).use { ch ->
                OnDeviceTimetable(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }
    }
}

object OnDeviceRaptor {
    const val INF = 0x3fffffff
    private const val VIA_ACCESS: Byte = 1
    private const val VIA_TRANSIT: Byte = 2
    private const val VIA_TRANSFER: Byte = 3

    class Access(val stop: Int, val time: Int)
    class Egress(val stop: Int, val walk: Int)

    /** Best arrival at the destination per round, as raptor.ts's targetArrival. */
    fun run(
        tt: OnDeviceTimetable,
        access: List<Access>,
        egress: List<Egress>,
        maxRounds: Int = 5,
        minChange: Int = 120,
        walkSlack: Int = 60,
    ): IntArray {
        val n = tt.nStops
        val best = IntArray(n) { INF }
        val egressWalk = IntArray(n) { -1 }
        for (e in egress) if (egressWalk[e.stop] < 0 || e.walk < egressWalk[e.stop]) egressWalk[e.stop] = e.walk
        val targetArrival = IntArray(maxRounds + 1) { INF }
        var targetBound = INF

        val labels = ArrayList<IntArray>()
        val via = ArrayList<ByteArray>()
        val viaA = ArrayList<IntArray>()
        val viaB = ArrayList<IntArray>()
        labels += IntArray(n) { INF }; via += ByteArray(n); viaA += IntArray(n) { -1 }; viaB += IntArray(n) { -1 }
        var marked = BooleanArray(n)
        var anyMarked = false
        for (a in access) {
            if (a.time < labels[0][a.stop]) {
                labels[0][a.stop] = a.time; best[a.stop] = a.time; via[0][a.stop] = VIA_ACCESS
                marked[a.stop] = true; anyMarked = true
            }
        }

        val queuePos = IntArray(tt.nPatterns) { -1 }
        val queued = IntArray(tt.nPatterns)
        for (k in 1..maxRounds) {
            if (!anyMarked) break
            val prev = labels[k - 1]
            val prevVia = via[k - 1]
            val cur = prev.copyOf(); val curVia = prevVia.copyOf()
            val curA = viaA[k - 1].copyOf(); val curB = viaB[k - 1].copyOf()
            labels += cur; via += curVia; viaA += curA; viaB += curB

            var nq = 0
            for (s in 0 until n) {
                if (!marked[s]) continue
                for (j in tt.stopPatternsStart[s] until tt.stopPatternsStart[s + 1]) {
                    val p = tt.stopPatterns[j]
                    val pos = tt.stopPatternPos[j]
                    if (queuePos[p] < 0) { queued[nq++] = p; queuePos[p] = pos }
                    else if (pos < queuePos[p]) queuePos[p] = pos
                }
            }
            val improved = BooleanArray(n)
            var anyImproved = false
            for (qi in 0 until nq) {
                val p = queued[qi]
                val startPos = queuePos[p]
                queuePos[p] = -1
                val ps = tt.patternStopsStart[p]
                val len = tt.patternStopsStart[p + 1] - ps
                var trip = -1; var tripBase = 0; var boardPos = -1
                for (i in startPos until len) {
                    val s = tt.patternStops[ps + i]
                    if (trip >= 0) {
                        val a = tt.arr[tripBase + i]
                        if (a < best[s] && a < targetBound) {
                            cur[s] = a; best[s] = a; curVia[s] = VIA_TRANSIT; curA[s] = trip; curB[s] = boardPos
                            improved[s] = true; anyImproved = true
                            val w = egressWalk[s]
                            if (w >= 0 && a + w < targetArrival[k]) {
                                targetArrival[k] = a + w
                                targetBound = min(targetBound, a + w)
                            }
                        }
                    }
                    val reach = prev[s]
                    if (reach >= INF) continue
                    val pv = prevVia[s]
                    val ready = reach + when (pv) { VIA_TRANSIT -> minChange; VIA_TRANSFER -> walkSlack; else -> 0 }
                    if (trip >= 0 && ready > tt.dep[tripBase + i]) continue
                    val t = earliestTrip(tt, p, i, ready)
                    if (t >= 0 && (trip < 0 || tt.dep[tt.tripTimesStart[t] + i] < tt.dep[tripBase + i])) {
                        trip = t; tripBase = tt.tripTimesStart[t]; boardPos = i
                    }
                }
            }

            val nextMarked = BooleanArray(n)
            anyMarked = false
            for (s in 0 until n) {
                if (!improved[s]) continue
                nextMarked[s] = true; anyMarked = true
                val base = cur[s]
                for (j in tt.transfersStart[s] until tt.transfersStart[s + 1]) {
                    val s2 = tt.transferTo[j]
                    val a = base + tt.transferSecs[j]
                    if (a < best[s2] && a < targetBound) {
                        cur[s2] = a; best[s2] = a; curVia[s2] = VIA_TRANSFER; curA[s2] = s; curB[s2] = -1
                        nextMarked[s2] = true
                        val w = egressWalk[s2]
                        if (w >= 0 && a + w < targetArrival[k]) {
                            targetArrival[k] = a + w
                            targetBound = min(targetBound, a + w)
                        }
                    }
                }
            }
            marked = nextMarked
            if (!anyImproved) break
        }
        return targetArrival
    }

    private fun earliestTrip(tt: OnDeviceTimetable, p: Int, pos: Int, time: Int): Int {
        val lo0 = tt.patternTripsStart[p]
        val hi0 = tt.patternTripsStart[p + 1]
        var lo = lo0; var hi = hi0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tt.dep[tt.tripTimesStart[mid] + pos] < time) lo = mid + 1 else hi = mid
        }
        return if (lo < hi0) lo else -1
    }

    /** Served stops within [radius] metres, by linear scan: 10k haversines is under a millisecond. */
    fun nearby(tt: OnDeviceTimetable, lat: Double, lon: Double, radius: Double): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (s in 0 until tt.nStops) {
            if (tt.stopPatternsStart[s + 1] == tt.stopPatternsStart[s]) continue
            val m = haversine(lat, lon, tt.stopLat[s] / 1e6, tt.stopLon[s] / 1e6)
            // Walk model as geo.ts: detour 1.3 at 1.3 m/s.
            if (m <= radius) out += s to (m * 1.3 / 1.3).roundToInt()
        }
        return out
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = Math.PI / 180
        val a = sin((lat2 - lat1) * r / 2).let { it * it } +
            cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
        return 2 * 6_371_008.8 * asin(min(1.0, sqrt(a)))
    }
}
