package io.github.realkhfan.tasto.engine

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * A built-in word list for one language: words with frequency (1..255, log scale) and up
 * to three likely next words each. Loaded once from the app's assets.
 */
class BaseDictionary private constructor(
    val language: String,
    private val words: Array<String>,
    private val freqs: IntArray,
    private val nexts: Array<IntArray?>,
    private val byLower: HashMap<String, IntArray>,
    val trie: FoldTrie,
) {
    val size: Int get() = words.size

    fun word(id: Int): String = words[id]
    fun freq(id: Int): Int = freqs[id]

    /** Entry ids with this exact lowercase form ("haus" finds "Haus"). */
    fun byLowercase(lower: String): IntArray = byLower[lower] ?: EMPTY

    /** Likely next words after [prevId], most likely first. */
    fun nextWords(prevId: Int): IntArray = nexts[prevId] ?: EMPTY

    companion object {
        private val EMPTY = IntArray(0)

        fun load(language: String, input: InputStream): BaseDictionary {
            val words = ArrayList<String>(120_000)
            val freqs = ArrayList<Int>(120_000)
            val rawNexts = ArrayList<String>(120_000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1)
                    val w = line.substring(0, t1)
                    val f = (if (t2 < 0) line.substring(t1 + 1) else line.substring(t1 + 1, t2)).toIntOrNull() ?: continue
                    words.add(w)
                    freqs.add(f.coerceIn(1, 255))
                    rawNexts.add(if (t2 < 0) "" else line.substring(t2 + 1))
                }
            }
            val wordArr = words.toTypedArray()
            val freqArr = freqs.toIntArray()
            val byLower = HashMap<String, IntArray>(wordArr.size * 2)
            val exactIndex = HashMap<String, Int>(wordArr.size * 2)
            for (i in wordArr.indices) {
                exactIndex.putIfAbsent(wordArr[i], i)
                val l = wordArr[i].lowercase()
                val prev = byLower[l]
                byLower[l] = if (prev == null) intArrayOf(i) else prev + i
            }
            val nexts = arrayOfNulls<IntArray>(wordArr.size)
            for (i in wordArr.indices) {
                val raw = rawNexts[i]
                if (raw.isEmpty()) continue
                val ids = raw.split('|').mapNotNull { exactIndex[it] }
                if (ids.isNotEmpty()) nexts[i] = ids.toIntArray()
            }
            val builder = FoldTrie.Builder { freqArr[it] }
            for (i in wordArr.indices) builder.add(Folding.fold(wordArr[i]), i)
            return BaseDictionary(language, wordArr, freqArr, nexts, byLower, builder.build())
        }
    }
}
