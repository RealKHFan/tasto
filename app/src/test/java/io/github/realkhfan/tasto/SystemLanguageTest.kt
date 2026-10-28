package io.github.realkhfan.tasto

import io.github.realkhfan.tasto.ime.TastoIme
import io.github.realkhfan.tasto.ime.TastoIme.SystemLanguageStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the keyboard does with the language selected in Android's 🌐 list when it opens. */
class SystemLanguageTest {
    private fun step(system: String, seen: String?, current: String) = TastoIme.systemLanguageStep(system, seen, current)

    @Test fun pickedInAndroidsListIsFollowed() {
        // You switched to Japanese in Tasto (Android knew), then picked German + English in the 🌐 list.
        assertEquals(SystemLanguageStep.FOLLOW, step(system = "de_en", seen = "ja_flick", current = "ja_flick"))
    }

    @Test fun firstTimeAfterTheUpdateFollowsAndroid() {
        assertEquals(SystemLanguageStep.FOLLOW, step(system = "de_en", seen = null, current = "ja_flick"))
    }

    @Test fun androidIsToldWhenItMissedASwitch() {
        // Android still shows what it showed before: Tasto's own choice wins and Android is told.
        assertEquals(SystemLanguageStep.TELL, step(system = "de_en", seen = "de_en", current = "ru"))
    }

    @Test fun nothingToDoWhenTheyAgree() {
        assertEquals(SystemLanguageStep.NOTHING, step(system = "ru", seen = "de_en", current = "ru"))
        assertEquals(SystemLanguageStep.NOTHING, step(system = "ru", seen = null, current = "ru"))
    }
}
