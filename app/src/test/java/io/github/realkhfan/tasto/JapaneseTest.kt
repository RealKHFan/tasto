package io.github.realkhfan.tasto

import io.github.realkhfan.tasto.engine.LearnedCache
import io.github.realkhfan.tasto.ime.BarState
import io.github.realkhfan.tasto.ime.FieldPolicy
import io.github.realkhfan.tasto.ime.ShiftState
import io.github.realkhfan.tasto.japanese.Kana
import io.github.realkhfan.tasto.japanese.KanaCandidate
import io.github.realkhfan.tasto.japanese.KanaConverter
import io.github.realkhfan.tasto.japanese.KanaDictionary
import io.github.realkhfan.tasto.japanese.KanaLogic
import io.github.realkhfan.tasto.japanese.Romaji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

object JaDict {
    val dict: KanaDictionary by lazy {
        val f = listOf(File("src/main/assets/dict/ja.txt"), File("app/src/main/assets/dict/ja.txt")).first { it.exists() }
        f.inputStream().use { KanaDictionary.load(it) }
    }
    val freq: Map<String, Int> by lazy {
        val f = listOf(File("src/main/assets/dict/ja_freq.txt"), File("app/src/main/assets/dict/ja_freq.txt")).first { it.exists() }
        f.inputStream().use { KanaDictionary.loadFrequencies(it) }
    }
}

class FakeKanaHost(val learned: LearnedCache = LearnedCache()) : KanaLogic.Host {
    val converter = KanaConverter(JaDict.dict, learned, JaDict.freq)
    val learnedLog = ArrayList<Pair<String, String>>()
    var lastBar: BarState = BarState.EMPTY
    var composing = false
    var lastShift = ShiftState.OFF
    override fun converter() = converter
    override fun learnConversion(reading: String, surface: String) {
        learnedLog.add(reading to surface)
        learned.learn(surface, surface, KanaConverter.LEARN_PREFIX + reading, 1, true, 0L)
    }
    override fun onBarChanged(bar: BarState) { lastBar = bar }
    override fun onShiftChanged(state: ShiftState) { lastShift = state }
    override fun onComposingChanged(composing: Boolean) { this.composing = composing }
    override var suggestionsEnabled = true
}

/** Types romaji key by key: ' ' is space (変換), '\n' enter (確定), '<' backspace. */
fun KanaLogic.type(s: String) {
    for (ch in s) when (ch) {
        ' ' -> onSpace()
        '\n' -> onEnter()
        '<' -> onBackspace()
        else -> onText(ch.toString())
    }
}

class RomajiTest {
    private fun k(r: String) = Romaji.toKana(r)

    @Test fun basics() {
        assertEquals("にほんご", k("nihongo"))
        assertEquals("こんにちは", k("konnichiha"))
        assertEquals("こんにちは", k("konnnichiha"))
        assertEquals("ありがとう", k("arigatou"))
        assertEquals("とうきょう", k("toukyou"))
        assertEquals("しんぶん", k("shinbun"))
        assertEquals("かんじ", k("kanji"))
        assertEquals("ほん", k("hon"))
        assertEquals("ほん", k("honn"))
    }

    @Test fun smallTsuAndN() {
        assertEquals("がっこう", k("gakkou"))
        assertEquals("まっちゃ", k("matcha"))
        assertEquals("ちょっと", k("chotto"))
        assertEquals("きって", k("kitte"))
        assertEquals("おんな", k("onna"))
        assertEquals("せんねん", k("sennen"))
        assertEquals("かんい", k("kan'i"))
        assertEquals("きんようび", k("kin'youbi"))
        assertEquals("ほんや", k("hon'ya"))
        assertEquals("こんにゃく", k("konnyaku"))
    }

    @Test fun spellingsAndSmallKana() {
        assertEquals("しゃしん", k("shashin"))
        assertEquals("しゃしん", k("syasin"))
        assertEquals("ちゅうい", k("chuui"))
        assertEquals("ふじ", k("fuji"))
        assertEquals("ふじ", k("huzi"))
        assertEquals("じゃあね", k("jaane"))
        assertEquals("らーめん", k("ra-men"))
        assertEquals("ぱーてぃー", k("pa-thi-"))
        assertEquals("っ", k("xtu"))
        assertEquals("ぁ", k("la"))
        assertEquals("ゔぁ", k("va"))
    }

