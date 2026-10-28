package io.github.realkhfan.tasto.japanese

import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.EditorBridge
import io.github.realkhfan.tasto.ime.FieldPolicy
import io.github.realkhfan.tasto.ime.InputLogic
import io.github.realkhfan.tasto.ime.ShiftState

/**
 * Japanese typing, the way Japanese keyboards work:
 *
 *  - romaji (QWERTZ) or kana (12-key flick) collect as underlined hiragana in the text field;
 *  - the bar shows conversions while you type — tap one to put it in (the rest of the reading,
 *    if any, stays underlined);
 *  - space converts (変換) and steps through the candidates, Enter accepts (確定);
 *  - backspace while converting goes back to the hiragana.
 *
 * Conversions you pick are remembered (unless incognito), so they come first next time.
 */
class KanaLogic(private val host: Host, private val clock: () -> Long = System::currentTimeMillis) {

    interface Host {
        fun converter(): KanaConverter?
        /** A conversion was chosen: [reading] became [surface]. The host decides whether to learn it. */
        fun learnConversion(reading: String, surface: String)
        fun onBarChanged(bar: BarState)
        fun onShiftChanged(state: ShiftState)
        /** Whether there is underlined text being composed (space shows 変換, Enter 確定). */
        fun onComposingChanged(composing: Boolean)
        val suggestionsEnabled: Boolean
    }

    var editor: EditorBridge? = null
        private set
    var policy: FieldPolicy = FieldPolicy.DEFAULT
        private set
    var shift: ShiftState = ShiftState.OFF
        private set
    var bar: BarState = BarState.EMPTY
        private set

    private val reading = StringBuilder()
    private var pending = ""
    /** Candidates while converting with space. */
    private var candidates: List<KanaCandidate> = emptyList()
    private var selected = -1
    /** Candidates shown in the bar while typing. */
    private var live: List<KanaCandidate> = emptyList()
    private var lastShiftTap = 0L
    private var composingReported = false

    val isComposing: Boolean get() = reading.isNotEmpty() || pending.isNotEmpty()
    val isConverting: Boolean get() = selected >= 0
    /** The underlined text as it is shown in the field. */
    val composingText: String get() = display()

    fun startInput(editor: EditorBridge?, policy: FieldPolicy) {
        this.editor = editor
        this.policy = policy
        clear()
        if (shift == ShiftState.ON) setShift(ShiftState.OFF)
        bar = BarState.EMPTY
        host.onBarChanged(bar)
        reportComposing()
    }

    /** Leaves the field: the underlined text stays as it is. */
    fun finishInput() {
        if (isComposing) editor?.finishComposing()
        clear()
        reportComposing()
        editor = null
    }

    // ---- keys -------------------------------------------------------------------------

    fun onText(raw: String) {
        val e = editor ?: return
        if (raw.isEmpty()) return
        if (raw == Kana.MODIFY) {
            onModify()
            return
        }
        val c = raw[0]
        val single = raw.length == 1
        when {
            single && (c in 'a'..'z' || c in 'A'..'Z' || c == '-' || c == '\'') -> {
                if (isConverting) commitAll(e)
                if (shift != ShiftState.OFF && c.isLetter()) {
                    // Shift types the letter itself, like Gboard (for names, "Tシャツ"…).
                    settle()
                    reading.append(c.uppercaseChar())
                    if (shift == ShiftState.ON) setShift(ShiftState.OFF)
                } else {
                    val step = Romaji.feed(pending, c)
                    reading.append(step.output)
                    pending = step.pending
                }
            }
            single && (Kana.isHiragana(c) || Kana.isKatakana(c) || c in Kana.COMPOSING_PUNCT || c.isDigit()) -> {
                if (isConverting) commitAll(e)
                settle()
                reading.append(c)
            }
            else -> {
                // Emoji, symbols, Latin punctuation: put in what is there, then the character.
                e.batch {
                    commitAll(e)
                    e.commit(raw)
                }
                afterChange()
                return
            }
        }
        showComposing(e)
        afterChange()
    }

    /** Space: convert, then the next candidate. Without text being composed, a space. */
    fun onSpace() {
        val e = editor ?: return
        if (!isComposing) {
            e.commit(" ")
            afterChange()
            return
        }
        if (!isConverting) {
            settle()
            val r = reading.toString()
            candidates = host.converter()?.conversion(r)?.takeIf { it.isNotEmpty() } ?: fallback(r)
            selected = 0
        } else {
            selected = (selected + 1) % candidates.size
        }
        showComposing(e)
        afterChange()
    }

    /** Enter accepts what is shown (確定); otherwise a new line or the field's action. */
    fun onEnter() {
        val e = editor ?: return
        if (isComposing) commitAll(e) else e.enter()
        afterChange()
    }

    /** A candidate in the bar was tapped. */
    fun onCandidate(index: Int) {
        val e = editor ?: return
        val list = if (isConverting) candidates else live
        val c = list.getOrNull(index) ?: return
        if (!isConverting) {
            // The bar was made from the reading with a trailing "n" read as ん.
            val lr = liveReading()
            if (lr != reading.toString()) {
                reading.setLength(0)
                reading.append(lr)
                pending = ""
            }
        }
        val consumed = c.consumed.coerceAtMost(reading.length)
        learn(reading.substring(0, consumed), c.surface)
        e.batch {
            e.setComposing(c.surface)
            e.finishComposing()
            reading.delete(0, consumed)
            selected = -1
            candidates = emptyList()
            if (isComposing) e.setComposing(display())
        }
        afterChange()
    }

