package io.github.realkhfan.tasto.japanese

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Kana → kanji readings from the SKK dictionary (see tools/build_ja.py). Kept as two sorted
 * arrays so prefix searches ("ありが…" → ありがとう) are a binary search, without a big map.
 */
class KanaDictionary private constructor(
    private val readings: Array<String>,
    private val candidates: Array<String>,
) {
    val size: Int get() = readings.size

    /** Candidates for exactly this reading, most common first. */
    fun lookup(reading: String): List<String> {
        val i = readings.binarySearch(reading)
        return if (i >= 0) candidates[i].split('\t') else emptyList()
    }

    /** Readings that start with [prefix] and are longer than it (no okuri entries), up to [limit]. */
    fun completions(prefix: String, limit: Int): List<Pair<String, List<String>>> {
        if (prefix.isEmpty()) return emptyList()
        var i = readings.binarySearch(prefix)
        i = if (i >= 0) i + 1 else -i - 1
        // Shorter (closer) readings first; only indices are collected while scanning.
        val best = ArrayList<Int>()
        while (i < readings.size && readings[i].startsWith(prefix)) {
            val r = readings[i]
            if (!r.last().isAsciiLetter()) {
                if (best.size < limit) {
                    best.add(i)
                } else {
                    val worst = best.indices.maxBy { readings[best[it]].length }
                    if (r.length < readings[best[worst]].length) best[worst] = i
                }
            }
            i++
        }
        return best.sortedWith(compareBy({ readings[it].length }, { it })).map { readings[it] to candidates[it].split('\t') }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z'

    companion object {
        /** "word<TAB>count" lines (see tools/build_ja_freq.py). */
        fun loadFrequencies(input: InputStream): Map<String, Int> {
            val out = HashMap<String, Int>(40_000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val t = line.indexOf('\t')
                    if (t > 0) line.substring(t + 1).toIntOrNull()?.let { out[line.substring(0, t)] = it }
                }
            }
            return out
        }

        fun load(input: InputStream): KanaDictionary {
            val rs = ArrayList<String>(150_000)
            val cs = ArrayList<String>(150_000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val t = line.indexOf('\t')
                    if (t <= 0) continue
                    rs.add(line.substring(0, t))
                    cs.add(line.substring(t + 1))
                }
            }
            // build_ja.py writes the file sorted; sort here only if it isn't.
            val sorted = (1 until rs.size).all { rs[it - 1] < rs[it] }
            if (sorted) return KanaDictionary(rs.toTypedArray(), cs.toTypedArray())
            val order = rs.indices.sortedBy { rs[it] }
            return KanaDictionary(Array(order.size) { rs[order[it]] }, Array(order.size) { cs[order[it]] })
        }
    }
}
