package com.example.tfiapp

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import kotlin.math.roundToInt

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

/** Top-bar toggle button; [onClick] shows/hides the inline [TimeTravelPanel]. */
@Composable
fun TimeTravelChip(controller: TimeController, onClick: () -> Unit) {
    val label = when {
        controller.isLive -> "NOW"
        controller.mode == TimeMode.RELATIVE -> signedHhmm(controller.offsetMinutes)
        else -> controller.absoluteSec?.let { absHeader(it) } ?: "NOW"
    }

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
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🕑", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
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
 */
@Composable
private fun Cylinder(label: String, textAt: (Int) -> String, onStep: (Int) -> Unit) {
    val density = LocalDensity.current
    val stepPx = with(density) { 26.dp.toPx() }
    var residual by remember { mutableStateOf(0f) }
    val onFace = MaterialTheme.colorScheme.onSurface

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(56.dp)
                .height(78.dp)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { residual = 0f },
                        onDragCancel = { residual = 0f },
                    ) { change, dy ->
                        change.consume()
                        residual += dy
                        while (residual <= -stepPx) { onStep(1); residual += stepPx }
                        while (residual >= stepPx) { onStep(-1); residual -= stepPx }
                    }
                },
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
                    modifier = Modifier.offset { IntOffset(0, (k * stepPx + residual).roundToInt()) },
                )
            }
        }
    }
}
