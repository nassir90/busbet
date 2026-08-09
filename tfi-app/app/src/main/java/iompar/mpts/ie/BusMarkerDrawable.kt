package iompar.mpts.ie

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Circular bus marker: filled circle with route label, plus a triangular arrow
 * extending from the edge in the heading direction when bearing is known.
 */
class BusMarkerDrawable(
    private val label: String,
    fillColor: Int,
    textColor: Int,
    private val bearing: Float?,
    density: Float,
) : Drawable() {

    private val radius   = 10f * density
    private val arrowLen = 7f  * density
    private val arrowHW  = 4f  * density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = 0x66000000
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val textPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = textColor
        textSize  = 9f * density
        typeface  = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val arrowPath = Path()

    private val intrinsic = ((radius + arrowLen) * 2f + 4f).toInt()

    override fun getIntrinsicWidth()  = intrinsic
    override fun getIntrinsicHeight() = intrinsic

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Label centred in the circle — no rotation needed
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)

        if (bearing != null) {
            canvas.save()
            canvas.rotate(bearing, cx, cy)
            arrowPath.rewind()
            arrowPath.moveTo(cx,           cy - radius - arrowLen)
            arrowPath.lineTo(cx - arrowHW, cy - radius)
            arrowPath.lineTo(cx + arrowHW, cy - radius)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.drawPath(arrowPath, borderPaint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ── Palette (matches gtfsr-historical-view PALETTE exactly) ─────────────────

private val ROUTE_PALETTE = intArrayOf(
    0xFFEF4444.toInt(), 0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(),
    0xFFA855F7.toInt(), 0xFFEC4899.toInt(), 0xFF14B8A6.toInt(), 0xFFF97316.toInt(),
    0xFF6366F1.toInt(), 0xFF84CC16.toInt(), 0xFFF0C060.toInt(), 0xFF60C0F0.toInt(),
    0xFFA0E090.toInt(), 0xFFE090C0.toInt(), 0xFFF09060.toInt(), 0xFFD94A8A.toInt(),
    0xFF5AD1B9.toInt(), 0xFFC988FF.toInt(), 0xFFFFAA33.toInt(), 0xFF66CC66.toInt(),
)

fun routeColor(routeShortName: String): Int =
    ROUTE_PALETTE[Math.abs(routeShortName.hashCode()) % ROUTE_PALETTE.size]
