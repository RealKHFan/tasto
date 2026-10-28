package io.github.realkhfan.tasto

import android.app.Activity
import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.Languages
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.Slot
import io.github.realkhfan.tasto.ime.SlotStyle
import io.github.realkhfan.tasto.ime.TastoIme
import io.github.realkhfan.tasto.keyboard.KeyType
import io.github.realkhfan.tasto.keyboard.KeyboardRoot
import io.github.realkhfan.tasto.keyboard.Page
import io.github.realkhfan.tasto.keyboard.Panel
import io.github.realkhfan.tasto.keyboard.TopBarView.Action
import io.github.realkhfan.tasto.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Drives the complete keyboard (suggestion bar, keys, panels and the preview layer on top) with
 * real taps on the whole keyboard view, the way a finger does, and checks every way in and out
 * of each menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xxhdpi")
class KeyboardNavigationTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var ime: TastoIme
    private lateinit var root: KeyboardRoot
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        LearnedStore.resetForTest()
        ClipboardStore.resetForTest()
        Languages.resetForTest()
        prefs = Prefs(ctx)
        ime = Robolectric.buildService(TastoIme::class.java).create().get()
        root = ime.onCreateInputView() as KeyboardRoot
        // Attach to a real window so clicks, layout and drawing behave like on the phone.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        startField(textField())
    }

    private fun textField() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    private fun passwordField() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

    private fun startField(info: EditorInfo) {
        ime.onStartInputView(info, false)
        idle()
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- touch helpers ---------------------------------------------------------------------

    private var downTime = 0L

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        root.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tapAt(x: Float, y: Float, holdMs: Long = 0) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        if (holdMs > 0) idle(holdMs)
        touch(MotionEvent.ACTION_UP, x, y)
        idle()
    }

    /** [p] is in [view]'s own coordinates. */
    private fun tapIn(view: View, p: PointF, holdMs: Long = 0) {
        val r = Rect(0, 0, 0, 0)
        root.offsetDescendantRectToMyCoords(view, r)
        tapAt(r.left + p.x, r.top + p.y, holdMs)
    }

    private fun tapCenter(view: View) = tapIn(view, PointF(view.width / 2f, view.height / 2f))

    /** Taps a toolbar icon, first opening the toolbar row with the round button if it is hidden. */
    private fun tapBar(a: Action) {
        if (!root.bar.toolbarRowVisible) tapBarBackArrow()
        assertTrue("toolbar row visible", root.bar.toolbarRowVisible)
        tapIn(root.bar, root.bar.actionCenterForTest(a))
    }
    private fun tapBarBackArrow() = tapIn(root.bar, root.bar.leftButtonCenterForTest())

    private fun tapKey(type: KeyType, label: String = "") {
        val r = root.keys.keyBoxesForTest().first { it.first.type == type && (label.isEmpty() || it.first.label == label) }.second
        tapIn(root.keys, PointF(r.centerX(), r.centerY()))
    }

    private fun findText(group: ViewGroup, text: String): TextView? {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c is TextView && c.text.toString() == text && c.isShown) return c
            if (c is ViewGroup) findText(c, text)?.let { return it }
        }
        return null
    }

    /** Exactly what Android does for the back gesture or the ⌄ button (InputMethodService.compatHandleBack). */
    private fun pressBack(): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD)
        val handledDown = ime.onKeyDown(KeyEvent.KEYCODE_BACK, down)
        val tracking = (down.flags and FLAG_START_TRACKING) != 0
        val up = KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY or (if (tracking) KeyEvent.FLAG_TRACKING else 0),
            InputDevice.SOURCE_KEYBOARD)
        val handledUp = ime.onKeyUp(KeyEvent.KEYCODE_BACK, up)
        idle()
        return handledDown && handledUp
    }

    private companion object {
        /** KeyEvent.FLAG_START_TRACKING (hidden from apps), set by KeyEvent.startTracking(). */
        const val FLAG_START_TRACKING = 0x40000000
    }

    private fun assertOnKeys() {
        assertEquals(Panel.KEYS, root.panel)
        assertEquals(View.VISIBLE, root.keys.visibility)
        assertEquals(View.GONE, root.emoji.visibility)
        assertEquals(View.GONE, root.clipboard.visibility)
        assertEquals(Panel.KEYS, root.bar.panel)
    }

    private fun assertOn(p: Panel) {
        assertEquals(p, root.panel)
        assertEquals(View.GONE, root.keys.visibility)
        assertEquals(if (p == Panel.EMOJI) View.VISIBLE else View.GONE, root.emoji.visibility)
        assertEquals(if (p == Panel.CLIPBOARD) View.VISIBLE else View.GONE, root.clipboard.visibility)
    }

    // ---- clipboard -------------------------------------------------------------------------

    @Test fun clipboardAbcButtonGoesBack() {
        tapBar(Action.CLIPBOARD)
        assertOn(Panel.CLIPBOARD)
        tapCenter(findText(root.clipboard, "ABC")!!)
        assertOnKeys()
    }

    @Test fun clipboardBackArrowGoesBack() {
        tapBar(Action.CLIPBOARD)
        assertOn(Panel.CLIPBOARD)
        tapBarBackArrow()
        assertOnKeys()
    }

    @Test fun clipboardIconAgainGoesBack() {
        tapBar(Action.CLIPBOARD)
        assertOn(Panel.CLIPBOARD)
        tapBar(Action.CLIPBOARD)
        assertOnKeys()
    }

    @Test fun clipboardAndroidBackGoesBack() {
        tapBar(Action.CLIPBOARD)
        assertOn(Panel.CLIPBOARD)
        assertTrue("Back handled by Tasto", pressBack())
        assertOnKeys()
        // With nothing left to go back from, Back is left to Android (closes the keyboard).
        assertFalse(ime.goBackInside())
    }

    @Test fun clipboardBlocksScreenshotsOnlyWhileOpen() {
        fun secure() = (ime.window.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        assertFalse(secure())
        tapBar(Action.CLIPBOARD)
        assertTrue("clipboard is screenshot-protected", secure())
        tapBarBackArrow()
        assertFalse("keys are not", secure())
        tapKey(KeyType.EMOJI)
        assertFalse("emoji panel is not", secure())
    }

    @Test fun clipboardItemsCanBePinnedRemovedAndCleared() {
        val clips = ClipboardStore.get(ctx)
        clips.clearAll()
        clips.add("erstes")
        clips.add("zweites")
        tapBar(Action.CLIPBOARD)
        // Pin "zweites" (newest, first row), then remove "erstes", then clear unpinned.
        val pin = root.clipboard.findViewsWithDesc("Pin").first()
        tapCenter(pin)
        assertTrue(clips.items().first { it.text == "zweites" }.pinned)
        val removeButtons = root.clipboard.findViewsWithDesc("Remove")
        tapCenter(removeButtons.last())
        assertEquals(listOf("zweites"), clips.items().map { it.text })
        clips.add("drittes")
        root.clipboard.refresh(); idle()
        tapCenter(findText(root.clipboard, "Clear")!!)
        assertEquals(listOf("zweites"), clips.items().map { it.text })
        // Still able to go back afterwards.
        tapCenter(findText(root.clipboard, "ABC")!!)
        assertOnKeys()
    }

    private fun View.findViewsWithDesc(desc: String): List<View> {
        val out = ArrayList<View>()
        findViewsWithText(out, desc, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return out.filter { it.isShown }
    }

    // ---- emoji -----------------------------------------------------------------------------

    @Test fun emojiAbcButtonGoesBack() {
        tapKey(KeyType.EMOJI)
        assertOn(Panel.EMOJI)
        tapIn(root.emoji, root.emoji.abcCenterForTest())
        assertOnKeys()
    }

    @Test fun emojiBackArrowGoesBack() {
        tapKey(KeyType.EMOJI)
        assertOn(Panel.EMOJI)
        tapBarBackArrow()
        assertOnKeys()
    }

    @Test fun emojiAndroidBackGoesBack() {
        tapKey(KeyType.EMOJI)
        assertTrue(pressBack())
        assertOnKeys()
    }

    @Test fun pickingEmojiKeepsPanelAndRemembersIt() {
        tapKey(KeyType.EMOJI)
        val first = root.emoji.shownEmojiForTest.first()
        tapIn(root.emoji, root.emoji.firstEmojiCenterForTest())
        assertOn(Panel.EMOJI)
        assertEquals(first, prefs.emojiRecents.first())
        tapBarBackArrow()
        assertOnKeys()
    }

    @Test fun fromEmojiToClipboardAndBack() {
        tapKey(KeyType.EMOJI)
        tapBar(Action.CLIPBOARD)
        assertOn(Panel.CLIPBOARD)
        tapBarBackArrow()
        assertOnKeys()
        // The keyboard keeps its size after visiting both panels.
        val keysHeight = root.keys.height
        tapKey(KeyType.EMOJI)
        assertEquals(keysHeight, root.emoji.height)
    }

    // ---- toolbar ---------------------------------------------------------------------------

    @Test fun roundButtonShowsAndHidesToolbarRow() {
        val collapsed = root.height
        assertFalse(root.bar.toolbarRowVisible)
        tapBarBackArrow() // the round ^ button left of the suggestions
        assertTrue(root.bar.toolbarRowVisible)
        assertTrue(prefs.toolbarExpanded)
        assertEquals("keyboard grows by the toolbar row", collapsed + (42 * 3), root.height)
        tapBarBackArrow() // now ⌄
        assertFalse(root.bar.toolbarRowVisible)
        assertEquals(collapsed, root.height)
        // Remembered: the next text field opens with the toolbar as you left it.
        tapBarBackArrow()
        startField(textField())
        assertTrue(root.bar.toolbarRowVisible)
    }

    @Test fun suggestionsStayVisibleUnderTheToolbar() {
        root.bar.bar = BarState(Slot("ich", SlotStyle.NORMAL), Slot("ist", SlotStyle.PRIMARY), Slot("in", SlotStyle.NORMAL), true)
        tapBarBackArrow()
        assertTrue(root.bar.toolbarRowVisible)
        // Tapping the middle suggestion still works with the toolbar open.
        tapIn(root.bar, root.bar.slotCenterForTest(1))
        assertTrue(root.bar.toolbarRowVisible)
    }

    @Test fun incognitoTogglesOnAndOff() {
        tapBar(Action.INCOGNITO)
        assertTrue(prefs.manualIncognito)
        assertTrue(ime.incognito)
        assertTrue(root.bar.incognito)
        assertTrue(root.keys.incognito)
        tapBar(Action.INCOGNITO)
        assertFalse(prefs.manualIncognito)
        assertFalse(ime.incognito)
        assertFalse(root.bar.incognito)
        assertFalse(root.keys.incognito)
    }

    @Test fun settingsOpensTastoApp() {
        tapBar(Action.SETTINGS)
        val started = shadowOf(ime.application).nextStartedActivity
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }

    @Test fun hideButtonDoesNotBreakAnything() {
        tapBar(Action.HIDE)
        startField(textField())
        assertOnKeys()
    }

    @Test fun forgetPromptKeepAndForget() {
        val store = LearnedStore.get(ctx)
        store.learn("de_en", "moinsen", "moinsen", null, 3, false)
        fun showWord() {
            root.bar.bar = BarState(null, Slot("moinsen", SlotStyle.PRIMARY), null, true)
            idle()
        }
        showWord()
        tapIn(root.bar, root.bar.slotCenterForTest(1), holdMs = 600)
        assertEquals("moinsen", root.bar.forgetWord)
        tapIn(root.bar, root.bar.forgetButtonsForTest().second.let { PointF(it.centerX(), it.centerY()) }) // Keep
        assertNull(root.bar.forgetWord)
        assertNotNull(store.cache("de_en").get("moinsen"))
        showWord()
        tapIn(root.bar, root.bar.slotCenterForTest(1), holdMs = 600)
        assertTrue(pressBack()) // Back also dismisses the question
        assertNull(root.bar.forgetWord)
        showWord()
        tapIn(root.bar, root.bar.slotCenterForTest(1), holdMs = 600)
        tapIn(root.bar, root.bar.forgetButtonsForTest().first.let { PointF(it.centerX(), it.centerY()) }) // Forget
        assertNull(store.cache("de_en").get("moinsen"))
    }

    // ---- symbol pages ----------------------------------------------------------------------

    @Test fun symbolPagesAndBack() {
        tapKey(KeyType.SYMBOLS)
        assertEquals(Page.SYMBOLS, root.keys.layout.page)
        tapKey(KeyType.MORE_SYMBOLS)
        assertEquals(Page.SYMBOLS2, root.keys.layout.page)
        tapKey(KeyType.SYMBOLS)
        assertEquals(Page.SYMBOLS, root.keys.layout.page)
        tapKey(KeyType.LETTERS)
        assertEquals(Page.LETTERS, root.keys.layout.page)
    }

    // ---- languages -------------------------------------------------------------------------

    private fun swipeSpace(dx: Float) {
        val r = root.keys.keyBoxesForTest().first { it.first.type == KeyType.SPACE }.second
        val x = r.centerX(); val y = r.centerY()
        val off = Rect(0, 0, 0, 0); root.offsetDescendantRectToMyCoords(root.keys, off)
        touch(MotionEvent.ACTION_DOWN, off.left + x, off.top + y)
        touch(MotionEvent.ACTION_MOVE, off.left + x + dx, off.top + y)
        touch(MotionEvent.ACTION_UP, off.left + x + dx, off.top + y)
        idle()
    }

    @Test fun swipeSpaceSwitchesToRussianAndBack() {
        prefs.enabledLanguages = setOf("ru")
        idle()
        assertEquals(2, root.keys.languageCount)
        swipeSpace(300f)
        assertEquals("ru", prefs.currentLanguage)
        assertEquals("ru", ime.currentProfile.id)
        assertEquals("йцукенгшщзх".map { it.toString() }, firstRow())
        assertEquals("ru", root.keys.spaceLabel)
        swipeSpace(-300f)
        assertEquals("de_en", prefs.currentLanguage)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
    }

    @Test fun russianIsRememberedAndLearnsSeparately() {
        prefs.enabledLanguages = setOf("ru")
        prefs.currentLanguage = "ru"
        LearnedStore.resetForTest(); Languages.resetForTest()
        val ime2 = Robolectric.buildService(TastoIme::class.java).create().get()
        ime2.onCreateInputView()
        ime2.onStartInputView(textField(), false)
        val deadline = System.currentTimeMillis() + 30_000
        while (ime2.engine() == null && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
        assertNotNull("Russian dictionary loaded", ime2.engine())
        assertEquals("ru", ime2.currentProfile.id)
        ime2.learn("приветик", null, 1, false)
        val store = LearnedStore.get(ctx)
        assertNotNull(store.cache("ru").get("приветик"))
        assertNull(store.cache("de_en").get("приветик"))
    }

    @Test fun addressFieldsUseLatinLettersInRussian() {
        prefs.enabledLanguages = setOf("ru")
        prefs.currentLanguage = "ru"
        idle()
        startField(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS })
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        startField(textField())
        assertEquals("йцукенгшщзх".map { it.toString() }, firstRow())
    }

    @Test fun turningRussianOffGoesBackToGerman() {
        prefs.enabledLanguages = setOf("ru")
        idle()
        swipeSpace(300f)
        assertEquals("ru", ime.currentProfile.id)
        prefs.enabledLanguages = emptySet()
        idle()
        assertEquals("de_en", ime.currentProfile.id)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        assertEquals(1, root.keys.languageCount)
    }

    // ---- password number row ---------------------------------------------------------------

    private fun firstRow() = root.keys.layout.rows.first().filter { it.type == KeyType.CHAR }.map { it.label }

    @Test fun passwordFieldsShowNumberRow() {
        startField(passwordField())
        assertEquals("1234567890".map { it.toString() }, firstRow())
        assertEquals("qwertzuiop".map { it.toString() }, root.keys.layout.rows[1].map { it.label })
        // Back in a normal field, the number row goes away again.
        startField(textField())
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
    }

    @Test fun passwordNumberRowCanBeTurnedOff() {
        prefs.numberRowPasswords = false
        startField(passwordField())
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        prefs.numberRowPasswords = true
        idle()
        assertEquals("1234567890".map { it.toString() }, firstRow())
    }

    @Test fun numberRowEverywhereSetting() {
        prefs.numberRow = true
        idle()
        assertEquals("1234567890".map { it.toString() }, firstRow())
    }

    @Test fun passwordNumberRowStaysAfterSymbolsAndPanels() {
        startField(passwordField())
        tapKey(KeyType.SYMBOLS)
        tapKey(KeyType.LETTERS)
        assertEquals("1234567890".map { it.toString() }, firstRow())
        tapKey(KeyType.EMOJI)
        tapBarBackArrow()
        assertEquals("1234567890".map { it.toString() }, firstRow())
        // The keyboard is taller by exactly one (slimmer) row, and still well under half the screen.
        assertTrue(root.height < 2992 / 2)
    }

    // ---- TalkBack in the bar ---------------------------------------------------------------

    @Test fun talkBackCanUseTheBar() {
        val provider = root.bar.accessibilityNodeProvider!!
        fun items(): Map<String, Int> {
            val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
            val ids = (0 until host.childCount).map { i ->
                // Each child is a node id: the virtual item's id sits in the upper 32 bits.
                val f = host.javaClass.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType)
                ((f.invoke(host, i) as Long) shr 32).toInt()
            }
            return ids.associateBy { provider.createAccessibilityNodeInfo(it)!!.contentDescription.toString() }
        }
        root.bar.bar = BarState(Slot("ich", SlotStyle.NORMAL), Slot("ist", SlotStyle.PRIMARY), Slot("in", SlotStyle.NORMAL), true)
        idle()
        assertTrue(items().keys.containsAll(listOf("Show toolbar", "ich", "ist", "in")))
        provider.performAction(items().getValue("Show toolbar"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        idle()
        assertTrue(root.bar.toolbarRowVisible)
        assertTrue(items().keys.containsAll(listOf("Clipboard", "Languages", "Incognito, off", "Tasto settings", "Hide keyboard")))
        provider.performAction(items().getValue("Incognito, off"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        idle()
        assertTrue(prefs.manualIncognito)
        assertTrue("Incognito, on" in items().keys)
    }
}
