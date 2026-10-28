package io.github.realkhfan.tasto

import io.github.realkhfan.tasto.engine.Folding
import io.github.realkhfan.tasto.engine.LearnedCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val engine = TestDicts.engine()

    private fun auto(typed: String, prev: String? = null) = engine.suggest(typed, prev).autoCorrect

    @Test fun folding() {
        assertEquals("strasse", Folding.fold("Straße"))
        assertEquals("dont", Folding.fold("don't"))
        assertEquals("uber", Folding.fold("über"))
        assertEquals("elka", Folding.fold("Ëlka"))
    }

    @Test fun dictionariesLoad() {
        assertTrue(TestDicts.de.size > 90_000)
        assertTrue(TestDicts.en.size > 60_000)
    }

    @Test fun correctsCommonTypos() {
        assertEquals("the", auto("teh"))
        assertEquals("hallo", auto("hallp"))
        assertEquals("this", auto("thsi"))
        assertEquals("receive", auto("recieve"))
        assertEquals("nicht", auto("nihct"))
        assertEquals("because", auto("becuase"))
    }

    @Test fun fixesUmlautsApostrophesAndCase() {
        assertEquals("über", auto("uber"))
        assertEquals("Straße", auto("strasse"))
        assertEquals("Haus", auto("haus"))
        assertEquals("I", auto("i"))
        assertEquals("don't", auto("dont"))
        assertEquals("für", auto("fur"))
    }

    @Test fun leavesRealWordsAlone() {
        for (w in listOf("die", "the", "Haus", "Hallo", "ich", "und", "house", "schon", "in", "an", "was", "Die", "THE")) {
            val s = engine.suggest(w, null)
            assertTrue("$w should be valid", s.typedValid)
            assertNull("$w must not be corrected", s.autoCorrect)
        }
    }

    @Test fun keepsCaseOfTypedWord() {
        assertEquals("The", auto("Teh"))
        assertEquals("THE", auto("TEH"))
    }

    @Test fun completesWords() {
        assertTrue(engine.suggest("Stra", null).ranked.contains("Straße"))
        assertTrue(engine.suggest("thin", null).ranked.any { it == "think" || it == "thing" })
        assertTrue(engine.suggest("viell", null).ranked.contains("vielleicht"))
    }

    @Test fun predictsNextWords() {
        assertTrue(engine.predict("I", false).isNotEmpty())
        assertTrue(engine.predict("ich", false).contains("habe"))
        assertEquals("Habe", engine.predict("ich", true).first { it.equals("habe", true) })
    }

    @Test fun learnedWordsAreUsed() {
        val learned = LearnedCache()
        learned.learn("Tasto", "Tasto", null, 2, false, 1L)
        val e = TestDicts.engine(learned)
        val s = e.suggest("Tasto", null)
        assertTrue(s.typedValid)
        assertNull(s.autoCorrect)
        assertTrue(e.suggest("Tas", null).ranked.contains("Tasto"))
    }

    @Test fun learnedBigramsPredict() {
        val learned = LearnedCache()
        learned.learn("moin", "moin", null, 3, false, 1L)
        learned.learn("Leute", "Leute", "moin", 1, true, 1L)
        learned.learn("Leute", "Leute", "moin", 1, true, 2L)
        val e = TestDicts.engine(learned)
        assertEquals("Leute", e.predict("moin", false).first())
    }

    @Test fun learnedWordsAreStillCorrectedFromTypos() {
        val learned = LearnedCache()
        // A new word only you use, and a dictionary word you use a lot.
        learned.learn("Grafeno", "Grafeno", null, 5, false, 1L)
        learned.learn("Schreibtisch", "Schreibtisch", null, 5, true, 1L)
        val e = TestDicts.engine(learned)
        assertTrue(e.suggest("Grafenp", null).ranked.contains("Grafeno"))
        assertTrue(e.suggest("Schreibtusch", null).ranked.contains("Schreibtisch"))
    }

    @Test fun learnedTypoOnceDoesNotBlockCorrection() {
        val learned = LearnedCache()
        learned.learn("teh", "teh", null, 1, false, 1L)
        val e = TestDicts.engine(learned)
        assertFalse(e.suggest("teh", null).typedValid)
    }

    @Test fun sentenceStarters() {
        val s = engine.predictStart(capitalize = true)
        assertEquals(listOf("Ich", "I", "Das"), s)
        val learned = LearnedCache()
        repeat(4) { learned.learn("moin", "moin", io.github.realkhfan.tasto.engine.SuggestionEngine.START, 1, false, 1L) }
        assertEquals("Moin", TestDicts.engine(learned).predictStart(capitalize = true).first())
    }

    @Test fun fastEnough() {
        val words = listOf("teh", "hallp", "Straße", "vielleich", "becuase", "recieve", "ich", "wahrscheinlich",
            "unfortunatly", "Entschuldiging", "definately", "morgen", "abcdefghij", "x", "zz")
        engine.suggest("warmup", null)
        val start = System.nanoTime()
        repeat(20) { for (w in words) engine.suggest(w, "ich") }
        val perCallMs = (System.nanoTime() - start) / 1e6 / (20 * words.size)
        println("suggest(): %.2f ms per call".format(perCallMs))
        assertTrue("too slow: $perCallMs ms", perCallMs < 15)
    }
}
