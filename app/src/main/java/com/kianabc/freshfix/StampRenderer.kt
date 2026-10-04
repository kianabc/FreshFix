package com.kianabc.freshfix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Draws the evidence card in the bottom-left of a photo:
 *
 *   | 2:32:07 PM                      * FRESHFIX
 *   | Sat, Oct 4, 2026 · MDT
 *   | -----------------------------------------
 *   | [pin]  123 Main St, Salt Lake City, UT
 *   | [+]    40.760779° N  111.891047° W   ±4 m
 *
 * The accent strip, dot and accuracy pill take the fix status colour.
 * Everything is sized from the photo's short side so it reads the same at any resolution.
 */
class StampRenderer(private val use24Hour: Boolean) {

    fun draw(bitmap: Bitmap, shutterWallMs: Long, location: Location?, address: String?, status: FixStatus) {
        val canvas = Canvas(bitmap)
        val u = minOf(bitmap.width, bitmap.height) / 100f
        val accent = when (status) {
            FixStatus.OK -> GREEN
            FixStatus.WEAK -> AMBER
            FixStatus.NO_FIX -> RED
        }

        val margin = 3f * u
        val pad = 3.2f * u
        val accentWidth = 0.9f * u
        val iconSize = 3.4f * u
        val iconGap = 1.8f * u
        val cardWidth = minOf(bitmap.width - 2 * margin, 92f * u)
        val contentLeft = margin + accentWidth + pad
        val contentRight = margin + cardWidth - pad
        val textLeft = contentLeft + iconSize + iconGap

        val timePaint = textPaint(6.4f * u, Color.WHITE, Typeface.create("sans-serif", Typeface.BOLD))
        val datePaint = textPaint(2.8f * u, MUTED, Typeface.create("sans-serif", Typeface.NORMAL))
        val labelPaint = textPaint(2.1f * u, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.18f
        }
        val bodyPaint = textPaint(3.3f * u, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL))
        val pillPaint = textPaint(2.7f * u, accent, Typeface.create("sans-serif-medium", Typeface.BOLD))

        val date = Date(shutterWallMs)
        val timeText = SimpleDateFormat(if (use24Hour) "HH:mm:ss" else "h:mm:ss a", Locale.US).format(date)
        val dateText = SimpleDateFormat("EEE, MMM d, yyyy · zzz", Locale.US).format(date)
        val addressText = address ?: if (location != null) "Address unavailable" else null
        val coordsText = location?.let { formatCoordinates(it.latitude, it.longitude) } ?: "No GPS fix"
        val pillText = location?.let { "±%.0f m".format(Locale.US, it.accuracy) }

