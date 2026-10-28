package io.github.realkhfan.tasto.ui

import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.keyboard.KeyboardTheme

/** Settings model: Compose state backed by [Prefs], which the keyboard listens to. */
class SettingsModel(val prefs: Prefs) {
    var theme by mutableStateOf(prefs.theme); private set
    var accent by mutableStateOf(prefs.accent); private set
    var keyHeight by mutableStateOf(prefs.keyHeight); private set
    var keyHints by mutableStateOf(prefs.keyHints); private set
    var keyPopup by mutableStateOf(prefs.keyPopup); private set
    var numberRow by mutableStateOf(prefs.numberRow); private set
    var numberRowPasswords by mutableStateOf(prefs.numberRowPasswords); private set
    var coloredEnter by mutableStateOf(prefs.coloredEnter); private set
    var autoSpace by mutableStateOf(prefs.autoSpace); private set
    var enabledLanguages by mutableStateOf(prefs.enabledLanguages); private set
    var autocorrect by mutableStateOf(prefs.autocorrect); private set
    var autoCap by mutableStateOf(prefs.autoCap); private set
    var doubleSpace by mutableStateOf(prefs.doubleSpace); private set
    var suggestions by mutableStateOf(prefs.suggestions); private set
    var vibrate by mutableStateOf(prefs.vibrate); private set
    var sound by mutableStateOf(prefs.sound); private set
    var learning by mutableStateOf(prefs.learning); private set
    var autoIncognito by mutableStateOf(prefs.autoIncognito); private set
    var manualIncognito by mutableStateOf(prefs.manualIncognito); private set
    var clipboardHistory by mutableStateOf(prefs.clipboardHistory); private set

    fun updateTheme(v: String) { prefs.theme = v; theme = v }
    fun updateAccent(v: String) { prefs.accent = v; accent = v }
    fun updateKeyHeight(v: String) { prefs.keyHeight = v; keyHeight = v }
    fun updateKeyHints(v: Boolean) { prefs.keyHints = v; keyHints = v }
    fun updateKeyPopup(v: Boolean) { prefs.keyPopup = v; keyPopup = v }
    fun updateNumberRow(v: Boolean) { prefs.numberRow = v; numberRow = v }
    fun updateNumberRowPasswords(v: Boolean) { prefs.numberRowPasswords = v; numberRowPasswords = v }
    fun updateColoredEnter(v: Boolean) { prefs.coloredEnter = v; coloredEnter = v }
    fun updateAutoSpace(v: Boolean) { prefs.autoSpace = v; autoSpace = v }
    fun setLanguageEnabled(id: String, on: Boolean) {
        val v = if (on) enabledLanguages + id else enabledLanguages - id
        prefs.enabledLanguages = v
        enabledLanguages = v
    }
    fun updateAutocorrect(v: Boolean) { prefs.autocorrect = v; autocorrect = v }
    fun updateAutoCap(v: Boolean) { prefs.autoCap = v; autoCap = v }
    fun updateDoubleSpace(v: Boolean) { prefs.doubleSpace = v; doubleSpace = v }
    fun updateSuggestions(v: Boolean) { prefs.suggestions = v; suggestions = v }
    fun updateVibrate(v: Boolean) { prefs.vibrate = v; vibrate = v }
    fun updateSound(v: Boolean) { prefs.sound = v; sound = v }
    fun updateLearning(v: Boolean) { prefs.learning = v; learning = v }
    fun updateAutoIncognito(v: Boolean) { prefs.autoIncognito = v; autoIncognito = v }
    fun updateManualIncognito(v: Boolean) { prefs.manualIncognito = v; manualIncognito = v }
    fun updateClipboardHistory(v: Boolean) { prefs.clipboardHistory = v; clipboardHistory = v }

    /** Picks up changes made from the keyboard (the incognito button, a language picked in Android's 🌐 list). */
    fun reload() {
        manualIncognito = prefs.manualIncognito
        enabledLanguages = prefs.enabledLanguages
    }

    val accentColor: Int get() = KeyboardTheme.ACCENTS[accent] ?: KeyboardTheme.ACCENTS.getValue("blue")
}

class MainActivity : ComponentActivity() {
    private lateinit var model: SettingsModel
    /** Bumped when the activity comes back, so the setup status is re-checked. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = SettingsModel(Prefs(this))
        setContent {
            TastoTheme(model.accentColor, model.theme) {
                App(model, resumeTick, ::isEnabled, ::isSelected)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.reload()
        resumeTick++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) resumeTick++
    }

    private fun isEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isSelected(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val current = getSystemService(InputMethodManager::class.java)?.currentInputMethodInfo
            if (current != null) return current.packageName == packageName
        }
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }
}

enum class Screen { HOME, APPEARANCE, TYPING, PRIVACY, WORDS, LANGUAGES, ABOUT }

@Composable
fun App(model: SettingsModel, resumeTick: Int, isEnabled: () -> Boolean, isSelected: () -> Boolean) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    val back = { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> HomeScreen(model, resumeTick, isEnabled, isSelected) { screen = it }
        Screen.APPEARANCE -> AppearanceScreen(model, back)
        Screen.TYPING -> TypingScreen(model, back)
        Screen.PRIVACY -> PrivacyScreen(model, back)
        Screen.WORDS -> LearnedWordsScreen(back)
        Screen.LANGUAGES -> LanguagesScreen(model, back)
        Screen.ABOUT -> AboutScreen(back)
    }
}
