package io.github.realkhfan.tasto.japanese

import io.github.realkhfan.tasto.engine.LearnedWords

/** One conversion choice: [surface] replaces the first [consumed] characters of the reading. */
data class KanaCandidate(val surface: String, val consumed: Int)

/**
 * Builds the candidate list for a kana reading, best first:
 * conversions you picked before, dictionary words for the whole reading (with okurigana for
 * verbs and adjectives: たべる → 食べる), the reading in hiragana and katakana, longer words that
 * start with it (ありが → 有難う), and finally words for the beginning of the reading so a long
 * reading can be converted piece by piece (わたしは → 私 + は).
 */
class KanaConverter(
    private val dict: KanaDictionary,
    private val learned: LearnedWords,
    /** How common words are in everyday Japanese (subtitles), to pick the likelier conversion. */
    private val freq: Map<String, Int> = emptyMap(),
) {

    /** Candidates shown while typing. */
    fun candidates(reading: String, limit: Int = 40): List<KanaCandidate> {
        if (reading.isEmpty()) return emptyList()
        // Punctuation at the end ("にほんご。") stays attached to whatever the word becomes.
        val core = reading.trimEnd { it in Kana.COMPOSING_PUNCT && it != 'ー' }
        if (core.length < reading.length && core.isNotEmpty()) {
            val tail = reading.substring(core.length)
            return candidates(core, limit).map {
                if (it.consumed == core.length) KanaCandidate(it.surface + tail, reading.length) else it
            }.distinctBy { it.surface }
        }
        val n = reading.length
        val out = LinkedHashMap<String, KanaCandidate>()
        fun add(surface: String, consumed: Int) {
            if (surface.isNotEmpty() && surface !in out) out[surface] = KanaCandidate(surface, consumed)
        }

        val whole = wordsFor(reading)
        // きょうは → 今日は: a word with a particle, when that is likelier than a word read きょうは.
        val compounds = particleCompounds(reading)
        // What you chose before for this reading comes first.
        learnedFor(reading).forEach { add(it, n) }
        // Words usually written in kana (する, ありがとう, これ) stay kana unless you pick otherwise.
        if (reading in KANA_WORDS) add(reading, n)
        compounds.firstOrNull()?.takeIf { (_, f) -> whole.isEmpty() || (f >= 100 && f > 4 * freqOf(whole[0])) }
            ?.let { add(it.first, n) }
        whole.firstOrNull()?.let { add(it, n) }
        add(reading, n)
        whole.drop(1).forEach { add(it, n) }
        compounds.forEach { add(it.first, n) }
        add(Kana.toKatakana(reading), n)
        if (n >= 2) {
            for ((r, cands) in dict.completions(reading, 6)) {
                learnedFor(r).forEach { add(it, n) }
                cands.take(2).forEach { add(it, n) }
            }
        }
        // Convert the start of the reading, longest first.
        for (len in n - 1 downTo 1) {
            if (out.size >= limit) break
            val head = reading.substring(0, len)
            (learnedFor(head) + wordsFor(head).take(4)).forEach { add(it, len) }
        }
        return out.values.take(limit)
    }

    /**
     * Candidates for the space key. If the whole reading is a word, its conversions come first;
     * otherwise the longest beginning that is a word ("わたしは" → 私 + は), so one press of space
     * gives a sensible result.
     */
    fun conversion(reading: String): List<KanaCandidate> {
        val list = candidates(reading)
        val n = reading.length
        val kata = Kana.toKatakana(reading)
        val hasWholeWord = list.any { it.consumed == n && it.surface != reading && it.surface != kata }
        if (hasWholeWord) return list
        val headLen = list.firstOrNull { it.consumed < n }?.consumed ?: return list
        return list.filter { it.consumed == headLen } + list.filter { it.consumed != headLen }
    }

    /**
     * How common [word] is. Single characters don't count: the subtitle word list splits verbs
     * into their kanji (言, 行), so those numbers say nothing about the character as a word.
     */
    private fun freqOf(word: String): Int {
        if (word.length >= 2) freq[word]?.let { return it }
        // Inflected words count as their stem when that is a word of its own (分かる → 分か).
        var end = word.length
        var best = 0
        while (end > 2 && word[end - 1] in 'ぁ'..'ゖ') {
            end--
            best = maxOf(best, freq[word.substring(0, end)] ?: 0)
        }
        return best
    }

    /** Words for the reading without a final particle, with the particle: 今日は, 日本語を. */
    private fun particleCompounds(reading: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (p in PARTICLES) {
            if (reading.length - p.length < 2 || !reading.endsWith(p)) continue
            val head = reading.substring(0, reading.length - p.length)
            wordsFor(head).take(3).forEachIndexed { i, w ->
                // Only the head's best word may lead (ありが is not 有が just because 有 is common).
                out.add(w + p to if (i == 0) freqOf(w) else 0)
            }
        }
        return out.sortedByDescending { it.second }
    }

    /**
     * The dictionary's own order is kept, except that a much more common word moves to the front
     * (たべ: 食べ before the name 田部).
     */
    private fun promoteCommon(list: MutableList<String>, reading: String) {
        if (reading.length < 2 || list.size < 2 || freq.isEmpty()) return
        val f0 = freqOf(list[0])
        var best = 0
        var bestF = f0
        for (i in 1 until minOf(list.size, 10)) {
            val f = freqOf(list[i])
            if (f > bestF) { bestF = f; best = i }
        }
        if (best > 0 && bestF > maxOf(4 * f0, 50)) list.add(0, list.removeAt(best))
    }

    /** Dictionary words for exactly [reading], including verb/adjective forms with okurigana. */
    fun wordsFor(reading: String): List<String> {
        val entries = dict.lookup(reading)
        val out = ArrayList(entries)
        var firstForm: String? = null
        fun addForms(key: String, okuri: String) {
            for (s in dict.lookup(key).take(6)) {
                val w = s + okuri
                if (firstForm == null && okuri.length == 1) firstForm = w
                if (w !in out) out.add(w)
            }
        }
        for (k in 1..minOf(6, reading.length - 1)) {
            val stem = reading.substring(0, reading.length - k)
            val okuri = reading.substring(reading.length - k)
            // かいた = 書 + い + た (SKK key "かi"), よみました = 読 + み + ました ("よm").
            val rest = okuri.substring(1)
            if (rest in ENDINGS || (okuri[0] == 'し' && rest in SHII_ENDINGS)) {
                Romaji.consonantOf(okuri[0])?.let { addForms(stem + it, okuri) }
            }
            // たべました = 食べ + ました: SKK keeps these verbs under "たべr".
            if (okuri in ICHIDAN_ENDINGS) addForms(stem + "r", okuri)
            // おいしい = 美味し + い, where the dictionary has the stem with its kana (美味し).
            if (okuri == "い") {
                for (e in dict.lookup(stem).take(4)) {
                    if (e.last() == stem.last() && e + okuri !in out) out.add(e + okuri)
                }
            }
        }
        // みる is 見る, not the rare noun 覩: for a verb or adjective ending, the inflected word
        // goes first unless the plain entry is a common word (かい stays 会, 回 …).
        val form = firstForm
        // Not for がく etc.: SKK lists 書く there too, for compounds like 手書き.
        if (form != null && entries.isNotEmpty() && freqOf(entries[0]) < 50 && !Kana.isVoiced(reading[0])) {
            val last = reading.last()
            if (last in VERB_FINAL || (last == 'い' && entries[0].length >= 2)) {
                out.remove(form)
                out.add(0, form)
            }
        }
        promoteCommon(out, reading)
        return out
    }

    /** Whether [surface] is one of the dictionary's words for [reading]. */
    fun isDictionaryWord(reading: String, surface: String): Boolean = surface in wordsFor(reading)

    private fun learnedFor(reading: String): List<String> =
        learned.nexts(LEARN_PREFIX + reading).entries.sortedByDescending { it.value }.mapNotNull { learned.get(it.key)?.word }

    companion object {
        /** Learned-phrase key for "this reading was converted to …". */
        const val LEARN_PREFIX = "@"

        /**
         * What may follow the first okurigana kana of a verb or adjective. Only real endings, so a
         * word plus a particle (わたしは) is not mistaken for a verb form (渡しは).
         */
        private val ENDINGS = setOf(
            "", "た", "だ", "て", "で", "ない", "なかった", "なく", "なくて", "ます", "ました", "ません",
            "ましょう", "たい", "たかった", "たく", "ば", "る", "れる", "られる", "せる", "させる", "よう", "う",
            "ろう", "く", "くて", "った", "かった", "くない", "ければ", "さ", "そう", "すぎる", "ても", "でも",
            "ては", "ている", "ています", "ていた", "てる", "てた", "ちゃう", "ちゃった", "ず", "ずに",
            "って", "ってる", "っている", "っていた", "ってた", "ったら", "たら", "り", "ります", "りました",
            "りません", "らない", "らなかった", "れば", "れる", "られる", "らせる", "ろう",
        )

        /** After し in adjectives like 楽しい (SKK key たのs). */
        private val SHII_ENDINGS = setOf("い", "く", "かった", "くて", "くない", "ければ", "さ", "そう")

        /** Everyday words that are normally written in kana. */
        private val KANA_WORDS = setOf(
            "する", "した", "して", "します", "しました", "ある", "あります", "いる", "います", "なる", "なります",
            "できる", "できます", "くる", "やる", "いう", "ない", "こと", "もの", "とき", "ところ", "ため", "よう",
            "ください", "ございます", "です", "でした", "ます", "ました", "ません", "ありがとう",
            "ありがとうございます", "おはよう", "おはようございます", "こんにちは", "こんばんは", "すみません",
            "ごめん", "ごめんなさい", "さようなら", "ここ", "そこ", "あそこ", "どこ", "これ", "それ", "あれ",
            "どれ", "この", "その", "あの", "どの", "なに", "なん", "だれ", "いつ", "もう", "まだ", "とても",
            "ちょっと", "やはり", "やっぱり", "たくさん", "みんな", "いい", "よい", "ほしい", "ね", "よ", "か",
            "わ", "な", "さ", "ぞ", "けど", "でも", "だから", "そして", "それから", "しかし", "また", "まで",
        )

        /** Kana that end a verb in its dictionary form. */
        private const val VERB_FINAL = "うくぐすつぬぶむる"

        /** Particles after a word (longest first). */
        private val PARTICLES = listOf(
            "には", "では", "とは", "にも", "でも", "から", "まで", "より", "のは", "のが", "のを",
            "は", "を", "が", "に", "で", "と", "の", "も", "へ",
        )

        /** Endings right after the stem of verbs like 食べる and 見る. */
        private val ICHIDAN_ENDINGS = setOf(
            "る", "た", "て", "ない", "なかった", "なく", "なくて", "ます", "ました", "ません", "ましょう",
            "たい", "たかった", "よう", "れば", "られる", "させる", "ろ", "ず", "ている", "ています", "ていた",
            "てる", "てた", "ちゃう", "ちゃった",
        )
    }
}
