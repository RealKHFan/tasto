package io.github.realkhfan.tasto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.FieldKind
import io.github.realkhfan.tasto.ime.ShiftState
import io.github.realkhfan.tasto.ime.Slot
import io.github.realkhfan.tasto.ime.SlotStyle
import io.github.realkhfan.tasto.data.Clip
import io.github.realkhfan.tasto.keyboard.ClipboardPanel
import io.github.realkhfan.tasto.keyboard.EmojiPanel
import io.github.realkhfan.tasto.keyboard.Icon
import io.github.realkhfan.tasto.keyboard.Key
import io.github.realkhfan.tasto.keyboard.KeyboardRoot
import io.github.realkhfan.tasto.keyboard.KeyboardTheme
import io.github.realkhfan.tasto.keyboard.KeyboardView
import io.github.realkhfan.tasto.keyboard.Layouts
import io.github.realkhfan.tasto.keyboard.Page
import io.github.realkhfan.tasto.keyboard.Panel
import io.github.realkhfan.tasto.keyboard.TopBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real keyboard views to PNGs (Pixel 8 Pro size) for visual checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xxhdpi")
class RenderTest {
    companion object {
        // Pixel 8 Pro: 1344 × 2992 px.
        const val SCREEN_W = 1344
        const val SCREEN_H = 2992
    }

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val outDir = File("build/screens").apply { mkdirs() }

    private object NoKeys : KeyboardView.Listener {
        override fun onKeyText(text: String) = Unit
        override fun onBackspace() = Unit
        override fun onDeleteWord() = Unit
        override fun onSpace() = Unit
        override fun onEnter() = Unit
        override fun onShift() = Unit
        override fun onShiftLongPress() = Unit
        override fun onPage(page: Page) = Unit
        override fun onEmoji() = Unit
        override fun onCursorMove(steps: Int) = Unit
        override fun onLanguageSwipe(direction: Int) = Unit
        override fun onKeyFeedback(key: Key) = Unit
    }

    private object NoBar : TopBarView.Listener {
        override fun onSlot(slot: Slot) = Unit
        override fun onSlotLongPress(slot: Slot) = Unit
        override fun onToolbar(action: TopBarView.Action) = Unit
        override fun onForgetConfirmed(word: String) = Unit
        override fun onKeyFeedbackLight() = Unit
        override fun onBarBack() = Unit
        override fun onToolbarToggle() = Unit
        override fun onCandidate(index: Int) = Unit
    }

    private object NoEmoji : EmojiPanel.Listener {
        override fun onEmoji(emoji: String) = Unit
        override fun onEmojiBackspace() = Unit
        override fun onEmojiClose() = Unit
        override fun emojiRecents(): List<String> = emptyList()
        override fun onKeyFeedbackLight() = Unit
    }

    private object NoLanguages : io.github.realkhfan.tasto.keyboard.LanguagePanel.Listener {
        override fun languageItems() = io.github.realkhfan.tasto.data.Languages.all.map {
            io.github.realkhfan.tasto.keyboard.LanguagePanel.Item(it.id, it.displayName, it.detail)
        }
        override fun currentLanguageId() = "ja_flick"
        override fun onLanguagePicked(id: String) = Unit
        override fun onLanguagesClose() = Unit
    }

    private class Clips(val items: List<Clip>) : ClipboardPanel.Listener {
        override fun clipItems() = items
        override fun clipHistoryEnabled() = true
        override fun onClipPaste(text: String) = Unit
        override fun onClipPin(text: String) = Unit
        override fun onClipRemove(text: String) = Unit
        override fun onClipClear() = Unit
        override fun onClipClose() = Unit
    }

    private fun root(theme: KeyboardTheme, clips: List<Clip> = emptyList()): KeyboardRoot =
        KeyboardRoot(ctx, NoKeys, NoBar, NoEmoji, Clips(clips), NoLanguages).apply {
            applyTheme(theme)
            keys.spaceLabel = "de/en"
            keys.layout = Layouts.letters(FieldKind.TEXT, numberRow = false, hints = true)
        }

