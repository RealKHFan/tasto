package io.github.realkhfan.tasto

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.Languages
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.ime.TastoIme
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

/** The real keyboard service: incognito decides what is learned and kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xxhdpi")
class ImeServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var ime: TastoIme
    private lateinit var store: LearnedStore

    @Before fun setUp() {
        LearnedStore.resetForTest()
        ClipboardStore.resetForTest()
        Languages.resetForTest()
        ime = Robolectric.buildService(TastoIme::class.java).create().get()
        ime.onCreateInputView()
        store = LearnedStore.get(ctx)
        // Wait for the dictionaries (background thread) and the database.
        val deadline = System.currentTimeMillis() + 30_000
        while ((ime.engine() == null || !store.loaded) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertNotNull("dictionaries loaded", ime.engine())
    }

    private fun field(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, options: Int = 0) =
        EditorInfo().apply { inputType = type; imeOptions = options }

    private fun learned(word: String) = store.cache("de_en").get(word.lowercase())

    @Test fun normalFieldLearns() {
        ime.onStartInputView(field(), false)
        assertFalse(ime.incognito)
        ime.learn("Tastowort", "mein", 1, false)
        assertEquals(1, learned("Tastowort")?.count)
        assertEquals(mapOf("tastowort" to 1), store.cache("de_en").nexts("mein"))
    }

    @Test fun privateTabIsIncognito() {
        ime.onStartInputView(field(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false)
        assertTrue(ime.incognito)
        ime.learn("Geheimwort", null, 1, false)
        assertNull(learned("Geheimwort"))
    }

    @Test fun autoIncognitoCanBeTurnedOff() {
        Prefs(ctx).autoIncognito = false
        ime.onStartInputView(field(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false)
        assertFalse(ime.incognito)
        Prefs(ctx).autoIncognito = true
    }

    @Test fun passwordsAreAlwaysIncognito() {
        Prefs(ctx).autoIncognito = false
        ime.onStartInputView(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false)
        assertTrue(ime.incognito)
        ime.learn("Passwort123", null, 1, false)
        assertNull(learned("Passwort123"))
        Prefs(ctx).autoIncognito = true
    }

    @Test fun manualIncognitoEverywhere() {
        Prefs(ctx).manualIncognito = true
        ime.onStartInputView(field(), false)
        assertTrue(ime.incognito)
        ime.learn("Inkognito", null, 1, false)
        assertNull(learned("Inkognito"))
        Prefs(ctx).manualIncognito = false
        assertFalse(ime.incognito)
    }

    @Test fun learningSwitchOff() {
        Prefs(ctx).learning = false
        ime.onStartInputView(field(), false)
        ime.learn("Nichtmerken", null, 1, false)
        assertNull(learned("Nichtmerken"))
        Prefs(ctx).learning = true
    }

    @Test fun sentenceStartCapitalIsNotStored() {
        ime.onStartInputView(field(), false)
        ime.learn("Moinsen", null, 1, startedWithAutoCap = true)
        assertEquals("moinsen", learned("moinsen")?.word)
        // Dictionary words keep their dictionary spelling ("Haus", not "HAUS").
        ime.learn("HAUS", null, 1, false)
        assertEquals("Haus", learned("haus")?.word)
    }

    @Test fun copyAfterPrivateTabIsNotKept() {
        val clips = ClipboardStore.get(ctx)
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        // Private tab, keyboard closed again, then a copy: not kept.
        ime.onStartInputView(field(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false)
        ime.onFinishInputView(true)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", "private link"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(clips.items().none { it.text == "private link" })
        // After a normal field, copies are kept again.
        ime.onStartInputView(field(), false)
        ime.onFinishInputView(true)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", "normal text"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(clips.items().any { it.text == "normal text" })
    }

    @Test fun landscapeKeysAreShorter() {
        ime.onStartInputView(field(), false)
        val view = ime.onCreateInputView()
        fun height(): Int {
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1344, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2992, android.view.View.MeasureSpec.AT_MOST),
            )
            return view.measuredHeight
        }
        val portrait = height()
        org.robolectric.RuntimeEnvironment.setQualifiers("+land")
        ime.onConfigurationChanged(ime.resources.configuration)
        assertTrue("landscape ${height()} < portrait $portrait", height() < portrait * 0.85)
    }

    @Test fun failedDictionaryLoadDoesNotCrashTheKeyboard() {
        assertNull(Languages.loadSafely<String> { throw OutOfMemoryError() })
        assertNull(Languages.loadSafely<String> { throw java.io.IOException("broken asset") })
        assertEquals("ok", Languages.loadSafely { "ok" })
    }

    @Test fun inputViewIsNotFullScreen() {
        ime.onStartInputView(field(), false)
        val view = ime.onCreateInputView()
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1344, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2992, android.view.View.MeasureSpec.AT_MOST),
        )
        assertTrue("keyboard is ${view.measuredHeight}px tall", view.measuredHeight in 600..1300)
    }

    @Test fun keyboardViewsAreBuilt() {
        ime.onStartInputView(field(InputType.TYPE_CLASS_PHONE), false)
        ime.onStartInputView(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS), false)
        ime.onStartInputView(field(InputType.TYPE_CLASS_NUMBER), false)
        ime.onFinishInputView(true)
    }
}
