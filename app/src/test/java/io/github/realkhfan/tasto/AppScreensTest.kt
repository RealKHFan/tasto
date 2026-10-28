package io.github.realkhfan.tasto

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        snap("23_app_privacy")
        rule.onNodeWithText("Incognito now").performClick()
        assertTrue(Prefs(ctx).manualIncognito)
        rule.onNodeWithText("Incognito now").performClick()
        assertTrue(!Prefs(ctx).manualIncognito)
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
