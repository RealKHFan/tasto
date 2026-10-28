package io.github.realkhfan.tasto.ui

import io.github.realkhfan.tasto.engine.Folding
import io.github.realkhfan.tasto.engine.LearnedWord

/** How often a learned word was used. */
enum class Usage(val label: String, val range: String) {
    ALL("All", ""),
    OFTEN("Often", "10+ uses"),
    SOMETIMES("Sometimes", "3–9 uses"),
    RARELY("Rarely", "1–2 uses");

    companion object {
        fun of(count: Int): Usage = when {
            count >= 10 -> OFTEN
            count >= 3 -> SOMETIMES
            else -> RARELY
        }
    }
}

enum class WordSort(val label: String) { MOST_USED("Most used"), RECENT("Recently used"), AZ("A–Z") }

data class WordRow(val lang: String, val word: LearnedWord)

object WordFilter {
    fun apply(
        all: List<Pair<String, LearnedWord>>,
        query: String,
        usage: Usage,
        newOnly: Boolean,
        sort: WordSort,
        language: String? = null,
    ): List<WordRow> {
        val q = Folding.fold(query.trim())
        return all.asSequence()
            .filter { (lang, _) -> language == null || lang == language }
            .filter { (_, w) -> q.isEmpty() || w.folded.contains(q) }
            .filter { (_, w) -> usage == Usage.ALL || Usage.of(w.count) == usage }
            .filter { (_, w) -> !newOnly || !w.inDictionary }
            .map { (lang, w) -> WordRow(lang, w) }
            .sortedWith(
                when (sort) {
                    WordSort.MOST_USED -> compareByDescending<WordRow> { it.word.count }.thenBy { it.word.key }
                    WordSort.RECENT -> compareByDescending<WordRow> { it.word.lastUsed }.thenBy { it.word.key }
                    WordSort.AZ -> compareBy<WordRow> { it.word.folded }.thenBy { it.word.word }
                },
            )
            .toList()
    }

    fun counts(all: List<Pair<String, LearnedWord>>): Map<Usage, Int> {
        val m = HashMap<Usage, Int>()
        m[Usage.ALL] = all.size
        for ((_, w) in all) m.merge(Usage.of(w.count), 1, Int::plus)
        return m
    }
}
