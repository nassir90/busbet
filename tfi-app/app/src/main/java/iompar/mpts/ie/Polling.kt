package iompar.mpts.ie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId

/** Every live board in the app refreshes on this cadence. */
const val POLL_INTERVAL_MS = 30_000L

/**
 * Runs [block] immediately and then every [intervalMs], but only while the app is actually in
 * front of the user.
 *
 * A bare `LaunchedEffect { while (true) { … ; delay(30_000) } }` keeps running when the activity
 * is merely stopped — Compose disposes the composition when the view detaches from the window,
 * which happens on destroy, not on backgrounding. So every board the user had open went on
 * fetching every 30 seconds with the screen off until the process was killed.
 *
 * `repeatOnLifecycle` cancels the loop at STOP and starts a fresh one at START, which also means
 * returning to the app refetches immediately rather than showing whatever was on screen until the
 * next tick came round.
 *
 * Pass [intervalMs] as null for a one-shot that still respects the lifecycle (used where the data
 * is static, e.g. a board pinned to a historical instant).
 */
@Composable
fun PollEffect(
    vararg keys: Any?,
    intervalMs: Long? = POLL_INTERVAL_MS,
    block: suspend () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // keys is a vararg array — a fresh instance each recomposition, and arrays compare by
    // identity, so it can't be used as a LaunchedEffect key directly. The list copy compares
    // structurally, which is the intent.
    val keyList = keys.toList()
    androidx.compose.runtime.LaunchedEffect(keyList, intervalMs, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                }
                if (intervalMs == null) break
                delay(intervalMs)
            }
        }
    }
}

/**
 * Minutes since midnight, re-emitted as the clock ticks over.
 *
 * Screens used to read `LocalTime.now()` straight through during composition. That isn't snapshot
 * state, so nothing recomposed when the minute changed: "3 mins" stayed on screen until some
 * unrelated state happened to invalidate the composable, and then jumped. This reads once and then
 * updates on the minute boundary, so the countdown ticks on its own.
 *
 * [zone] defaults to [SERVICE_ZONE] because nearly every caller is subtracting this from a time
 * the backend sent, which is always agency-local. Pass the device zone only for the few things
 * that are about the user's own day rather than the timetable's.
 */
@Composable
fun rememberNowMinutes(zone: ZoneId = SERVICE_ZONE): Int {
    val now by produceState(initialValue = LocalTime.now(zone).let { it.hour * 60 + it.minute }, zone) {
        while (true) {
            val time = LocalTime.now(zone)
            value = time.hour * 60 + time.minute
            // Land just after the next minute boundary rather than drifting on a fixed 60s delay.
            delay(60_000L - (time.second * 1000L + time.nano / 1_000_000L) + 250L)
        }
    }
    return now
}

/**
 * Minutes since midnight for a board, honouring a time-travel pin.
 *
 * When pinned to a historical instant the value is fixed — the board is showing a frozen moment,
 * so a ticking clock would be wrong as well as wasteful.
 */
@Composable
fun rememberBoardMinutes(pinnedSec: Long?): Int {
    val live = rememberNowMinutes()
    return remember(pinnedSec, live) {
        pinnedSec?.let { serviceMinutesAt(it) } ?: live
    }
}

/**
 * Departures for several stops, in one request where the backend supports it.
 *
 * The batch endpoint is newer than the app's configurable backend URL, and Settings lets anyone
 * point the app at an older deployment — which answers `/departures?codes=` with a 404. So the
 * first failure downgrades to concurrent per-stop calls and stays there, rather than paying a
 * doomed round trip on every poll.
 */
object Boards {
    @Volatile private var batchSupported = true

    /**
     * @param boards the stops that returned a board
     * @param missing stop codes the timetable doesn't have — distinct from "hasn't loaded yet",
     *   so a favourite whose stop was dropped in a GTFS reload can say so instead of sitting on
     *   "Loading…" forever.
     */
    data class Result(
        val boards: Map<String, DeparturesResponse> = emptyMap(),
        val missing: Set<String> = emptySet(),
    )

    suspend fun fetch(codes: List<String>, timeSec: Long?): Result {
        if (codes.isEmpty()) return Result()

        if (batchSupported) {
            try {
                val resp = Api.service().departuresBatch(codes.joinToString(","), timeSec)
                return Result(resp.results, resp.missing.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e
                android.util.Log.i("tfi", "backend has no batch departures endpoint; using per-stop calls")
                batchSupported = false
            }
        }

        // Concurrently, so the fallback is still one round trip's latency rather than N.
        val outcomes = coroutineScope {
            codes.map { code ->
                async {
                    code to runCatching { Api.service().departures(code, timeSec) }
                        .fold(
                            onSuccess = { it },
                            onFailure = { e ->
                                if (e is CancellationException) throw e
                                // A 404 here is the same "no such stop" the batch reports; any
                                // other failure is transient and shouldn't be called missing.
                                if (e is retrofit2.HttpException && e.code() == 404) MISSING else null
                            },
                        )
                }
            }.awaitAll()
        }
        return Result(
            boards = outcomes.mapNotNull { (c, b) -> if (b != null && b !== MISSING) c to b else null }.toMap(),
            missing = outcomes.mapNotNull { (c, b) -> if (b === MISSING) c else null }.toSet(),
        )
    }

    /** Sentinel for "the server says this stop doesn't exist", distinct from a transient failure. */
    private val MISSING = DeparturesResponse(Stop("", "", ""), emptyList())
}

/**
 * Process-wide cache of "which routes serve this stop".
 *
 * Static GTFS: it changes when a new timetable is loaded, not while the app is open. Three screens
 * ask for it (the stop board's route filter, the notification sheet, and the home screen's history
 * rows), and none of them used to share a result, so the same lookup went out repeatedly.
 *
 * Failures are cached as an empty list too. Without that, the home screen's per-history-row lookup
 * retried a failing stop every time the row re-entered composition.
 */
object StopRoutesCache {
    private const val MAX_ENTRIES = 256

    private val map = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) =
            size > MAX_ENTRIES
    }

    @Synchronized
    private fun cached(code: String): List<String>? = map[code]

    @Synchronized
    private fun store(code: String, routes: List<String>) {
        map[code] = routes
    }

    /** Routes serving [code], from cache when known. Never throws; an error caches as empty. */
    suspend fun get(code: String): List<String> {
        cached(code)?.let { return it }
        val routes = try {
            Api.service().stopRoutes(code).sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("tfi", "stop-routes $code failed", e)
            emptyList()
        }
        store(code, routes)
        return routes
    }
}