    @Test fun whileTyping() {
        // "k" waits for its vowel; "n" waits too (な or ん).
        val a = Romaji.feed("", 'k')
        assertEquals("" to "k", a.output to a.pending)
        val b = Romaji.feed("k", 'a')
        assertEquals("か" to "", b.output to b.pending)
        val c = Romaji.feed("n", 'k')
        assertEquals("ん" to "k", c.output to c.pending)
        val d = Romaji.feed("", 'n')
        assertEquals("" to "n", d.output to d.pending)
    }

    @Test fun okuriganaConsonants() {
        assertEquals('r', Romaji.consonantOf('る'))
        assertEquals('k', Romaji.consonantOf('く'))
        assertEquals('s', Romaji.consonantOf('し'))
        assertEquals('t', Romaji.consonantOf('っ'))
        assertEquals('i', Romaji.consonantOf('い'))
    }
}

class KanaTest {
    @Test fun modifierKeyCycles() {
        assertEquals('が', Kana.modify('か'))
        assertEquals('か', Kana.modify('が'))
        assertEquals('ば', Kana.modify('は'))
        assertEquals('ぱ', Kana.modify('ば'))
        assertEquals('は', Kana.modify('ぱ'))
        assertEquals('っ', Kana.modify('つ'))
        assertEquals('づ', Kana.modify('っ'))
        assertEquals('つ', Kana.modify('づ'))
        assertEquals('ゃ', Kana.modify('や'))
        assertEquals('ぅ', Kana.modify('う'))
        assertEquals('ゔ', Kana.modify('ぅ'))
        assertEquals(null, Kana.modify('ん'))
    }

    @Test fun katakana() {
        assertEquals("カタカナ", Kana.toKatakana("かたかな"))
        assertEquals("ラーメン", Kana.toKatakana("らーめん"))
    }

    @Test fun flickTableHasFiveCharactersPerKey() {
        assertEquals(11, Kana.FLICK.size)
        for ((k, v) in Kana.FLICK) {
            assertEquals(k, 5, v.size)
            assertEquals(k, v[0])
        }
        assertEquals(listOf("た", "ち", "つ", "て", "と"), Kana.FLICK["た"])
    }

    @Test fun holdBackspaceSteps() {
        assertEquals(1, Kana.deleteWordLength("東京は"))
        assertEquals(2, Kana.deleteWordLength("東京"))
        assertEquals(4, Kana.deleteWordLength("すごーい"))
        assertEquals(4, Kana.deleteWordLength("日本語。"))
        assertEquals(3, Kana.deleteWordLength("私はテスト"))
        assertEquals(6, Kana.deleteWordLength("Hallo "))
    }
}

class KanaConverterTest {
    private val conv = KanaConverter(JaDict.dict, LearnedCache(), JaDict.freq)
    private fun first(r: String) = conv.candidates(r).first().surface
    private fun all(r: String) = conv.candidates(r).map { it.surface }

    @Test fun dictionaryIsLoaded() {
        assertTrue("${JaDict.dict.size} readings", JaDict.dict.size > 100_000)
    }

    @Test fun commonWords() {
        assertEquals("日本語", first("にほんご"))
        assertEquals("学生", first("がくせい"))
        assertEquals("先生", first("せんせい"))
        assertEquals("今日", first("きょう"))
        assertEquals("変換", first("へんかん"))
        assertEquals("東京", first("とうきょう"))
        assertTrue(all("ありがとう").contains("有難う"))
        assertTrue(all("ありがとう").contains("ありがとう"))
    }

    @Test fun verbsAndAdjectivesWithOkurigana() {
        assertTrue(all("たべる").take(3).contains("食べる"))
        assertTrue(all("かいた").contains("書いた"))
        assertTrue(all("みる").contains("見る"))
        assertTrue(all("たかい").contains("高い"))
        assertTrue(all("いきます").contains("行きます"))
        assertTrue(all("たべました").contains("食べました"))
        // A word and a particle is not a verb form.
        assertFalse(conv.wordsFor("わたしは").contains("渡しは"))
    }

    @Test fun hiraganaAndKatakanaAreAlwaysThere() {
        val c = all("てすと")
        assertTrue(c.contains("てすと"))
        assertTrue(c.contains("テスト"))
        // Even for something no dictionary knows.
        val x = all("ぬぽぺ")
        assertEquals("ぬぽぺ", x[0])
        assertTrue(x.contains("ヌポペ"))
    }

    @Test fun completionsWhileTyping() {
        assertTrue(all("ありが").contains("有難う"))
    }

