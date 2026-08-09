package iompar.mpts.ie

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** A rectangle drawn on the screenshot, in fractions of the image so it survives any scaling. */
data class FeatureRequestBox(
    /**
     * Stable identity, handed out from a counter rather than the position in the list.
     *
     * It has to survive a deletion: renumbering the survivors would leave every "#2" already typed
     * into the description silently pointing at a different box.
     */
    val index: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** What the user renamed it to. Null or blank means it is still known by its [index]. */
    val label: String? = null,
)

/** What this box is called — its [label] if it has one, otherwise its number. */
val FeatureRequestBox.name: String
    get() = label?.takeIf { it.isNotBlank() } ?: index.toString()

/**
 * One filed request.
 *
 * [context] is the machine-readable half — which screen was open, its arguments, and the payload it
 * was rendering — so whoever picks the request up doesn't have to infer the app's state from the
 * pixels. [boxes] are referenced from [description] as "#1", "#2".
 */
data class FeatureRequest(
    val id: String,
    /** Epoch seconds. */
    val createdAt: Long,
    /** One line naming the screen, e.g. "Stop board — 8220DB000324". */
    val screen: String,
    /** Pretty-printed JSON; see [screenContextJson]. */
    val context: String,
    val description: String,
    val boxes: List<FeatureRequestBox> = emptyList(),
    /** File name of the annotated PNG beside the JSON, or null if the capture failed. */
    val screenshot: String? = null,
)

private val featureGson = GsonBuilder().setPrettyPrinting().create()

/**
 * Filed requests, kept as loose files (a JSON document plus its PNG) under
 * `filesDir/feature-requests`.
 *
 * Not DataStore, which every other store here uses: the screenshot has to be a real file to be
 * handed to the share sheet as a `content://` URI and to be streamed by the on-device server, and
 * splitting a request across two stores would only invite the halves to drift.
 *
 * Nothing prunes this directory. Requests leave the device through the share sheet or the on-device
 * API, and whatever files them (see the `list_feature_requests` MCP tool) deletes them on the way
 * out.
 */
class FeatureRequestStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, DIR)

    /** Newest first. An unreadable entry is skipped rather than failing the whole listing. */
    fun list(): List<FeatureRequest> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { file -> runCatching { featureGson.fromJson(file.readText(), FeatureRequest::class.java) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun get(id: String): FeatureRequest? {
        if (!isSafeId(id)) return null
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching { featureGson.fromJson(file.readText(), FeatureRequest::class.java) }.getOrNull()
    }

    /** The PNG for [id], or null when the capture failed or the request is gone. */
    fun screenshotFile(id: String): File? {
        if (!isSafeId(id)) return null
        return File(dir, "$id.png").takeIf { it.exists() }
    }

    /**
     * Writes a new request and returns it. [screenshot] is stored with [FeatureRequest.boxes]
     * already drawn on, so the PNG still says what the user meant when it's read on its own — in a
     * mail client, say, far from this JSON.
     */
    fun save(
        screen: String,
        context: String,
        description: String,
        boxes: List<FeatureRequestBox>,
        screenshot: Bitmap?,
    ): FeatureRequest {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val pngWritten = screenshot?.let { bitmap ->
            runCatching {
                FileOutputStream(File(dir, "$id.png")).use { out ->
                    bitmap.withAnnotations(boxes).compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }.isSuccess
        } ?: false
        val request = FeatureRequest(
            id = id,
            createdAt = System.currentTimeMillis() / 1000,
            screen = screen,
            context = context,
            description = description,
            boxes = boxes,
            screenshot = if (pngWritten) "$id.png" else null,
        )
        File(dir, "$id.json").writeText(featureGson.toJson(request))
        return request
    }

    fun delete(id: String) {
        if (!isSafeId(id)) return
        File(dir, "$id.json").delete()
        File(dir, "$id.png").delete()
    }

    companion object {
        const val DIR = "feature-requests"

        /** Ids come from [UUID], and they reach [get]/[delete] straight off an HTTP path. */
        private fun isSafeId(id: String) = id.matches(Regex("[A-Za-z0-9-]{1,64}"))
    }
}

// --- capture -----------------------------------------------------------------------------------

/**
 * Everything captured at the instant the gesture fired.
 *
 * Deliberately not a data class: it rides in the navigation stack, whose SaveableStateHolder slot
 * keys are built from `toString`, and the context JSON would make every key a kilobyte of screenshot
 * metadata.
 */
class FeatureRequestDraft(
    val screen: String,
    val context: String,
    val screenshot: Bitmap?,
)

/**
 * Grabs what is on screen right now.
 *
 * PixelCopy rather than replaying the view hierarchy onto a software canvas: the stop and trip
 * screens host an osmdroid MapView and the app draws edge-to-edge under the system bars, and only
 * reading back the composited window surface captures what the user actually saw. The redraw is
 * kept as a fallback because PixelCopy refuses on a window with no surface yet.
 */
fun captureWindow(activity: Activity, onResult: (Bitmap?) -> Unit) {
    val view = activity.window.decorView
    if (view.width <= 0 || view.height <= 0) return onResult(null)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(
            activity.window,
            bitmap,
            { result -> onResult(if (result == PixelCopy.SUCCESS) bitmap else redraw(activity)) },
            Handler(Looper.getMainLooper()),
        )
    } catch (e: IllegalArgumentException) {
        onResult(redraw(activity))
    }
}

private fun redraw(activity: Activity): Bitmap? = runCatching {
    val view = activity.window.decorView
    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
}.getOrNull()

/** A copy of this bitmap with [boxes] and their numbers drawn on it. */
fun Bitmap.withAnnotations(boxes: List<FeatureRequestBox>): Bitmap {
    if (boxes.isEmpty()) return this
    val out = copy(Bitmap.Config.ARGB_8888, true) ?: return this
    val canvas = Canvas(out)
    // Scaled off the image so annotations look the same on a phone screenshot as on a tablet's.
    val stroke = (minOf(out.width, out.height) / 160f).coerceAtLeast(3f)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ANNOTATION_COLOR
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ANNOTATION_COLOR
        textSize = stroke * 6f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    boxes.forEach { box ->
        val left = box.left * out.width
        val top = box.top * out.height
        canvas.drawRect(left, top, box.right * out.width, box.bottom * out.height, outline)
        // Below the top edge when the box is at the very top, so the number is never clipped away.
        val baseline = if (top > label.textSize * 1.4f) top - stroke * 2f else top + label.textSize * 1.2f
        canvas.drawText("#${box.name}", left, baseline, label)
    }
    return out
}

/** Opaque magenta: nothing in the app's palettes is close, so an annotation always reads as one. */
private const val ANNOTATION_COLOR = 0xFFE6007E.toInt()

// --- the gesture -------------------------------------------------------------------------------

/** How long the top bar has to be held before the request screen opens. */
private const val HOLD_MILLIS = 5_000L

/** The band, measured from the top of the window, that the hold has to start in. */
private val HOLD_BAND = 112.dp

/**
 * Fires [onTrigger] when a finger rests near the top of this element for [HOLD_MILLIS].
 *
 * Events are watched on the initial pass and never consumed, so this is a passive layer over the top
 * bar rather than a gesture competing with it: the back arrow, the search field and the overflow
 * icons underneath all keep behaving exactly as they did. Any lift, or any movement past touch slop,
 * abandons the hold — five seconds is long enough that an accidental trigger would be baffling.
 */
@Composable
fun Modifier.holdTopBarForFeatureRequest(enabled: Boolean, onTrigger: () -> Unit): Modifier {
    val trigger by rememberUpdatedState(onTrigger)
    if (!enabled) return this
    return this.pointerInput(Unit) {
        val band = HOLD_BAND.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.y > band) return@awaitEachGesture
            val settled = withTimeoutOrNull(HOLD_MILLIS) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > slop) break
                }
            }
            // Null means the timeout won: the finger is still down and still where it started.
            if (settled == null) trigger()
        }
    }
}

