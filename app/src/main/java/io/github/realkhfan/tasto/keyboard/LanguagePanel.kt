package io.github.realkhfan.tasto.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** The keyboard's languages: tap one to type in it. Opened from the 🌐 toolbar button or by holding ABC. */
@SuppressLint("ViewConstructor")
class LanguagePanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    /** One language in the list. */
    data class Item(val id: String, val name: String, val detail: String)

    interface Listener {
        fun languageItems(): List<Item>
        fun currentLanguageId(): String
        fun onLanguagePicked(id: String)
        fun onLanguagesClose()
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

    fun refresh() {
        setBackgroundColor(theme.background)
        header.removeAllViews()
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(px(14f), 0, px(4f), 0)
        header.addView(TextView(context).apply {
            text = "Languages"
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "ABC"
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(theme.functionKey, 7f)
            setOnClickListener { listener.onLanguagesClose() }
        }, LayoutParams(px(56f), px(34f)).apply { marginEnd = px(4f) })

        list.removeAllViews()
        list.setPadding(px(5f), 0, px(5f), px(6f))
        // Two tiles per row, so all languages fit in the keyboard's height without scrolling.
        val current = listener.currentLanguageId()
        var line: LinearLayout? = null
        listener.languageItems().forEachIndexed { i, item ->
            if (i % 2 == 0) {
                line = LinearLayout(context).also {
                    list.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
                }
            }
            line!!.addView(tile(item, item.id == current), LayoutParams(0, px(84f), 1f).apply {
                marginStart = px(3f); marginEnd = px(3f)
            })
        }
        if (listener.languageItems().size % 2 == 1) line?.addView(android.view.View(context), LayoutParams(0, px(84f), 1f))
    }

    private fun tile(item: Item, selected: Boolean): LinearLayout {
        val tile = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (selected) {
                GradientDrawable().apply {
                    setColor(theme.key)
                    cornerRadius = 10f * resources.displayMetrics.density
                    setStroke(px(2f), theme.accent)
                }
            } else {
                rounded(theme.key, 10f)
            }
            setPadding(px(12f), px(6f), px(10f), px(6f))
            contentDescription = item.name
            setOnClickListener { listener.onLanguagePicked(item.id) }
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            text = item.name
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            if (selected) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            top.addView(ImageView(context).apply {
                setImageDrawable(IconDrawable(Icon.CHECK, theme.accent))
                contentDescription = "Current language"
            }, LayoutParams(px(22f), px(22f)))
        }
        tile.addView(top)
        tile.addView(TextView(context).apply {
            text = item.detail
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
        })
        return tile
    }

    /** For tests: the row for a language. */
    internal fun rowForTest(name: String): android.view.View? {
        val found = ArrayList<android.view.View>()
        list.findViewsWithText(found, name, FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return found.firstOrNull { it.contentDescription == name }
    }
}