    fun onBackspace() {
        val e = editor ?: return
        when {
            isConverting -> {
                selected = -1
                candidates = emptyList()
            }
            pending.isNotEmpty() -> pending = pending.dropLast(1)
            reading.isNotEmpty() -> reading.setLength(reading.length - InputLogic.lastGraphemeLength(reading))
            else -> {
                if (!e.selectedText().isNullOrEmpty()) {
                    e.commit("")
                } else {
                    val before = e.textBefore(48)
                    e.deleteBefore(if (before.isEmpty()) 1 else InputLogic.lastGraphemeLength(before))
                }
                afterChange()
                return
            }
        }
        showComposing(e)
        afterChange()
    }

    /** Holding backspace: the whole underlined text, then one run of kana or kanji after another. */
    fun onDeleteWord() {
        val e = editor ?: return
        if (isComposing) {
            clear()
            e.batch {
                e.setComposing("")
                e.finishComposing()
            }
        } else {
            val n = Kana.deleteWordLength(e.textBefore(64).toString())
            if (n > 0) e.deleteBefore(n)
        }
        afterChange()
    }

    /** ゛゜小: か→が, は→ば→ぱ, つ→っ→づ, や→ゃ … on the last kana being typed. */
    private fun onModify() {
        val e = editor ?: return
        if (isConverting) {
            selected = -1
            candidates = emptyList()
        }
        settle()
        if (reading.isEmpty()) return
        val m = Kana.modify(reading[reading.length - 1]) ?: return
        reading.setCharAt(reading.length - 1, m)
        showComposing(e)
        afterChange()
    }

    fun onCursorMove(steps: Int) {
        val e = editor ?: return
        commitAll(e)
        e.moveCursor(steps)
        afterChange()
    }

    fun onPaste(text: String) {
        val e = editor ?: return
        e.batch {
            commitAll(e)
            e.commit(text)
        }
        afterChange()
    }

    fun onShiftKey() {
        val t = clock()
        val next = when {
            t - lastShiftTap < InputLogic.DOUBLE_TAP_MS && shift != ShiftState.LOCKED -> ShiftState.LOCKED
            shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
        lastShiftTap = t
        setShift(next)
    }

    fun onShiftLongPress() = setShift(ShiftState.LOCKED)

    /** The editor reports a selection change; a cursor move by the user ends the composing. */
    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        val e = editor ?: return
        if (!isComposing) return
        if (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd) {
            clear()
            e.finishComposing()
            afterChange()
        }
    }

    // ---- internals --------------------------------------------------------------------

    /** Pending romaji becomes kana (a final "n" is ん). */
    private fun settle() {
        if (pending.isEmpty()) return
        reading.append(Romaji.flush(pending))
        pending = ""
    }

    private fun display(): String =
        if (isConverting) {
            val c = candidates[selected]
            c.surface + reading.substring(c.consumed.coerceAtMost(reading.length))
        } else {
            reading.toString() + pending
        }

    /** The reading for live candidates: with a trailing "n" as ん, without other half-typed romaji. */
    private fun liveReading(): String {
        val f = Romaji.flush(pending)
        return if (f.any { it in 'a'..'z' }) reading.toString() else reading.toString() + f
    }

    private fun fallback(r: String): List<KanaCandidate> =
        listOf(KanaCandidate(r, r.length), KanaCandidate(Kana.toKatakana(r), r.length)).distinctBy { it.surface }

    /** Puts in the text as shown (accepting the conversion), so nothing is underlined anymore. */
    private fun commitAll(e: EditorBridge) {
        if (!isComposing) return
        val text = if (isConverting) {
            val c = candidates[selected]
            learn(reading.substring(0, c.consumed.coerceAtMost(reading.length)), c.surface)
            display()
        } else {
            reading.toString() + Romaji.flush(pending)
        }
        e.batch {
            e.setComposing(text)
            e.finishComposing()
        }
        clear()
    }

    private fun learn(r: String, surface: String) {
        if (r.isEmpty() || surface.isEmpty() || !policy.learning) return
        host.learnConversion(r, surface)
    }

    private fun showComposing(e: EditorBridge) {
        if (isComposing) {
            e.setComposing(display())
        } else {
            e.batch {
                e.setComposing("")
                e.finishComposing()
            }
        }
    }

    private fun clear() {
        reading.setLength(0)
        pending = ""
        candidates = emptyList()
        selected = -1
        live = emptyList()
    }

    private fun afterChange() {
        reportComposing()
        refreshBar()
    }

    private fun reportComposing() {
        val now = isComposing
        if (now != composingReported) {
            composingReported = now
            host.onComposingChanged(now)
        }
    }

    private fun setShift(s: ShiftState) {
        if (s == shift) return
        shift = s
        host.onShiftChanged(s)
    }

    fun refreshBar() {
        val next = when {
            editor == null || !isComposing -> BarState.EMPTY
            isConverting -> BarState.candidates(candidates.map { it.surface }, selected)
            !host.suggestionsEnabled -> BarState.EMPTY
            else -> {
                val r = liveReading()
                live = if (r.isEmpty()) emptyList() else host.converter()?.candidates(r, LIVE_LIMIT) ?: fallback(r)
                BarState.candidates(live.map { it.surface }, -1)
            }
        }
        if (next != bar) {
            bar = next
            host.onBarChanged(next)
        }
    }

    companion object {
        const val LIVE_LIMIT = 30
    }
}
