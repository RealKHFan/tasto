package io.github.realkhfan.tasto.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings, shared by the keyboard and the Tasto app. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    var theme: String
        get() = s(THEME, "system")
        set(v) = put(THEME, v)
    var accent: String
        get() = s(ACCENT, "blue")
        set(v) = put(ACCENT, v)
    var keyHeight: String
        get() = s(KEY_HEIGHT, "normal")
        set(v) = put(KEY_HEIGHT, v)
    var keyHints: Boolean
        get() = b(KEY_HINTS, false)
        set(v) = put(KEY_HINTS, v)
    var keyPopup: Boolean
        get() = b(KEY_POPUP, true)
        set(v) = put(KEY_POPUP, v)
    var numberRow: Boolean
        get() = b(NUMBER_ROW, false)
        set(v) = put(NUMBER_ROW, v)
    var numberRowPasswords: Boolean
        get() = b(NUMBER_ROW_PASSWORDS, true)
        set(v) = put(NUMBER_ROW_PASSWORDS, v)
    var coloredEnter: Boolean
        get() = b(COLORED_ENTER, false)
        set(v) = put(COLORED_ENTER, v)
    var toolbarExpanded: Boolean
        get() = b(TOOLBAR_EXPANDED, false)
        set(v) = put(TOOLBAR_EXPANDED, v)

    var autocorrect: Boolean
        get() = b(AUTOCORRECT, true)
        set(v) = put(AUTOCORRECT, v)
    var autoCap: Boolean
        get() = b(AUTO_CAP, true)
        set(v) = put(AUTO_CAP, v)
    var doubleSpace: Boolean
        get() = b(DOUBLE_SPACE, true)
        set(v) = put(DOUBLE_SPACE, v)
    var autoSpace: Boolean
        get() = b(AUTO_SPACE, true)
        set(v) = put(AUTO_SPACE, v)
    var suggestions: Boolean
        get() = b(SUGGESTIONS, true)
        set(v) = put(SUGGESTIONS, v)
    var vibrate: Boolean
        get() = b(VIBRATE, true)
        set(v) = put(VIBRATE, v)
    var sound: Boolean
        get() = b(SOUND, false)
        set(v) = put(SOUND, v)

    var learning: Boolean
        get() = b(LEARNING, true)
        set(v) = put(LEARNING, v)
    var autoIncognito: Boolean
        get() = b(AUTO_INCOGNITO, true)
        set(v) = put(AUTO_INCOGNITO, v)
    var manualIncognito: Boolean
        get() = b(MANUAL_INCOGNITO, false)
        set(v) = put(MANUAL_INCOGNITO, v)
    var clipboardHistory: Boolean
        get() = b(CLIPBOARD_HISTORY, true)
        set(v) = put(CLIPBOARD_HISTORY, v)

    /**
     * Extra languages turned on (German + English is always on). All of them are on until you
     * turn some off; languages added in an update start on too.
     */
    var enabledLanguages: Set<String>
        get() {
            val stored = sp.getString(ENABLED_LANGUAGES, null) ?: return EXTRA_LANGUAGES
            val chosen = stored.split(',').filter { it.isNotEmpty() }.toSet()
            // Before 0.3.0 only Russian existed, so a saved choice knew only about it.
            val known = s(KNOWN_LANGUAGES, "ru").split(',').toSet()
            return chosen.intersect(EXTRA_LANGUAGES) + (EXTRA_LANGUAGES - known)
        }
        set(v) = sp.edit()
            .putString(ENABLED_LANGUAGES, v.sorted().joinToString(","))
            .putString(KNOWN_LANGUAGES, EXTRA_LANGUAGES.sorted().joinToString(","))
            .apply()
    var currentLanguage: String
        get() = s(CURRENT_LANGUAGE, "de_en")
        set(v) = put(CURRENT_LANGUAGE, v)
    /** The Tasto language Android's keyboard switcher had selected when the keyboard last looked. */
    var systemLanguage: String?
        get() = sp.getString(SYSTEM_LANGUAGE, null)
        set(v) = sp.edit().putString(SYSTEM_LANGUAGE, v).apply()

    var emojiRecents: List<String>
        get() = s(EMOJI_RECENTS, "").split('\n').filter { it.isNotEmpty() }
        set(v) = put(EMOJI_RECENTS, v.joinToString("\n"))

    companion object {
        const val FILE = "tasto_settings"
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val KEY_HEIGHT = "key_height"
        const val KEY_HINTS = "key_hints"
        const val KEY_POPUP = "key_popup"
        const val NUMBER_ROW = "number_row"
        const val NUMBER_ROW_PASSWORDS = "number_row_passwords"
        const val COLORED_ENTER = "colored_enter"
        const val TOOLBAR_EXPANDED = "toolbar_expanded"
        const val AUTO_SPACE = "auto_space"
        const val AUTOCORRECT = "autocorrect"
        const val AUTO_CAP = "auto_cap"
        const val DOUBLE_SPACE = "double_space"
        const val SUGGESTIONS = "suggestions"
        const val VIBRATE = "vibrate"
        const val SOUND = "sound"
        const val LEARNING = "learning"
        const val AUTO_INCOGNITO = "auto_incognito"
        const val MANUAL_INCOGNITO = "manual_incognito"
        const val CLIPBOARD_HISTORY = "clipboard_history"
        const val EMOJI_RECENTS = "emoji_recents"
        const val ENABLED_LANGUAGES = "enabled_languages"
        const val CURRENT_LANGUAGE = "current_language"
        const val KNOWN_LANGUAGES = "known_languages"
        const val SYSTEM_LANGUAGE = "system_language"

        /** Languages besides German + English, by id (see Languages). */
        val EXTRA_LANGUAGES = setOf("ru", "ja", "ja_flick")

        /** Keys that change how the keyboard looks; the keyboard rebuilds its views for them. */
        val LOOK_KEYS = setOf(THEME, ACCENT, KEY_HEIGHT, KEY_HINTS, NUMBER_ROW, NUMBER_ROW_PASSWORDS, COLORED_ENTER, TOOLBAR_EXPANDED)
    }
}
