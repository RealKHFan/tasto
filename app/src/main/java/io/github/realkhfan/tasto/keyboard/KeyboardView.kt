package io.github.realkhfan.tasto.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import io.github.realkhfan.tasto.ime.ShiftState
import io.github.realkhfan.tasto.japanese.Kana
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun View.dp(v: Float): Float = v * resources.displayMetrics.density
internal fun View.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * The key area. Everything is drawn on one canvas for speed. Supports fast two-finger typing,
 * long-press popups, sliding to another key before releasing, space-bar cursor control or
 * language switching, held backspace and swipe-left-on-backspace to delete a word.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKeyText(text: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onSpace()
        fun onEnter()
        fun onShift()
        fun onShiftLongPress()
        fun onPage(page: Page)
        fun onEmoji()
        fun onCursorMove(steps: Int)
        fun onLanguageSwipe(direction: Int)
        fun onKeyFeedback(key: Key)
        /** The ABC key on the 12-key layout: back to German + English. */
        fun onLatinKey() = onLanguageSwipe(1)
        /** Holding the ABC key: the list of languages. */
        fun onLanguageMenu() = Unit
    }

    private class Box(val key: Key, val rect: RectF, val hit: RectF)

    private inner class Ptr(val id: Int, var box: Box, val downX: Float, val downY: Float) {
        var x = downX
        var y = downY
        val downTime = SystemClock.uptimeMillis()
        var longFired = false
        var popupItems: List<String>? = null
        var popupCells: List<RectF> = emptyList()
        var popupSelected = 0
        var popupOpenX = 0f
        var cursorMode = false
        var cursorX = 0f
        var langSwipe = 0
        var deleteSwipe = false
        var repeats = 0
        /** 12-key flick: 0 tap, 1 left, 2 up, 3 right, 4 down. */
        var flickDir = 0
        /** 12-key flick: the cross with all five characters is showing (after holding). */
        var flickGuide = false
        /** Holding ◀ or ▶ keeps moving the cursor. */
        val cursorRepeat = object : Runnable {
            override fun run() {
                listener.onCursorMove(if (box.key.type == KeyType.CURSOR_LEFT) -1 else 1)
                handler.postDelayed(this, CURSOR_REPEAT_MS)
            }
        }
        val longPress = Runnable { onLongPress(this) }
        /** Holding backspace: removes the rest of the word, then one whole word after another. */
        val repeat = object : Runnable {
            override fun run() {
                repeats++
                listener.onDeleteWord()
                listener.onKeyFeedback(box.key)
                handler.postDelayed(this, if (repeats > 3) WORD_REPEAT_FAST_MS else WORD_REPEAT_MS)
            }
        }
    }

    var theme: KeyboardTheme = KeyboardTheme.dark(KeyboardTheme.ACCENTS.getValue("blue"))
        set(v) { field = v; invalidate() }
    var layout: KeyboardLayout = Layouts.letters(io.github.realkhfan.tasto.ime.FieldKind.TEXT, false, true)
        set(v) {
            cancelAll()
            field = v
            requestLayout()
            layoutKeys()
            invalidate()
        }
    var shiftState: ShiftState = ShiftState.OFF
        set(v) { if (field != v) { field = v; invalidate(); a11y.invalidateRoot() } }
    var enterIcon: Icon = Icon.ENTER
        set(v) { field = v; invalidate() }
    var spaceLabel: String = ""
        set(v) { field = v; invalidate() }
    /** Text on the Enter key instead of its icon (確定 while Japanese is being composed). */
    var enterLabel: String? = null
        set(v) { field = v; invalidate() }
    var incognito: Boolean = false
        set(v) { field = v; invalidate() }
    var languageCount: Int = 1
    var showPreviews: Boolean = true
    var showHints: Boolean = false
    /** Enter in the accent colour (off = same grey as every other key, like SwiftKey). */
    var coloredEnter: Boolean = false
        set(v) { field = v; invalidate() }
    var longPressTimeout: Long = 300L
    var keyHeightDp: Float = 49f
        set(v) { field = v; requestLayout() }
    var overlay: PreviewOverlay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val boxes = ArrayList<Box>()
    private val pointers = LinkedHashMap<Int, Ptr>()

    // Measured from SwiftKey on a Pixel 8 Pro: 39.6dp × 48.9dp keys, 5.2dp / 6.2dp gaps.
    private val gapH get() = dp(5.2f)
    private val gapV get() = dp(6.2f)
    private val sidePad get() = dp(0f)
    private val topPad get() = dp(4.6f)
    private val bottomPad get() = dp(5.5f)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val iconRect = RectF()

    /** TalkBack: each key is its own item; double-tap (or lift to type) presses it. */
    private val a11y = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            boxes.indexOfFirst { it.hit.contains(x, y) }.takeIf { it >= 0 } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids.addAll(boxes.indices)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val b = boxes.getOrNull(id)
            val r = Rect()
            b?.rect?.roundOut(r)
            if (r.isEmpty) r.set(0, 0, 1, 1) // a key that just went away still needs bounds
            @Suppress("DEPRECATION")
            node.setBoundsInParent(r)
            node.contentDescription = b?.let { describe(it.key) } ?: ""
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            // Long-press letters and flick characters, as actions in TalkBack's menu.
            b?.key?.let { alternatives(it) }?.forEachIndexed { i, alt ->
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(ALT_ACTION + i, alt))
            }
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            val k = boxes.getOrNull(id)?.key ?: return false
            when {
                action == AccessibilityNodeInfoCompat.ACTION_CLICK -> fire(k)
                action >= ALT_ACTION -> listener.onKeyText(alternatives(k).getOrNull(action - ALT_ACTION) ?: return false)
                else -> return false
            }
            listener.onKeyFeedback(k)
            return true
        }
    }

    init {
        isHapticFeedbackEnabled = false
        contentDescription = "Tasto keyboard"
        ViewCompat.setAccessibilityDelegate(this, a11y)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun alternatives(k: Key): List<String> =
        if (k.type != KeyType.CHAR) emptyList() else k.flick.drop(1).filter { it.isNotEmpty() }.ifEmpty { k.popup }

    /** What TalkBack says for a key. */
    private fun describe(k: Key): String = when (k.type) {
        KeyType.CHAR -> k.flick.firstOrNull() ?: displayLabel(k)
        KeyType.SPACE -> if (layout.page == Page.LETTERS && spaceLabel.isNotEmpty()) "Space, $spaceLabel" else "Space"
        KeyType.BACKSPACE -> "Delete"
        KeyType.ENTER -> enterLabel ?: when (enterIcon) {
            Icon.SEARCH -> "Search"
            Icon.SEND -> "Send"
            Icon.NEXT -> "Go"
            Icon.DONE -> "Done"
            else -> "Enter"
        }
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "Shift"
            ShiftState.ON -> "Shift on"
            ShiftState.LOCKED -> "Caps lock"
        }
        KeyType.SYMBOLS -> "Numbers and symbols"
        KeyType.LETTERS -> "Letters"
        KeyType.MORE_SYMBOLS -> "More symbols"
        KeyType.EMOJI -> "Emoji"
        KeyType.TAB -> "Tab"
        KeyType.KANA_MOD -> "Dakuten, handakuten, small kana"
        KeyType.LANGUAGE -> "German and English"
        KeyType.CURSOR_LEFT -> "Move cursor left"
        KeyType.CURSOR_RIGHT -> "Move cursor right"
        KeyType.SPACER -> ""
    }

    /** What a plain tap on [k] does (also TalkBack's double-tap). */
    private fun fire(k: Key) = when (k.type) {
        KeyType.CHAR -> listener.onKeyText(k.flick.firstOrNull() ?: k.label)
        KeyType.SPACE -> listener.onSpace()
        KeyType.BACKSPACE -> listener.onBackspace()
        KeyType.SHIFT -> listener.onShift()
        KeyType.ENTER -> listener.onEnter()
        KeyType.EMOJI -> listener.onEmoji()
        KeyType.TAB -> listener.onKeyText("\t")
        KeyType.KANA_MOD -> listener.onKeyText(Kana.MODIFY)
        KeyType.LANGUAGE -> listener.onLatinKey()
        KeyType.CURSOR_LEFT -> listener.onCursorMove(-1)
        KeyType.CURSOR_RIGHT -> listener.onCursorMove(1)
        KeyType.SYMBOLS -> listener.onPage(Page.SYMBOLS)
        KeyType.LETTERS -> listener.onPage(Page.LETTERS)
        KeyType.MORE_SYMBOLS -> listener.onPage(Page.SYMBOLS2)
        KeyType.SPACER -> Unit
    }

    fun totalHeight(): Int {
        val keyH = dp(keyHeightDp)
        val rows = layout.rowHeights
        return (topPad + bottomPad + rows.sum() * keyH + gapV * (rows.size - 1)).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, totalHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        boxes.clear()
        val w = width.toFloat()
        if (w <= 0f) return
        val keyH = dp(keyHeightDp)
        val maxUnits = layout.rows.maxOf { row -> row.sumOf { it.width.toDouble() } }.toFloat()
        val unit = (w - 2 * sidePad) / maxUnits
        var y = topPad
        layout.rows.forEachIndexed { r, row ->
            val rowH = keyH * layout.rowHeights[r]
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            var x = sidePad + (maxUnits - units) / 2f * unit
            val hitTop = if (r == 0) 0f else y - gapV / 2f
            val hitBottom = if (r == layout.rows.size - 1) height.toFloat().coerceAtLeast(y + rowH) else y + rowH + gapV / 2f
            row.forEachIndexed { i, key ->
                val kw = key.width * unit
                val rect = RectF(x + gapH / 2f, y, x + kw - gapH / 2f, y + rowH)
                val hit = RectF(if (i == 0) 0f else x, hitTop, if (i == row.size - 1) w else x + kw, hitBottom)
                if (key.type != KeyType.SPACER) boxes.add(Box(key, rect, hit))
                x += kw
            }
            y += rowH + gapV
        }
        a11y.invalidateRoot()
    }

    private fun boxAt(x: Float, y: Float): Box? {
        boxes.firstOrNull { it.hit.contains(x, y) }?.let { return it }
        return boxes.minByOrNull {
            val dx = it.rect.centerX() - x
            val dy = it.rect.centerY() - y
            dx * dx + dy * dy
        }
    }

    // ---- drawing ----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val pressed = pointers.values.map { it.box }.toSet()
        val radius = dp(6f)
        val edge = dp(1.2f)
        for (b in boxes) {
            val k = b.key
            val isPressed = b in pressed
            if (!isPressed && !(k.type == KeyType.ENTER && coloredEnter)) {
                keyPaint.color = theme.keyShadow
                canvas.drawRoundRect(b.rect.left, b.rect.top + edge, b.rect.right, b.rect.bottom + edge, radius, radius, keyPaint)
            }
            keyPaint.color = when {
                k.type == KeyType.ENTER && coloredEnter ->
                    if (isPressed) blend(theme.accent, 0xFF000000.toInt(), 0.2f) else theme.accent
                k.type == KeyType.SHIFT && shiftState != ShiftState.OFF -> if (isPressed) theme.keyPressed else theme.key
                k.isFunction -> if (isPressed) theme.functionKeyPressed else theme.functionKey
                else -> if (isPressed) theme.keyPressed else theme.key
            }
            canvas.drawRoundRect(b.rect, radius, radius, keyPaint)
            drawKeyContent(canvas, b)
        }
    }

    private fun drawKeyContent(canvas: Canvas, b: Box) {
        val k = b.key
        val r = b.rect
        val iconSize = minOf(r.height() * 0.52f, dp(26f))
        iconRect.set(r.centerX() - iconSize / 2, r.centerY() - iconSize / 2, r.centerX() + iconSize / 2, r.centerY() + iconSize / 2)
        when (k.type) {
            KeyType.CHAR -> {
                val label = displayLabel(k)
                val hint = k.hint
                val topHint = hint != null && !k.label[0].isLetter()
                textPaint.color = theme.text
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = sp(
                    when {
                        label.length > 1 -> 16f
                        label[0].isLetter() -> 24f
                        layout.page == Page.LETTERS && !label[0].isDigit() -> 24f // , and .
                        else -> 21f // digits and symbols, like SwiftKey's symbol pages
                    },
                )
                fitTo(label, r.width())
                when {
                    k.subLabel != null -> {
                        drawText(canvas, label, r.centerX(), r.centerY() - r.height() * 0.1f)
                        textPaint.textSize = sp(10f)
                        textPaint.color = theme.hint
                        drawText(canvas, k.subLabel, r.centerX(), r.centerY() + r.height() * 0.25f)
                    }
                    // With a hint above it (",!?" on the full stop) the symbol sits a little lower.
                    topHint -> drawText(canvas, label, r.centerX(), r.centerY() + dp(4f), centerGlyph = label in CJK_PUNCT)
                    else -> drawText(canvas, label, r.centerX(), r.centerY(), centerGlyph = label in CJK_PUNCT)
                }
                if (hint != null) {
                    hintPaint.color = theme.hint
                    if (topHint) {
                        // Punctuation hints are always shown, centred above the symbol.
                        hintPaint.textSize = sp(13f)
                        hintPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(hint, r.centerX(), r.top + dp(4f) - hintPaint.ascent(), hintPaint)
                    } else if (showHints) {
                        // Optional digit hints on the top letter row, top-right corner.
                        hintPaint.textSize = sp(11f)
                        hintPaint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(hint, r.right - dp(5f), r.top + dp(3f) - hintPaint.ascent(), hintPaint)
                    }
                }
            }
            KeyType.SPACE -> if (layout.page != Page.LETTERS) {
                // Symbol pages show a space symbol instead of the language, like SwiftKey.
                Icons.draw(canvas, Icon.SPACE, iconRect, theme.text)
            } else {
                textPaint.color = theme.hint
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = sp(16f)
                val label = spaceLabel
                if (languageCount > 1 && r.width() > dp(150f)) {
                    // ‹ › at the ends of the space bar, dimmer than the label (like SwiftKey).
                    textPaint.color = blend(theme.hint, theme.key, 0.5f)
                    drawText(canvas, "‹", r.left + dp(14f), r.centerY())
                    drawText(canvas, "›", r.right - dp(14f), r.centerY())
                    textPaint.color = theme.hint
                }
                val tw = textPaint.measureText(label)
                if (incognito) {
                    val s = dp(16f)
                    val left = r.centerX() - (tw + s + dp(6f)) / 2f
                    iconRect.set(left, r.centerY() - s / 2, left + s, r.centerY() + s / 2)
                    Icons.draw(canvas, Icon.INCOGNITO, iconRect, theme.incognitoAccent, theme.key)
                    drawText(canvas, label, left + s + dp(6f) + tw / 2f, r.centerY())
                } else {
                    drawText(canvas, label, r.centerX(), r.centerY())
                }
            }
            KeyType.SHIFT -> {
                val icon = when (shiftState) {
                    ShiftState.OFF -> Icon.SHIFT
                    ShiftState.ON -> Icon.SHIFT_ON
                    ShiftState.LOCKED -> Icon.SHIFT_LOCKED
                }
                Icons.draw(canvas, icon, iconRect, if (shiftState == ShiftState.LOCKED) theme.accent else theme.text)
            }
            KeyType.BACKSPACE -> Icons.draw(canvas, Icon.BACKSPACE, iconRect, theme.text)
            KeyType.ENTER -> {
                val color = if (coloredEnter) theme.onAccent else theme.text
                val label = enterLabel
                if (label != null) {
                    textPaint.color = color
                    textPaint.typeface = Typeface.DEFAULT
                    textPaint.textSize = sp(16f)
                    fitTo(label, r.width())
                    drawText(canvas, label, r.centerX(), r.centerY())
                } else {
                    Icons.draw(canvas, enterIcon, iconRect, color)
                }
            }
            KeyType.CURSOR_LEFT -> Icons.draw(canvas, Icon.BACK, iconRect, theme.text)
            KeyType.CURSOR_RIGHT -> Icons.draw(canvas, Icon.FORWARD, iconRect, theme.text)
            KeyType.KANA_MOD -> {
                textPaint.color = theme.text
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = sp(17f)
                fitTo(k.label, r.width())
                drawText(canvas, k.label, r.centerX(), r.centerY())
            }
            KeyType.EMOJI -> Icons.draw(canvas, Icon.EMOJI, iconRect, theme.text)
            KeyType.TAB -> Icons.draw(canvas, Icon.TAB, iconRect, theme.text)
            KeyType.SYMBOLS, KeyType.LETTERS, KeyType.MORE_SYMBOLS, KeyType.LANGUAGE -> {
                textPaint.color = theme.text
                textPaint.typeface = Typeface.DEFAULT
                // "abc" is lower case and reads larger, so it is set a bit smaller than "123".
                textPaint.textSize = sp(if (k.label.all { it.isLetter() }) 18f else 21f)
                fitTo(k.label, r.width())
                drawText(canvas, k.label, r.centerX(), r.centerY())
            }
            KeyType.SPACER -> Unit
        }
    }

    /** Shrinks the text so [s] fits in a key [keyWidth] wide (at the largest system font sizes). */
    private fun fitTo(s: String, keyWidth: Float) {
        val max = keyWidth - dp(6f)
        val w = textPaint.measureText(s)
        if (w > max && max > 0f) textPaint.textSize *= max / w
    }

    private val glyphBounds = android.graphics.Rect()

    /**
     * [centerGlyph]: centre the ink itself — 、 and 。 sit in the corner of their character cell
     * and would look off-centre on a key.
     */
    private fun drawText(canvas: Canvas, s: String, cx: Float, cy: Float, centerGlyph: Boolean = false) {
        if (centerGlyph) {
            textPaint.getTextBounds(s, 0, s.length, glyphBounds)
            val align = textPaint.textAlign
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(s, cx - glyphBounds.exactCenterX(), cy - glyphBounds.exactCenterY(), textPaint)
            textPaint.textAlign = align
            return
        }
        canvas.drawText(s, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun displayLabel(k: Key): String {
        if (layout.page != Page.LETTERS || shiftState == ShiftState.OFF) return k.label
        if (k.label == "ß" || !k.label[0].isLetter()) return k.label
        return k.label.uppercase()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) * (1 - t) + ch(b, s) * t).roundToInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // ---- previews and popups ----------------------------------------------------------

    private fun overlayOffset(): FloatArray {
        val ov = overlay ?: return floatArrayOf(0f, 0f)
        // Through the view tree (works before the keyboard is on screen, too).
        var x = 0f
        var y = 0f
        var v: View = this
        while (true) {
            x += v.left - v.scrollX
            y += v.top - v.scrollY
            val parent = v.parent as? View ?: break
            if (parent === ov.parent) return floatArrayOf(x - ov.left, y - ov.top)
            v = parent
        }
        return floatArrayOf(0f, 0f)
    }

    private fun showPreview(p: Ptr) {
        val ov = overlay ?: return
        val k = p.box.key
        if (!showPreviews || k.type != KeyType.CHAR) {
            ov.setPreview(p.id, null)
            return
        }
        val off = overlayOffset()
        val r = p.box.rect
        val w = maxOf(r.width() * 1.15f, dp(42f))
        val h = r.height() * 1.15f
        var top = r.top - h - dp(4f) + off[1]
        if (top < 0f) top = 0f
        val left = (r.centerX() - w / 2f + off[0]).coerceIn(0f, maxOf(0f, ov.width - w))
        ov.setPreview(p.id, PreviewOverlay.Preview(RectF(left, top, left + w, top + h), k.flick.firstOrNull() ?: displayLabel(k)))
    }

    // ---- 12-key flick -----------------------------------------------------------------

    private fun flickDir(p: Ptr): Int {
        val dx = p.x - p.downX
        val dy = p.y - p.downY
        val th = dp(FLICK_THRESHOLD_DP)
        if (dx * dx + dy * dy < th * th) return 0
        return if (abs(dx) > abs(dy)) (if (dx < 0) 1 else 3) else (if (dy < 0) 2 else 4)
    }

    /**
     * Flick feedback: the tap character as a normal preview, the flicked character next to the
     * key in that direction, or (after holding) a cross with all five characters.
     */
    private fun showFlick(p: Ptr) {
        val ov = overlay ?: return
        val items = p.box.key.flick
        if (!p.flickGuide && p.flickDir == 0) {
            ov.setPopup(null)
            showPreview(p)
            return
        }
        hidePreview(p)
        val off = overlayOffset()
        val r = p.box.rect
        val s = r.height() * 1.05f
        val cx = r.centerX() + off[0]
        val cy = r.centerY() + off[1]
        // The guide is a cross of cells around the key's centre; a single flicked character sits
        // on the key's edge in that direction.
        fun cell(i: Int, guide: Boolean): RectF {
            val (dx, dy) = FLICK_OFFSETS[i]
            val ox = if (guide) dx * s else dx * r.width() / 2f
            val oy = if (guide) dy * s else dy * r.height() / 2f
            val left = (cx + ox - s / 2f).coerceIn(0f, maxOf(0f, ov.width - s))
            val top = (cy + oy - s / 2f).coerceIn(0f, maxOf(0f, ov.height - s))
            return RectF(left, top, left + s, top + s)
        }
        if (p.flickGuide) {
            val shown = items.indices.filter { items[it].isNotEmpty() }
            ov.setPopup(PreviewOverlay.Popup(shown.map { cell(it, true) }, shown.map { items[it] }, shown.indexOf(p.flickDir), null))
        } else {
            ov.setPopup(PreviewOverlay.Popup(listOf(cell(p.flickDir, false)), listOf(items[p.flickDir]), 0, null))
        }
    }

    private fun hidePreview(p: Ptr) = overlay?.setPreview(p.id, null)

    private fun openPopup(p: Ptr, items: List<String>) {
        val ov = overlay ?: return
        val off = overlayOffset()
        val r = p.box.rect
        val cellW = maxOf(r.width(), dp(38f))
        val cellH = r.height() * 0.92f
        val pad = dp(4f)
        val panelW = items.size * cellW + 2 * pad
        val maxLeft = ov.width - panelW - dp(2f)
        var left = r.centerX() - cellW / 2f - pad + off[0]
        // Near the right edge the list runs leftwards so the default item stays near the finger.
        val reversed = left > maxLeft
        if (reversed) left = r.centerX() + cellW / 2f + pad + off[0] - panelW
        left = left.coerceIn(dp(2f), maxOf(dp(2f), maxLeft))
        val top = (r.top - cellH - 2 * pad - dp(6f) + off[1]).coerceAtLeast(0f)
        val cells = items.indices.map { i ->
            val slot = if (reversed) items.size - 1 - i else i
            val x = left + pad + slot * cellW
            RectF(x, top + pad, x + cellW, top + pad + cellH)
        }
        p.popupItems = items
        p.popupCells = cells
        p.popupSelected = 0
        p.popupOpenX = p.x
        hidePreview(p)
        ov.setPopup(PreviewOverlay.Popup(cells, items, 0, RectF(left, top, left + panelW, top + cellH + 2 * pad)))
    }

    private fun updatePopup(p: Ptr) {
        val items = p.popupItems ?: return
        val off = overlayOffset()
        val fx = p.x + off[0]
        val sel = if (abs(p.x - p.popupOpenX) < p.popupCells[0].width() * 0.5f) {
            0
        } else {
            p.popupCells.indices.minBy { abs(p.popupCells[it].centerX() - fx) }
        }
        // Dragging far below the key cancels the popup selection.
        val cancel = p.y > p.box.rect.bottom + p.box.rect.height()
        val selected = if (cancel) -1 else sel
        if (selected != p.popupSelected) {
            p.popupSelected = selected
            val panel = RectF(
                p.popupCells.minOf { it.left } - dp(4f), p.popupCells[0].top - dp(4f),
                p.popupCells.maxOf { it.right } + dp(4f), p.popupCells[0].bottom + dp(4f),
            )
            overlay?.setPopup(PreviewOverlay.Popup(p.popupCells, items, selected, panel))
        }
    }

    // ---- touch ------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                // Fast typing: a new finger finishes a letter that is still held down.
                for (other in pointers.values.toList()) {
                    if (other.box.key.type == KeyType.CHAR && other.popupItems == null && !other.longFired) release(other)
                }
                val x = e.getX(i)
                val y = e.getY(i)
                val box = boxAt(x, y) ?: return true
                val p = Ptr(e.getPointerId(i), box, x, y)
                pointers[p.id] = p
                down(p)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = pointers[e.getPointerId(i)] ?: continue
                    p.x = e.getX(i)
                    p.y = e.getY(i)
                    move(p)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val p = pointers[e.getPointerId(e.actionIndex)]
                if (p != null) {
                    p.x = e.getX(e.actionIndex)
                    p.y = e.getY(e.actionIndex)
                    release(p)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(p: Ptr) {
        val k = p.box.key
        listener.onKeyFeedback(k)
        when (k.type) {
            KeyType.CHAR -> if (k.flick.isNotEmpty()) {
                showFlick(p)
                handler.postDelayed(p.longPress, longPressTimeout)
            } else {
                showPreview(p)
                if (k.popup.isNotEmpty()) handler.postDelayed(p.longPress, longPressTimeout)
            }
            KeyType.CURSOR_LEFT, KeyType.CURSOR_RIGHT -> {
                fire(k)
                handler.postDelayed(p.cursorRepeat, HOLD_TO_WORDS_MS)
            }
            KeyType.BACKSPACE -> {
                // A tap deletes one character; holding switches to whole words.
                fire(k)
                handler.postDelayed(p.repeat, HOLD_TO_WORDS_MS)
            }
            KeyType.SPACE -> handler.postDelayed(p.longPress, 380L)
            KeyType.LANGUAGE -> handler.postDelayed(p.longPress, 450L)
            KeyType.SHIFT -> handler.postDelayed(p.longPress, 450L)
            else -> Unit
        }
    }

    private fun onLongPress(p: Ptr) {
        if (pointers[p.id] !== p) return
        p.longFired = true
        val k = p.box.key
        when (k.type) {
            KeyType.CHAR -> if (k.flick.isNotEmpty()) {
                p.flickGuide = true
                showFlick(p)
            } else {
                val items = k.popup.map { if (shiftState != ShiftState.OFF && layout.page == Page.LETTERS && it != "ß") it.uppercase() else it }
                listener.onKeyFeedback(k)
                openPopup(p, items)
            }
            KeyType.SPACE -> {
                p.cursorMode = true
                p.cursorX = p.x
                listener.onKeyFeedback(k)
            }
            KeyType.SHIFT -> listener.onShiftLongPress()
            KeyType.LANGUAGE -> {
                listener.onKeyFeedback(k)
                listener.onLanguageMenu()
            }
            else -> Unit
        }
    }

    private fun move(p: Ptr) {
        val k = p.box.key
        when {
            p.popupItems != null -> updatePopup(p)
            k.type == KeyType.CHAR && k.flick.isNotEmpty() -> {
                val d = flickDir(p)
                if (d != p.flickDir) {
                    p.flickDir = d
                    if (d != 0) handler.removeCallbacks(p.longPress)
                    showFlick(p)
                }
            }
            k.type == KeyType.SPACE -> {
                val dx = p.x - p.downX
                val step = dp(11f)
                if (!p.cursorMode && p.langSwipe == 0 && abs(dx) > dp(26f)) {
                    handler.removeCallbacks(p.longPress)
                    if (languageCount > 1) {
                        p.langSwipe = if (dx > 0) 1 else -1
                    } else {
                        // One language: sliding on the space bar moves the cursor right away.
                        p.cursorMode = true
                        p.cursorX = p.downX
                    }
                }
                if (p.cursorMode) {
                    val steps = ((p.x - p.cursorX) / step).toInt()
                    if (steps != 0) {
                        listener.onCursorMove(steps)
                        p.cursorX += steps * step
                    }
                }
            }
            k.type == KeyType.BACKSPACE -> {
                if (!p.deleteSwipe && p.x - p.downX < -dp(40f)) {
                    p.deleteSwipe = true
                    handler.removeCallbacks(p.repeat)
                }
            }
            k.type == KeyType.CHAR && !p.longFired -> {
                // Sliding off a letter before lifting picks the letter under the finger.
                if (!p.box.hit.contains(p.x, p.y)) {
                    val nb = boxAt(p.x, p.y)
                    if (nb != null && nb !== p.box && nb.key.type == KeyType.CHAR) {
                        handler.removeCallbacks(p.longPress)
                        p.box = nb
                        showPreview(p)
                        if (nb.key.popup.isNotEmpty()) handler.postDelayed(p.longPress, longPressTimeout)
                        invalidate()
                    }
                }
            }
        }
    }

    private fun release(p: Ptr) {
        pointers.remove(p.id)
        handler.removeCallbacks(p.longPress)
        handler.removeCallbacks(p.repeat)
        handler.removeCallbacks(p.cursorRepeat)
        hidePreview(p)
        val k = p.box.key
        when (k.type) {
            KeyType.CHAR -> if (k.flick.isNotEmpty()) {
                overlay?.setPopup(null)
                val ch = k.flick.getOrNull(flickDir(p))?.takeIf { it.isNotEmpty() } ?: k.flick[0]
                listener.onKeyText(ch)
            } else {
                val items = p.popupItems
                if (items != null) {
                    overlay?.setPopup(null)
                    if (p.popupSelected >= 0) listener.onKeyText(items[p.popupSelected].lowercaseIfShifted())
                } else {
                    fire(k)
                }
            }
            KeyType.SPACE -> when {
                p.cursorMode -> Unit
                p.langSwipe != 0 -> listener.onLanguageSwipe(p.langSwipe)
                else -> fire(k)
            }
            KeyType.BACKSPACE -> if (p.deleteSwipe) listener.onDeleteWord()
            // Already done when the finger went down.
            KeyType.CURSOR_LEFT, KeyType.CURSOR_RIGHT -> Unit
            KeyType.SHIFT, KeyType.LANGUAGE -> if (!p.longFired) fire(k)
            else -> fire(k)
        }
        invalidate()
    }

    /** Popup items are shown in the current case; the input logic applies shift itself. */
    private fun String.lowercaseIfShifted(): String =
        if (shiftState != ShiftState.OFF && layout.page == Page.LETTERS) lowercase() else this

    fun cancelAll() {
        for (p in pointers.values) {
            handler.removeCallbacks(p.longPress)
            handler.removeCallbacks(p.repeat)
            handler.removeCallbacks(p.cursorRepeat)
        }
        pointers.clear()
        overlay?.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }

    /** For tests: the key rectangles in view coordinates. */
    internal fun keyBoxesForTest(): List<Pair<Key, RectF>> = boxes.map { it.key to it.rect }

    companion object {
        /** How long backspace must be held before it starts deleting whole words. */
        const val HOLD_TO_WORDS_MS = 400L
        const val WORD_REPEAT_MS = 300L
        const val WORD_REPEAT_FAST_MS = 200L
        const val CURSOR_REPEAT_MS = 70L
        private val CJK_PUNCT = setOf("、", "。")
        /** TalkBack custom actions for a key's other characters (outside Android's own action ids). */
        private const val ALT_ACTION = 0x7f7f0000
        /** How far a finger must move for a flick on the 12-key layout. */
        const val FLICK_THRESHOLD_DP = 18f
        /** Where each flick direction's character is shown, in cell sizes from the key centre. */
        private val FLICK_OFFSETS = listOf(0f to 0f, -1f to 0f, 0f to -1f, 1f to 0f, 0f to 1f)
    }
}
