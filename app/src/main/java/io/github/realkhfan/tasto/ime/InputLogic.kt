package io.github.realkhfan.tasto.ime

import io.github.realkhfan.tasto.engine.Folding
import io.github.realkhfan.tasto.engine.SuggestionEngine
import io.github.realkhfan.tasto.engine.Suggestions
import java.text.BreakIterator

/** The text field Tasto types into. Implemented over InputConnection, and faked in tests. */
interface EditorBridge {
    fun textBefore(n: Int): CharSequence
    fun textAfter(n: Int): CharSequence
    fun selectedText(): CharSequence?
    fun setComposing(text: CharSequence)
    fun finishComposing()
    fun commit(text: CharSequence)
    fun deleteBefore(chars: Int)
    /** Whether the field wants a capital letter at the cursor (sentence start). */
    fun capsMode(): Boolean
    /** Enter key: newline or the field's action (send, search, go…). */
    fun enter()
    /** Backspace as a key event, for editors without text access. */
    fun sendBackspaceKey()
    fun moveCursor(steps: Int)
    fun batch(block: () -> Unit)
}

enum class ShiftState { OFF, ON, LOCKED }

enum class SlotStyle { NORMAL, PRIMARY, AUTOCORRECT, VERBATIM }

data class Slot(val word: String, val style: SlotStyle)

/**
 * The three suggestion slots: left, middle (best) and right — or, for Japanese, a scrolling
 * list of conversion [candidates] with the one being converted to [selected] (-1 = none).
 */
data class BarState(
    val left: Slot?,
    val middle: Slot?,
    val right: Slot?,
    val isPrediction: Boolean = false,
    val candidates: List<String>? = null,
    val selected: Int = -1,
) {
    val isEmpty: Boolean get() = left == null && middle == null && right == null && candidates.isNullOrEmpty()
    val slots: List<Slot?> get() = listOf(left, middle, right)

    companion object {
        val EMPTY = BarState(null, null, null)

        fun candidates(list: List<String>, selected: Int): BarState =
            if (list.isEmpty()) EMPTY else BarState(null, null, null, candidates = list, selected = selected)

        fun arrange(s: Suggestions): BarState {
            if (s.typed.isEmpty()) return EMPTY
            val r = s.ranked
            return when {
                s.typedValid -> BarState(
                    r.getOrNull(0)?.let { Slot(it, SlotStyle.NORMAL) },
                    Slot(s.typed, SlotStyle.PRIMARY),
                    r.getOrNull(1)?.let { Slot(it, SlotStyle.NORMAL) },
                )
                s.autoCorrect != null -> BarState(
                    Slot(s.typed, SlotStyle.VERBATIM),
                    Slot(s.autoCorrect, SlotStyle.AUTOCORRECT),
                    r.firstOrNull { it != s.autoCorrect }?.let { Slot(it, SlotStyle.NORMAL) },
                )
                else -> BarState(
                    Slot(s.typed, SlotStyle.VERBATIM),
                    r.getOrNull(0)?.let { Slot(it, SlotStyle.PRIMARY) },
                    r.getOrNull(1)?.let { Slot(it, SlotStyle.NORMAL) },
                )
            }
        }

        fun predictions(words: List<String>): BarState {
            if (words.isEmpty()) return EMPTY
            return BarState(
                words.getOrNull(1)?.let { Slot(it, SlotStyle.NORMAL) },
                Slot(words[0], SlotStyle.PRIMARY),
                words.getOrNull(2)?.let { Slot(it, SlotStyle.NORMAL) },
                isPrediction = true,
            )
        }
    }
}

/**
 * Everything that happens between a key press and the text field: composing the current
 * word, autocorrect (with undo on backspace), smart punctuation, double-space period,
 * shift and caps lock, and asking the engine for suggestions.
 */
class InputLogic(private val host: Host, private val clock: () -> Long = System::currentTimeMillis) {

    interface Host {
        fun engine(): SuggestionEngine?
        /** A word was committed. The host decides whether learning is allowed right now. */
        fun learn(word: String, prev: String?, boost: Int, startedWithAutoCap: Boolean)
        fun onBarChanged(bar: BarState)
        fun onShiftChanged(state: ShiftState)
        val autocorrectEnabled: Boolean
        val autoCapEnabled: Boolean
        val doubleSpacePeriod: Boolean
        val suggestionsEnabled: Boolean
        /** Type a space automatically after . , ! ? : ; that follow a word. */
        val autoSpaceAfterPunctuation: Boolean
    }

