package io.github.realkhfan.tasto.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import io.github.realkhfan.tasto.data.Prefs

/**
 * Colours for one keyboard look. The dark palette follows SwiftKey's dark keyboard: neutral
 * grey keys (all keys the same, Enter included) on a near-black background.
 */
data class KeyboardTheme(
    val dark: Boolean,
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val functionKey: Int,
    val functionKeyPressed: Int,
    val text: Int,
    /** Secondary labels: space-bar language, "!?" hints, digit hints. */
    val hint: Int,
    val accent: Int,
    val onAccent: Int,
    val divider: Int,
    val popup: Int,
    val popupText: Int,
    val barIncognito: Int,
    val incognitoAccent: Int,
    /** Thin edge under each key for a bit of depth. */
    val keyShadow: Int,
    /** The toolbar row above the suggestions. */
    val toolbar: Int,
    /** Left and right suggestions (the middle one uses [text]). */
    val suggestionDim: Int,
    /** Fill of the round ⌄/^ button left of the suggestions. */
    val roundButton: Int,
) {
    companion object {
        val ACCENTS = linkedMapOf(
            "blue" to 0xFF3D8BFF.toInt(),
            "teal" to 0xFF14B8A6.toInt(),
            "purple" to 0xFF8B5CF6.toInt(),
            "orange" to 0xFFFF7A1A.toInt(),
            "pink" to 0xFFEC4899.toInt(),
            "green" to 0xFF22C55E.toInt(),
        )

        fun dark(accent: Int) = KeyboardTheme(
            dark = true,
            background = 0xFF141414.toInt(),
            key = 0xFF232323.toInt(),
            keyPressed = 0xFF3C3C3C.toInt(),
            functionKey = 0xFF232323.toInt(),
            functionKeyPressed = 0xFF3C3C3C.toInt(),
            text = 0xFFFFFFFF.toInt(),
            hint = 0xFF777777.toInt(),
            accent = accent,
            onAccent = Color.WHITE,
            divider = 0xFF2A2A2A.toInt(),
            popup = 0xFF3A3A3A.toInt(),
            popupText = 0xFFFFFFFF.toInt(),
            barIncognito = 0xFF221A2E.toInt(),
            incognitoAccent = 0xFFB794FF.toInt(),
            keyShadow = 0xFF0C0C0C.toInt(),
            toolbar = 0xFF212121.toInt(),
            suggestionDim = 0xFF939393.toInt(),
            roundButton = 0xFF373737.toInt(),
        )

        fun light(accent: Int) = KeyboardTheme(
            dark = false,
            background = 0xFFE8E8EA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFD2D2D6.toInt(),
            functionKey = 0xFFFFFFFF.toInt(),
            functionKeyPressed = 0xFFD2D2D6.toInt(),
            text = 0xFF111111.toInt(),
            hint = 0xFF8A8A8F.toInt(),
            accent = accent,
            onAccent = Color.WHITE,
            divider = 0xFFD0D0D4.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupText = 0xFF111111.toInt(),
            barIncognito = 0xFFE3DAF5.toInt(),
            incognitoAccent = 0xFF7044D8.toInt(),
            keyShadow = 0xFFBDBDC2.toInt(),
            toolbar = 0xFFF4F4F6.toInt(),
            suggestionDim = 0xFF6E6E73.toInt(),
            roundButton = 0xFFD6D6DA.toInt(),
        )

        fun from(context: Context, prefs: Prefs): KeyboardTheme {
            val accent = ACCENTS[prefs.accent] ?: ACCENTS.getValue("blue")
            val dark = when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return if (dark) dark(accent) else light(accent)
        }
    }
}
