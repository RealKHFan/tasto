package io.github.realkhfan.tasto.engine

/** A word the keyboard learned from the user, keyed by its lowercase form. */
data class LearnedWord(
    val key: String,
    val word: String,
    val count: Int,
    val firstUsed: Long,
    val lastUsed: Long,
    val inDictionary: Boolean,
) {
    val folded: String = Folding.fold(word)
}

/** Read access to what the user taught the keyboard. */
interface LearnedWords {
    fun get(key: String): LearnedWord?
    fun all(): Collection<LearnedWord>
    /** Words that followed [prevKey], with how often. */
    fun nexts(prevKey: String): Map<String, Int>
}

/**
 * In-memory learned words for one language profile. The SQLite store mirrors it; this class
 * holds the logic so it can be tested without Android.
 */
class LearnedCache : LearnedWords {
    private val words = HashMap<String, LearnedWord>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    override fun get(key: String): LearnedWord? = words[key]
    override fun all(): Collection<LearnedWord> = words.values
    override fun nexts(prevKey: String): Map<String, Int> = bigrams[prevKey] ?: emptyMap()

    val size: Int get() = words.size

    fun put(w: LearnedWord) { words[w.key] = w }

    fun forEachBigram(block: (String, String, Int) -> Unit) {
        for ((p, m) in bigrams) for ((n, c) in m) block(p, n, c)
    }

    fun putBigram(prev: String, next: String, count: Int) {
        bigrams.getOrPut(prev) { HashMap() }[next] = count
    }

    /**
     * Records one use of [word] (after [prevKey], if any). [displayForm] decides how the word
     * is shown later. Returns the updated word and bigram count (0 when there was no prev).
     */
    fun learn(
        word: String,
        displayForm: String,
        prevKey: String?,
        increment: Int,
        inDictionary: Boolean,
        now: Long,
    ): Pair<LearnedWord, Int> {
        val key = word.lowercase()
        val old = words[key]
        val updated = if (old == null) {
            LearnedWord(key, displayForm, increment, now, now, inDictionary)
        } else {
            old.copy(word = displayForm, count = old.count + increment, lastUsed = now, inDictionary = inDictionary)
        }
        words[key] = updated
        var bigramCount = 0
        if (prevKey != null && prevKey != key) {
            val m = bigrams.getOrPut(prevKey) { HashMap() }
            bigramCount = (m[key] ?: 0) + 1
            m[key] = bigramCount
        }
        return updated to bigramCount
    }

    fun remove(keys: Collection<String>) {
        for (k in keys) {
            words.remove(k)
            bigrams.remove(k)
        }
        for (m in bigrams.values) m.keys.removeAll(keys.toSet())
        bigrams.values.removeAll { it.isEmpty() }
    }

    fun clear() {
        words.clear()
        bigrams.clear()
    }
}
