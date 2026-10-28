package io.github.realkhfan.tasto

import org.junit.Assert.assertTrue
import org.junit.Test

/** Autocorrect must leave real words alone and still catch typos most of the time. */
class AutocorrectSafetyTest {
    private val engine = TestDicts.engine()

    private fun checkRealWords(dict: io.github.realkhfan.tasto.engine.BaseDictionary, n: Int): Double {
        val changed = ArrayList<String>()
        for (id in 0 until n) {
            val w = dict.word(id)
            val a = engine.suggest(w, null).autoCorrect
            if (a != null) changed.add("$w→$a")
        }
        println("${dict.language}: ${changed.size}/$n real words changed, e.g. ${changed.take(25)}")
        return changed.size.toDouble() / n
    }

    @Test fun realGermanWordsStay() {
        assertTrue(checkRealWords(TestDicts.de, 5000) < 0.02)
    }

    @Test fun realEnglishWordsStay() {
        assertTrue(checkRealWords(TestDicts.en, 5000) < 0.02)
    }

    @Test fun catchesAdjacentKeyTypos() {
        // Replace one middle letter of common longer words with a neighbouring key.
        val neighbours = mapOf('a' to 's', 'e' to 'r', 'i' to 'o', 'o' to 'i', 'n' to 'm', 't' to 'r', 's' to 'd', 'r' to 't', 'l' to 'k', 'h' to 'j', 'u' to 'z')
        var tried = 0
        var fixed = 0
        val misses = ArrayList<String>()
        for (dict in listOf(TestDicts.de, TestDicts.en)) {
            for (id in 0 until 3000) {
                val w = dict.word(id)
                if (w.length < 5 || w.any { it.code > 0x7F || !it.isLowerCase() }) continue
                val pos = w.length / 2
                val r = neighbours[w[pos]] ?: continue
                val typo = w.substring(0, pos) + r + w.substring(pos + 1)
                if (engine.suggest(typo, null).typedValid) continue // typo is itself a word
                tried++
                if (engine.suggest(typo, null).autoCorrect == w) fixed++ else if (misses.size < 20) misses.add("$typo→${engine.suggest(typo, null).autoCorrect}")
            }
        }
        val rate = fixed.toDouble() / tried
        println("typo fix rate: $fixed/$tried = ${"%.1f".format(rate * 100)}%, misses e.g. $misses")
        assertTrue(rate > 0.80)
    }
}