    /** Measures like Android measures the keyboard window: full width, at most the screen height. */
    private fun measureLikeWindow(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.AT_MOST),
        )
    }

    private fun snap(v: View, name: String): Bitmap {
        measureLikeWindow(v)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private val blue = KeyboardTheme.ACCENTS.getValue("blue")

    private fun insets(navBar: Int, imeButtons: Int): android.view.WindowInsets {
        val t = android.view.WindowInsets.Type.navigationBars()
        val c = android.view.WindowInsets.Type.captionBar()
        return android.view.WindowInsets.Builder()
            .setInsets(t, android.graphics.Insets.of(0, 0, 0, navBar))
            .setInsetsIgnoringVisibility(t, android.graphics.Insets.of(0, 0, 0, navBar))
            .setInsets(c, android.graphics.Insets.of(0, 0, 0, imeButtons))
            .setInsetsIgnoringVisibility(c, android.graphics.Insets.of(0, 0, 0, imeButtons))
            .build()
    }

    @Test fun bottomSpaceMatchesAndroidButtonStrip() {
        // Gesture navigation with the keyboard's ⌄ / 🌐 strip (reported as a caption bar).
        assertEquals(144, KeyboardRoot.bottomSpace(insets(navBar = 72, imeButtons = 144)))
        // Only a navigation bar (3-button navigation).
        assertEquals(144, KeyboardRoot.bottomSpace(insets(navBar = 144, imeButtons = 0)))
        // Nothing below the keyboard.
        assertEquals(0, KeyboardRoot.bottomSpace(insets(navBar = 0, imeButtons = 0)))
    }

    @Test fun heightMatchesSwiftKeyOnPixel8Pro() {
        val r = root(KeyboardTheme.dark(blue))
        // Android hands the keyboard its insets; the ⌄ / 🌐 strip is 48dp = 144px here.
        r.dispatchApplyWindowInsets(insets(navBar = 72, imeButtons = 144))
        measureLikeWindow(r)
        val swiftKey = 945 // px, measured from the SwiftKey screenshot (top of bar to screen bottom)
        val diff = kotlin.math.abs(r.measuredHeight - swiftKey)
        println("Tasto ${r.measuredHeight}px vs SwiftKey ${swiftKey}px")
        assertTrue("Tasto ${r.measuredHeight}px vs SwiftKey ${swiftKey}px", diff <= swiftKey * 0.03)
        r.layout(0, 0, r.measuredWidth, r.measuredHeight)
        // The keys end above the button strip.
        val lastKeyBottom = r.keys.keyBoxesForTest().maxOf { it.second.bottom } + r.bar.measuredHeight
        assertTrue("keys end at $lastKeyBottom", lastKeyBottom <= r.measuredHeight - 144)
        snap(r, "00_with_button_strip")
    }

    @Test fun keyboardOnlyTakesItsOwnHeight() {
        val r = root(KeyboardTheme.dark(blue))
        fun check(what: String) {
            measureLikeWindow(r)
            val expected = r.bar.measuredHeight + r.keys.totalHeight()
            assertEquals("$what: keyboard height", expected, r.measuredHeight)
            assertTrue("$what: under half the screen (${r.measuredHeight}px)", r.measuredHeight < SCREEN_H / 2)
            assertEquals("$what: overlay matches keyboard", r.measuredHeight, r.overlay.measuredHeight)
        }
        check("letters")
        r.keys.layout = Layouts.letters(FieldKind.TEXT, numberRow = true, hints = true)
        check("number row")
        r.keys.layout = Layouts.phone()
        check("phone pad")
        r.keys.layout = Layouts.letters(FieldKind.TEXT, numberRow = false, hints = true)
        measureLikeWindow(r)
        r.layout(0, 0, r.measuredWidth, r.measuredHeight)
        val keysOnly = r.measuredHeight
        r.showPanel(Panel.EMOJI)
        measureLikeWindow(r)
        assertEquals("emoji panel keeps the keyboard height", keysOnly, r.measuredHeight)
        r.showPanel(Panel.CLIPBOARD)
        measureLikeWindow(r)
        assertEquals("clipboard panel keeps the keyboard height", keysOnly, r.measuredHeight)
    }

    @Test fun darkWithSuggestions() {
        val r = root(KeyboardTheme.dark(blue))
        r.bar.bar = BarState(Slot("hallo", SlotStyle.VERBATIM), Slot("Hallo", SlotStyle.AUTOCORRECT), Slot("Halle", SlotStyle.NORMAL))
        r.keys.shiftState = ShiftState.ON
        val bmp = snap(r, "01_dark_suggestions")
        assertNotEquals(bmp.getPixel(10, bmp.height - 10), 0)
    }

    @Test fun darkToolbar() {
        snap(root(KeyboardTheme.dark(blue)), "02_dark_toolbar")
    }

    @Test fun lightTheme() {
        val r = root(KeyboardTheme.light(blue))
        r.bar.bar = BarState(Slot("ich", SlotStyle.NORMAL), Slot("ist", SlotStyle.PRIMARY), Slot("in", SlotStyle.NORMAL), true)
        snap(r, "03_light")
    }

    @Test fun incognito() {
        val r = root(KeyboardTheme.dark(KeyboardTheme.ACCENTS.getValue("purple")))
        r.bar.incognito = true
        r.keys.incognito = true
        r.keys.enterIcon = Icon.SEARCH
        snap(r, "04_incognito")
    }

    @Test fun symbolsAndNumbers() {
        val r = root(KeyboardTheme.dark(blue))
        r.keys.layout = Layouts.symbols(FieldKind.TEXT)
        snap(r, "05_symbols")
        r.keys.layout = Layouts.symbols2(FieldKind.TEXT)
        snap(r, "06_symbols2")
        r.keys.layout = Layouts.phone()
        r.keys.enterIcon = Icon.DONE
        snap(r, "07_phone")
        r.keys.layout = Layouts.letters(FieldKind.TEXT, numberRow = true, hints = true)
        r.keys.shiftState = ShiftState.LOCKED
        snap(r, "08_number_row_capslock")
    }

    @Test fun emojiAndClipboard() {
        val r = root(KeyboardTheme.dark(blue), listOf(Clip("Treffen um 18 Uhr am Bahnhof?", 0, true), Clip("https://grapheneos.org", 0, false)))
        snap(r, "09_base")
        r.showPanel(Panel.EMOJI)
        snap(r, "10_emoji")
        r.showPanel(Panel.CLIPBOARD)
        snap(r, "11_clipboard")
    }

    /** Same states as the user's SwiftKey screenshots, for a side-by-side check. */
    @Test fun swiftKeyComparisonStates() {
        val r = root(KeyboardTheme.dark(blue))
        r.dispatchApplyWindowInsets(insets(navBar = 72, imeButtons = 144))
        r.bar.expanded = true
        r.bar.bar = BarState(Slot("I", SlotStyle.NORMAL), Slot("Ich", SlotStyle.PRIMARY), Slot("Und", SlotStyle.NORMAL), true)
        r.keys.shiftState = ShiftState.ON
        snap(r, "30_cmp_letters")
        r.bar.expanded = false
        r.bar.bar = BarState(null, Slot("50", SlotStyle.PRIMARY), null, true)
        r.keys.shiftState = ShiftState.OFF
        r.keys.layout = Layouts.symbols(FieldKind.TEXT)
        snap(r, "31_cmp_sym1")
        r.keys.layout = Layouts.symbols2(FieldKind.TEXT)
        snap(r, "32_cmp_sym2")
    }

    @Test fun russian() {
        val r = root(KeyboardTheme.dark(blue))
        r.dispatchApplyWindowInsets(insets(navBar = 72, imeButtons = 144))
        r.keys.languageCount = 2
        r.keys.spaceLabel = "ru"
        r.keys.layout = Layouts.letters(FieldKind.TEXT, false, false, io.github.realkhfan.tasto.keyboard.Alphabet.JCUKEN)
        r.keys.shiftState = ShiftState.ON
        r.bar.bar = BarState(Slot("Привет", SlotStyle.NORMAL), Slot("Я", SlotStyle.PRIMARY), Slot("Это", SlotStyle.NORMAL), true)
        snap(r, "40_russian")
        r.keys.layout = Layouts.letters(FieldKind.TEXT, false, false)
        r.keys.spaceLabel = "de/en"
        r.bar.bar = BarState(Slot("I", SlotStyle.NORMAL), Slot("Ich", SlotStyle.PRIMARY), Slot("Das", SlotStyle.NORMAL), true)
        snap(r, "41_german_two_languages")
    }

    @Test fun japanese() {
        val r = root(KeyboardTheme.dark(blue))
        r.dispatchApplyWindowInsets(insets(navBar = 72, imeButtons = 144))
        r.keys.languageCount = 4
        // Romaji on QWERTZ while typing にほんご: candidates in the bar, 変換 / 確定 on the keys.
        r.keys.layout = Layouts.letters(FieldKind.TEXT, false, false, io.github.realkhfan.tasto.keyboard.Alphabet.ROMAJI)
        r.keys.spaceLabel = "変換"
        r.keys.enterLabel = "確定"
        r.bar.bar = BarState.candidates(listOf("日本語", "にほんご", "ニホンゴ", "日本", "二本", "日本語学校", "日本語版"), -1)
        snap(r, "50_japanese_romaji")
        r.bar.bar = BarState.candidates(listOf("日本語", "にほんご", "ニホンゴ", "日本", "二本", "日本語学校", "日本語版"), 0)
        snap(r, "51_japanese_converting")
        // 12-key flick.
        r.keys.layout = Layouts.letters(FieldKind.TEXT, false, false, io.github.realkhfan.tasto.keyboard.Alphabet.FLICK12)
        r.keys.spaceLabel = "空白"
        r.keys.enterLabel = null
        r.bar.bar = BarState.EMPTY
        snap(r, "52_japanese_flick")
        // Holding た shows the flick guide.
        measureLikeWindow(r)
        r.layout(0, 0, r.measuredWidth, r.measuredHeight)
        val ta = r.keys.keyBoxesForTest().first { it.first.label == "た" }.second
        val t0 = android.os.SystemClock.uptimeMillis()
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, ta.centerX(), ta.centerY(), 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 450, android.view.MotionEvent.ACTION_MOVE, ta.centerX(), ta.centerY() - ta.height(), 0))
        snap(r, "53_japanese_flick_guide")
        r.keys.cancelAll()
        // A quick flick without holding shows only the flicked character.
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, ta.centerX(), ta.centerY(), 0))
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 50, android.view.MotionEvent.ACTION_MOVE, ta.centerX() - ta.height(), ta.centerY(), 0))
        snap(r, "54_japanese_flick_left")
        r.keys.cancelAll()
        r.keys.layout = Layouts.symbols(FieldKind.TEXT, "あいう")
        snap(r, "55_japanese_symbols")
        r.showPanel(io.github.realkhfan.tasto.keyboard.Panel.LANGUAGES)
        snap(r, "56_language_list")
        r.showPanel(io.github.realkhfan.tasto.keyboard.Panel.KEYS)
        r.bar.expanded = true
        snap(r, "57_toolbar_with_globe")
    }

    @Test fun largestSystemFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val r = root(KeyboardTheme.dark(blue))
        r.dispatchApplyWindowInsets(insets(navBar = 72, imeButtons = 144))
        snap(r, "17_font_200_percent")
        r.keys.layout = Layouts.letters(FieldKind.TEXT, false, false, io.github.realkhfan.tasto.keyboard.Alphabet.FLICK12)
        r.keys.spaceLabel = "空白"
        snap(r, "18_font_200_percent_flick")
    }

    @Test fun passwordField() {
        val r = root(KeyboardTheme.dark(blue))
        r.keys.layout = Layouts.letters(FieldKind.TEXT, numberRow = true, hints = true)
        r.dispatchApplyWindowInsets(insets(navBar = 72, imeButtons = 144))
        snap(r, "16_password_number_row")
    }

    @Test fun forgetPrompt() {
        val r = root(KeyboardTheme.dark(blue))
        r.bar.bar = BarState(Slot("moin", SlotStyle.NORMAL), Slot("Moinsen", SlotStyle.PRIMARY), null)
        r.bar.forgetWord = "Moinsen"
        snap(r, "12_forget")
    }

    @Test fun longPressPopupAndPreview() {
        val r = root(KeyboardTheme.dark(blue))
        measureLikeWindow(r)
        r.layout(0, 0, r.measuredWidth, r.measuredHeight)
        fun keyCenter(label: String) = r.keys.keyBoxesForTest().first { it.first.label == label }.second
        val o = keyCenter("o")
        val t0 = android.os.SystemClock.uptimeMillis()
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, o.centerX(), o.centerY(), 0))
        snap(r, "13_key_preview")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 450, android.view.MotionEvent.ACTION_MOVE, o.centerX() - o.width(), o.centerY(), 0))
        snap(r, "14_popup_o")
        r.keys.cancelAll()
        val e = keyCenter("e")
        r.keys.onTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, e.centerX(), e.centerY(), 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        snap(r, "15_popup_e")
        r.keys.cancelAll()
    }
}
