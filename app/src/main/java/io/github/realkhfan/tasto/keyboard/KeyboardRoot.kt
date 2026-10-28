package io.github.realkhfan.tasto.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout

enum class Panel { KEYS, EMOJI, CLIPBOARD, LANGUAGES }

/** Suggestion bar + key area (or emoji / clipboard panel) + the preview overlay on top. */
@SuppressLint("ViewConstructor")
class KeyboardRoot(
    context: Context,
    keyListener: KeyboardView.Listener,
    barListener: TopBarView.Listener,
    emojiListener: EmojiPanel.Listener,
    clipListener: ClipboardPanel.Listener,
    languageListener: LanguagePanel.Listener,
) : FrameLayout(context) {

    val bar = TopBarView(context, barListener)
    val keys = KeyboardView(context, keyListener)
    val emoji = EmojiPanel(context, emojiListener)
    val clipboard = ClipboardPanel(context, clipListener)
    val languages = LanguagePanel(context, languageListener)
    val overlay = PreviewOverlay(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val content = FrameLayout(context)
    var panel = Panel.KEYS
        private set

    init {
        content.addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        content.addView(clipboard, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        content.addView(languages, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        emoji.visibility = View.GONE
        clipboard.visibility = View.GONE
        languages.visibility = View.GONE
        column.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keys.overlay = overlay
        // Keep the keys above the strip where Android draws the keyboard's back (⌄) and
        // keyboard-switch (🌐) buttons. Our own view may get insets that a parent already
        // consumed, so read the window's root insets like Android does when it sizes that strip.
        setOnApplyWindowInsetsListener { _, insets ->
            applyBottomSpace(rootWindowInsets ?: insets)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        post { refreshBottomSpace() }
    }

    /** Re-reads the window insets (called each time the keyboard is shown). */
    fun refreshBottomSpace() {
        rootWindowInsets?.let { applyBottomSpace(it) }
    }

    private fun applyBottomSpace(insets: WindowInsets) {
        val bottom = bottomSpace(insets)
        if (column.paddingBottom != bottom) column.setPadding(0, 0, 0, bottom)
    }

    companion object {
        /**
         * Height of the system strip below the keyboard. Mirrors how Android's
         * InputMethodService NavigationBarController sizes the IME navigation bar: system bars
         * (navigation bar and the IME's own button bar, which Android reports as a caption bar),
         * limited to the stable height so a transient state can't inflate it.
         */
        fun bottomSpace(insets: WindowInsets): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                return insets.systemWindowInsetBottom
            }
            val current = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).bottom
            val stable = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).bottom
            return minOf(current, stable)
        }
    }

    /**
     * The keyboard is exactly as tall as the suggestion bar + keys (+ space for the gesture bar).
     * Android sizes the whole keyboard window from this, so the preview overlay must only ever
     * match that size and never ask for more: a "fill the space" overlay makes the window cover
     * the entire screen.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        column.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = column.measuredHeight
        overlay.measure(exactW, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    fun applyTheme(theme: KeyboardTheme) {
        setBackgroundColor(theme.background)
        bar.theme = theme
        keys.theme = theme
        emoji.theme = theme
        clipboard.theme = theme
        languages.theme = theme
        overlay.theme = theme
    }

    fun showPanel(p: Panel) {
        if (p != Panel.KEYS) {
            // Panels take the height of the key area so the keyboard does not jump.
            val h = keys.height.takeIf { it > 0 } ?: keys.totalHeight()
            val target = when (p) {
                Panel.EMOJI -> emoji
                Panel.CLIPBOARD -> clipboard
                else -> languages
            }
            target.layoutParams = target.layoutParams.apply { height = h }
        }
        keys.cancelAll()
        panel = p
        bar.panel = p
        keys.visibility = if (p == Panel.KEYS) View.VISIBLE else View.GONE
        emoji.visibility = if (p == Panel.EMOJI) View.VISIBLE else View.GONE
        clipboard.visibility = if (p == Panel.CLIPBOARD) View.VISIBLE else View.GONE
        languages.visibility = if (p == Panel.LANGUAGES) View.VISIBLE else View.GONE
        when (p) {
            Panel.EMOJI -> emoji.open()
            Panel.CLIPBOARD -> clipboard.refresh()
            Panel.LANGUAGES -> languages.refresh()
            Panel.KEYS -> Unit
        }
    }
}
