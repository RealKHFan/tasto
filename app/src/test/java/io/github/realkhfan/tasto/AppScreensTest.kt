package io.github.realkhfan.tasto

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.ui.App
import io.github.realkhfan.tasto.ui.AppearanceScreen
import io.github.realkhfan.tasto.ui.HomeScreen
import io.github.realkhfan.tasto.ui.LearnedWordsScreen
import io.github.realkhfan.tasto.ui.PrivacyScreen
import io.github.realkhfan.tasto.ui.SettingsModel
import io.github.realkhfan.tasto.ui.TastoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-night-xxhdpi")
class AppScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var model: SettingsModel
    private lateinit var store: LearnedStore

    @Before fun setUp() {
        LearnedStore.resetForTest()
        store = LearnedStore.get(ctx)
        val deadline = System.currentTimeMillis() + 10_000
        while (!store.loaded && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        model = SettingsModel(Prefs(ctx))
    }

    private fun snap(name: String) {
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeShowsSetupSteps() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { HomeScreen(model, 0, { false }, { false }) {} } }
        rule.onNodeWithText("Set up Tasto").assertIsDisplayed()
        rule.onNodeWithText("Turn on Tasto in your keyboard list").assertIsDisplayed()
        snap("20_app_home_setup")
    }

    @Test fun homeWhenReady() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { HomeScreen(model, 0, { true }, { true }) {} } }
        assertTrue(rule.onAllNodesWithTextCount("Set up Tasto") == 0)
        snap("21_app_home_ready")
    }

    @Test fun learnedWordsFilterAndDelete() {
        repeat(12) { store.learn("de_en", "oft", "oft", null, 1, true) }
        repeat(4) { store.learn("de_en", "Moinsen", "Moinsen", null, 1, false) }
        store.learn("de_en", "selten", "selten", null, 1, true)
        rule.setContent { TastoTheme(model.accentColor, "dark") { LearnedWordsScreen {} } }
        rule.onNodeWithText("Moinsen").assertIsDisplayed()
        rule.onNodeWithText("oft").assertIsDisplayed()
        snap("22_app_learned_words")
        rule.onNodeWithText("Often · 1").performClick()
        rule.onNodeWithText("oft").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("Moinsen") == 0)
        rule.onNodeWithText("Often · 1").performClick() // still selected, no-op
        rule.onNodeWithText("All · 3").performClick()
        rule.onNodeWithContentDescription("Delete Moinsen").performClick()
        rule.waitForIdle()
        assertNull(store.cache("de_en").get("moinsen"))
        assertEquals(2, store.allWords().size)
    }

    @Test fun addWordByHand() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { LearnedWordsScreen {} } }
        rule.onNodeWithContentDescription("Add a word").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("newWord").assertIsDisplayed()
        snap("25_app_add_word")
        // The blinking text cursor never lets Compose settle; drive the clock by hand.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("newWord").performTextInput("Grafeno")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Add").performClick()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(2, store.cache("de_en").get("grafeno")?.count)
    }

    @Test fun addWordWhileTypingJapaneseGoesToGermanEnglish() {
        Prefs(ctx).currentLanguage = "ja_flick"
        rule.setContent { TastoTheme(model.accentColor, "dark") { LearnedWordsScreen {} } }
        rule.onNodeWithContentDescription("Add a word").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("newWord").performTextInput("Grafeno")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Add").performClick()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(2, store.cache("de_en").get("grafeno")?.count)
        assertNull(store.cache("ja").get("grafeno"))
    }

    @Test fun deleteAllDialog() {
        store.learn("de_en", "eins", "eins", null, 1, true)
        rule.setContent { TastoTheme(model.accentColor, "dark") { LearnedWordsScreen {} } }
        rule.onNodeWithContentDescription("Delete all").performClick()
        rule.onNodeWithText("Delete all").performClick()
        rule.waitForIdle()
        assertTrue(store.allWords().isEmpty())
    }

    @Test fun privacyTogglesWritePrefs() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { PrivacyScreen(model) {} } }
        rule.onNodeWithText("Incognito now").performClick()
        assertTrue(Prefs(ctx).manualIncognito)
        rule.onNodeWithText("Incognito now").performClick()
        assertTrue(!Prefs(ctx).manualIncognito)
    }

    /** Steps the test clock until [cond] holds; encryption runs on another thread. */
    private fun advanceUntil(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $what" }
            rule.mainClock.advanceTimeBy(50)
            Thread.sleep(20)
        }
    }

    private fun pickFile(action: String, uri: android.net.Uri, confirm: String) {
        val shadow = shadowOf(rule.activity)
        val started = shadow.nextStartedActivityForResult
        assertEquals(action, started.intent.action)
        // The form focuses its password field, whose blinking cursor never lets Compose settle.
        rule.mainClock.autoAdvance = false
        shadow.receiveResult(started.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(50)
        }
        // It opens at the bottom of the screen: the field has focus and the buttons were scrolled into view.
        rule.onNodeWithTag("password").assertIsFocused()
        rule.onNodeWithText(confirm).assertIsDisplayed()
    }

    @Test fun backupExportAndImportThroughTheFilePicker() {
        store.learn("de_en", "moinsen", "moinsen", null, 3, false)
        store.learn("de_en", "leute", "Leute", "moinsen", 1, true)
        Prefs(ctx).accent = "red"
        val file = java.io.ByteArrayOutputStream()
        val uri = android.net.Uri.parse("content://test.documents/tasto-backup.tasto")
        rule.setContent { TastoTheme(model.accentColor, "dark") { PrivacyScreen(model) {} } }
        snap("23_app_privacy")
        rule.onNodeWithText("Export backup").performScrollTo()
        val resolver = shadowOf(rule.activity.contentResolver)
        resolver.registerOutputStream(uri, file)
        rule.onNodeWithText("Export backup").performClick()
        pickFile(android.content.Intent.ACTION_CREATE_DOCUMENT, uri, "Export")
        rule.onNodeWithText("Protect the backup").assertIsDisplayed()
        rule.onNodeWithTag("password").performTextInput("short")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("passwordHint").assertTextEquals("At least 8 characters")
        rule.onNodeWithText("Export").assertIsNotEnabled()
        rule.onNodeWithTag("password").performTextInput(" but now long")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("password2").performTextInput("short but")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("passwordHint").assertTextEquals("The passwords don't match")
        rule.onNodeWithText("Export").assertIsNotEnabled()
        rule.onNodeWithTag("password2").performTextInput(" now long")
        rule.mainClock.advanceTimeBy(100)
        snap("26_app_backup_password")
        rule.onNodeWithText("Export").assertIsEnabled()
        rule.onNodeWithTag("password2").performImeAction() // ✓ on the keyboard exports
        advanceUntil("export") { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("backupStatus")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("backupStatus").assertTextEquals("Backup saved: 2 words, 1 phrase, settings and 0 pinned clips.")
        assertEquals(0, rule.onAllNodesWithTextCount("Protect the backup"))

        // The new phone.
        store.deleteAll()
        Prefs(ctx).accent = "blue"
        resolver.registerInputStream(uri, java.io.ByteArrayInputStream(file.toByteArray()))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Import backup").performScrollTo().performClick()
        pickFile(android.content.Intent.ACTION_OPEN_DOCUMENT, uri, "Import")
        rule.onNodeWithText("Enter the password", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("password").performTextInput("wrong password")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Import").performSemanticsAction(SemanticsActions.OnClick)
        advanceUntil("wrong password") { rule.onAllNodesWithTextCount("Wrong password, or the file was changed.") == 1 }
        rule.onNodeWithTag("passwordHint").assertTextEquals("Wrong password, or the file was changed.")
        assertNull(store.cache("de_en").get("moinsen"))
        resolver.registerInputStream(uri, java.io.ByteArrayInputStream(file.toByteArray()))
        rule.onNodeWithTag("password").performTextClearance()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("password").performTextInput("short but now long")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Import").performSemanticsAction(SemanticsActions.OnClick)
        advanceUntil("import") { rule.onAllNodesWithTextCount("Unlock the backup") == 0 }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("backupStatus").assertTextEquals("Imported: 2 words, 1 phrase, settings and 0 pinned clips.")
        assertEquals(3, store.cache("de_en").get("moinsen")?.count)
        assertEquals(mapOf("leute" to 1), store.cache("de_en").nexts("moinsen"))
        assertEquals("red", Prefs(ctx).accent)
        assertEquals("red", model.accent)
    }

    @Test fun appearanceShowsLivePreview() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { AppearanceScreen(model) {} } }
        rule.onNodeWithText("Light").performClick()
        assertEquals("light", Prefs(ctx).theme)
        rule.onNodeWithText("Dark").performClick()
        snap("24_app_appearance")
        Prefs(ctx).theme = "system"
    }

    private val screens = listOf("Appearance", "Typing", "Privacy & incognito", "Learned words", "Languages", "About")

    private fun openFromHome(title: String) {
        rule.onNodeWithText(title).performScrollTo().performClick()
        rule.waitForIdle()
        // On a sub-screen: its title is shown and the home list ("Settings") is gone.
        rule.onNodeWithText(title).assertIsDisplayed()
        assertEquals("left home for $title", 0, rule.onAllNodesWithTextCount("Settings"))
    }

    private fun assertHome() {
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun learnedWordsScreenIsScreenshotProtected() {
        fun secure() = (rule.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        rule.setContent { TastoTheme(model.accentColor, "dark") { App(model, 0, { true }, { true }) } }
        assertTrue(!secure())
        openFromHome("Learned words")
        assertTrue("learned words protected", secure())
        rule.onNodeWithContentDescription("Back").performClick()
        assertHome()
        assertTrue("home not protected", !secure())
    }

    @Test fun everySettingsScreenBackArrowReturnsHome() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { App(model, 0, { true }, { true }) } }
        for (title in screens) {
            openFromHome(title)
            rule.onNodeWithContentDescription("Back").performClick()
            assertHome()
        }
    }

    @Test fun everySettingsScreenAndroidBackReturnsHome() {
        rule.setContent { TastoTheme(model.accentColor, "dark") { App(model, 0, { true }, { true }) } }
        for (title in screens) {
            openFromHome(title)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            assertHome()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
}
