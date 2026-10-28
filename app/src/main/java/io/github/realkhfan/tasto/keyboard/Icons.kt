package io.github.realkhfan.tasto.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

enum class Icon {
    SHIFT, SHIFT_ON, SHIFT_LOCKED, BACKSPACE, ENTER, SEARCH, SEND, DONE, NEXT, EMOJI, CLIPBOARD,
    INCOGNITO, SETTINGS, HIDE, BACK, TOOLBAR, PIN, PIN_FILLED, DELETE, CLOSE, CLOCK,
    SPACE, TAB, CIRCLE_UP, CIRCLE_DOWN, FORWARD, GLOBE, CHECK,
}

/**
 * Tasto's line icons, drawn in a 24×24 box and scaled to any size. Drawing them in code keeps
 * them crisp at every key size and theme without image assets.
 */
object Icons {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(canvas: Canvas, icon: Icon, bounds: RectF, color: Int, background: Int = 0) {
        val size = minOf(bounds.width(), bounds.height())
        val s = size / 24f
        canvas.save()
        canvas.translate(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f)
        canvas.scale(s, s)
        stroke.color = color
        stroke.strokeWidth = 1.9f
        fill.color = color
        when (icon) {
            Icon.SHIFT -> canvas.drawPath(shiftPath, stroke)
            // Shift on for the next letter: outline arrow with a bar underneath (like SwiftKey).
            Icon.SHIFT_ON -> {
                canvas.drawPath(shiftPath, stroke)
                canvas.drawRoundRect(8.5f, 20.6f, 15.5f, 22.6f, 1f, 1f, fill)
            }
            Icon.SHIFT_LOCKED -> {
                canvas.drawPath(shiftPath, fill); canvas.drawPath(shiftPath, stroke)
                canvas.drawRoundRect(8.5f, 20.6f, 15.5f, 22.6f, 1f, 1f, fill)
            }
            Icon.BACKSPACE -> {
                canvas.drawPath(backspacePath, stroke)
                canvas.drawLine(11.5f, 9.5f, 16.5f, 14.5f, stroke)
                canvas.drawLine(16.5f, 9.5f, 11.5f, 14.5f, stroke)
            }
            Icon.ENTER -> {
                canvas.drawPath(path { moveTo(19.5f, 5.5f); lineTo(19.5f, 11.5f); quadTo(19.5f, 14.5f, 16.5f, 14.5f); lineTo(5f, 14.5f) }, stroke)
                canvas.drawPath(path { moveTo(9f, 10.5f); lineTo(5f, 14.5f); lineTo(9f, 18.5f) }, stroke)
            }
            Icon.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 6f, stroke)
                canvas.drawLine(15f, 15f, 19.5f, 19.5f, stroke)
            }
            Icon.SEND -> {
                canvas.drawPath(path { moveTo(4f, 19.5f); lineTo(20.5f, 12f); lineTo(4f, 4.5f); lineTo(6.5f, 12f); close() }, stroke)
                canvas.drawLine(6.5f, 12f, 12.5f, 12f, stroke)
            }
            Icon.DONE -> canvas.drawPath(path { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }, stroke)
            Icon.NEXT -> {
                canvas.drawLine(5f, 12f, 19f, 12f, stroke)
                canvas.drawPath(path { moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f) }, stroke)
            }
            Icon.EMOJI -> {
                canvas.drawCircle(12f, 12f, 8.8f, stroke)
                canvas.drawCircle(9f, 10f, 1.2f, fill)
                canvas.drawCircle(15f, 10f, 1.2f, fill)
                canvas.drawPath(path { moveTo(8.2f, 14.2f); quadTo(12f, 17.8f, 15.8f, 14.2f) }, stroke)
            }
            Icon.CLIPBOARD -> {
                canvas.drawRoundRect(5.5f, 5f, 18.5f, 21f, 2.2f, 2.2f, stroke)
                canvas.drawRoundRect(9f, 3f, 15f, 7f, 1.2f, 1.2f, stroke)
                canvas.drawLine(9f, 12f, 15f, 12f, stroke)
                canvas.drawLine(9f, 16f, 13f, 16f, stroke)
            }
            Icon.INCOGNITO -> {
                // A small masquerade mask.
                canvas.drawPath(maskPath, fill)
                fill.color = background
                canvas.drawOval(5.6f, 10.4f, 10.2f, 13.6f, fill)
                canvas.drawOval(13.8f, 10.4f, 18.4f, 13.6f, fill)
                fill.color = color
            }
            Icon.SETTINGS -> {
                canvas.drawPath(gearPath, stroke)
                canvas.drawCircle(12f, 12f, 3f, stroke)
            }
            Icon.HIDE -> canvas.drawPath(path { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) }, stroke)
            Icon.BACK -> canvas.drawPath(path { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }, stroke)
            Icon.FORWARD -> canvas.drawPath(path { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }, stroke)
            Icon.TOOLBAR -> {
                canvas.drawRoundRect(4f, 4f, 10.5f, 10.5f, 2f, 2f, stroke)
                canvas.drawRoundRect(13.5f, 4f, 20f, 10.5f, 2f, 2f, stroke)
                canvas.drawRoundRect(4f, 13.5f, 10.5f, 20f, 2f, 2f, stroke)
                canvas.drawRoundRect(13.5f, 13.5f, 20f, 20f, 2f, 2f, stroke)
            }
            Icon.PIN, Icon.PIN_FILLED -> {
                val p = path { moveTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(14f, 9.5f); lineTo(17.5f, 13f); lineTo(6.5f, 13f); lineTo(10f, 9.5f); close() }
                if (icon == Icon.PIN_FILLED) canvas.drawPath(p, fill)
                canvas.drawPath(p, stroke)
                canvas.drawLine(12f, 13f, 12f, 20.5f, stroke)
            }
            Icon.DELETE -> {
                canvas.drawLine(4f, 7f, 20f, 7f, stroke)
                canvas.drawPath(path { moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f) }, stroke)
                canvas.drawPath(path { moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f) }, stroke)
            }
            Icon.CLOSE -> {
                canvas.drawLine(6.5f, 6.5f, 17.5f, 17.5f, stroke)
                canvas.drawLine(17.5f, 6.5f, 6.5f, 17.5f, stroke)
            }
            Icon.SPACE -> canvas.drawPath(path { moveTo(4f, 11f); lineTo(4f, 15f); lineTo(20f, 15f); lineTo(20f, 11f) }, stroke)
            Icon.TAB -> {
                canvas.drawLine(3.5f, 12f, 17f, 12f, stroke)
                canvas.drawPath(path { moveTo(12f, 7f); lineTo(17f, 12f); lineTo(12f, 17f) }, stroke)
                canvas.drawLine(20.5f, 6f, 20.5f, 18f, stroke)
            }
            // Filled grey circle with the arrow, no outline ring.
            Icon.CIRCLE_UP, Icon.CIRCLE_DOWN -> {
                fill.color = background
                canvas.drawCircle(12f, 12f, 11.4f, fill)
                fill.color = color
                stroke.strokeWidth = 1.8f
                if (icon == Icon.CIRCLE_UP) {
                    canvas.drawPath(path { moveTo(8.2f, 13.6f); lineTo(12f, 9.8f); lineTo(15.8f, 13.6f) }, stroke)
                } else {
                    canvas.drawPath(path { moveTo(8.2f, 10.4f); lineTo(12f, 14.2f); lineTo(15.8f, 10.4f) }, stroke)
                }
            }
            Icon.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8.8f, stroke)
                canvas.drawOval(8.2f, 3.2f, 15.8f, 20.8f, stroke)
                canvas.drawLine(3.2f, 12f, 20.8f, 12f, stroke)
                canvas.drawPath(path { moveTo(5f, 7.6f); quadTo(12f, 9.6f, 19f, 7.6f) }, stroke)
                canvas.drawPath(path { moveTo(5f, 16.4f); quadTo(12f, 14.4f, 19f, 16.4f) }, stroke)
            }
            Icon.CHECK -> canvas.drawPath(path { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }, stroke)
            Icon.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8.8f, stroke)
                canvas.drawPath(path { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f) }, stroke)
            }
        }
        canvas.restore()
    }

    private inline fun path(block: Path.() -> Unit) = Path().apply(block)

    private val shiftPath = path {
        moveTo(12f, 3.5f); lineTo(20f, 11.5f); lineTo(15.5f, 11.5f); lineTo(15.5f, 18.5f)
        lineTo(8.5f, 18.5f); lineTo(8.5f, 11.5f); lineTo(4f, 11.5f); close()
    }
    private val backspacePath = path {
        moveTo(8.5f, 5f); lineTo(19f, 5f); quadTo(21f, 5f, 21f, 7f); lineTo(21f, 17f)
        quadTo(21f, 19f, 19f, 19f); lineTo(8.5f, 19f); lineTo(2.5f, 12f); close()
    }
    private val maskPath = path {
        moveTo(2.5f, 10f); quadTo(12f, 5.5f, 21.5f, 10f); lineTo(21.5f, 12f)
        quadTo(21.5f, 17.5f, 16.5f, 17.5f); quadTo(13.8f, 17.5f, 12f, 15f)
        quadTo(10.2f, 17.5f, 7.5f, 17.5f); quadTo(2.5f, 17.5f, 2.5f, 12f); close()
    }
    private val gearPath = Path().apply {
        val inner = 7.3f
        val outer = 9.6f
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            fun pt(deg: Double, r: Float) = floatArrayOf(
                12f + r * cos(a + Math.toRadians(deg)).toFloat(), 12f + r * sin(a + Math.toRadians(deg)).toFloat(),
            )
            val p1 = pt(-15.0, inner); val p2 = pt(-9.0, outer); val p3 = pt(9.0, outer); val p4 = pt(15.0, inner)
            if (k == 0) moveTo(p1[0], p1[1]) else lineTo(p1[0], p1[1])
            lineTo(p2[0], p2[1]); lineTo(p3[0], p3[1]); lineTo(p4[0], p4[1])
        }
        close()
    }
}

/** An [Icon] as a Drawable, for normal Android views. */
class IconDrawable(private val icon: Icon, private var color: Int, private val bg: Int = 0) : Drawable() {
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        Icons.draw(canvas, icon, rect, color, bg)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
