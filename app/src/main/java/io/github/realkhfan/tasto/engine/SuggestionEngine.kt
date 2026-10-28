package io.github.realkhfan.tasto.engine

import kotlin.math.ln

/** What the engine found for the word being typed. */
data class Suggestions(
    val typed: String,
    /** The typed word is a real word (or one the user taught), so it should not be changed. */
    val typedValid: Boolean,
    /** Best alternatives, best first, already in the typed word's case. Excludes [typed]. */
    val ranked: List<String>,
    /** The word that replaces [typed] when the user presses space, if autocorrect applies. */
    val autoCorrect: String?,
) {
    companion object {
        val EMPTY = Suggestions("", false, emptyList(), null)
    }
}

/**
 * Suggests completions, corrections and next words from the built-in dictionaries of the
 * active language profile plus everything the user taught the keyboard.
 */
class SuggestionEngine(
    private val dicts: List<BaseDictionary>,
    private val learned: LearnedWords,
    private val proximity: KeyProximity,
) {
    private enum class Kind { TYPED, FOLD, COMPLETION, CORRECTION }

    private class Cand(val word: String, val kind: Kind, val cost: Int, val baseF: Int, val dictMask: Int) {
        var score = 0f
        var userCount = 0
    }

    /** Recent share of words that exist only in each dictionary (language affinity). */
    private val share = FloatArray(dicts.size) { 1f / maxOf(1, dicts.size) }

    fun isKnown(word: String): Boolean {
        val l = word.lowercase()
        return dicts.any { it.byLowercase(l).isNotEmpty() }
    }

    /** Dictionary form for [word] if it exists with exactly this spelling (any case pattern). */
    fun dictionaryForm(word: String): String? {
        val l = word.lowercase()
        for (d in dicts) {
            val ids = d.byLowercase(l)
            if (ids.isEmpty()) continue
            ids.firstOrNull { d.word(it) == word }?.let { return d.word(it) }
            return d.word(ids.maxBy { d.freq(it) })
        }
        return null
    }

    /** Lets the engine lean towards the language the user is currently writing in. */
    fun onWordCommitted(word: String) {
        if (dicts.size < 2) return
        val l = word.lowercase()
        val inDict = BooleanArray(dicts.size) { dicts[it].byLowercase(l).isNotEmpty() }
        if (inDict.count { it } != 1) return
        for (i in dicts.indices) share[i] = share[i] * 0.85f + (if (inDict[i]) 0.15f else 0f)
    }

    private fun affinityBonus(mask: Int): Float {
        if (dicts.size < 2 || Integer.bitCount(mask) != 1) return 0f
        val i = Integer.numberOfTrailingZeros(mask)
        return 36f * (share[i] - 1f / dicts.size)
    }

    private fun userBoost(count: Int): Float = if (count <= 0) 0f else 22f * ln(1f + count)

    private fun contextScores(prev: String?): Map<String, Float> {
        if (prev.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Float>()
        val pl = prev.lowercase()
        val rankBonus = floatArrayOf(70f, 55f, 45f)
        for (d in dicts) {
            for (pid in d.byLowercase(pl)) {
                d.nextWords(pid).forEachIndexed { r, nid ->
                    val k = d.word(nid).lowercase()
                    val b = rankBonus[minOf(r, 2)]
                    if (b > (out[k] ?: 0f)) out[k] = b
                }
            }
        }
        for ((k, c) in learned.nexts(pl)) {
            val b = minOf(110f, 50f + 18f * ln(c.toFloat()))
            out[k] = maxOf(out[k] ?: 0f, b)
        }
        return out
    }

    private fun maxCostFor(len: Int) = when {
        len < 3 -> 0
        len <= 4 -> 10
        len <= 7 -> 16
        else -> 20
    }

    fun suggest(typed: String, prev: String?, limit: Int = 4): Suggestions {
        val folded = Folding.fold(typed)
        if (folded.isEmpty()) return Suggestions.EMPTY
        val cands = HashMap<String, Cand>()

        fun add(word: String, kind: Kind, cost: Int, baseF: Int, mask: Int) {
            val old = cands[word]
            if (old == null || kind.ordinal < old.kind.ordinal || (kind == old.kind && cost < old.cost)) {
                cands[word] = Cand(word, kind, cost, maxOf(baseF, old?.baseF ?: 0), mask or (old?.dictMask ?: 0))
            } else if (baseF > old.baseF || (mask and old.dictMask.inv()) != 0) {
                cands[word] = Cand(old.word, old.kind, old.cost, maxOf(baseF, old.baseF), mask or old.dictMask)
            }
        }

        val maxCost = maxCostFor(folded.length)
        dicts.forEachIndexed { di, d ->
            val bit = 1 shl di
            for (id in d.trie.exact(folded)) {
                val w = d.word(id)
                add(w, if (sameAsTyped(w, typed)) Kind.TYPED else Kind.FOLD, 0, d.freq(id), bit)
            }
            for (id in d.trie.completions(folded, 12)) add(d.word(id), Kind.COMPLETION, 0, d.freq(id), bit)
            if (maxCost > 0) {
                d.trie.fuzzy(folded, maxCost, proximity) { ids, cost ->
                    if (cost > 0) for (id in ids) add(d.word(id), Kind.CORRECTION, cost, d.freq(id), bit)
                }
            }
        }
        for (lw in learned.all()) {
            val f = lw.folded
            when {
                f == folded -> add(lw.word, if (sameAsTyped(lw.word, typed)) Kind.TYPED else Kind.FOLD, 0, 0, 0)
                f.startsWith(folded) -> add(lw.word, Kind.COMPLETION, 0, 0, 0)
                // Dictionary words were already found by the trie's fuzzy search above.
                maxCost > 0 && !lw.inDictionary && kotlin.math.abs(f.length - folded.length) * 10 <= maxCost -> {
                    val c = EditDistance.weighted(folded, f, proximity, maxCost)
                    if (c in 1..maxCost) add(lw.word, Kind.CORRECTION, c, 0, 0)
                }
            }
        }

        val ctx = contextScores(prev)
        for (c in cands.values) {
            val key = c.word.lowercase()
            c.userCount = learned.get(key)?.count ?: 0
            var s = if (c.baseF > 0) c.baseF + userBoost(c.userCount) else 125f + 25f * ln(1f + c.userCount)
            s += ctx[key] ?: 0f
            s += affinityBonus(c.dictMask)
            s -= 7f * c.cost
            when (c.kind) {
                Kind.FOLD -> s -= 5f
                Kind.COMPLETION -> s -= 6f + 3f * (Folding.fold(c.word).length - folded.length)
                else -> Unit
            }
            c.score = s
        }

        val typedLearned = learned.get(typed.lowercase())
        val typedValid = cands.values.any { it.kind == Kind.TYPED && (it.baseF > 0 || it.userCount >= 2) } ||
            (typedLearned != null && typedLearned.count >= 2 && Folding.fold(typedLearned.word) == folded)

        var auto: Cand? = null
        if (typedValid) {
            // "welt" is a rare word but "Welt" a common one; "uber" vs "über": switch to the
            // clearly dominant spelling of the same letters.
            val typedScore = cands.values.filter { it.kind == Kind.TYPED }.maxOfOrNull { it.score } ?: 0f
            auto = cands.values
                .filter { it.kind == Kind.FOLD && it.baseF >= 40 && keepsDeliberateChars(typed, it.word) }
                .filter { it.score >= typedScore + 75f }
                .maxByOrNull { it.score }
        } else {
            val usable = cands.values.filter { (it.baseF >= 40 || it.userCount >= 2) && it.score >= 45f }
            // A case, accent or apostrophe fix ("haus" -> "Haus", "dont" -> "don't") is almost
            // certainly what was meant, so it wins over spelling corrections.
            auto = usable.filter { it.kind == Kind.FOLD && keepsDeliberateChars(typed, it.word) }.maxByOrNull { it.score }
            // Apostrophes and hyphens are typed on purpose ("geht's", "E-Mail"): only fold fixes.
            val deliberate = typed.any { it == '\'' || it == '’' || it == '-' }
            if (auto == null && folded.length >= 3 && !deliberate) {
                auto = usable.filter { it.kind == Kind.CORRECTION }.maxByOrNull { it.score }
            }
        }

        val ranked = cands.values
            .filter { it.kind != Kind.TYPED || !sameAsTyped(it.word, typed) }
            .sortedByDescending { it.score }
            .map { applyCase(it.word, typed) }
            .filter { it != typed }
            .distinct()
            .take(limit)
        val autoWord = auto?.let { applyCase(it.word, typed) }?.takeIf { it != typed }
        val rankedFinal = if (autoWord != null) listOf(autoWord) + ranked.filter { it != autoWord } else ranked
        return Suggestions(typed, typedValid && autoWord == null, rankedFinal.take(limit), autoWord)
    }

    /** Next-word predictions after [prev]. */
    fun predict(prev: String?, capitalize: Boolean, limit: Int = 3): List<String> {
        if (prev.isNullOrEmpty()) return emptyList()
        val pl = prev.lowercase()
        val scores = HashMap<String, Float>()
        val forms = HashMap<String, String>()
        val rankBonus = floatArrayOf(70f, 55f, 45f)
        for (d in dicts) {
            for (pid in d.byLowercase(pl)) {
                d.nextWords(pid).forEachIndexed { r, nid ->
                    val w = d.word(nid)
                    val k = w.lowercase()
                    val s = rankBonus[minOf(r, 2)] + 0.25f * d.freq(nid)
                    if (s > (scores[k] ?: -1f)) { scores[k] = s; forms[k] = w }
                }
            }
        }
        for ((k, c) in learned.nexts(pl)) {
            val lw = learned.get(k) ?: continue
            val s = 80f + 18f * ln(c.toFloat()) + userBoost(lw.count) * 0.5f
            scores[k] = maxOf(scores[k] ?: 0f, s) + (if (scores.containsKey(k)) 10f else 0f)
            forms[k] = lw.word
        }
        return scores.entries.sortedByDescending { it.value }
            .map { forms[it.key]!! }
            .map { if (capitalize) it.replaceFirstChar { c -> c.titlecase() } else it }
            .distinct()
            .take(limit)
    }

    /**
     * Suggestions before the first word of a sentence: words you often start sentences with,
     * plus common sentence starters of each language (leaning to the one you write in).
     */
    fun predictStart(capitalize: Boolean, limit: Int = 3): List<String> {
        val scores = HashMap<String, Float>()
        val forms = HashMap<String, String>()
        dicts.forEachIndexed { di, d ->
            STARTERS[d.language]?.forEachIndexed { r, w ->
                val k = w.lowercase()
                // Earlier languages in the profile win ties, so German leads in "German + English".
                val sc = 60f - 5f * r + 40f * share[di] - 0.5f * di
                if (sc > (scores[k] ?: -1f)) { scores[k] = sc; forms[k] = w }
            }
        }
        for ((k, c) in learned.nexts(START)) {
            val lw = learned.get(k) ?: continue
            val sc = 85f + 18f * ln(c.toFloat())
            scores[k] = maxOf(scores[k] ?: 0f, sc) + (if (forms.containsKey(k)) 5f else 0f)
            forms.putIfAbsent(k, lw.word)
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .map { forms.getValue(it.key) }
            .map { if (capitalize) it.replaceFirstChar { c -> c.titlecase() } else it }
            .distinct()
            .take(limit)
    }

    companion object {
        /** Learned-phrase key meaning "start of a sentence". */
        const val START = "^"

        private val STARTERS = mapOf(
            "de" to listOf("Ich", "Das", "Ja", "Hallo", "Wir", "Danke", "Und", "Es", "Okay", "Die"),
            "en" to listOf("I", "The", "Thanks", "Hi", "Yes", "It", "We", "Okay", "Hey", "That"),
            "ru" to listOf("Я", "Привет", "Это", "Да", "Спасибо", "Мы", "Как", "Ну", "Не", "А"),
        )

        /** A fix may add apostrophes and accents but never drop ones the user typed. */
        fun keepsDeliberateChars(typed: String, candidate: String): Boolean {
            if (typed.count { it == '\'' || it == '’' } > candidate.count { it == '\'' || it == '’' }) return false
            val lower = candidate.lowercase()
            // Only Latin accents (ä, é, ß…) count as deliberate; Cyrillic letters are ordinary letters.
            return typed.lowercase().filter { it.code in 0xC0..0x24F && it.isLetter() }.all { lower.contains(it) }
        }

        /** True when [word] is the typed word, allowing sentence-start capitals and caps lock. */
        fun sameAsTyped(word: String, typed: String): Boolean {
            if (word == typed) return true
            if (typed.isEmpty()) return false
            if (typed[0].isUpperCase() && word == typed.replaceFirstChar { it.lowercaseChar() }) return true
            if (typed.length > 1 && isAllCaps(typed) && word.uppercase() == typed) return true
            return false
        }

        fun isAllCaps(s: String): Boolean = s.any { it.isLetter() } && s.all { !it.isLetter() || it.isUpperCase() }

        /** Gives [word] the case pattern of what the user typed. */
        fun applyCase(word: String, typed: String): String = when {
            typed.length > 1 && isAllCaps(typed) -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.titlecase() }
            else -> word
        }
    }
}

object EditDistance {
    /** Weighted optimal-string-alignment distance (10 per edit), or > [max] if it exceeds it. */
    fun weighted(a: String, b: String, proximity: KeyProximity, max: Int): Int {
        val n = a.length
        val m = b.length
        var prev2 = IntArray(n + 1)
        var prev = IntArray(n + 1) { it * 10 }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i * 10
            var rowMin = cur[0]
            for (j in 1..n) {
                var best = prev[j - 1] + proximity.substitutionCost(a[j - 1], b[i - 1])
                if (prev[j] + 10 < best) best = prev[j] + 10
                if (cur[j - 1] + 10 < best) best = cur[j - 1] + 10
                if (i > 1 && j > 1 && b[i - 1] == a[j - 2] && b[i - 2] == a[j - 1] && prev2[j - 2] + 8 < best) {
                    best = prev2[j - 2] + 8
                }
                cur[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[n]
    }
}
