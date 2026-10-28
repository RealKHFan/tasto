package io.github.realkhfan.tasto.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.Slot
import io.github.realkhfan.tasto.ime.SlotStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The strip above the keys, laid out like SwiftKey's:
 *
 *  - toolbar row (clipboard, incognito, settings, hide) — shown when expanded with the round
 *    button, and always while the emoji or clipboard panel is open (then with a ‹ back arrow);
 *  - suggestion row — round ⌄/^ button on the left, three suggestions with the best one in the
 *    middle (or the "Forget word?" question). Hidden while a panel is open. For Japanese it is a
 *    scrolling row of conversion candidates instead.
 */
@SuppressLint("ViewConstructor")
class TopBarView(context: Context, private val listener: Listener) : View(context) {

    enum class Action { CLIPBOARD, LANGUAGES, INCOGNITO, SETTINGS, HIDE }

    interface Listener {
        fun onSlot(slot: Slot)
        fun onSlotLongPress(slot: Slot)
        fun onToolbar(action: Action)
        fun onForgetConfirmed(word: String)
        fun onKeyFeedbackLight()
        /** The ‹ arrow shown while the emoji or clipboard panel is open. */
        fun onBarBack()
        /** The round button left of the suggestions: show or hide the toolbar row. */
        fun onToolbarToggle()
        /** A Japanese conversion candidate was tapped. */
        fun onCandidate(index: Int)
    }

    var theme: KeyboardTheme = KeyboardTheme.dark(KeyboardTheme.ACCENTS.getValue("blue"))
        set(v) { field = v; invalidate() }
    var bar: BarState = BarState.EMPTY
        set(v) {
            if (v.candidates != field.candidates) {
                candScroll = 0f
                candLayoutFor = null
            }
            field = v
            if (!v.isEmpty) forgetWord = null
            revealSelectedCandidate()
            changed()
        }
    var incognito = false
        set(v) { field = v; changed() }
    /** Toolbar row visible above the suggestions (the round button). */
    var expanded = false
        set(v) { if (field != v) { field = v; requestLayout(); changed() } }
    /** A learned word the user long-pressed; asks whether to forget it. */
    var forgetWord: String? = null
        set(v) { field = v; changed() }
    /** The panel below the bar. */
    var panel: Panel = Panel.KEYS
        set(v) { if (field != v) { field = v; requestLayout(); changed() } }

    private fun changed() {
        invalidate()
        a11y.invalidateRoot()
    }

    // ---- TalkBack: every button, suggestion and candidate is its own item ----------------

