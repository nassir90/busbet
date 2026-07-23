package net.uzoukwu.tfiapp

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Shared height for top-bar pills (the [TimeTravelChip] "now"/offset indicator and the
 * Week/Day toggle in the notifications screen). The average of the two former sizes — a bit
 * taller than the offset chip used to be, a bit shorter than the Week/Day toggle.
 */
val PILL_HEIGHT = 34.dp

enum class TimeMode { RELATIVE, ABSOLUTE }

/**
 * Shared "time machine" state. RELATIVE holds a signed minute delta from now (0 = live);
 * ABSOLUTE pins a fixed instant. [querySec] is what the app queries, or null when live
 * (preserving "?time omitted = now").
 */
class TimeController {
    var mode by mutableStateOf(TimeMode.RELATIVE)
    var offsetMinutes by mutableIntStateOf(0)
    var absoluteSec by mutableStateOf<Long?>(null)

    // Debounced mirror of [querySec]; screens fetch against this so spinning the drums
    // doesn't refetch/recompose the board on every detent. Updated by App().
    var committedSec by mutableStateOf<Long?>(null)

    private fun nowSec() = System.currentTimeMillis() / 1000

    val isLive: Boolean get() = mode == TimeMode.RELATIVE && offsetMinutes == 0

    val querySec: Long?
        get() = when (mode) {
            TimeMode.RELATIVE -> if (offsetMinutes == 0) null else nowSec() + offsetMinutes * 60L
            TimeMode.ABSOLUTE -> absoluteSec
        }

    fun stepMinutes(steps: Int) { offsetMinutes += steps }
    fun stepHours(steps: Int) { offsetMinutes += steps * 60 }

    fun useRelative() { mode = TimeMode.RELATIVE }
    fun useAbsolute() {
        if (absoluteSec == null) absoluteSec = querySec ?: nowSec()
        mode = TimeMode.ABSOLUTE
    }
    fun setAbsolute(sec: Long) { absoluteSec = sec; mode = TimeMode.ABSOLUTE }
    fun reset() { offsetMinutes = 0; absoluteSec = null; mode = TimeMode.RELATIVE }
}

/** "+1:25" / "−0:10" / "NOW". */
private fun signedHhmm(offsetMin: Int): String {
    if (offsetMin == 0) return "NOW"
    val sign = if (offsetMin > 0) "+" else "−"
    val a = abs(offsetMin)
    return "$sign${a / 60}:${"%02d".format(a % 60)}"
}

/** Two-digit minutes with a sign, e.g. "00", "05", "−05", "−59". */
private fun signedMin(m: Int): String = if (m < 0) "−%02d".format(-m) else "%02d".format(m)

