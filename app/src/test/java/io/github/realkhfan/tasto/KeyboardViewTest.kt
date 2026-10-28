package io.github.realkhfan.tasto

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.ime.FieldKind
import io.github.realkhfan.tasto.keyboard.Key
import io.github.realkhfan.tasto.keyboard.KeyType
import io.github.realkhfan.tasto.keyboard.KeyboardView
import io.github.realkhfan.tasto.keyboard.Layouts
import io.github.realkhfan.tasto.keyboard.Page
import io.github.realkhfan.tasto.keyboard.PreviewOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

/** Drives the real key view with touch events, like a finger would. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xxhdpi")
class KeyboardViewTest {
    private val events = ArrayList<String>()
    private lateinit var view: KeyboardView

    private val listener = object : KeyboardView.Listener {
        override fun onKeyText(text: String) { events.add("text:$text") }
        override fun onBackspace() { events.add("backspace") }
        override fun onDeleteWord() { events.add("deleteWord") }
        override fun onSpace() { events.add("space") }
        override fun onEnter() { events.add("enter") }
        override fun onShift() { events.add("shift") }
        override fun onShiftLongPress() { events.add("shiftLock") }
        override fun onPage(page: Page) { events.add("page:$page") }
        override fun onEmoji() { events.add("emoji") }
        override fun onCursorMove(steps: Int) { events.add("cursor:$steps") }
        override fun onLanguageSwipe(direction: Int) { events.add("lang:$direction") }
        override fun onKeyFeedback(key: Key) = Unit
    }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        view = KeyboardView(ctx, listener)
        view.overlay = PreviewOverlay(ctx).apply {
            measure(View.MeasureSpec.makeMeasureSpec(1344, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1344, 900)
        }
        view.layout = Layouts.letters(FieldKind.TEXT, numberRow = false, hints = true)
        view.measure(View.MeasureSpec.makeMeasureSpec(1344, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2992, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun center(label: String, type: KeyType = KeyType.CHAR): Pair<Float, Float> {
        val r = view.keyBoxesForTest().first { it.first.type == type && (type != KeyType.CHAR || it.first.label == label) }.second
        return r.centerX() to r.centerY()
    }

    private var downTime = 0L
    private fun ev(action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        return view.onTouchEvent(e).also { e.recycle() }
    }

    private fun tap(label: String, type: KeyType = KeyType.CHAR) {
        val (x, y) = center(label, type)
        ev(MotionEvent.ACTION_DOWN, x, y)
        ev(MotionEvent.ACTION_UP, x, y)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun layoutHasQwertzRows() {
        val chars = view.keyBoxesForTest().filter { it.first.type == KeyType.CHAR }.map { it.first.label }
        assertEquals(listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"), chars.take(10))
        assertTrue(chars.containsAll(listOf("y", "x", "c", "v", "b", "n", "m", ",", ".")))
        // Keys fit inside the view and don't overlap within a row.
        val boxes = view.keyBoxesForTest().map { it.second }
        assertTrue(boxes.all { it.left >= 0 && it.right <= 1344 && it.top >= 0 && it.bottom <= view.measuredHeight })
    }

    @Test fun tappingTypes() {
        tap("h"); tap("a"); tap("l"); tap("l"); tap("o")
        assertEquals(listOf("text:h", "text:a", "text:l", "text:l", "text:o"), events)
    }

    @Test fun functionKeys() {
        tap("", KeyType.SHIFT); tap("", KeyType.SPACE); tap("", KeyType.ENTER); tap("", KeyType.EMOJI); tap("", KeyType.SYMBOLS)
        assertEquals(listOf("shift", "space", "enter", "emoji", "page:SYMBOLS"), events)
    }

    @Test fun longPressPicksAccentToTheRight() {
        val (x, y) = center("e")
        ev(MotionEvent.ACTION_DOWN, x, y)
        idle(400) // popup opens: 3 é è ê ë €, "3" preselected, list runs right
        val cellW = view.keyBoxesForTest().first { it.first.label == "e" }.second.width()
        ev(MotionEvent.ACTION_MOVE, x + cellW, y)
        ev(MotionEvent.ACTION_UP, x + cellW, y)
        assertEquals(listOf("text:é"), events)
    }

    @Test fun longPressNearRightEdgeRunsLeft() {
        val (x, y) = center("u")
        ev(MotionEvent.ACTION_DOWN, x, y)
        idle(400) // popup opens: 7 ü ú ù û; too wide to the right, so it runs left
        val cellW = view.keyBoxesForTest().first { it.first.label == "u" }.second.width()
        ev(MotionEvent.ACTION_MOVE, x - cellW, y)
        ev(MotionEvent.ACTION_UP, x - cellW, y)
        assertEquals(listOf("text:ü"), events)
    }

    @Test fun longPressAGivesUmlautDirectly() {
        val (x, y) = center("a")
        ev(MotionEvent.ACTION_DOWN, x, y)
        idle(400)
        ev(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("text:ä"), events)
    }

    @Test fun longPressTopRowGivesDigit() {
        val (x, y) = center("q")
        ev(MotionEvent.ACTION_DOWN, x, y)
        idle(400)
        ev(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("text:1"), events)
    }

    @Test fun slidingOnSpaceMovesCursor() {
        val (x, y) = center("", KeyType.SPACE)
        ev(MotionEvent.ACTION_DOWN, x, y)
        ev(MotionEvent.ACTION_MOVE, x + 200f, y)
        ev(MotionEvent.ACTION_UP, x + 200f, y)
        assertTrue(events.toString(), events.any { it.startsWith("cursor:") })
        assertTrue("no space typed", "space" !in events)
    }

    @Test fun holdingBackspaceDeletesWords() {
        val (x, y) = center("", KeyType.BACKSPACE)
        ev(MotionEvent.ACTION_DOWN, x, y)
        idle(350)
        assertEquals("a short press is one character", listOf("backspace"), events)
        idle(1000) // 400ms: first word, then every 300ms (200ms after a few)
        ev(MotionEvent.ACTION_UP, x, y)
        assertEquals(1, events.count { it == "backspace" })
        assertTrue(events.toString(), events.count { it == "deleteWord" } in 3..5)
    }

    @Test fun tappingBackspaceRepeatedlyDeletesCharacters() {
        repeat(4) { tap("", KeyType.BACKSPACE) }
        assertEquals(List(4) { "backspace" }, events)
    }

    @Test fun russianLayout() {
        val l = Layouts.letters(FieldKind.TEXT, false, false, io.github.realkhfan.tasto.keyboard.Alphabet.JCUKEN)
        assertEquals("йцукенгшщзх".map { it.toString() }, l.rows[0].map { it.label })
        assertEquals("фывапролджэ".map { it.toString() }, l.rows[1].map { it.label })
        assertEquals(listOf("SHIFT") + "ячсмитьбю".map { it.toString() } + "BACKSPACE",
            l.rows[2].map { if (it.type == KeyType.CHAR) it.label else it.type.name })
        for (row in l.rows) assertEquals(10f, row.sumOf { it.width.toDouble() }.toFloat(), 0.01f)
        // ё on long-press of е (first choice), ъ on ь.
        assertEquals("ё", l.rows[0].first { it.label == "е" }.popup.first())
        assertEquals("ъ", l.rows[2].first { it.label == "ь" }.popup.first())
    }

    @Test fun swipeLeftOnBackspaceDeletesWord() {
        val (x, y) = center("", KeyType.BACKSPACE)
        ev(MotionEvent.ACTION_DOWN, x, y)
        ev(MotionEvent.ACTION_MOVE, x - 300f, y)
        ev(MotionEvent.ACTION_UP, x - 300f, y)
        assertEquals(listOf("backspace", "deleteWord"), events)
    }

    @Test fun longPressShiftLocksCaps() {
        val (x, y) = center("", KeyType.SHIFT)
        ev(MotionEvent.ACTION_DOWN, x, y)
        idle(600)
        ev(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("shiftLock"), events)
    }

    @Test fun rollingTwoFingersTypesBoth() {
        val (x1, y1) = center("a")
        val (x2, y2) = center("s")
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
        val c1 = MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        val c2 = MotionEvent.PointerCoords().apply { x = x2; y = y2 }
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, props, arrayOf(c1), 0, 0, 1f, 1f, 0, 0, 0, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, arrayOf(c1, c2), 0, 0, 1f, 1f, 0, 0, 0, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_UP, 2, props, arrayOf(c1, c2), 0, 0, 1f, 1f, 0, 0, 0, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1, arrayOf(props[1]), arrayOf(c2), 0, 0, 1f, 1f, 0, 0, 0, 0))
        assertEquals(listOf("text:a", "text:s"), events)
    }

    private fun labels(layout: io.github.realkhfan.tasto.keyboard.KeyboardLayout) =
        layout.rows.map { row -> row.map { k -> if (k.type == KeyType.CHAR) k.label else k.type.name + (if (k.label.isNotEmpty()) ":" + k.label else "") } }

    @Test fun symbolPagesMatchSwiftKey() {
        val p1 = labels(Layouts.symbols(FieldKind.TEXT))
        assertEquals("1234567890".map { it.toString() }, p1[0])
        assertEquals(listOf("@", "#", "£", "&", "_", "-", "(", ")", "=", "%"), p1[1])
        assertEquals(listOf("MORE_SYMBOLS:{&=", "\"", "*", "'", ":", "/", "!", "?", "+", "BACKSPACE"), p1[2])
        assertEquals(listOf("LETTERS:abc", ",", "SPACE", ".", "ENTER"), p1[3])
        val p2 = labels(Layouts.symbols2(FieldKind.TEXT))
        assertEquals(listOf("$", "€", "¥", "¢", "©", "®", "™", "~", "¿"), p2[0])
        assertEquals(listOf("TAB", "[", "]", "{", "}", "<", ">", "^", "¡"), p2[1])
        assertEquals(listOf("SYMBOLS:123", "`", ";", "÷", "\\", "|", "¦", "¬", "BACKSPACE"), p2[2])
        assertEquals(listOf("LETTERS:abc", "SPACE", "×", "§", "¶", "°", "ENTER"), p2[3])
        // Every row spans the full width (10 key widths), like SwiftKey.
        for (l in listOf(Layouts.symbols(FieldKind.TEXT), Layouts.symbols2(FieldKind.TEXT), Layouts.letters(FieldKind.TEXT, false, false))) {
            for (row in l.rows) assertEquals(10f, row.sumOf { it.width.toDouble() }.toFloat(), 0.01f)
        }
    }

    @Test fun lettersBottomRowLikeSwiftKey() {
        val bottom = Layouts.letters(FieldKind.TEXT, false, false).rows.last()
        assertEquals(listOf(KeyType.SYMBOLS, KeyType.EMOJI, KeyType.CHAR, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), bottom.map { it.type })
        assertEquals(listOf(1f, 1f, 1f, 4.5f, 1f, 1.5f), bottom.map { it.width })
    }

    @Test fun tabKeyTypesTab() {
        view.layout = Layouts.symbols2(FieldKind.TEXT)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        tap("", KeyType.TAB)
        assertEquals(listOf("text:\t"), events)
    }

    @Test fun symbolsPages() {
        view.layout = Layouts.symbols(FieldKind.TEXT)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        tap("@"); tap("", KeyType.MORE_SYMBOLS); tap("", KeyType.LETTERS)
        assertEquals(listOf("text:@", "page:SYMBOLS2", "page:LETTERS"), events)
    }

    // ---- TalkBack ------------------------------------------------------------------------

    private fun a11yNames(): List<String> {
        val provider = view.accessibilityNodeProvider!!
        return view.keyBoxesForTest().indices.map { provider.createAccessibilityNodeInfo(it)!!.contentDescription.toString() }
    }

    @Test fun talkBackReadsEveryKeyAndCanPressIt() {
        val provider = view.accessibilityNodeProvider!!
        assertEquals(view.keyBoxesForTest().size, provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount)
        val names = a11yNames()
        assertTrue(names.containsAll(listOf("q", "Shift", "Delete", "Numbers and symbols", "Emoji", "Enter", ",", ".")))
        assertTrue(names.any { it.startsWith("Space") })
        // Double-tap types the key.
        provider.performAction(names.indexOf("q"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("text:q", events.last())
        provider.performAction(names.indexOf("Delete"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("backspace", events.last())
        // Long-press letters are actions in TalkBack's menu.
        val a = names.indexOf("a")
        val umlaut = provider.createAccessibilityNodeInfo(a)!!.actionList.first { it.label == "ä" }
        provider.performAction(a, umlaut.id, null)
        assertEquals("text:ä", events.last())
    }

    @Test fun talkBackOnTheTwelveKeyLayout() {
        view.layout = Layouts.letters(FieldKind.TEXT, false, false, io.github.realkhfan.tasto.keyboard.Alphabet.FLICK12)
        val provider = view.accessibilityNodeProvider!!
        val names = a11yNames()
        assertTrue(names.containsAll(listOf("た", "Move cursor left", "German and English", "Dakuten, handakuten, small kana")))
        val ta = names.indexOf("た")
        val flicks = provider.createAccessibilityNodeInfo(ta)!!.actionList.mapNotNull { it.label?.toString() }
        assertTrue(flicks.containsAll(listOf("ち", "つ", "て", "と")))
        provider.performAction(ta, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("text:た", events.last())
    }
}