    private val a11y = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            a11yItems().firstOrNull { a11yRect(it).contains(x, y) } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids.addAll(a11yItems())
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val r = Rect()
            a11yRect(id).roundOut(r)
            if (r.isEmpty) r.set(0, 0, 1, 1) // an item that just went away still needs bounds
            @Suppress("DEPRECATION")
            node.setBoundsInParent(r)
            node.contentDescription = a11yText(id)
            if (id != A11Y_FORGET_MSG) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            if (id in A11Y_SLOT until A11Y_SLOT + 3) node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            val slot = slotAt(id - A11Y_SLOT)
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> listener.onSlotLongPress(slot ?: return false)
                AccessibilityNodeInfoCompat.ACTION_CLICK -> when {
                    id == A11Y_ROUND -> if (inPanel) listener.onBarBack() else listener.onToolbarToggle()
                    id in A11Y_ACTION until A11Y_ACTION + actions.size -> listener.onToolbar(actions[id - A11Y_ACTION])
                    slot != null -> listener.onSlot(slot)
                    id == A11Y_FORGET -> forgetWord?.let { listener.onForgetConfirmed(it); forgetWord = null }
                    id == A11Y_KEEP -> forgetWord = null
                    id >= A11Y_CAND -> listener.onCandidate(id - A11Y_CAND)
                    else -> return false
                }
                else -> return false
            }
            return true
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, a11y)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** The items TalkBack can reach, in reading order. */
    private fun a11yItems(): List<Int> {
        val ids = ArrayList<Int>()
        if (toolbarRowVisible) {
            if (inPanel) ids.add(A11Y_ROUND)
            ids.addAll(actions.indices.map { A11Y_ACTION + it })
        }
        if (suggestionRowVisible) {
            when {
                forgetWord != null -> ids.addAll(listOf(A11Y_FORGET_MSG, A11Y_FORGET, A11Y_KEEP))
                bar.candidates != null -> {
                    ids.add(A11Y_ROUND)
                    layoutCandidates()
                    ids.addAll(candLefts.indices.filter { !a11yRect(A11Y_CAND + it).isEmpty }.map { A11Y_CAND + it })
                }
                else -> {
                    ids.add(A11Y_ROUND)
                    ids.addAll((0..2).filter { bar.slots[it] != null }.map { A11Y_SLOT + it })
                }
            }
        }
        return ids
    }

    private fun a11yRect(id: Int): RectF {
        val top = suggestionTop
        val bottom = top + suggestionH
        return when {
            id == A11Y_ROUND -> if (inPanel) RectF(0f, 0f, leftW, toolbarH) else RectF(0f, top, circleHit, bottom)
            id in A11Y_ACTION until A11Y_ACTION + actions.size -> {
                val start = toolbarStart()
                val w = (width - start) / actions.size
                val i = id - A11Y_ACTION
                RectF(start + w * i, 0f, start + w * (i + 1), toolbarH)
            }
            id in A11Y_SLOT until A11Y_SLOT + 3 -> {
                val x0 = slotStart + (id - A11Y_SLOT) * slotW
                RectF(x0, top, x0 + slotW, bottom)
            }
            id == A11Y_FORGET_MSG -> RectF(0f, top, forgetButtons().first.left, bottom)
            id == A11Y_FORGET -> forgetButtons().first
            id == A11Y_KEEP -> forgetButtons().second
            id >= A11Y_CAND -> {
                val i = id - A11Y_CAND
                if (i >= candLefts.size) return RectF()
                val x0 = candStart + candLefts[i] - candScroll
                // Only the part inside the strip counts; candidates scrolled away are left out.
                RectF(maxOf(x0, candStart), top, minOf(x0 + candWidths[i], width.toFloat()), bottom).takeIf { it.width() > 0 } ?: RectF()
            }
            else -> RectF()
        }
    }

    private fun a11yText(id: Int): String = when {
        id == A11Y_ROUND -> when {
            inPanel -> "Back to keys"
            expanded -> "Hide toolbar"
            else -> "Show toolbar"
        }
        id in A11Y_ACTION until A11Y_ACTION + actions.size -> when (actions[id - A11Y_ACTION]) {
            Action.CLIPBOARD -> "Clipboard"
            Action.LANGUAGES -> "Languages"
            Action.INCOGNITO -> if (incognito) "Incognito, on" else "Incognito, off"
            Action.SETTINGS -> "Tasto settings"
            Action.HIDE -> "Hide keyboard"
        }
        id in A11Y_SLOT until A11Y_SLOT + 3 -> slotAt(id - A11Y_SLOT)?.word ?: ""
        id == A11Y_FORGET_MSG -> "Forget “${forgetWord ?: ""}”?"
        id == A11Y_FORGET -> "Forget"
        id == A11Y_KEEP -> "Keep"
        id >= A11Y_CAND -> {
            val i = id - A11Y_CAND
            val c = bar.candidates?.getOrNull(i) ?: ""
            if (i == bar.selected) "$c, selected" else c
        }
        else -> ""
    }

    private val inPanel: Boolean get() = panel != Panel.KEYS
    val toolbarRowVisible: Boolean get() = inPanel || expanded
    private val suggestionRowVisible: Boolean get() = !inPanel
    private val actions = listOf(Action.CLIPBOARD, Action.LANGUAGES, Action.INCOGNITO, Action.SETTINGS, Action.HIDE)

    // Japanese candidates: left edges and widths in the strip, and how far it is scrolled.
    private var candLefts = FloatArray(0)
    private var candWidths = FloatArray(0)
    private var candLayoutFor: List<String>? = null
    private var candScroll = 0f
    private var candDownScroll = 0f
    private var candScrolling = false
    private var downInStrip = false
    private val candStart get() = dp(36f)

    private val handler = Handler(Looper.getMainLooper())
    private val bgPaint = Paint()
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    /** Pressed target: -2 round button / back arrow, 0..2 suggestion slot, 10+i toolbar action. */
    private var pressed = -1
    private var downX = 0f
    private var longFired = false
    private val longPress = Runnable {
        val slot = slotAt(pressed)
        if (slot != null) {
            longFired = true
            listener.onSlotLongPress(slot)
        }
    }

    private val leftW get() = dp(48f)
    // Suggestion row like SwiftKey: middle suggestion centred on the screen, round button
    // tucked into the left edge.
    private val circleCx get() = dp(17f)
    private val circleHit get() = dp(34f)
    private val slotStart get() = dp(24f)
    private val slotW get() = (width - 2 * slotStart) / 3f
    private val toolbarH get() = dp(TOOLBAR_HEIGHT_DP)
    private val suggestionH get() = dp(BAR_HEIGHT_DP)
    /** Top of the suggestion row inside this view. */
    private val suggestionTop: Float get() = if (toolbarRowVisible) toolbarH else 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (if (toolbarRowVisible) toolbarH else 0f) + (if (suggestionRowVisible) suggestionH else 0f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        revealSelectedCandidate()
        a11y.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        if (toolbarRowVisible) drawToolbarRow(canvas)
        if (suggestionRowVisible) {
            val top = suggestionTop
            bgPaint.color = if (incognito) theme.barIncognito else theme.background
            canvas.drawRect(0f, top, width.toFloat(), top + suggestionH, bgPaint)
            val forget = forgetWord
            when {
                forget != null -> drawForget(canvas, forget, top)
                bar.candidates != null -> drawCandidates(canvas, top)
                else -> drawSuggestions(canvas, top)
            }
        }
    }

    // ---- toolbar row --------------------------------------------------------------------

    private fun toolbarStart() = if (inPanel) leftW else 0f

    private fun drawToolbarRow(canvas: Canvas) {
        bgPaint.color = if (incognito && inPanel) theme.barIncognito else theme.toolbar
        canvas.drawRect(0f, 0f, width.toFloat(), toolbarH, bgPaint)
        val s = dp(23f)
        val cy = toolbarH / 2f
        if (inPanel) {
            if (pressed == -2) {
                pill.color = theme.keyPressed
                canvas.drawRoundRect(dp(4f), dp(5f), leftW - dp(4f), toolbarH - dp(5f), dp(10f), dp(10f), pill)
            }
            rect.set(leftW / 2 - s / 2, cy - s / 2, leftW / 2 + s / 2, cy + s / 2)
            Icons.draw(canvas, Icon.BACK, rect, theme.text)
        }
        val start = toolbarStart()
        val slotW = (width - start) / actions.size
        actions.forEachIndexed { i, a ->
            val cx = start + slotW * i + slotW / 2f
            val active = (a == Action.CLIPBOARD && panel == Panel.CLIPBOARD) || (a == Action.LANGUAGES && panel == Panel.LANGUAGES)
            if (10 + i == pressed || active) {
                pill.color = if (active) theme.key else theme.keyPressed
                canvas.drawRoundRect(cx - dp(22f), dp(5f), cx + dp(22f), toolbarH - dp(5f), dp(10f), dp(10f), pill)
            }
            rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            val (icon, color) = when (a) {
                Action.CLIPBOARD -> Icon.CLIPBOARD to if (active) theme.accent else theme.text
                Action.LANGUAGES -> Icon.GLOBE to if (active) theme.accent else theme.text
                Action.INCOGNITO -> Icon.INCOGNITO to if (incognito) theme.incognitoAccent else theme.text
                Action.SETTINGS -> Icon.SETTINGS to theme.text
                Action.HIDE -> Icon.HIDE to theme.text
            }
            Icons.draw(canvas, icon, rect, color, theme.toolbar)
        }
    }

    // ---- suggestion row -----------------------------------------------------------------

    private fun drawSuggestions(canvas: Canvas, top: Float) {
        val cy = top + suggestionH / 2f
        val s = dp(26f)
        rect.set(circleCx - s / 2, cy - s / 2, circleCx + s / 2, cy + s / 2)
        Icons.draw(canvas, if (expanded) Icon.CIRCLE_DOWN else Icon.CIRCLE_UP, rect, theme.text, theme.roundButton)
        val slotW = slotW
        for (i in 0 until 3) {
            val x0 = slotStart + i * slotW
            if (i == pressed) {
                pill.color = theme.key
                canvas.drawRoundRect(x0 + dp(3f), top + dp(4f), x0 + slotW - dp(3f), top + suggestionH - dp(4f), dp(8f), dp(8f), pill)
            }
            val slot = bar.slots[i] ?: continue
            text.typeface = if (slot.style == SlotStyle.AUTOCORRECT) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            text.color = when {
                slot.style == SlotStyle.AUTOCORRECT || slot.style == SlotStyle.PRIMARY -> theme.text
                else -> theme.suggestionDim
            }
            text.textSize = sp(21f)
            val raw = if (slot.style == SlotStyle.VERBATIM) "“${slot.word}”" else slot.word
            val shown = TextUtils.ellipsize(raw, text, slotW - dp(12f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, x0 + slotW / 2f, cy - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    // ---- Japanese candidates ------------------------------------------------------------

    private fun candidateText() {
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.CENTER
        text.textSize = sp(20f)
    }

    private fun layoutCandidates() {
        val list = bar.candidates ?: return
        if (candLayoutFor === list) return
        candidateText()
        candLefts = FloatArray(list.size)
        candWidths = FloatArray(list.size)
        var x = 0f
        list.forEachIndexed { i, c ->
            val w = maxOf(text.measureText(c) + dp(26f), dp(48f))
            candLefts[i] = x
            candWidths[i] = w
            x += w
        }
        candLayoutFor = list
    }

    private val candViewWidth get() = width - candStart
    private val candMaxScroll get() = maxOf(0f, (candLefts.lastOrNull() ?: 0f) + (candWidths.lastOrNull() ?: 0f) - candViewWidth)

    /** Keeps the candidate being converted to on screen while space steps through them. */
    private fun revealSelectedCandidate() {
        val sel = bar.selected
        if (bar.candidates == null || sel < 0 || width == 0) return
        layoutCandidates()
        if (sel >= candLefts.size) return
        val x0 = candLefts[sel]
        val x1 = x0 + candWidths[sel]
        if (x0 < candScroll) candScroll = x0
        if (x1 > candScroll + candViewWidth) candScroll = x1 - candViewWidth
        candScroll = candScroll.coerceIn(0f, candMaxScroll)
    }

    private fun candidateAt(x: Float): Int {
        layoutCandidates()
        val sx = x - candStart + candScroll
        for (i in candLefts.indices) if (sx >= candLefts[i] && sx < candLefts[i] + candWidths[i]) return i
        return -1
    }

    private fun drawCandidates(canvas: Canvas, top: Float) {
        val list = bar.candidates ?: return
        layoutCandidates()
        val cy = top + suggestionH / 2f
        val s = dp(26f)
        rect.set(circleCx - s / 2, cy - s / 2, circleCx + s / 2, cy + s / 2)
        Icons.draw(canvas, if (expanded) Icon.CIRCLE_DOWN else Icon.CIRCLE_UP, rect, theme.text, theme.roundButton)
        canvas.save()
        canvas.clipRect(candStart, top, width.toFloat(), top + suggestionH)
        candidateText()
        for (i in list.indices) {
            val x0 = candStart + candLefts[i] - candScroll
            val x1 = x0 + candWidths[i]
            if (x1 < candStart || x0 > width) continue
            if (i == bar.selected || 100 + i == pressed) {
                pill.color = if (i == bar.selected) theme.key else theme.keyPressed
                canvas.drawRoundRect(x0 + dp(2f), top + dp(4f), x1 - dp(2f), top + suggestionH - dp(4f), dp(8f), dp(8f), pill)
            } else if (i > 0 && i - 1 != bar.selected) {
                pill.color = theme.key
                canvas.drawRect(x0 - dp(0.5f), cy - dp(9f), x0 + dp(0.5f), cy + dp(9f), pill)
            }
            text.color = theme.text
            canvas.drawText(list[i], (x0 + x1) / 2f, cy - (text.descent() + text.ascent()) / 2f, text)
        }
        canvas.restore()
    }

    private fun forgetButtons(top: Float = suggestionTop): Pair<RectF, RectF> {
        val bw = dp(84f)
        val keep = RectF(width - bw - dp(6f), top + dp(6f), width - dp(6f), top + suggestionH - dp(6f))
        val forget = RectF(keep.left - bw - dp(6f), top + dp(6f), keep.left - dp(6f), top + suggestionH - dp(6f))
        return forget to keep
    }

    private fun drawForget(canvas: Canvas, word: String, top: Float) {
        val (forgetR, keepR) = forgetButtons(top)
        val cy = top + suggestionH / 2f
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = sp(15f)
        text.color = theme.text
        val msg = TextUtils.ellipsize("Forget “$word”?", text, forgetR.left - dp(20f), TextUtils.TruncateAt.MIDDLE).toString()
        canvas.drawText(msg, dp(14f), cy - (text.descent() + text.ascent()) / 2f, text)
        text.textAlign = Paint.Align.CENTER
        pill.color = theme.accent
        canvas.drawRoundRect(forgetR, dp(8f), dp(8f), pill)
        pill.color = theme.key
        canvas.drawRoundRect(keepR, dp(8f), dp(8f), pill)
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.color = theme.onAccent
        canvas.drawText("Forget", forgetR.centerX(), forgetR.centerY() - (text.descent() + text.ascent()) / 2f, text)
        text.color = theme.text
        canvas.drawText("Keep", keepR.centerX(), keepR.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }

    // ---- touch ----------------------------------------------------------------------------

    private fun targetAt(x: Float, y: Float): Int {
        if (toolbarRowVisible && y < toolbarH) {
            if (inPanel && x < leftW) return -2
            val start = toolbarStart()
            val i = ((x - start) / ((width - start) / actions.size)).toInt().coerceIn(0, actions.size - 1)
            return 10 + i
        }
        if (!suggestionRowVisible || forgetWord != null) return -1
        if (x < circleHit) return -2
        if (bar.candidates != null) return candidateAt(x).let { if (it >= 0) 100 + it else -1 }
        return ((x - slotStart) / slotW).toInt().coerceIn(0, 2)
    }

    private fun slotAt(i: Int): Slot? = if (i in 0..2 && suggestionRowVisible) bar.slots[i] else null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                longFired = false
                val fw = forgetWord
                if (fw != null && suggestionRowVisible && e.y >= suggestionTop) {
                    val (f, _) = forgetButtons()
                    if (f.contains(e.x, e.y)) listener.onForgetConfirmed(fw)
                    forgetWord = null
                    return true
                }
                pressed = targetAt(e.x, e.y)
                candScrolling = false
                candDownScroll = candScroll
                downInStrip = bar.candidates != null && suggestionRowVisible && e.y >= suggestionTop && e.x >= candStart
                if (pressed in 0..2) {
                    if (slotAt(pressed) == null) pressed = -1 else handler.postDelayed(longPress, 500L)
                }
                listener.onKeyFeedbackLight()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when {
                // Japanese candidates: drag sideways to see more.
                downInStrip && bar.candidates != null && (candScrolling || abs(e.x - downX) > dp(8f)) -> {
                    candScrolling = true
                    pressed = -1
                    layoutCandidates()
                    candScroll = (candDownScroll - (e.x - downX)).coerceIn(0f, candMaxScroll)
                    invalidate()
                }
                abs(e.x - downX) > dp(24f) -> {
                    handler.removeCallbacks(longPress)
                    pressed = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val t = pressed
                pressed = -1
                if (!longFired) {
                    when {
                        t == -2 && inPanel -> listener.onBarBack()
                        t == -2 -> listener.onToolbarToggle()
                        t in 0..2 -> slotAt(t)?.let { listener.onSlot(it) }
                        t >= 100 -> if (!candScrolling) listener.onCandidate(t - 100)
                        t in 10 until 10 + actions.size -> listener.onToolbar(actions[t - 10])
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    // ---- test helpers (positions in this view's coordinates) ----

    /** The round ⌄/^ button (keys showing) or the ‹ back arrow (panel open). */
    internal fun leftButtonCenterForTest() =
        if (inPanel) PointF(leftW / 2f, toolbarH / 2f) else PointF(circleCx, suggestionTop + suggestionH / 2f)

    internal fun actionCenterForTest(a: Action): PointF {
        check(toolbarRowVisible) { "toolbar row is hidden" }
        val start = toolbarStart()
        val slotW = (width - start) / actions.size
        return PointF(start + slotW * actions.indexOf(a) + slotW / 2f, toolbarH / 2f)
    }

    internal fun slotCenterForTest(i: Int): PointF =
        PointF(slotStart + slotW * i + slotW / 2f, suggestionTop + suggestionH / 2f)

    internal fun forgetButtonsForTest() = forgetButtons()

    internal fun candidateCenterForTest(i: Int): PointF {
        layoutCandidates()
        return PointF(candStart + candLefts[i] - candScroll + candWidths[i] / 2f, suggestionTop + suggestionH / 2f)
    }

    internal fun candidateScrollForTest() = candScroll

    companion object {
        const val BAR_HEIGHT_DP = 42f
        private const val A11Y_ROUND = 1
        private const val A11Y_ACTION = 10
        private const val A11Y_SLOT = 20
        private const val A11Y_FORGET_MSG = 30
        private const val A11Y_FORGET = 31
        private const val A11Y_KEEP = 32
        private const val A11Y_CAND = 100
        /** Same as the suggestion row, so opening a panel never changes the keyboard height. */
        const val TOOLBAR_HEIGHT_DP = 42f
    }
}
