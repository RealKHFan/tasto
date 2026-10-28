package io.github.realkhfan.tasto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RussianTest {
    private val engine = TestDicts.ruEngine()
    private fun auto(typed: String) = engine.suggest(typed, null).autoCorrect

    @Test fun dictionaryLoads() {
        assertTrue(TestDicts.ru.size > 90_000)
    }

    @Test fun correctsTypos() {
        assertEquals("спасибо", auto("спсибо"))
        assertEquals("привет", auto("приывет"))
        assertEquals("пожалуйста", auto("пожалйста"))
        assertEquals("сегодня", auto("сегодяя"))
        assertEquals("хорошо", auto("хоршо"))
    }

    @Test fun leavesRealWordsAlone() {
        for (w in listOf("я", "не", "что", "привет", "Привет", "Москва", "еще", "ещё", "всё", "это", "хорошо")) {
            val s = engine.suggest(w, null)
            assertTrue("$w should be valid", s.typedValid)
            assertNull("$w must not be corrected", s.autoCorrect)
        }
    }

    @Test fun completesAndPredicts() {
        assertTrue(engine.suggest("пожал", null).ranked.contains("пожалуйста"))
        assertTrue(engine.predict("я", false).isNotEmpty())
        assertEquals(listOf("Я", "Привет", "Это"), engine.predictStart(capitalize = true))
    }

    @Test fun realWordsAndTypoRate() {
        var changed = 0
        for (id in 0 until 5000) if (engine.suggest(TestDicts.ru.word(id), null).autoCorrect != null) changed++
        println("ru: $changed/5000 real words changed")
        assertTrue(changed < 100)
        val neighbours = mapOf('а' to 'п', 'о' to 'л', 'е' to 'к', 'и' to 'т', 'н' to 'г', 'т' to 'ь', 'р' to 'о', 'с' to 'м', 'л' to 'д', 'в' to 'а')
        var tried = 0
        var fixed = 0
        for (id in 0 until 3000) {
            val w = TestDicts.ru.word(id)
            if (w.length < 5 || !w.all { it.isLowerCase() }) continue
            val pos = w.length / 2
            val r = neighbours[w[pos]] ?: continue
            val typo = w.substring(0, pos) + r + w.substring(pos + 1)
            if (engine.suggest(typo, null).typedValid) continue
            tried++
            if (engine.suggest(typo, null).autoCorrect == w) fixed++
        }
        println("ru typo fix rate: $fixed/$tried")
        assertTrue(fixed.toDouble() / tried > 0.80)
    }
}
