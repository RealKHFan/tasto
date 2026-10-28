package io.github.realkhfan.tasto

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.ime.FieldPolicy
import io.github.realkhfan.tasto.ime.ImeEditor
import io.github.realkhfan.tasto.ime.InputLogic
import io.github.realkhfan.tasto.ime.KeySender
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Types into a real Android EditText through its InputConnection (composing text, caps mode,
 * deleting) using the same bridge the keyboard service uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealEditTextTest {
    private lateinit var edit: EditText
    private lateinit var logic: InputLogic
    private val enters = ArrayList<Char>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        edit = EditText(ctx)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val info = EditorInfo()
        val ic = edit.onCreateInputConnection(info)
        val keys = object : KeySender {
            override fun sendKeyChar(c: Char) { enters.add(c); ic.commitText(c.toString(), 1) }
            override fun sendDownUpKeyEvents(keyCode: Int) = Unit
        }
        logic = InputLogic(FakeHost())
        logic.startInput(ImeEditor({ ic }, info, keys), FieldPolicy.from(info))
    }

    private val text get() = edit.text.toString()

    @Test fun typingAWholeSentence() {
        logic.type("ich hab teh haus gesehen")
        logic.onText(".")
        logic.type(" das war schon  ")
        assertEquals("Ich hab the Haus gesehen. Das war schon. ", text)
    }

    @Test fun undoAutocorrectInRealField() {
        logic.type("teh ")
        assertEquals("The ", text)
        logic.onBackspace()
        assertEquals("Teh", text)
        logic.onSpace()
        assertEquals("Teh ", text)
    }

    @Test fun backspaceAfterCaseFixRestoresTypedWord() {
        logic.type("hallo welt ")
        assertEquals("Hallo Welt ", text)
        logic.onBackspace()
        assertEquals("Hallo welt", text)
        assertEquals("welt", logic.composingText)
    }

    @Test fun backspaceIntoWordAndContinue() {
        logic.type("ich bin ")
        logic.onBackspace()
        assertEquals("bin", logic.composingText)
        logic.onBackspace()
        assertEquals("Ich bi", text)
        logic.type("tte ")
        assertEquals("Ich bitte ", text)
    }

    @Test fun enterGoesThroughKeySender() {
        logic.type("ok")
        logic.onEnter()
        assertEquals(listOf('\n'), enters)
    }

    @Test fun emojiAndDelete() {
        logic.type("super ")
        logic.onText("👍🏽")
        logic.onBackspace()
        assertEquals("Super ", text)
    }
}
