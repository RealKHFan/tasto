package io.github.realkhfan.tasto.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Draws key previews and long-press popups on top of the whole keyboard (including the
 * suggestion bar), so popups for the top row are not cut off. It never takes touches.
 */
class PreviewOverlay(context: Context) : View(context) {
    data class Preview(val rect: RectF, val label: String)
    /** Long-press alternatives in one [panel], or (panel = null) the 12-key flick guide's separate cells. */
    data class Popup(val cells: List<RectF>, val items: List<String>, val selected: Int, val panel: RectF?)

    var theme: KeyboardTheme = KeyboardTheme.dark(KeyboardTheme.ACCENTS.getValue("blue"))
    private val previews = LinkedHashMap<Int, Preview>()
    private var popup: Popup? = null
    private val density = resources.displayMetrics.density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPreview(id: Int, preview: Preview?) {
        if (preview == null) previews.remove(id) else previews[id] = preview
        invalidate()
    }

    fun setPopup(p: Popup?) {
        popup = p
        invalidate()
    }

    fun clear() {
        previews.clear()
        popup = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 10f * density
        bg.color = theme.popup
        bg.setShadowLayer(6f * density, 0f, 2f * density, 0x55000000)
        for (p in previews.values) {
            canvas.drawRoundRect(p.rect, r, r, bg)
            text.color = theme.popupText
            text.typeface = Typeface.DEFAULT
            text.textSize = sp(if (p.label.length > 1) 20f else 28f)
            drawCentered(canvas, p.label, p.rect)
        }
        popup?.let { p ->
            if (p.panel != null) {
                canvas.drawRoundRect(p.panel, r, r, bg)
            } else {
                for (cell in p.cells) canvas.drawRoundRect(cell, r, r, bg)
            }
            sel.color = theme.accent
            p.cells.forEachIndexed { i, cell ->
                if (i == p.selected) {
                    val inset = 2f * density
                    canvas.drawRoundRect(cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset, r * 0.8f, r * 0.8f, sel)
                }
                val label = p.items[i]
                text.color = if (i == p.selected) theme.onAccent else theme.popupText
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(if (label.length > 2) 15f else if (p.panel == null) 26f else 22f)
                drawCentered(canvas, label, cell)
            }
        }
    }

    private fun drawCentered(canvas: Canvas, s: String, rect: RectF) {
        val y = rect.centerY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(s, rect.centerX(), y, text)
    }
}
