package io.github.realkhfan.tasto.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Emoji grouped by category, loaded once from the app's assets. */
object EmojiData {
    val CATEGORIES = listOf("recent", "smileys", "nature", "food", "travel", "activities", "objects", "symbols", "flags")
    private val TAB_GLYPH = mapOf(
        "smileys" to "😀", "nature" to "🐻", "food" to "🍔", "travel" to "🚗",
        "activities" to "⚽", "objects" to "💡", "symbols" to "🔣", "flags" to "🏳",
    )
    private var groups: Map<String, List<String>>? = null

    fun tabGlyph(cat: String) = TAB_GLYPH[cat]

    fun groups(context: Context): Map<String, List<String>> {
        groups?.let { return it }
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        val probe = Paint()
        context.assets.open("emoji.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) {
                    cur = out.getOrPut(line.substring(1)) { ArrayList() }
                } else if (line.isNotEmpty() && probe.hasGlyph(line)) {
                    cur?.add(line)
                }
            }
        }
        return out.also { groups = it }
    }
}

/**
 * Emoji keyboard: a scrolling grid per category, category tabs, ABC and backspace.
 * Recent emoji are kept unless incognito is on.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onEmojiBackspace()
        fun onEmojiClose()
        fun emojiRecents(): List<String>
        fun onKeyFeedbackLight()
    }

    var theme: KeyboardTheme = KeyboardTheme.dark(KeyboardTheme.ACCENTS.getValue("blue"))
        set(v) {
            field = v
            setBackgroundColor(v.background)
            grid.invalidate()
            tabs.invalidate()
        }

    private var category = "smileys"
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val grid = Grid()
    private val tabs = Tabs()

    init {
        orientation = VERTICAL
        scroll.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 46).roundToInt()))
    }

    fun open() {
        category = if (listener.emojiRecents().isNotEmpty()) "recent" else "smileys"
        showCategory(category)
    }

    // ---- test helpers (positions in this view's coordinates) ----

    internal fun abcCenterForTest() = android.graphics.PointF(dp(28f), tabs.top + tabs.height / 2f)

    internal fun firstEmojiCenterForTest() = android.graphics.PointF(dp(23f), scroll.top + dp(27f))

    internal val shownEmojiForTest: List<String> get() = grid.items

    private fun showCategory(cat: String) {
        category = cat
        grid.items = if (cat == "recent") listener.emojiRecents() else EmojiData.groups(context)[cat].orEmpty()
        scroll.scrollTo(0, 0)
        tabs.invalidate()
    }

    private inner class Grid : View(context) {
        var items: List<String> = emptyList()
            set(v) { field = v; requestLayout(); invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val cell get() = dp(46f)
        private val cols get() = maxOf(6, (width / cell).toInt())
        private var downX = 0f
        private var downY = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val c = maxOf(6, (w / cell).toInt())
            val rows = (items.size + c - 1) / c
            setMeasuredDimension(w, (rows * cell + dp(8f)).roundToInt())
        }

        override fun onDraw(canvas: Canvas) {
            val cw = width / cols.toFloat()
            paint.textSize = sp(27f)
            items.forEachIndexed { i, e ->
                val cx = (i % cols) * cw + cw / 2f
                val cy = (i / cols) * cell + cell / 2f + dp(4f)
                canvas.drawText(e, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> if (abs(e.x - downX) < dp(10f) && abs(e.y - downY) < dp(10f)) {
                    val cw = width / cols.toFloat()
                    val col = (e.x / cw).toInt().coerceIn(0, cols - 1)
                    val row = ((e.y - dp(4f)) / cell).toInt()
                    val i = row * cols + col
                    if (i in items.indices) {
                        listener.onKeyFeedbackLight()
                        listener.onEmoji(items[i])
                    }
                }
            }
            return true
        }
    }

    private inner class Tabs : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val handler = Handler(Looper.getMainLooper())
        private var holdingBackspace = false
        private val repeat = object : Runnable {
            override fun run() {
                listener.onEmojiBackspace()
                handler.postDelayed(this, 60L)
            }
        }
        private val sideW get() = dp(56f)

        private fun tabCount() = EmojiData.CATEGORIES.size
        private fun tabW() = (width - 2 * sideW) / tabCount()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(theme.background)
            paint.color = theme.text
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = sp(14f)
            pill.color = theme.functionKey
            canvas.drawRoundRect(dp(4f), dp(6f), sideW - dp(4f), height - dp(6f), dp(7f), dp(7f), pill)
            canvas.drawText("ABC", sideW / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
            canvas.drawRoundRect(width - sideW + dp(4f), dp(6f), width - dp(4f), height - dp(6f), dp(7f), dp(7f), pill)
            val s = dp(22f)
            rect.set(width - sideW / 2f - s / 2, height / 2f - s / 2, width - sideW / 2f + s / 2, height / 2f + s / 2)
            Icons.draw(canvas, Icon.BACKSPACE, rect, theme.text)
            val tw = tabW()
            EmojiData.CATEGORIES.forEachIndexed { i, cat ->
                val cx = sideW + tw * i + tw / 2f
                if (cat == category) {
                    pill.color = theme.key
                    canvas.drawRoundRect(cx - tw / 2f + dp(1f), dp(6f), cx + tw / 2f - dp(1f), height - dp(6f), dp(7f), dp(7f), pill)
                    pill.color = theme.functionKey
                }
                val icon = minOf(tw, dp(20f))
                if (cat == "recent") {
                    rect.set(cx - icon / 2, height / 2f - icon / 2, cx + icon / 2, height / 2f + icon / 2)
                    Icons.draw(canvas, Icon.CLOCK, rect, theme.text)
                } else {
                    paint.textSize = minOf(sp(18f), tw * 0.7f)
                    canvas.drawText(EmojiData.tabGlyph(cat) ?: "", cx, height / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    listener.onKeyFeedbackLight()
                    if (e.x > width - sideW) {
                        holdingBackspace = true
                        listener.onEmojiBackspace()
                        handler.postDelayed(repeat, 400L)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeat)
                    if (holdingBackspace) {
                        holdingBackspace = false
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        when {
                            e.x < sideW -> listener.onEmojiClose()
                            e.x < width - sideW -> {
                                val i = ((e.x - sideW) / tabW()).toInt().coerceIn(0, tabCount() - 1)
                                showCategory(EmojiData.CATEGORIES[i])
                            }
                        }
                    }
                }
            }
            return true
        }
    }
}