    private data class Correction(val original: String, val corrected: String, val separator: String)

    var editor: EditorBridge? = null
        private set
    var policy: FieldPolicy = FieldPolicy.DEFAULT
        private set
    var shift: ShiftState = ShiftState.OFF
        private set
    var bar: BarState = BarState.EMPTY
        private set

    private val composing = StringBuilder()
    private var composingAutoCap = false
    private var manualShift = false
    private var lastShiftTap = 0L
    private var lastCorrection: Correction? = null
    private var spaceAfterWord: String? = null
    private var rejected: String? = null
    /** The last key added a space by itself after punctuation ("Hallo." became "Hallo. "). */
    private var autoSpaced = false

    val composingText: String get() = composing.toString()

    fun startInput(editor: EditorBridge?, policy: FieldPolicy) {
        this.editor = editor
        this.policy = policy
        composing.setLength(0)
        lastCorrection = null
        spaceAfterWord = null
        rejected = null
        autoSpaced = false
        manualShift = false
        if (shift != ShiftState.LOCKED) setShift(ShiftState.OFF)
        updateShiftFromCaps()
        // The bar may show another language's candidates; always send a fresh state.
        bar = BarState.EMPTY
        host.onBarChanged(bar)
        refreshBar()
    }

    fun finishInput() {
        if (composing.isNotEmpty()) editor?.finishComposing()
        composing.setLength(0)
        lastCorrection = null
        spaceAfterWord = null
        editor = null
    }

    // ---- keys -------------------------------------------------------------------------

    /** A character key (or a multi-character key such as an emoji). */
    fun onText(raw: String) {
        val e = editor ?: return
        val text = applyShift(raw)
        val single = text.codePointCount(0, text.length) == 1
        val c = text[0]
        val wasAutoSpaced = autoSpaced && e.textBefore(1).toString() == " "
        autoSpaced = false
        when {
            single && policy.useComposing &&
                (Folding.isWordChar(c) || (composing.isNotEmpty() && Folding.isWordConnector(c))) -> {
                if (composing.isEmpty()) {
                    composingAutoCap = shift == ShiftState.ON && !manualShift
                    spaceAfterWord = null
                    lastCorrection = null
                }
                composing.append(text)
                e.setComposing(composing)
            }
            single && text in SENTENCE_PUNCT -> handlePunctuation(e, text, wasAutoSpaced)
            single && composing.isNotEmpty() && text in CLOSERS -> e.batch {
                finishWord(e, autocorrect = true, separator = text)
                e.commit(text)
            }
            else -> e.batch {
                if (composing.isNotEmpty()) finishWord(e, autocorrect = false, separator = "")
                e.commit(text)
                spaceAfterWord = null
                lastCorrection = null
            }
        }
        consumeShift()
        updateShiftFromCaps()
        refreshBar()
    }

    private fun handlePunctuation(e: EditorBridge, p: String, wasAutoSpaced: Boolean) {
        e.batch {
            when {
                wasAutoSpaced -> {
                    // "Hallo. " + "." -> "Hallo.. ", "Was? " + "!" -> "Was?! ": keep the run together.
                    e.deleteBefore(1)
                    e.commit(p)
                    autoSpaceAfter(e, p, followsWord = true)
                    lastCorrection = null
                }
                composing.isNotEmpty() -> {
                    finishWord(e, autocorrect = true, separator = p)
                    e.commit(p)
                    if (autoSpaceAfter(e, p, followsWord = true)) lastCorrection = null
                }
                spaceAfterWordValid(e) -> {
                    // "word ." -> "word. "
                    e.deleteBefore(1)
                    e.commit("$p ")
                    autoSpaced = true
                    lastCorrection = null
                }
                else -> {
                    val before = e.textBefore(1)
                    e.commit(p)
                    autoSpaceAfter(e, p, followsWord = before.isNotEmpty() && Folding.isWordChar(before[0]))
                    lastCorrection = null
                }
            }
            spaceAfterWord = null
        }
    }