    @Test fun beginningOfAReading() {
        // にほんごを: 日本語 for the first four kana, を stays to convert next.
        assertTrue(conv.candidates("にほんごを").contains(KanaCandidate("日本語", 4)))
        // …but space gives the word with its particle in one go.
        assertEquals(KanaCandidate("日本語を", 5), conv.conversion("にほんごを").first())
        assertEquals("今日は", conv.conversion("きょうは").first().surface)
        assertTrue(conv.candidates("わたしは").contains(KanaCandidate("私", 3)))
        // SKK knows 私は as a whole.
        assertEquals("私は", conv.conversion("わたしは").first().surface)
        // A whole word wins over a beginning.
        assertEquals(KanaCandidate("日本語", 4), conv.conversion("にほんご").first())
    }

    @Test fun punctuationStaysAttached() {
        assertEquals("日本語。", first("にほんご。"))
        assertEquals("にほんご。", all("にほんご。")[1])
        assertEquals(KanaCandidate("日本語。", 5), conv.conversion("にほんご。").first())
    }

    @Test fun chosenConversionsComeFirst() {
        val learned = LearnedCache()
        val c = KanaConverter(JaDict.dict, learned, JaDict.freq)
        val before = c.candidates("きかい").map { it.surface }
        val other = before.first { it != before[0] && it != "きかい" }
        learned.learn(other, other, KanaConverter.LEARN_PREFIX + "きかい", 1, true, 0L)
        assertEquals(other, c.candidates("きかい").first().surface)
        assertEquals(other, c.conversion("きかい").first().surface)
    }
}

class KanaLogicTest {
    private val host = FakeKanaHost()
    private val ed = FakeEditor()
    private val logic = KanaLogic(host).apply { startInput(ed, FieldPolicy.DEFAULT) }
    private fun bar() = host.lastBar.candidates.orEmpty()

    @Test fun romajiBecomesUnderlinedHiragana() {
        logic.type("nihong")
        assertEquals("にほんg", ed.toString())
        assertTrue("underlined", ed.compStart >= 0)
        assertTrue(host.composing)
        logic.type("o")
        assertEquals("にほんご", ed.toString())
        assertEquals("日本語", bar().first())
    }

    @Test fun spaceConvertsEnterAccepts() {
        logic.type("nihongo ")
        assertEquals("日本語", ed.toString())
        assertTrue(logic.isConverting)
        assertEquals(0, host.lastBar.selected)
        logic.type("\n")
        assertEquals("日本語", ed.toString())
        assertEquals(-1, ed.compStart)
        assertFalse(host.composing)
        assertEquals(0, ed.enterPresses)
        assertEquals(listOf("にほんご" to "日本語"), host.learnedLog)
        // Enter with nothing underlined is a normal Enter.
        logic.type("\n")
        assertEquals(1, ed.enterPresses)
    }

    @Test fun spaceAgainStepsThroughCandidates() {
        logic.type("kikai ")
        val firstChoice = ed.toString()
        logic.type(" ")
        assertEquals(1, host.lastBar.selected)
        assertTrue(ed.toString() != firstChoice)
        assertEquals(host.lastBar.candidates!![1], ed.toString())
    }

    @Test fun backspaceWhileConvertingGoesBackToHiragana() {
        logic.type("nihongo ")
        assertEquals("日本語", ed.toString())
        logic.type("<")
        assertEquals("にほんご", ed.toString())
        assertFalse(logic.isConverting)
        logic.type("<")
        assertEquals("にほん", ed.toString())
        logic.type("<<<")
        assertEquals("", ed.toString())
        assertFalse(host.composing)
        logic.type("<") // nothing left: nothing breaks
        assertEquals("", ed.toString())
    }

    @Test fun tappingACandidatePutsItInAndKeepsTheRest() {
        logic.type("watashiha")
        assertEquals("わたしは", ed.toString())
        val i = bar().indexOf("私")
        assertTrue("私 in ${bar()}", i >= 0)
        logic.onCandidate(i)
        assertEquals("私は", ed.toString())
        assertEquals("は stays underlined", 1, ed.compStart)
        assertTrue(host.composing)
        logic.type("\n")
        assertEquals("私は", ed.toString())
        assertEquals(-1, ed.compStart)
        assertEquals("わたし" to "私", host.learnedLog.first())
    }

