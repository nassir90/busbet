package iompar.mpts.ie.plan

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Measures planning on the phone's side of the trade-off: load a day's timetable, then plan the
 * benchmark journeys, and check every answer against what the Node planner gave for the same
 * query. Skipped unless IOMPAR_ONDEVICE_TT points at a file from scripts/export-ondevice.ts.
 *
 *   IOMPAR_ONDEVICE_TT=/path/tt-20261013.bin ./gradlew testPlayDebugUnitTest --tests '*OnDeviceRaptorBenchmark*'
 *
 * Plain JVM, no Robolectric: this is the algorithm's cost, not Android's.
 */
class OnDeviceRaptorBenchmark {
    @Test fun planOnDevice() {
        val path = System.getenv("IOMPAR_ONDEVICE_TT")
        assumeTrue("IOMPAR_ONDEVICE_TT not set", path != null)
        val file = File(path!!)
        val expected = JsonParser.parseString(File(path.removeSuffix(".bin") + ".expected.json").readText()).asJsonObject

        val loadStarted = System.nanoTime()
        val tt = OnDeviceTimetable.load(file)
        val loadMs = (System.nanoTime() - loadStarted) / 1e6
        println("load: %.0f ms for %.1f MB, %d stops, %d patterns".format(loadMs, file.length() / 1048576.0, tt.nStops, tt.nPatterns))

        var mismatches = 0
        for (e in expected.getAsJsonArray("expected")) {
            val o = e.asJsonObject
            val from = o.getAsJsonArray("from"); val to = o.getAsJsonArray("to")
            val t0 = o.get("t0").asInt
            val want = o.getAsJsonArray("targetArrival").map { it.asInt }

            fun once(): IntArray {
                val access = OnDeviceRaptor.nearby(tt, from[0].asDouble, from[1].asDouble, 1000.0)
                    .map { (s, w) -> OnDeviceRaptor.Access(s, t0 + w) }
                val egress = OnDeviceRaptor.nearby(tt, to[0].asDouble, to[1].asDouble, 1000.0)
                    .map { (s, w) -> OnDeviceRaptor.Egress(s, w) }
                return OnDeviceRaptor.run(tt, access, egress)
            }
            // JIT warm-up, then the median of 20: a phone plans the second query faster than the first too.
            val cold = System.nanoTime().let { s -> once(); (System.nanoTime() - s) / 1e6 }
            repeat(10) { once() }
            val times = (1..20).map { val s = System.nanoTime(); once(); (System.nanoTime() - s) / 1e6 }.sorted()
            val got = once()

            // Coordinates in the file are rounded to 1e-6°, so a walk can differ by a second.
            val same = want.indices.all { k -> kotlin.math.abs(want[k] - got[k]) <= 2 || (want[k] >= OnDeviceRaptor.INF && got[k] >= OnDeviceRaptor.INF) }
            if (!same) mismatches++
            println("%-40s cold %6.1f ms, warm median %5.1f ms  %s".format(o.get("name").asString, cold, times[10], if (same) "matches Node" else "DIFFERS: want $want got ${got.toList()}"))
        }
        assertEquals("journeys whose arrivals differ from the Node planner", 0, mismatches)
    }
}