    /**
     * Adds the automatic space after [p] (just committed) when the setting is on, this is a
     * normal text field, and the punctuation ends a word. Not after numbers ("3.5", "12:30"),
     * web or mail addresses, or abbreviations that already contain a dot ("z.B.").
     */
    private fun autoSpaceAfter(e: EditorBridge, p: String, followsWord: Boolean): Boolean {
        if (!host.autoSpaceAfterPunctuation || !followsWord || p !in SENTENCE_PUNCT) return false
        if (policy.kind != FieldKind.TEXT || policy.secret || policy.rawKeys) return false
        val before = e.textBefore(80).toString().dropLast(p.length)
        val token = before.takeLastWhile { !it.isWhitespace() }.trimEnd('.', '!', '?', ',', ':', ';')
        if (token.isEmpty() && !before.endsWith('.') && !before.endsWith('!') && !before.endsWith('?')) return false
        if (token.contains('@') || token.contains("://") || token.startsWith("www", ignoreCase = true) || token.contains('.')) {
            return false
        }
        e.commit(" ")
        autoSpaced = true
        return true
    }

    fun onSpace() {
        val e = editor ?: return
        if (autoSpaced && e.textBefore(1).toString() == " ") {
            // The space is already there; the habitual extra tap does nothing.
            autoSpaced = false
            updateShiftFromCaps()
            refreshBar()
            return
        }
        autoSpaced = false
        e.batch {
            if (composing.isNotEmpty()) {
                val word = finishWord(e, autocorrect = true, separator = " ")
                e.commit(" ")
                spaceAfterWord = word
            } else if (host.doubleSpacePeriod && spaceAfterWordValid(e)) {
                e.deleteBefore(1)
                e.commit(". ")
                spaceAfterWord = null
                lastCorrection = null
            } else {
                e.commit(" ")
                spaceAfterWord = null
                lastCorrection = null
            }
        }
        updateShiftFromCaps()
        refreshBar()
    }

    fun onEnter() {
        val e = editor ?: return
        e.batch {
            if (composing.isNotEmpty()) finishWord(e, autocorrect = true, separator = "")
            // Don't send "Danke. " with the automatic trailing space.
            if (autoSpaced && e.textBefore(1).toString() == " ") e.deleteBefore(1)
            autoSpaced = false
            e.enter()
        }
        lastCorrection = null
        spaceAfterWord = null
        updateShiftFromCaps()
        refreshBar()
    }

