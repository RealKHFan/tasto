package io.github.realkhfan.tasto.japanese

/**
 * Romaji → hiragana, the way Japanese keyboards do it while you type ("ka" → か, "kk" → っk,
 * "n" before a consonant → ん, "nn" → ん, "-" → ー). Hepburn, Kunrei and common IME spellings.
 */
object Romaji {
    private val table: Map<String, String> = buildMap {
        fun row(c: String, kana: String) { // kana: a i u e o
            listOf("a", "i", "u", "e", "o").forEachIndexed { i, v -> if (kana[i] != '_') put(c + v, kana[i].toString()) }
        }
        row("", "あいうえお")
        row("k", "かきくけこ"); row("g", "がぎぐげご")
        row("s", "さしすせそ"); row("z", "ざじずぜぞ")
        row("t", "たちつてと"); row("d", "だぢづでど")
        row("n", "なにぬねの")
        row("h", "はひふへほ"); row("b", "ばびぶべぼ"); row("p", "ぱぴぷぺぽ")
        row("m", "まみむめも")
        row("y", "や_ゆ_よ"); put("ye", "いぇ")
        row("r", "らりるれろ"); row("l", "ぁぃぅぇぉ"); row("x", "ぁぃぅぇぉ")
        put("wa", "わ"); put("wi", "うぃ"); put("wu", "う"); put("we", "うぇ"); put("wo", "を")
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ"); put("ji", "じ")
        fun yrow(c: String, kana: String) { put(c + "ya", kana + "ゃ"); put(c + "yu", kana + "ゅ"); put(c + "yo", kana + "ょ"); put(c + "ye", kana + "ぇ") }
        yrow("k", "き"); yrow("g", "ぎ"); yrow("s", "し"); yrow("z", "じ"); yrow("t", "ち"); yrow("d", "ぢ")
        yrow("n", "に"); yrow("h", "ひ"); yrow("b", "び"); yrow("p", "ぴ"); yrow("m", "み"); yrow("r", "り")
        yrow("c", "ち"); yrow("j", "じ")
        put("sha", "しゃ"); put("shu", "しゅ"); put("she", "しぇ"); put("sho", "しょ")
        put("cha", "ちゃ"); put("chu", "ちゅ"); put("che", "ちぇ"); put("cho", "ちょ")
        put("ja", "じゃ"); put("ju", "じゅ"); put("je", "じぇ"); put("jo", "じょ")
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ"); put("fyu", "ふゅ")
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
        put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ")
        put("thi", "てぃ"); put("thu", "てゅ"); put("dhi", "でぃ"); put("dhu", "でゅ")
        put("twu", "とぅ"); put("dwu", "どぅ")
        put("kwa", "くぁ"); put("gwa", "ぐぁ"); put("qa", "くぁ"); put("qi", "くぃ"); put("qe", "くぇ"); put("qo", "くぉ")
        put("xtu", "っ"); put("ltu", "っ"); put("xtsu", "っ"); put("ltsu", "っ")
        put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ"); put("lya", "ゃ"); put("lyu", "ゅ"); put("lyo", "ょ")
        put("xwa", "ゎ"); put("lwa", "ゎ"); put("xka", "ゕ"); put("xke", "ゖ")
        put("n'", "ん"); put("xn", "ん")
        put("-", "ー")
    }
    private val prefixes: Set<String> = buildSet {
        for (k in table.keys) for (i in 1..k.length) add(k.substring(0, i))
        add("nn") // decided by the next letter: "konnichiha" → こんにちは, "konnnichiha" too
    }
    private const val VOWELS = "aiueo"

    /** Result of feeding one key: kana (or literal letters) to append, and what is still pending. */
    data class Step(val output: String, val pending: String)

    /** Adds [c] to the [pending] romaji and returns what can be turned into kana now. */
    fun feed(pending: String, c: Char): Step {
        var p = pending + c.lowercaseChar()
        val out = StringBuilder()
        while (p.isNotEmpty()) {
            if (p.length >= 3 && p.startsWith("nn")) {
                // "nn" + vowel/y → ん + な行 ("konnichiha"); "nnn" → ん + n…; "nn" + consonant → ん.
                out.append('ん')
                p = if (p[2] in VOWELS || p[2] == 'y') p.substring(1) else p.substring(2)
                continue
            }
            val exact = table[p]
            if (exact != null && !(p == "n" || longerMatchPossible(p))) {
                out.append(exact); p = ""; break
            }
            if (p in prefixes) break // wait for more letters
            when {
                // "kk" → っ + k, but "nn" is ん (in the table)
                p.length >= 2 && p[0] == p[1] && p[0] !in VOWELS && p[0] != 'n' && p[0].isLetter() -> {
                    out.append('っ'); p = p.substring(1)
                }
                // "tch" (matcha) → っ + ch
                p.startsWith("tc") -> {
                    out.append('っ'); p = p.substring(1)
                }
                // "n" before a consonant → ん
                p.length >= 2 && p[0] == 'n' && p[1] !in VOWELS && p[1] != 'y' && p[1] != 'n' -> {
                    out.append('ん'); p = p.substring(1)
                }
                else -> {
                    // Not romaji: keep the letter as it is.
                    out.append(p[0]); p = p.substring(1)
                }
            }
        }
        return Step(out.toString(), p)
    }

    /** Pending letters when typing stops (e.g. a trailing "n" becomes ん). */
    fun flush(pending: String): String = when (pending) {
        "" -> ""
        "n", "nn" -> "ん"
        else -> table[pending] ?: pending
    }

    /** Whole string at once (for tests and pasted romaji). */
    fun toKana(romaji: String): String {
        var pending = ""
        val out = StringBuilder()
        for (c in romaji) {
            val s = feed(pending, c)
            out.append(s.output)
            pending = s.pending
        }
        return out.append(flush(pending)).toString()
    }

    private fun longerMatchPossible(p: String): Boolean = table.keys.any { it.length > p.length && it.startsWith(p) }

    /** The romaji consonant SKK uses for okurigana starting with [kana] (る → r, く → k). */
    fun consonantOf(kana: Char): Char? = OKURI[kana]

    private val OKURI: Map<Char, Char> = buildMap {
        for ((k, v) in table) {
            if (v.length == 1 && k.length >= 2 && k[0].isLetter() && k[0] !in VOWELS && k[0] != 'x' && k[0] != 'l') {
                putIfAbsent(v[0], k[0])
            }
        }
        listOf('あ', 'い', 'う', 'え', 'お').forEachIndexed { i, ch -> put(ch, VOWELS[i]) }
        put('っ', 't'); put('ん', 'n')
    }
}