private fun absClock(sec: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun absDate(sec: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM"))

/** Clock time, or lowercase "now" when within the current minute. */
private fun absHeader(sec: Long): String =
    if (abs(sec - System.currentTimeMillis() / 1000) < 60) "now" else absClock(sec)

/**
 * Top-bar toggle button; [onClick] shows/hides the inline [TimeTravelPanel]. Also a
 * shortcut: dragging up/down on it spins the relative minute offset, using the same
 * direction as the [Cylinder] drums (up = later, down = earlier).
 */
@Composable
fun TimeTravelChip(controller: TimeController, onClick: () -> Unit) {
    val label = when {
        controller.isLive -> "NOW"
        controller.mode == TimeMode.RELATIVE -> signedHhmm(controller.offsetMinutes)
        else -> controller.absoluteSec?.let { absHeader(it) } ?: "NOW"
    }

    val stepPx = with(LocalDensity.current) { 26.dp.toPx() }
    var residual by remember { mutableStateOf(0f) }

    Surface(
        color = if (controller.isLive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.onPrimary,
        contentColor = if (controller.isLive) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50),
        modifier = Modifier.clip(RoundedCornerShape(50)),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        // The shortcut only makes sense in relative mode; enter it on first drag.
                        onDragStart = { controller.useRelative() },
                        onDragEnd = { residual = 0f },
                        onDragCancel = { residual = 0f },
                    ) { change, dy ->
                        change.consume()
                        residual += dy
                        while (residual <= -stepPx) { controller.stepMinutes(1); residual += stepPx }
                        while (residual >= stepPx) { controller.stepMinutes(-1); residual -= stepPx }
                    }
                }
                .height(PILL_HEIGHT)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockIcon(controller.querySec, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A live analogue clock face whose hands point at [sec] (the instant being viewed), or the
 * current time when null. Replaces the static Material `Schedule` icon so the hands track
 * the relative offset as you scrub.
 */
@Composable
private fun ClockIcon(sec: Long?, modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    val dt = if (sec != null)
        LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault())
    else
        LocalDateTime.now()
    val minute = dt.minute
    val hour = dt.hour % 12

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = r * 0.13f

        // Face (inset by half the stroke so the ring stays inside the bounds).
        drawCircle(tint, radius = r - stroke / 2f, center = center, style = Stroke(width = stroke))

        // Angles measured clockwise from 12 o'clock; -90° puts 0 at the top.
        fun hand(angleDeg: Double, length: Float, width: Float) {
            val a = Math.toRadians(angleDeg - 90.0)
            drawLine(
                tint,
                center,
                Offset(center.x + (cos(a) * length).toFloat(), center.y + (sin(a) * length).toFloat()),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
        // Minute hand: 6° per minute. Hour hand: 30° per hour + 0.5° per minute.
        hand(minute * 6.0, r * 0.78f, stroke)
        hand(hour * 30.0 + minute * 0.5, r * 0.5f, stroke)
    }
}

/** Inline time-travel controls, rendered as a full-width band below the app bar. */
@Composable
fun TimeTravelPanel(controller: TimeController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val relative = controller.mode == TimeMode.RELATIVE

    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            // 16dp start margin aligns the controls with the favourite/departure cards.
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Column 1: reset + mode (fixed width → equal-size buttons) ──
            Column(Modifier.width(98.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BorderedButton("now", onClick = { controller.reset() }, enabled = !controller.isLive)
                ModePill(relative, onRelative = { controller.useRelative() }, onAbsolute = { controller.useAbsolute() })
            }

            if (relative) {
                val off = controller.offsetMinutes
                Cylinder(
                    label = "Hours",
                    // Truncation toward zero: hour ticks only after minutes pass ∓59.
                    textAt = { k -> "${(off + k * 60) / 60}" },
                    onStep = { controller.stepHours(it) },
                )
                Text(":", style = MaterialTheme.typography.headlineSmall)
                Cylinder(
                    label = "Min",
                    // Signed minutes: 0 → −1 → … → −59, then the hour carries.
                    textAt = { k -> signedMin((off + k) % 60) },
                    onStep = { controller.stepMinutes(it) },
                )

                Spacer(Modifier.weight(1f))

                Column(horizontalAlignment = Alignment.End) {
                    Text("at", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        controller.querySec?.let { absClock(it) } ?: "now",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                val sec = controller.absoluteSec ?: (System.currentTimeMillis() / 1000)
                PickerCell(
                    label = "Date", value = absDate(sec), modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = sec * 1000 }
                        DatePickerDialog(context, { _, y, mo, d ->
                            cal.set(y, mo, d); controller.setAbsolute(cal.timeInMillis / 1000)
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                )
                PickerCell(
                    label = "Time", value = absClock(sec), modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = sec * 1000 }
                        TimePickerDialog(context, { _, h, m ->
                            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0)
                            controller.setAbsolute(cal.timeInMillis / 1000)
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                    },
                )
            }
        }
    }
}

@Composable
private fun BorderedButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Vertical two-segment selector: Relative on top, Absolute below — equal halves. */
@Composable
private fun ModePill(relative: Boolean, onRelative: () -> Unit, onAbsolute: () -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, outline, RoundedCornerShape(8.dp)),
    ) {
        ModeHalf("Relative", relative, onRelative)
        HorizontalDivider(color = outline)
        ModeHalf("Absolute", !relative, onAbsolute)
    }
}

@Composable
private fun ModeHalf(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

@Composable
private fun PickerCell(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A vertical drum you drag to spin, like a cylinder on a rod. Each detent of vertical
 * travel commits one step ([onStep], +1 when dragged up). The centre value is bold and the
 * neighbours fade toward transparency above and below.
 *
 * Flicking carries momentum: [fling] runs the decay physics and [residual] mirrors its
 * frame-by-frame motion (consuming detents as it crosses them) so the digits stay in
 * lockstep with the animation instead of jumping straight to the rest position. Whatever
 * fractional offset is left once the fling dies out eases to dead-centre instead of
 * snapping there.
 */
@Composable
private fun Cylinder(label: String, textAt: (Int) -> String, onStep: (Int) -> Unit) {
    val density = LocalDensity.current
    val stepPx = with(density) { 26.dp.toPx() }
    val residual = remember { Animatable(0f) }
    val fling = remember { Animatable(0f) }
    val onFace = MaterialTheme.colorScheme.onSurface
    val scope = rememberCoroutineScope()

    fun consumeSteps(raw: Float): Float {
        var r = raw
        while (r <= -stepPx) { onStep(1); r += stepPx }
        while (r >= stepPx) { onStep(-1); r -= stepPx }
        return r
    }

    LaunchedEffect(fling) {
        var prev = 0f
        snapshotFlow { fling.value }.collect { v ->
            val delta = v - prev
            prev = v
            residual.snapTo(consumeSteps(residual.value + delta))
        }
    }

    val draggableState = rememberDraggableState { delta ->
        scope.launch { residual.snapTo(consumeSteps(residual.value + delta)) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(56.dp)
                .height(78.dp)
                .clipToBounds()
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { fling.stop() },
                    onDragStopped = { velocity ->
                        fling.snapTo(0f)
                        fling.animateDecay(velocity, exponentialDecay(frictionMultiplier = 6f))
                        residual.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            for (k in -2..2) {
                val alpha = when (abs(k)) { 0 -> 1f; 1 -> 0.35f; else -> 0.10f }
                Text(
                    textAt(k),
                    style = if (k == 0) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = if (k == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = onFace.copy(alpha = alpha),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset { IntOffset(0, (k * stepPx + residual.value).roundToInt()) },
                )
            }
        }
    }
}