        // Measure.
        val addressPaint = bodyPaint.withColor(if (address == null) MUTED else Color.WHITE)
        val addressLayout = addressText?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, addressPaint, (contentRight - textLeft).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.1f)
                .build()
        }
        val timeHeight = lineHeight(timePaint)
        val dateHeight = lineHeight(datePaint)
        val rowGap = 2.2f * u
        val dividerGap = 2.4f * u
        val addressHeight = addressLayout?.let { maxOf(it.height.toFloat(), iconSize) } ?: 0f
        val coordsHeight = maxOf(lineHeight(bodyPaint), iconSize)
        val cardHeight = pad + timeHeight + 0.6f * u + dateHeight + dividerGap * 2 +
            (if (addressLayout != null) addressHeight + rowGap else 0f) + coordsHeight + pad

        val card = RectF(margin, bitmap.height - margin - cardHeight, margin + cardWidth, bitmap.height - margin)
        val radius = 2.6f * u

        // Card, accent strip and hairline border.
        val cardPath = Path().apply { addRoundRect(card, radius, radius, Path.Direction.CW) }
        canvas.drawPath(cardPath, fill(CARD))
        canvas.save()
        canvas.clipPath(cardPath)
        canvas.drawRect(card.left, card.top, card.left + accentWidth, card.bottom, fill(accent))
        canvas.restore()
        canvas.drawRoundRect(card, radius, radius, stroke(BORDER, 0.15f * u))

        // Header: time, brand label with status dot, date.
        var y = card.top + pad
        val timeBaseline = y - timePaint.fontMetrics.ascent
        canvas.drawText(timeText, contentLeft, timeBaseline, timePaint)
        val label = "FRESHFIX"
        val labelWidth = labelPaint.measureText(label)
        val labelBaseline = timeBaseline - (timePaint.textSize - labelPaint.textSize) / 2f
        canvas.drawText(label, contentRight - labelWidth, labelBaseline, labelPaint)
        val dotRadius = 0.8f * u
        canvas.drawCircle(
            contentRight - labelWidth - dotRadius - 1.2f * u,
            labelBaseline - labelPaint.textSize * 0.36f,
            dotRadius, fill(accent),
        )
        y += timeHeight + 0.6f * u
        canvas.drawText(dateText, contentLeft, y - datePaint.fontMetrics.ascent, datePaint)
        y += dateHeight + dividerGap

        canvas.drawLine(contentLeft, y, contentRight, y, stroke(DIVIDER, 0.15f * u))
        y += dividerGap

        // Address row.
        if (addressLayout != null) {
            val firstLineCenter = y + (addressLayout.getLineBottom(0) - addressLayout.getLineTop(0)) / 2f
            drawPin(canvas, contentLeft + iconSize / 2f, firstLineCenter, iconSize, accent)
            canvas.save()
            canvas.translate(textLeft, y)
            addressLayout.draw(canvas)
            canvas.restore()
            y += addressHeight + rowGap
        }

        // Coordinates row with accuracy pill.
        val coordsCenter = y + coordsHeight / 2f
        drawCrosshair(canvas, contentLeft + iconSize / 2f, coordsCenter, iconSize, accent)
        val coordsPaint = bodyPaint.withColor(if (location == null) RED else Color.WHITE)
        canvas.drawText(coordsText, textLeft, baselineFor(coordsPaint, coordsCenter), coordsPaint)
        if (pillText != null) {
            val pillPadX = 1.4f * u
            val pillHeight = pillPaint.textSize * 1.7f
            val pillWidth = pillPaint.measureText(pillText) + pillPadX * 2
            val pill = RectF(contentRight - pillWidth, coordsCenter - pillHeight / 2f, contentRight, coordsCenter + pillHeight / 2f)
            // Skip the pill rather than overlap the coordinates on a very narrow photo.
            if (pill.left > textLeft + coordsPaint.measureText(coordsText) + u) {
                canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, fill(withAlpha(accent, 0.22f)))
                canvas.drawText(pillText, pill.left + pillPadX, baselineFor(pillPaint, coordsCenter), pillPaint)
            }
        }
    }

    /** Map pin: round head with a hole, tapering to a point at the bottom. */
    private fun drawPin(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val r = size * 0.34f
        val headY = cy - size * 0.14f
        val tipY = cy + size * 0.48f
        val body = Path().apply {
            addCircle(cx, headY, r, Path.Direction.CW)
            moveTo(cx - r * 0.86f, headY + r * 0.5f)
            lineTo(cx, tipY)
            lineTo(cx + r * 0.86f, headY + r * 0.5f)
            close()
        }
        val hole = Path().apply { addCircle(cx, headY, r * 0.42f, Path.Direction.CW) }
        body.op(hole, Path.Op.DIFFERENCE)
        canvas.drawPath(body, fill(color))
    }

    /** GPS crosshair: ring, four ticks and a centre dot. */
    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val ring = size * 0.3f
        val outer = size * 0.5f
        val line = stroke(color, size * 0.09f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawCircle(cx, cy, ring, line)
        canvas.drawLine(cx, cy - outer, cx, cy - ring, line)
        canvas.drawLine(cx, cy + ring, cx, cy + outer, line)
        canvas.drawLine(cx - outer, cy, cx - ring, cy, line)
        canvas.drawLine(cx + ring, cy, cx + outer, cy, line)
        canvas.drawCircle(cx, cy, size * 0.09f, fill(color))
    }

    private fun formatCoordinates(lat: Double, lon: Double): String =
        "%.6f° %s  %.6f° %s".format(Locale.US, abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

    private fun textPaint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = face
        fontFeatureSettings = "tnum" // Fixed-width digits so times and coordinates line up.
    }

    private fun TextPaint.withColor(c: Int) = TextPaint(this).apply { color = c }

    private fun lineHeight(paint: Paint) = paint.fontMetrics.let { it.descent - it.ascent }

    private fun baselineFor(paint: Paint, centerY: Float) =
        paint.fontMetrics.let { centerY - (it.ascent + it.descent) / 2f }

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }

    private fun stroke(c: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = width
    }

    private fun withAlpha(c: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        private val CARD = Color.argb(150, 12, 14, 18)
        private val BORDER = Color.argb(46, 255, 255, 255)
        private val DIVIDER = Color.argb(38, 255, 255, 255)
        private val MUTED = Color.argb(185, 255, 255, 255)
        private val GREEN = Color.rgb(74, 222, 128)
        private val AMBER = Color.rgb(251, 191, 36)
        private val RED = Color.rgb(248, 113, 113)
    }
}
