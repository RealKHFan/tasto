package io.github.realkhfan.tasto

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.github.realkhfan.tasto.ime.FieldKind
import io.github.realkhfan.tasto.ime.FieldPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FieldPolicyTest {
    private fun info(type: Int, options: Int = 0) = EditorInfo().apply { inputType = type; imeOptions = options }

    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Test fun normalTextLearns() {
        val p = FieldPolicy.from(info(text))
        assertEquals(FieldKind.TEXT, p.kind)
        assertTrue(p.learning)
        assertTrue(p.showSuggestions)
        assertTrue(p.autocorrect)
        assertFalse(p.privateField)
    }

    @Test fun privateBrowserTabIsIncognito() {
        // What Vanadium/Chromium and Firefox send in private tabs.
        val p = FieldPolicy.from(info(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(p.privateField)
        assertFalse(p.learning)
        assertTrue("suggestions still work in incognito", p.showSuggestions)
    }

    @Test fun passwordsAreSecret() {
        for (v in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            val p = FieldPolicy.from(info(InputType.TYPE_CLASS_TEXT or v))
            assertTrue(p.secret)
            assertTrue(p.privateField)
            assertFalse(p.learning)
            assertFalse(p.showSuggestions)
            assertFalse(p.useComposing)
        }
        val pin = FieldPolicy.from(info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(pin.secret)
        assertEquals(FieldKind.NUMBER, pin.kind)
    }

    @Test fun emailAndUrlFields() {
        val email = FieldPolicy.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals(FieldKind.EMAIL, email.kind)
        assertFalse(email.learning)
        val url = FieldPolicy.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO))
        assertEquals(FieldKind.URI, url.kind)
        assertEquals(EditorInfo.IME_ACTION_GO, url.enterAction)
    }

    @Test fun numbersAndPhones() {
        assertEquals(FieldKind.NUMBER, FieldPolicy.from(info(InputType.TYPE_CLASS_NUMBER)).kind)
        assertEquals(FieldKind.PHONE, FieldPolicy.from(info(InputType.TYPE_CLASS_PHONE)).kind)
        assertEquals(FieldKind.NUMBER, FieldPolicy.from(info(InputType.TYPE_CLASS_DATETIME)).kind)
    }

    @Test fun terminalsGetRawKeys() {
        val p = FieldPolicy.from(info(InputType.TYPE_NULL))
        assertTrue(p.rawKeys)
        assertFalse(p.useComposing)
    }

    @Test fun noSuggestionsFlagIsRespected() {
        val p = FieldPolicy.from(info(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(p.showSuggestions)
        assertFalse(p.autocorrect)
    }

    @Test fun sentenceCapitalsEvenIfFieldDoesNotAsk() {
        // A plain text field (e.g. a Compose field with default options) still gets sentence capitals.
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, FieldPolicy.from(info(InputType.TYPE_CLASS_TEXT)).capsRequest)
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            FieldPolicy.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE)).capsRequest)
        // Fields that ask for word or all capitals get exactly that.
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            FieldPolicy.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)).capsRequest)
        // No automatic capitals in passwords, addresses, filters, or fields without suggestions.
        for (t in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputType.TYPE_CLASS_NUMBER,
        )) assertEquals("type $t", 0, FieldPolicy.from(info(t)).capsRequest)
    }

    @Test fun enterActionUnlessNoEnterAction() {
        assertEquals(EditorInfo.IME_ACTION_SEND, FieldPolicy.from(info(text, EditorInfo.IME_ACTION_SEND)).enterAction)
        assertEquals(
            EditorInfo.IME_ACTION_NONE,
            FieldPolicy.from(info(text, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)).enterAction,
        )
    }
}
