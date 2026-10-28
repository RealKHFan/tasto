package io.github.realkhfan.tasto

import io.github.realkhfan.tasto.ime.FieldKind
import io.github.realkhfan.tasto.ime.FieldPolicy
import io.github.realkhfan.tasto.ime.InputLogic
import io.github.realkhfan.tasto.ime.ShiftState
import io.github.realkhfan.tasto.ime.SlotStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputLogicTest {
    private lateinit var host: FakeHost
    private lateinit var editor: FakeEditor
    private lateinit var logic: InputLogic
    private var now = 0L

    @Before fun setUp() {
        host = FakeHost()
        editor = FakeEditor()
        logic = InputLogic(host) { now }
        logic.startInput(editor, FieldPolicy.DEFAULT)
    }

    @Test fun autoCapitalizesAndCorrectsNouns() {
        logic.type("hallo welt ")
        assertEquals("Hallo Welt ", editor.toString())
    }

    @Test fun autocorrectKeepsSentenceCase() {
        logic.type("teh ")
        assertEquals("The ", editor.toString())
    }

    @Test fun backspaceUndoesAutocorrectAndKeepsWord() {
        logic.type("ich mag teh ")
        assertEquals("Ich mag the ", editor.toString())
        logic.onBackspace()
        assertEquals("Ich mag teh", editor.toString())
        assertEquals("teh", logic.composingText)
        logic.onSpace()
        assertEquals("Ich mag teh ", editor.toString())
        assertTrue(host.learnedLog.contains("teh" to 2))
    }

    @Test fun doubleSpaceAddsPeriodAndCapitalizes() {
        logic.type("ich bin da  ")
        assertEquals("Ich bin da. ", editor.toString())
        assertEquals(ShiftState.ON, logic.shift)
    }

    @Test fun punctuationSwapsWithAutoSpace() {
        logic.type("hallo ")
        logic.onText(",")
        assertEquals("Hallo, ", editor.toString())
        logic.type("wie geht's ")
        logic.onText("?")
        assertEquals("Hallo, wie geht's? ", editor.toString())
    }

    @Test fun punctuationRightAfterWordAutocorrects() {
        logic.type("teh")
        logic.onText(".")
        assertEquals("The. ", editor.toString())
    }

    // ---- space after punctuation ----

    @Test fun autoSpaceAfterFullStopAndCapitalNext() {
        logic.type("hallo")
        logic.onText(".")
        assertEquals("Hallo. ", editor.toString())
        assertEquals(ShiftState.ON, logic.shift)
        logic.type("wie geht's")
        logic.onText("?")
        assertEquals("Hallo. Wie geht's? ", editor.toString())
    }

    @Test fun autoSpaceAfterCommaWithoutCapital() {
        logic.type("ja")
        logic.onText(",")
        logic.type("klar")
        assertEquals("Ja, klar", editor.toString())
    }

    @Test fun habitualSpaceAfterAutoSpaceIsIgnored() {
        logic.type("ok")
        logic.onText(".")
        logic.onSpace()
        logic.type("gut")
        assertEquals("Ok. Gut", editor.toString())
    }

    @Test fun ellipsisAndDoublePunctuationStayTogether() {
        logic.type("hmm")
        logic.onText("."); logic.onText("."); logic.onText(".")
        assertEquals("Hmm... ", editor.toString())
        editor.text.setLength(0); editor.cursor = 0
        logic.startInput(editor, FieldPolicy.DEFAULT)
        logic.type("echt")
        logic.onText("?"); logic.onText("!")
        assertEquals("Echt?! ", editor.toString())
    }

    @Test fun noAutoSpaceInNumbersAndAddresses() {
        logic.onText("3"); logic.onText("."); logic.onText("5")
        logic.onSpace()
        logic.onText("1"); logic.onText("2"); logic.onText(":"); logic.onText("3"); logic.onText("0")
        assertEquals("3.5 12:30", editor.toString())
        editor.text.setLength(0); editor.cursor = 0
        logic.startInput(editor, FieldPolicy.DEFAULT)
        logic.type("z")
        logic.onText(".")
        logic.type("B")
        logic.onText(".")
        assertEquals("Z. B. ", editor.toString())
    }

    @Test fun noAutoSpaceInEmailAndUrlFields() {
        logic.startInput(editor, FieldPolicy.DEFAULT.copy(kind = FieldKind.URI, showSuggestions = false, autocorrect = false))
        logic.type("example")
        logic.onText(".")
        logic.type("com")
        assertEquals("example.com", editor.toString())
    }

    @Test fun enterDropsTheAutomaticSpace() {
        logic.type("danke")
        logic.onText(".")
        logic.onEnter()
        assertEquals("Danke.\n", editor.toString())
    }

    @Test fun backspaceRemovesOnlyTheAutomaticSpace() {
        logic.type("hallo")
        logic.onText(".")
        logic.onBackspace()
        assertEquals("Hallo.", editor.toString())
        logic.type("de")
        assertEquals("Hallo.de", editor.toString())
    }

    @Test fun autoSpaceCanBeTurnedOff() {
        host.autoSpaceAfterPunctuation = false
        logic.type("hallo")
        logic.onText(".")
        assertEquals("Hallo.", editor.toString())
    }

    @Test fun sentenceStartShowsStarters() {
        // Empty field: suggestions are sentence starters, capitalised.
        val words = logic.bar.slots.filterNotNull().map { it.word }
        assertTrue(words.toString(), words.containsAll(listOf("Ich", "I")))
        logic.type("ok")
        logic.onText(".")
        assertTrue(logic.bar.slots.filterNotNull().any { it.word == "Ich" })
    }

    @Test fun learnsHowYouStartSentences() {
        repeat(3) {
            logic.type("morgen leute")
            logic.onText(".")
        }
        assertEquals("Morgen", logic.bar.middle?.word)
    }

    @Test fun pickingSuggestionCommitsWithSpace() {
        logic.type("viell")
        val slot = logic.bar.slots.filterNotNull().first { it.word == "Vielleicht" }
        logic.onSuggestion(slot)
        assertEquals("Vielleicht ", editor.toString())
    }

    @Test fun pickingVerbatimLearnsStrongly() {
        logic.type("moinsen")
        val verbatim = logic.bar.left
        assertNotNull(verbatim)
        assertEquals(SlotStyle.VERBATIM, verbatim!!.style)
        logic.onSuggestion(verbatim)
        assertEquals("Moinsen ", editor.toString())
        assertTrue(host.learnedLog.contains("Moinsen" to 2))
    }

    @Test fun nextWordPredictionsAfterSpace() {
        logic.type("ich ")
        assertTrue(logic.bar.isPrediction)
        assertTrue(logic.bar.slots.filterNotNull().any { it.word == "habe" })
    }

    @Test fun backspaceIntoWordResumesComposing() {
        logic.type("hallo ")
        logic.onBackspace()
        assertEquals("Hallo", editor.toString())
        assertEquals("Hallo", logic.composingText)
        logic.type("chen ")
        val result = editor.toString()
        assertTrue(result, result == "Hallochen " || result == "Hallöchen ")
    }

    @Test fun shiftDoubleTapLocksCaps() {
        logic.startInput(editor, FieldPolicy.DEFAULT)
        assertEquals(ShiftState.ON, logic.shift) // sentence start
        now = 1000; logic.onShiftKey()
        assertEquals(ShiftState.OFF, logic.shift)
        now = 1200; logic.onShiftKey()
        assertEquals(ShiftState.LOCKED, logic.shift)
        logic.onText("a"); logic.onText("b")
        assertEquals("AB", editor.toString())
        assertEquals(ShiftState.LOCKED, logic.shift)
        now = 5000; logic.onShiftKey()
        assertEquals(ShiftState.OFF, logic.shift)
    }

    @Test fun backspaceRemovesWholeEmoji() {
        logic.onText("👍🏽")
        logic.onText("👨‍👩‍👧")
        logic.onBackspace()
        assertEquals("👍🏽", editor.toString())
        logic.onBackspace()
        assertEquals("", editor.toString())
    }

    @Test fun holdingBackspaceDeletesWordByWord() {
        logic.type("eins zwei drei")
        logic.onSpace() // "Eins zwei drei "
        logic.onBackspace() // the tap: one character (the space) ...
        assertEquals("Eins zwei drei", editor.toString())
        logic.onDeleteWord() // ... then held: the rest of the word
        assertEquals("Eins zwei ", editor.toString())
        logic.onDeleteWord()
        assertEquals("Eins ", editor.toString())
        logic.onDeleteWord()
        assertEquals("", editor.toString())
    }

    @Test fun tappingBackspaceStillDeletesCharacters() {
        logic.type("eins zwei ")
        logic.onBackspace(); logic.onBackspace(); logic.onBackspace()
        assertEquals("Eins zw", editor.toString())
    }

    @Test fun holdingBackspaceTakesPunctuationWithItsWord() {
        logic.type("hallo")
        logic.onText(",")
        logic.type("wie")
        logic.onDeleteWord()
        assertEquals("Hallo, ", editor.toString())
        logic.onDeleteWord()
        assertEquals("", editor.toString())
    }

    @Test fun holdingBackspaceRemovesLoneEmojiOnItsOwn() {
        logic.type("super ")
        logic.onText("👍")
        logic.onDeleteWord()
        assertEquals("Super ", editor.toString())
    }

    @Test fun deleteWordSwipe() {
        logic.type("eins zwei ")
        logic.onDeleteWord()
        assertEquals("Eins ", editor.toString())
    }

    @Test fun passwordFieldsNeverLearnOrSuggest() {
        val pw = FieldPolicy.DEFAULT.copy(secret = true, privateField = true, showSuggestions = false, autocorrect = false)
        logic.startInput(editor, pw)
        logic.type("teh secret ")
        assertEquals("teh secret ", editor.toString())
        assertTrue(host.learnedLog.isEmpty())
        assertTrue(logic.bar.isEmpty)
    }

    @Test fun privateFieldsSuggestButNeverLearn() {
        logic.startInput(editor, FieldPolicy.DEFAULT.copy(privateField = true))
        logic.type("teh ")
        assertEquals("The ", editor.toString())
        assertTrue(host.learnedLog.isEmpty())
    }

    @Test fun emailFieldsDontCapitalizeOrCorrect() {
        logic.startInput(editor, FieldPolicy.DEFAULT.copy(kind = FieldKind.EMAIL, showSuggestions = false, autocorrect = false))
        logic.type("teh")
        logic.onText("@")
        assertEquals("teh@", editor.toString())
        assertFalse(host.learnedLog.isNotEmpty())
    }

    @Test fun enterAutocorrectsThenSends() {
        logic.type("teh")
        logic.onEnter()
        assertEquals("The\n", editor.toString())
        assertEquals(1, editor.enterPresses)
    }

    @Test fun userMovingCursorEndsWord() {
        logic.type("hal")
        logic.onUpdateSelection(1, 1, 0, 3)
        assertEquals("", logic.composingText)
        assertEquals(-1, editor.compStart)
    }
}