    @Test fun spaceOnASentenceConvertsTheFirstWord() {
        logic.type("watashiha ")
        assertEquals("私は", ed.toString())
        logic.type("\n")
        assertEquals("私は", ed.toString())
    }

    @Test fun trailingNIsReadAsN() {
        logic.type("hon")
        assertEquals("ほn", ed.toString())
        assertTrue("本 in ${bar()}", bar().contains("本"))
        logic.onCandidate(bar().indexOf("本"))
        assertEquals("本", ed.toString())
        assertFalse(host.composing)
    }

    @Test fun typingOnAfterConvertingAcceptsTheConversion() {
        logic.type("nihongo ")
        logic.type("wo")
        assertEquals("日本語を", ed.toString())
        assertEquals("を is new and underlined", 3, ed.compStart)
        assertEquals(listOf("にほんご" to "日本語"), host.learnedLog)
    }

    @Test fun japanesePunctuationStaysInTheConversion() {
        logic.type("nihongo")
        logic.onText("。")
        assertEquals("にほんご。", ed.toString())
        logic.type(" ")
        assertEquals("日本語。", ed.toString())
    }

    @Test fun otherSymbolsPutInTheTextFirst() {
        logic.type("nihongo")
        logic.onText("@")
        assertEquals("にほんご@", ed.toString())
        assertEquals(-1, ed.compStart)
        logic.onText("😀")
        assertEquals("にほんご@😀", ed.toString())
    }

    @Test fun spaceWithoutTextIsASpace() {
        logic.type(" ")
        assertEquals(" ", ed.toString())
    }

    @Test fun flickKanaAndTheModifierKey() {
        logic.onText("か")
        logic.onText(Kana.MODIFY)
        assertEquals("が", ed.toString())
        logic.onText("つ")
        logic.onText(Kana.MODIFY)
        assertEquals("がっ", ed.toString())
        logic.onText(Kana.MODIFY)
        assertEquals("がづ", ed.toString())
        // On its own (nothing underlined) the key does nothing.
        logic.type("\n")
        logic.onText(Kana.MODIFY)
        assertEquals("がづ", ed.toString())
    }

    @Test fun flickedReadingConverts() {
        for (k in listOf("と", "う", "き", "よ")) logic.onText(k)
        logic.onText(Kana.MODIFY) // よ → ょ
        logic.onText("う")
        assertEquals("とうきょう", ed.toString())
        logic.type(" ")
        assertEquals("東京", ed.toString())
    }

    @Test fun holdBackspaceDeletesUnderlinedTextThenWords() {
        logic.type("nihongo")
        logic.onDeleteWord()
        assertEquals("", ed.toString())
        ed.commit("東京は")
        logic.onDeleteWord()
        assertEquals("東京", ed.toString())
        logic.onDeleteWord()
        assertEquals("", ed.toString())
    }

    @Test fun shiftTypesTheLetterItself() {
        logic.onShiftKey()
        assertEquals(ShiftState.ON, host.lastShift)
        logic.type("t")
        logic.type("shatsu")
        assertEquals("Tしゃつ", ed.toString())
        assertEquals(ShiftState.OFF, host.lastShift)
    }

    @Test fun cursorMoveAcceptsFirst() {
        logic.type("nihongo ")
        logic.onCursorMove(-1)
        assertEquals("日本語", ed.toString())
        assertEquals(-1, ed.compStart)
        assertEquals(2, ed.cursor)
    }

    @Test fun nothingIsLearnedInPrivateFields() {
        val private = FieldPolicy.DEFAULT.copy(privateField = true)
        logic.startInput(ed, private)
        logic.type("nihongo \n")
        assertEquals("日本語", ed.toString())
        assertTrue(host.learnedLog.isEmpty())
    }

    @Test fun chosenConversionComesFirstNextTime() {
        logic.type("kikai")
        val pick = bar().drop(1).first { it != "きかい" }
        logic.onCandidate(bar().indexOf(pick))
        logic.type(" kikai")
        assertEquals(pick, bar().first())
        logic.type(" ")
        assertTrue(ed.toString().endsWith(pick))
    }

    @Test fun barIsEmptyWithoutComposing() {
        logic.type("nihongo \n")
        assertTrue(host.lastBar.isEmpty)
    }

    @Test fun cursorMovedByTheUserEndsComposing() {
        logic.type("nihongo")
        logic.onUpdateSelection(0, 0, 0, 4)
        assertFalse(logic.isComposing)
        assertEquals("にほんご", ed.toString())
    }
}
