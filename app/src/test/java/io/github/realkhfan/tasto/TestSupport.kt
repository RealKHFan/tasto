package io.github.realkhfan.tasto

import io.github.realkhfan.tasto.engine.BaseDictionary
import io.github.realkhfan.tasto.engine.KeyProximity
import io.github.realkhfan.tasto.engine.LearnedCache
import io.github.realkhfan.tasto.engine.SuggestionEngine
import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.EditorBridge
import io.github.realkhfan.tasto.ime.InputLogic
import io.github.realkhfan.tasto.ime.ShiftState
import java.io.File

object TestDicts {
    private fun asset(name: String): File {
        val candidates = listOf(File("src/main/assets/dict/$name"), File("app/src/main/assets/dict/$name"))
        return candidates.first { it.exists() }
    }

    val de: BaseDictionary by lazy { asset("de.txt").inputStream().use { BaseDictionary.load("de", it) } }
    val en: BaseDictionary by lazy { asset("en.txt").inputStream().use { BaseDictionary.load("en", it) } }
    val ru: BaseDictionary by lazy { asset("ru.txt").inputStream().use { BaseDictionary.load("ru", it) } }

    fun ruEngine(learned: LearnedCache = LearnedCache()) =
        SuggestionEngine(listOf(ru), learned, KeyProximity.JCUKEN)

    fun engine(learned: LearnedCache = LearnedCache()) =
        SuggestionEngine(listOf(de, en), learned, KeyProximity.QWERTZ)
}

/** In-memory text field that behaves like an Android EditText's InputConnection. */
class FakeEditor : EditorBridge {
    val text = StringBuilder()
    var cursor = 0
    var compStart = -1
    var compEnd = -1
    var enterPresses = 0
    var autoCaps = true

    override fun textBefore(n: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    override fun textAfter(n: Int): CharSequence = text.substring(cursor, minOf(text.length, cursor + n))
    override fun selectedText(): CharSequence? = null

    override fun setComposing(text: CharSequence) {
        val start = if (compStart >= 0) compStart else cursor
        val end = if (compStart >= 0) compEnd else cursor
        this.text.replace(start, end, text.toString())
        compStart = start
        compEnd = start + text.length
        cursor = compEnd
    }

    override fun finishComposing() {
        compStart = -1
        compEnd = -1
    }

    override fun commit(text: CharSequence) {
        val start = if (compStart >= 0) compStart else cursor
        val end = if (compStart >= 0) compEnd else cursor
        this.text.replace(start, end, text.toString())
        cursor = start + text.length
        compStart = -1
        compEnd = -1
    }

    override fun deleteBefore(chars: Int) {
        val start = maxOf(0, cursor - chars)
        text.delete(start, cursor)
        cursor = start
    }

    override fun capsMode(): Boolean {
        if (!autoCaps) return false
        val before = text.substring(0, cursor)
        if (before.isBlank()) return true
        if (!before.last().isWhitespace()) return false
        val t = before.trimEnd()
        return t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || before.endsWith("\n")
    }

    override fun enter() {
        enterPresses++
        commit("\n")
    }

    override fun sendBackspaceKey() = deleteBefore(1)
    override fun moveCursor(steps: Int) { cursor = (cursor + steps).coerceIn(0, text.length) }
    override fun batch(block: () -> Unit) = block()

    override fun toString(): String = text.toString()
}

class FakeHost(val learned: LearnedCache = LearnedCache()) : InputLogic.Host {
    val engine = TestDicts.engine(learned)
    val learnedLog = ArrayList<Pair<String, Int>>()
    var lastBar: BarState = BarState.EMPTY
    var lastShift = ShiftState.OFF
    override fun engine(): SuggestionEngine = engine
    override fun learn(word: String, prev: String?, boost: Int, startedWithAutoCap: Boolean) {
        learnedLog.add(word to boost)
        learned.learn(word, word, prev?.lowercase(), boost, engine.isKnown(word), 0L)
    }
    override fun onBarChanged(bar: BarState) { lastBar = bar }
    override fun onShiftChanged(state: ShiftState) { lastShift = state }
    override var autocorrectEnabled = true
    override var autoCapEnabled = true
    override var doubleSpacePeriod = true
    override var suggestionsEnabled = true
    override var autoSpaceAfterPunctuation = true
}

/** Types a string key by key: ' ' is space, '<' is backspace, '\n' is enter. */
fun InputLogic.type(s: String) {
    for (ch in s) when (ch) {
        ' ' -> onSpace()
        '<' -> onBackspace()
        '\n' -> onEnter()
        else -> {
            // Keys send lowercase; uppercase in the script means "press shift first".
            if (ch.isUpperCase() && shift == ShiftState.OFF) onShiftKey()
            onText(ch.lowercaseChar().toString())
        }
    }
}
