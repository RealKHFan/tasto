package io.github.realkhfan.tasto.japanese

/** Small kana helpers: katakana, the ゛゜小 key, and the 12-key flick table. */
object Kana {
    fun toKatakana(s: String): String = buildString {
        for (ch in s) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
    }

    fun isHiragana(ch: Char) = ch in 'ぁ'..'ゖ' || ch == 'ー'
    fun isKatakana(ch: Char) = ch in 'ァ'..'ヺ'

    /** が, ざ, だ, ば, ぱ … (kana with ゛ or ゜). */
    fun isVoiced(ch: Char) = ch in "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゔ"

    /** What the ゛゜小 key sends (it is not a character of its own). */
    const val MODIFY = "゛゜小"

    /** Japanese punctuation that stays in the text being composed (Gboard does the same). */
    const val COMPOSING_PUNCT = "、。？！ー〜「」・…，．"

    /**
     * What the ゛゜小 key turns the last kana into: か→が→か, は→ば→ぱ→は, つ→っ→づ→つ,
     * あ→ぁ→あ, や→ゃ→や, う→ぅ→ゔ→う.
     */
    fun modify(ch: Char): Char? = CYCLE[ch]

    private val CYCLE: Map<Char, Char> = buildMap {
        fun cycle(vararg cs: Char) { for (i in cs.indices) put(cs[i], cs[(i + 1) % cs.size]) }
        for (base in "かきくけこさしすせそたちてと") cycle(base, base + 1)
        cycle('つ', 'っ', 'づ')
        for (base in "はひふへほ") cycle(base, base + 1, base + 2)
        cycle('あ', 'ぁ'); cycle('い', 'ぃ'); cycle('う', 'ぅ', 'ゔ'); cycle('え', 'ぇ'); cycle('お', 'ぉ')
        cycle('や', 'ゃ'); cycle('ゆ', 'ゅ'); cycle('よ', 'ょ'); cycle('わ', 'ゎ')
    }

    /**
     * 12-key flick: each key has five characters — tap, flick left, up, right, down.
     * Laid out like Gboard / iOS: あかさ / たなは / まやら / ゛゜小 わ 、。?!
     */
    val FLICK: Map<String, List<String>> = linkedMapOf(
        "あ" to listOf("あ", "い", "う", "え", "お"),
        "か" to listOf("か", "き", "く", "け", "こ"),
        "さ" to listOf("さ", "し", "す", "せ", "そ"),
        "た" to listOf("た", "ち", "つ", "て", "と"),
        "な" to listOf("な", "に", "ぬ", "ね", "の"),
        "は" to listOf("は", "ひ", "ふ", "へ", "ほ"),
        "ま" to listOf("ま", "み", "む", "め", "も"),
        "や" to listOf("や", "「", "ゆ", "」", "よ"),
        "ら" to listOf("ら", "り", "る", "れ", "ろ"),
        "わ" to listOf("わ", "を", "ん", "ー", "〜"),
        "、" to listOf("、", "。", "？", "！", "…"),
    )

    /** Rough "word" classes for holding backspace in Japanese: runs of the same script. */
    fun scriptOf(ch: Char): Int = when {
        ch in 'ぁ'..'ゖ' -> 1
        ch in 'ァ'..'ヺ' -> 2
        ch in '一'..'鿿' || ch in '㐀'..'䶿' || ch == '々' -> 3
        ch.isLetterOrDigit() -> 4
        ch.isWhitespace() -> 5
        else -> PUNCT
    }
    const val PUNCT = 6

    /**
     * How many characters before the end of [before] one hold-backspace step removes:
     * spaces, then punctuation, then the run of the same script (東京は → は, then 東京).
     */
    fun deleteWordLength(before: String): Int {
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        while (end > 0 && scriptOf(before[end - 1]) == PUNCT && before[end - 1] != 'ー') end--
        var i = end
        while (i > 0 && before[i - 1] == 'ー') i--
        if (i > 0) {
            val cls = scriptOf(before[i - 1])
            end = i
            while (end > 0 && (scriptOf(before[end - 1]) == cls || before[end - 1] == 'ー')) end--
        } else {
            end = 0
        }
        return before.length - end
    }
}