    fun onBackspace() {
        val e = editor ?: return
        autoSpaced = false
        if (policy.rawKeys) {
            e.sendBackspaceKey()
            return
        }
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - lastGraphemeLength(composing))
            if (composing.isEmpty()) {
                e.batch {
                    e.setComposing("")
                    e.finishComposing()
                }
                updateShiftFromCaps()
            } else {
                e.setComposing(composing)
            }
            refreshBar()
            return
        }
        if (!e.selectedText().isNullOrEmpty()) {
            e.commit("")
            afterDelete()
            return
        }
        val c = lastCorrection
        if (c != null) {
            val tail = c.corrected + c.separator
            if (e.textBefore(tail.length).toString() == tail) {
                e.batch {
                    e.deleteBefore(tail.length)
                    composing.append(c.original)
                    e.setComposing(composing)
                }
                composingAutoCap = false
                rejected = c.original
                lastCorrection = null
                spaceAfterWord = null
                refreshBar()
                return
            }
        }
        lastCorrection = null
        spaceAfterWord = null
        val before = e.textBefore(48).toString()
        if (before.isEmpty()) {
            e.deleteBefore(1)
            afterDelete()
            return
        }
        val n = lastGraphemeLength(before)
        e.batch {
            e.deleteBefore(n)
            maybeResumeComposing(e, before.substring(0, before.length - n))
        }
        afterDelete()
    }

    /** Swipe left on backspace: delete the previous word. */
    fun onDeleteWord() {
        val e = editor ?: return
        autoSpaced = false
        if (policy.rawKeys) {
            // Terminals have no text access: fall back to single backspaces.
            e.sendBackspaceKey()
            return
        }
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            e.batch {
                e.setComposing("")
                e.finishComposing()
            }
        } else {
            val before = e.textBefore(64).toString()
            fun wordish(ch: Char) = Folding.isWordChar(ch) || ch.isDigit()
            var end = before.length
            // Spaces before the cursor go with the word in front of them.
            while (end > 0 && before[end - 1].isWhitespace()) end--
            // Punctuation or emoji right there…
            val punctEnd = end
            while (end > 0 && !wordish(before[end - 1]) && !before[end - 1].isWhitespace()) end--
            // …goes together with the word it is attached to ("Hallo, " in one step),
            // but a lone emoji or symbol after a space is a step of its own.
            if (end == punctEnd || (end > 0 && wordish(before[end - 1]))) {
                while (end > 0 && (wordish(before[end - 1]) || Folding.isWordConnector(before[end - 1]))) end--
            }
            val n = before.length - end
            if (n > 0) e.deleteBefore(n)
        }
        lastCorrection = null
        spaceAfterWord = null
        afterDelete()
    }

    fun onSuggestion(slot: Slot) {
        val e = editor ?: return
        val word = slot.word
        autoSpaced = false
        e.batch {
            val prev = prevWord(e) ?: if (atSentenceStart(e)) START else null
            if (composing.isNotEmpty()) {
                if (word != composing.toString()) e.setComposing(word)
                e.finishComposing()
                composing.setLength(0)
                learnCommitted(word, prev, if (slot.style == SlotStyle.VERBATIM) 2 else 1)
            } else {
                e.commit(word)
                learnCommitted(word, prev, 1)
            }
            e.commit(" ")
        }
        spaceAfterWord = word
        lastCorrection = null
        rejected = null
        consumeShift()
        updateShiftFromCaps()
        refreshBar()
    }

    /** Text from outside the keys, e.g. a clipboard item. */
    fun onPaste(text: String) {
        val e = editor ?: return
        autoSpaced = false
        e.batch {
            if (composing.isNotEmpty()) finishWord(e, autocorrect = false, separator = "")
            e.commit(text)
        }
        lastCorrection = null
        spaceAfterWord = null
        updateShiftFromCaps()
        refreshBar()
    }

    fun onShiftKey() {
        val t = clock()
        val next = if (t - lastShiftTap < DOUBLE_TAP_MS && shift != ShiftState.LOCKED) {
            ShiftState.LOCKED
        } else if (shift == ShiftState.OFF) {
            ShiftState.ON
        } else {
            ShiftState.OFF
        }
        lastShiftTap = t
        manualShift = true
        setShift(next)
        if (composing.isEmpty()) refreshBar()
    }

    fun onShiftLongPress() {
        manualShift = true
        setShift(ShiftState.LOCKED)
    }

    fun onCursorMove(steps: Int) {
        val e = editor ?: return
        autoSpaced = false
        if (composing.isNotEmpty()) {
            e.finishComposing()
            composing.setLength(0)
        }
        e.moveCursor(steps)
    }

    /** The editor reports a selection change; detect cursor moves by the user. */
    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        val e = editor ?: return
        if (composing.isNotEmpty()) {
            if (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd) {
                composing.setLength(0)
                e.finishComposing()
                lastCorrection = null
                spaceAfterWord = null
                manualShift = false
                updateShiftFromCaps()
                refreshBar()
            }
        } else {
            // Our own ". " also arrives here; only a real cursor move cancels the automatic space.
            if (autoSpaced && e.textBefore(1).toString() != " ") autoSpaced = false
            updateShiftFromCaps()
            refreshBar()
        }
    }

    // ---- internals --------------------------------------------------------------------

    private fun finishWord(e: EditorBridge, autocorrect: Boolean, separator: String): String {
        val typed = composing.toString()
        val prev = prevWord(e)
        val learnPrev = prev ?: if (atSentenceStart(e)) START else null
        var final = typed
        val wasRejected = typed == rejected
        if (autocorrect && !wasRejected && host.autocorrectEnabled && policy.autocorrect) {
            host.engine()?.suggest(typed, prev)?.autoCorrect?.let { final = it }
        }
        if (final != typed) e.setComposing(final)
        e.finishComposing()
        composing.setLength(0)
        lastCorrection = if (final != typed && separator.isNotEmpty()) Correction(typed, final, separator) else null
        rejected = null
        learnCommitted(final, learnPrev, if (wasRejected) 2 else 1)
        return final
    }

    private fun learnCommitted(word: String, prev: String?, boost: Int) {
        host.engine()?.onWordCommitted(word)
        if (policy.learning) host.learn(word, prev, boost, composingAutoCap)
        composingAutoCap = false
    }

    private fun spaceAfterWordValid(e: EditorBridge): Boolean {
        val w = spaceAfterWord ?: return false
        return e.textBefore(w.length + 1).toString() == "$w "
    }

    /** The word before the cursor (before the word being typed), if separated by spaces only. */
    fun prevWord(e: EditorBridge): String? {
        var before = e.textBefore(64 + composing.length).toString()
        if (composing.isNotEmpty() && before.endsWith(composing)) before = before.dropLast(composing.length)
        val m = PREV_WORD.find(before) ?: return null
        return m.groupValues[1]
    }

    /** Whether the cursor (before the word being typed) is where a new sentence starts. */
    fun atSentenceStart(e: EditorBridge): Boolean {
        var before = e.textBefore(64 + composing.length).toString()
        if (composing.isNotEmpty() && before.endsWith(composing)) before = before.dropLast(composing.length)
        if (before.endsWith("\n")) return true
        val t = before.trimEnd(' ')
        return t.isEmpty() || t.endsWith('.') || t.endsWith('!') || t.endsWith('?') || t.endsWith('\n')
    }

    private fun maybeResumeComposing(e: EditorBridge, before: String) {
        if (!policy.useComposing) return
        val after = e.textAfter(1)
        if (after.isNotEmpty() && (Folding.isWordChar(after[0]) || after[0].isDigit())) return
        val m = TRAILING_WORD.find(before) ?: return
        val word = m.value
        if (word.length > 40) return
        e.deleteBefore(word.length)
        composing.append(word)
        e.setComposing(composing)
        composingAutoCap = false
    }

    private fun afterDelete() {
        updateShiftFromCaps()
        refreshBar()
    }

    private fun applyShift(text: String): String {
        if (shift == ShiftState.OFF) return text
        if (text == "ß") return text
        return text.uppercase()
    }

    private fun consumeShift() {
        if (shift == ShiftState.ON) setShift(ShiftState.OFF)
        manualShift = false
    }

    private fun updateShiftFromCaps() {
        if (shift == ShiftState.LOCKED || manualShift) return
        val e = editor
        val want = e != null && host.autoCapEnabled && composing.isEmpty() &&
            !policy.secret && policy.kind == FieldKind.TEXT && e.capsMode()
        setShift(if (want) ShiftState.ON else ShiftState.OFF)
    }

    private fun setShift(s: ShiftState) {
        if (s == shift) return
        shift = s
        host.onShiftChanged(s)
    }

    fun refreshBar() {
        val e = editor
        val engine = host.engine()
        val next = when {
            e == null || engine == null || !policy.showSuggestions || !host.suggestionsEnabled -> BarState.EMPTY
            composing.isNotEmpty() -> BarState.arrange(engine.suggest(composing.toString(), prevWord(e)))
            else -> {
                val prev = prevWord(e)
                when {
                    prev != null -> BarState.predictions(engine.predict(prev, shift == ShiftState.ON))
                    atSentenceStart(e) -> BarState.predictions(engine.predictStart(shift != ShiftState.OFF || policy.capsRequest != 0))
                    else -> BarState.EMPTY
                }
            }
        }
        if (next != bar) {
            bar = next
            host.onBarChanged(next)
        }
    }

    companion object {
        const val DOUBLE_TAP_MS = 350L
        /** Learned "previous word" for words that start a sentence. */
        const val START = io.github.realkhfan.tasto.engine.SuggestionEngine.START
        val SENTENCE_PUNCT = setOf(".", ",", "!", "?", ";", ":")
        private val CLOSERS = setOf(")", "]", "}", "\"", "»", "“", "”")
        private val PREV_WORD = Regex("""(\p{L}[\p{L}'’\-]*) +$""")
        private val TRAILING_WORD = Regex("""\p{L}[\p{L}'’\-]*$""")

        fun lastGraphemeLength(s: CharSequence): Int {
            if (s.isEmpty()) return 0
            val it = BreakIterator.getCharacterInstance()
            it.setText(s.toString())
            val start = it.preceding(s.length)
            return if (start == BreakIterator.DONE) 1 else s.length - start
        }
    }
}