// --- what the screen is ------------------------------------------------------------------------

/** The machine-readable half of a request. Serialised into [FeatureRequest.context]. */
private data class ScreenContext(
    val screen: String,
    val description: String,
    /** The navigation entry's own arguments (stop code, trip id, …). */
    val arguments: Screen?,
    /** What the screen was rendering, where the app has it to hand. */
    val payload: Any?,
    val capturedAt: String,
    val appVersion: String,
)

/** A short human title for [screen], used as [FeatureRequest.screen] and in the request's heading. */
fun describeScreen(screen: Screen?, onNotificationsPage: Boolean): String = when {
    onNotificationsPage -> "Notifications — notification windows"
    screen == null || screen is Screen.Home -> "Home — search, favourites and nearby stops"
    screen is Screen.Settings -> "Settings"
    screen is Screen.Privacy -> "Privacy policy"
    screen is Screen.StopBoard -> "Stop board — ${screen.code}"
    screen is Screen.RouteView -> "Route ${screen.route} (direction ${screen.direction})"
    screen is Screen.TripView -> "Trip ${screen.tripId}"
    screen is Screen.Report -> "Report — ${screen.routeShortName} at ${screen.stopCode}"
    else -> "Feature request"
}

/**
 * The context blob for [screen]: its arguments, plus the data it was rendering where the app still
 * holds it. Only the stop board has a payload worth quoting — it is the one screen whose content is
 * a server response rather than a rendering of local state, and [DeparturesCache] already has the
 * exact response that produced the pixels.
 */
fun screenContextJson(screen: Screen?, onNotificationsPage: Boolean): String = featureGson.toJson(
    ScreenContext(
        screen = if (onNotificationsPage) "Notifications" else (screen ?: Screen.Home).javaClass.simpleName,
        description = describeScreen(screen, onNotificationsPage),
        arguments = if (onNotificationsPage) null else screen,
        payload = if (!onNotificationsPage && screen is Screen.StopBoard) DeparturesCache.get(screen.code) else null,
        capturedAt = Instant.now().toString(),
        appVersion = BuildConfig.VERSION_NAME,
    ),
)
