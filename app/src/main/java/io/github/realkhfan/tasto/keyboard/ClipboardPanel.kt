package io.github.realkhfan.tasto.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.realkhfan.tasto.data.Clip
import kotlin.math.roundToInt

/** Recently copied text: tap to paste, pin to keep, remove one or clear all. */
@SuppressLint("ViewConstructor")
class ClipboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun clipItems(): List<Clip>
        fun clipHistoryEnabled(): Boolean
        fun onClipPaste(text: String)
        fun onClipPin(text: String)
        fun onClipRemove(text: String)
        fun onClipClear()
        fun onClipClose()
    }

    var theme: KeyboardTheme = KeyboardTheme.dark(KeyboardTheme.ACCENTS.getValue("blue"))

    private fun px(v: Float) = (v * resources.displayMetrics.density).roundToInt()
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val header = LinearLayout(context)

    init {
        orientation = VERTICAL
        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, px(44f)))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun iconButton(icon: Icon, color: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageDrawable(IconDrawable(icon, color, theme.key))
        val p = px(10f)
        setPadding(p, p, p, p)
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    fun refresh() {
        setBackgroundColor(theme.background)
        header.removeAllViews()
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(px(14f), 0, px(4f), 0)
        header.addView(TextView(context).apply {
            text = "Clipboard"
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val items = listener.clipItems()
        if (items.any { !it.pinned }) {
            header.addView(TextView(context).apply {
                text = "Clear"
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(px(12f), px(10f), px(12f), px(10f))
                setOnClickListener { listener.onClipClear(); refresh() }
            })
        }
        header.addView(TextView(context).apply {
            text = "ABC"
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(theme.functionKey, 7f)
            setOnClickListener { listener.onClipClose() }
        }, LayoutParams(px(56f), px(34f)).apply { marginEnd = px(4f) })

        list.removeAllViews()
        list.setPadding(px(8f), 0, px(8f), px(8f))
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (listener.clipHistoryEnabled()) {
                    "Text you copy shows up here for one hour.\nPin an item to keep it."
                } else {
                    "Clipboard history is off.\nTurn it on in Tasto settings → Privacy."
                }
                setTextColor(theme.hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(px(16f), px(40f), px(16f), px(16f))
            })
            return
        }
        for (clip in items) {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(theme.key, 10f)
                setPadding(px(12f), px(4f), px(2f), px(4f))
            }
            row.addView(TextView(context).apply {
                text = clip.text
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, px(8f), 0, px(8f))
                setOnClickListener { listener.onClipPaste(clip.text) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                iconButton(if (clip.pinned) Icon.PIN_FILLED else Icon.PIN, if (clip.pinned) theme.accent else theme.hint,
                    if (clip.pinned) "Unpin" else "Pin") { listener.onClipPin(clip.text); refresh() },
                LayoutParams(px(42f), px(42f)),
            )
            row.addView(
                iconButton(Icon.DELETE, theme.hint, "Remove") { listener.onClipRemove(clip.text); refresh() },
                LayoutParams(px(42f), px(42f)),
            )
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
        }
    }
}
