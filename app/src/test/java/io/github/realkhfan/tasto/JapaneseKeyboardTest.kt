package io.github.realkhfan.tasto

import android.app.Activity
import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.Languages
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.engine.LearnedCache
import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.FieldPolicy
import io.github.realkhfan.tasto.ime.ImeEditor
import io.github.realkhfan.tasto.ime.KeySender
import io.github.realkhfan.tasto.ime.TastoIme
import io.github.realkhfan.tasto.japanese.KanaLogic
import io.github.realkhfan.tasto.keyboard.KeyType
import io.github.realkhfan.tasto.keyboard.KeyboardRoot
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
 * The whole keyboard with real touches: switching languages by swiping the space bar (all of
 * them are on from the start), Japanese romaji and 12-key flick typing, and the candidate bar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xxhdpi")
class JapaneseKeyboardTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var ime: TastoIme
    private lateinit var root: KeyboardRoot
    private lateinit var prefs: Prefs
    private val kana: KanaLogic get() = ime.kanaForTest

    @Before fun setUp() {
        LearnedStore.resetForTest()
        ClipboardStore.resetForTest()
        Languages.resetForTest()
        prefs = Prefs(ctx)
        ime = Robolectric.buildService(TastoIme::class.java).create().get()
        root = ime.onCreateInputView() as KeyboardRoot
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

    private fun waitForJapaneseDictionary() {
        val deadline = System.currentTimeMillis() + 60_000
        while (!ime.converterLoadedForTest && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
        assertTrue("Japanese dictionary loaded", ime.converterLoadedForTest)
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

    private fun offsetOf(v: android.view.View): Rect = Rect(0, 0, 0, 0).also { root.offsetDescendantRectToMyCoords(v, it) }

    private fun keyCenter(type: KeyType, label: String = ""): PointF {
        val r = root.keys.keyBoxesForTest().first { it.first.type == type && (label.isEmpty() || it.first.label == label) }.second
        val off = offsetOf(root.keys)
        return PointF(off.left + r.centerX(), off.top + r.centerY())
    }

    private fun tapKey(type: KeyType, label: String = "", holdMs: Long = 0) {
        val p = keyCenter(type, label)
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        if (holdMs > 0) idle(holdMs)
        touch(MotionEvent.ACTION_UP, p.x, p.y)
        idle()
    }

    private fun tapLetters(s: String) = s.forEach { tapKey(KeyType.CHAR, it.toString()) }

    /** Flick from the middle of a 12-key key: dx/dy in pixels. */
    private fun flick(label: String, dx: Float, dy: Float) {
        val p = keyCenter(KeyType.CHAR, label)
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        touch(MotionEvent.ACTION_MOVE, p.x + dx / 2, p.y + dy / 2)
        touch(MotionEvent.ACTION_MOVE, p.x + dx, p.y + dy)
        touch(MotionEvent.ACTION_UP, p.x + dx, p.y + dy)
        idle()
    }

    private fun swipeSpace(dx: Float) {
        val p = keyCenter(KeyType.SPACE)
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        touch(MotionEvent.ACTION_MOVE, p.x + dx, p.y)
        touch(MotionEvent.ACTION_UP, p.x + dx, p.y)
        idle()
    }

    private fun tapCandidate(i: Int) {
        val c = root.bar.candidateCenterForTest(i)
        val off = offsetOf(root.bar)
        touch(MotionEvent.ACTION_DOWN, off.left + c.x, off.top + c.y)
        touch(MotionEvent.ACTION_UP, off.left + c.x, off.top + c.y)
        idle()
    }

    private fun firstRow() = root.keys.layout.rows.first().filter { it.type == KeyType.CHAR }.map { it.label }
    private fun labels() = root.keys.layout.rows.flatten().filter { it.type == KeyType.CHAR }.map { it.label }

    private fun switchTo(id: String) {
        prefs.currentLanguage = id
        idle()
        assertEquals(id, ime.currentProfile.id)
    }

    // ---- language switching ----------------------------------------------------------------

    @Test fun allLanguagesAreOnFromTheStart() {
        assertEquals(setOf("ru", "ja", "ja_flick"), prefs.enabledLanguages)
        assertEquals(4, root.keys.languageCount)
    }

    @Test fun swipingTheSpaceBarGoesThroughEveryLanguage() {
        assertEquals("de_en", ime.currentProfile.id)
        swipeSpace(300f)
        assertEquals("ru", ime.currentProfile.id)
        assertEquals("йцукенгшщзх".map { it.toString() }, firstRow())
        swipeSpace(300f)
        assertEquals("ja", ime.currentProfile.id)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        assertEquals("ー", root.keys.layout.rows[1].last().label)
        assertEquals("日本語", root.keys.spaceLabel)
        assertTrue(ime.japaneseActiveForTest)
        swipeSpace(300f)
        assertEquals("ja_flick", ime.currentProfile.id)
        assertTrue(labels().containsAll(listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")))
        assertEquals("空白", root.keys.spaceLabel)
        swipeSpace(300f)
        assertEquals("de_en", ime.currentProfile.id)
        assertFalse(ime.japaneseActiveForTest)
        // And backwards.
        swipeSpace(-300f)
        assertEquals("ja_flick", ime.currentProfile.id)
        swipeSpace(-300f)
        assertEquals("ja", ime.currentProfile.id)
    }

    @Test fun languageCanBeRemembered() {
        swipeSpace(300f); swipeSpace(300f)
        assertEquals("ja", prefs.currentLanguage)
        startField(textField())
        assertEquals("ja", ime.currentProfile.id)
    }

    @Test fun japaneseCanBeTurnedOff() {
        prefs.enabledLanguages = setOf("ru")
        idle()
        assertEquals(2, root.keys.languageCount)
        swipeSpace(300f); swipeSpace(300f)
        assertEquals("de_en", ime.currentProfile.id)
    }

    @Test fun updateFromRussianOnlyTurnsJapaneseOn() {
        // 0.2.0 saved only "ru" (or nothing) and didn't know about Japanese.
        prefs.sp.edit().putString(Prefs.ENABLED_LANGUAGES, "ru").remove(Prefs.KNOWN_LANGUAGES).commit()
        assertEquals(setOf("ru", "ja", "ja_flick"), prefs.enabledLanguages)
        prefs.sp.edit().putString(Prefs.ENABLED_LANGUAGES, "").remove(Prefs.KNOWN_LANGUAGES).commit()
        assertEquals(setOf("ja", "ja_flick"), prefs.enabledLanguages)
        // Once chosen in the new version, the choice stays.
        prefs.enabledLanguages = setOf("ja")
        assertEquals(setOf("ja"), prefs.enabledLanguages)
    }

    // ---- romaji ----------------------------------------------------------------------------

    @Test fun romajiTypingConvertingAndAccepting() {
        switchTo("ja")
        waitForJapaneseDictionary()
        tapLetters("nihongo")
        assertEquals("にほんご", kana.composingText)
        assertEquals("変換", root.keys.spaceLabel)
        assertEquals("確定", root.keys.enterLabel)
        assertEquals("日本語", root.bar.bar.candidates?.first())
        tapKey(KeyType.SPACE)
        assertEquals("日本語", kana.composingText)
        assertEquals(0, root.bar.bar.selected)
        tapKey(KeyType.ENTER)
        assertFalse(kana.isComposing)
        assertEquals("日本語", root.keys.spaceLabel)
        assertNull(root.keys.enterLabel)
        assertTrue(root.bar.bar.isEmpty)
        // Learned for next time, under Japanese.
        assertNotNull(LearnedStore.get(ctx).cache("ja").get("日本語"))
    }

    @Test fun tappingACandidateInTheBar() {
        switchTo("ja")
        waitForJapaneseDictionary()
        tapLetters("watashiha")
        val i = root.bar.bar.candidates!!.indexOf("私")
        assertTrue(i >= 0)
        tapCandidate(i)
        assertEquals("は", kana.composingText)
        tapKey(KeyType.ENTER)
        assertFalse(kana.isComposing)
    }

    @Test fun shiftAndPunctuationInRomaji() {
        switchTo("ja")
        waitForJapaneseDictionary()
        tapKey(KeyType.SHIFT)
        tapLetters("t")
        tapLetters("shatsu")
        tapKey(KeyType.CHAR, "。")
        assertEquals("Tしゃつ。", kana.composingText)
        tapKey(KeyType.CHAR, "ー")
        assertEquals("Tしゃつ。ー", kana.composingText)
    }

    @Test fun incognitoLearnsNothing() {
        prefs.manualIncognito = true
        switchTo("ja")
        waitForJapaneseDictionary()
        tapLetters("nihongo")
        tapKey(KeyType.SPACE)
        tapKey(KeyType.ENTER)
        assertNull(LearnedStore.get(ctx).cache("ja").get("日本語"))
    }

    @Test fun passwordsAndAddressesUseLatinKeysInJapanese() {
        switchTo("ja_flick")
        startField(passwordField())
        assertFalse(ime.japaneseActiveForTest)
        assertEquals("1234567890".map { it.toString() }, firstRow())
        startField(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS })
        assertFalse(ime.japaneseActiveForTest)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        startField(textField())
        assertTrue(ime.japaneseActiveForTest)
        assertTrue(labels().contains("あ"))
    }

    @Test fun switchingLanguageWhileComposingKeepsTheText() {
        switchTo("ja")
        tapLetters("nihongo")
        assertTrue(kana.isComposing)
        swipeSpace(300f) // → 12-key: still Japanese, so you can go on typing the same word
        assertEquals("にほんご", kana.composingText)
        swipeSpace(300f) // → German + English: the text is put in as it is
        assertFalse(kana.isComposing)
        assertFalse(ime.japaneseActiveForTest)
        tapLetters("hallo")
        assertTrue(root.bar.bar.candidates == null)
    }

    // ---- 12-key flick ----------------------------------------------------------------------

    @Test fun flickDirections() {
        switchTo("ja_flick")
        val d = 25f * 3 // 25dp at xxhdpi, more than the 18dp it takes
        tapKey(KeyType.CHAR, "た")
        flick("た", -d, 0f)
        flick("た", 0f, -d)
        flick("た", d, 0f)
        flick("た", 0f, d)
        assertEquals("たちつてと", kana.composingText)
        // A small wobble is still a tap.
        flick("あ", 20f, 10f)
        assertEquals("たちつてとあ", kana.composingText)
    }

    @Test fun modifierKeyAndPunctuationKey() {
        switchTo("ja_flick")
        tapKey(KeyType.CHAR, "は")
        tapKey(KeyType.KANA_MOD)
        assertEquals("ば", kana.composingText)
        tapKey(KeyType.KANA_MOD)
        assertEquals("ぱ", kana.composingText)
        flick("た", 0f, -75f) // つ
        tapKey(KeyType.KANA_MOD)
        assertEquals("ぱっ", kana.composingText)
        tapKey(KeyType.CHAR, "、。?!")
        flick("、。?!", -75f, 0f)
        assertEquals("ぱっ、。", kana.composingText)
    }

    @Test fun holdingAKeyShowsTheGuideAndStillTypes() {
        switchTo("ja_flick")
        tapKey(KeyType.CHAR, "か", holdMs = 500)
        assertEquals("か", kana.composingText)
        // Hold, then flick: the guide is showing and the flick still counts.
        val p = keyCenter(KeyType.CHAR, "さ")
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        idle(500)
        touch(MotionEvent.ACTION_MOVE, p.x, p.y + 80f)
        touch(MotionEvent.ACTION_UP, p.x, p.y + 80f)
        idle()
        assertEquals("かそ", kana.composingText)
    }

    @Test fun flickConvertsWithSpace() {
        switchTo("ja_flick")
        waitForJapaneseDictionary()
        // とうきょう: と (た down), う (あ up), き (か left), よ (や down) + 小, う (あ up)
        flick("た", 0f, 75f)
        flick("あ", 0f, -75f)
        flick("か", -75f, 0f)
        flick("や", 0f, 75f)
        tapKey(KeyType.KANA_MOD)
        flick("あ", 0f, -75f)
        assertEquals("とうきょう", kana.composingText)
        assertEquals("変換", root.keys.spaceLabel)
        tapKey(KeyType.SPACE)
        assertEquals("東京", kana.composingText)
        tapKey(KeyType.ENTER)
        assertFalse(kana.isComposing)
        assertEquals("空白", root.keys.spaceLabel)
    }

    @Test fun languageKeyAndCursorKeys() {
        switchTo("ja_flick")
        tapKey(KeyType.CHAR, "か")
        tapKey(KeyType.CURSOR_LEFT) // puts in か first
        assertFalse(kana.isComposing)
        tapKey(KeyType.CURSOR_RIGHT)
        tapKey(KeyType.LANGUAGE) // ABC: straight to German + English
        assertEquals("de_en", ime.currentProfile.id)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
    }

    @Test fun holdingAbcShowsTheLanguageList() {
        switchTo("ja_flick")
        tapKey(KeyType.LANGUAGE, holdMs = 600)
        assertEquals(io.github.realkhfan.tasto.keyboard.Panel.LANGUAGES, root.panel)
        assertEquals("ja_flick", ime.currentProfile.id) // holding doesn't switch by itself
        tapView(root.languages.rowForTest("Russian (ЙЦУКЕН)")!!)
        assertEquals("ru", ime.currentProfile.id)
        assertEquals(io.github.realkhfan.tasto.keyboard.Panel.KEYS, root.panel)
        assertEquals("йцукенгшщзх".map { it.toString() }, firstRow())
    }

    @Test fun globeInTheToolbarShowsTheLanguageList() {
        switchTo("ja_flick")
        // The round button opens the toolbar row; the globe opens the list.
        val round = root.bar.leftButtonCenterForTest()
        tapBarAt(round)
        tapBarAt(root.bar.actionCenterForTest(io.github.realkhfan.tasto.keyboard.TopBarView.Action.LANGUAGES))
        assertEquals(io.github.realkhfan.tasto.keyboard.Panel.LANGUAGES, root.panel)
        // Every language is listed; tapping one switches and goes back to the keys.
        for (name in listOf("German + English (QWERTZ)", "Russian (ЙЦУКЕН)", "Japanese (romaji, QWERTZ)", "Japanese (12-key flick)")) {
            assertNotNull(name, root.languages.rowForTest(name))
        }
        tapView(root.languages.rowForTest("German + English (QWERTZ)")!!)
        assertEquals("de_en", ime.currentProfile.id)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        // Back closes the list without changing anything.
        tapBarAt(root.bar.actionCenterForTest(io.github.realkhfan.tasto.keyboard.TopBarView.Action.LANGUAGES))
        assertTrue(ime.goBackInside())
        idle()
        assertEquals(io.github.realkhfan.tasto.keyboard.Panel.KEYS, root.panel)
        assertEquals("de_en", ime.currentProfile.id)
    }

    @Test fun languagePickedInAndroidsSwitcherIsUsed() {
        switchTo("ja_flick")
        ime.systemLanguageChangedForTest("de_en")
        assertEquals("de_en", ime.currentProfile.id)
        assertEquals("qwertzuiop".map { it.toString() }, firstRow())
        // A language turned off in Tasto but picked in Android's list is turned on.
        prefs.enabledLanguages = setOf("ru")
        idle()
        ime.systemLanguageChangedForTest("ja_flick")
        assertEquals("ja_flick", ime.currentProfile.id)
        assertTrue("ja_flick" in prefs.enabledLanguages)
        assertTrue(labels().contains("あ"))
    }

    @Test fun androidSubtypesMapToLanguages() {
        fun subtype(extra: String) = android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeExtraValue(extra).setSubtypeMode("keyboard").build()
        assertEquals("ja_flick", TastoIme.profileIdOf(subtype("tasto=ja_flick")))
        assertEquals("de_en", TastoIme.profileIdOf(subtype("tasto=de_en")))
        assertNull(TastoIme.profileIdOf(subtype("")))
    }

    private fun tapView(v: android.view.View) {
        val off = offsetOf(v)
        touch(MotionEvent.ACTION_DOWN, off.left + v.width / 2f, off.top + v.height / 2f)
        touch(MotionEvent.ACTION_UP, off.left + v.width / 2f, off.top + v.height / 2f)
        idle()
    }

    private fun tapBarAt(p: PointF) {
        val off = offsetOf(root.bar)
        touch(MotionEvent.ACTION_DOWN, off.left + p.x, off.top + p.y)
        touch(MotionEvent.ACTION_UP, off.left + p.x, off.top + p.y)
        idle()
    }

    @Test fun symbolsPageAndBackToKana() {
        switchTo("ja_flick")
        tapKey(KeyType.SYMBOLS)
        tapKey(KeyType.LETTERS, "あいう")
        assertTrue(labels().contains("あ"))
    }

    @Test fun backspaceHeldAndTapped() {
        switchTo("ja_flick")
        tapKey(KeyType.CHAR, "か")
        tapKey(KeyType.CHAR, "さ")
        tapKey(KeyType.BACKSPACE)
        assertEquals("か", kana.composingText)
        tapKey(KeyType.CHAR, "た")
        tapKey(KeyType.BACKSPACE, holdMs = 500) // holding removes the whole underlined text
        assertFalse(kana.isComposing)
    }

    // ---- candidate bar ---------------------------------------------------------------------

    @Test fun candidateBarScrollsAndKeepsTheSelectionVisible() {
        val words = (1..40).map { "候補$it" }
        root.bar.bar = BarState.candidates(words, -1)
        idle()
        assertEquals(0f, root.bar.candidateScrollForTest())
        // Drag from right to left: the strip scrolls, nothing is picked.
        val off = offsetOf(root.bar)
        val y = off.top + root.bar.candidateCenterForTest(0).y
        touch(MotionEvent.ACTION_DOWN, off.left + 1000f, y)
        touch(MotionEvent.ACTION_MOVE, off.left + 700f, y)
        touch(MotionEvent.ACTION_MOVE, off.left + 400f, y)
        touch(MotionEvent.ACTION_UP, off.left + 400f, y)
        idle()
        assertTrue(root.bar.candidateScrollForTest() > 0f)
        // Space stepping to a candidate far right scrolls it into view.
        root.bar.bar = BarState.candidates(words, 35)
        idle()
        val c = root.bar.candidateCenterForTest(35)
        assertTrue("candidate 35 at ${c.x}", c.x > 0 && c.x < root.bar.width)
    }
}

/** Japanese typed into a real Android EditText through its InputConnection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JapaneseEditTextTest {
    private lateinit var edit: EditText
    private lateinit var logic: KanaLogic
    private lateinit var host: FakeKanaHost

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        edit = EditText(ctx)
        edit.inputType = InputType.TYPE_CLASS_TEXT
        val info = EditorInfo()
        val ic = edit.onCreateInputConnection(info)
        val keys = object : KeySender {
            override fun sendKeyChar(c: Char) { ic.commitText(c.toString(), 1) }
            override fun sendDownUpKeyEvents(keyCode: Int) = Unit
        }
        host = FakeKanaHost(LearnedCache())
        logic = KanaLogic(host)
        logic.startInput(ImeEditor({ ic }, info, keys), FieldPolicy.from(info))
    }

    private fun composingSpan(): Pair<Int, Int> {
        val t = edit.text
        return android.view.inputmethod.BaseInputConnection.getComposingSpanStart(t) to
            android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(t)
    }

    @Test fun convertAndAccept() {
        logic.type("nihongo")
        assertEquals("にほんご", edit.text.toString())
        assertEquals(0 to 4, composingSpan())
        logic.type(" ")
        assertEquals("日本語", edit.text.toString())
        logic.type("\n")
        assertEquals("日本語", edit.text.toString())
        assertEquals(-1 to -1, composingSpan())
        assertEquals(3, edit.selectionStart)
    }

    @Test fun pieceByPiece() {
        logic.type("watashiha")
        logic.onCandidate(host.lastBar.candidates!!.indexOf("私"))
        assertEquals("私は", edit.text.toString())
        assertEquals(1 to 2, composingSpan())
        logic.type("\n")
        assertEquals(-1 to -1, composingSpan())
        logic.type("nihongo")
        assertEquals("私はにほんご", edit.text.toString())
        assertEquals(2 to 6, composingSpan())
        logic.type(" \n")
        assertEquals("私は日本語", edit.text.toString())
        assertEquals(-1 to -1, composingSpan())
    }

    @Test fun backspaceAndHold() {
        logic.type("nihongo <")
        assertEquals("にほんご", edit.text.toString())
        logic.type("<")
        assertEquals("にほん", edit.text.toString())
        logic.type("\n")
        logic.onDeleteWord()
        assertEquals("", edit.text.toString())
    }
}
