package io.github.realkhfan.tasto.engine

import java.util.PriorityQueue

/**
 * Compact trie over folded keys. Each terminal node points to a group of entry ids
 * (several original words can share one folded key, e.g. "Haus" and "haus").
 * Every node also stores the best frequency found in its subtree, which makes
 * top-k prefix completion a cheap best-first search.
 */
class FoldTrie private constructor(
    private val chars: CharArray,
    private val firstChild: IntArray,
    private val nextSibling: IntArray,
    private val terminal: IntArray,
    private val subMax: IntArray,
    private val groups: Array<IntArray>,
    private val freqOf: (Int) -> Int,
) {
    /** Entry ids whose folded key equals [key] exactly. */
    fun exact(key: String): IntArray {
        val n = find(key)
        if (n < 0 || terminal[n] < 0) return EMPTY
        return groups[terminal[n]]
    }

    private fun find(key: String): Int {
        var node = 0
        for (c in key) {
            var child = firstChild[node]
            while (child >= 0 && chars[child] != c) child = nextSibling[child]
            if (child < 0) return -1
            node = child
        }
        return node
    }

    /**
     * Up to [limit] entry ids whose key starts with [prefix] (and is longer than it),
     * highest frequency first.
     */
    fun completions(prefix: String, limit: Int): List<Int> {
        val start = find(prefix)
        if (start < 0) return emptyList()
        val out = ArrayList<Int>(limit)
        // Items: node >= 0 -> expand subtree; encoded entry = -(id + 1) -> emit.
        val pq = PriorityQueue<LongArray>(32) { a, b -> b[0].compareTo(a[0]) }
        var child = firstChild[start]
        while (child >= 0) {
            pq.add(longArrayOf(subMax[child].toLong(), child.toLong()))
            child = nextSibling[child]
        }
        while (pq.isNotEmpty() && out.size < limit) {
            val item = pq.poll()!!
            val v = item[1]
            if (v < 0) {
                out.add((-v - 1).toInt())
                continue
            }
            val node = v.toInt()
            if (terminal[node] >= 0) {
                for (id in groups[terminal[node]]) {
                    pq.add(longArrayOf(freqOf(id).toLong(), -(id.toLong() + 1)))
                }
            }
            var c = firstChild[node]
            while (c >= 0) {
                pq.add(longArrayOf(subMax[c].toLong(), c.toLong()))
                c = nextSibling[c]
            }
        }
        return out
    }

    /**
     * All keys within [maxCost] (scaled: 10 = one edit) of [typed]. Uses weighted
     * Damerau-Levenshtein with cheaper substitutions for neighbouring keys.
     * Calls [onMatch] with (entry ids, cost).
     */
    fun fuzzy(typed: String, maxCost: Int, proximity: KeyProximity, onMatch: (IntArray, Int) -> Unit) {
        val n = typed.length
        if (n == 0) return
        val maxDepth = n + maxCost / 10 + 1
        val rows = Array(maxDepth + 1) { IntArray(n + 1) }
        val path = CharArray(maxDepth + 1)
        for (j in 0..n) rows[0][j] = j * 10
        fun visit(node: Int, depth: Int) {
            var child = firstChild[node]
            while (child >= 0) {
                val c = chars[child]
                val i = depth + 1
                if (i <= maxDepth) {
                    path[i] = c
                    val prev = rows[i - 1]
                    val cur = rows[i]
                    cur[0] = i * 10
                    var rowMin = cur[0]
                    for (j in 1..n) {
                        val t = typed[j - 1]
                        var best = prev[j - 1] + proximity.substitutionCost(t, c)
                        val del = prev[j] + 10
                        if (del < best) best = del
                        val ins = cur[j - 1] + 10
                        if (ins < best) best = ins
                        if (i > 1 && j > 1 && c == typed[j - 2] && path[i - 1] == t) {
                            val tr = rows[i - 2][j - 2] + 8
                            if (tr < best) best = tr
                        }
                        cur[j] = best
                        if (best < rowMin) rowMin = best
                    }
                    if (terminal[child] >= 0 && cur[n] <= maxCost) onMatch(groups[terminal[child]], cur[n])
                    if (rowMin <= maxCost) visit(child, i)
                }
                child = nextSibling[child]
            }
        }
        visit(0, 0)
    }

    class Builder(private val freqOf: (Int) -> Int) {
        private val keys = ArrayList<Pair<String, Int>>()

        fun add(foldedKey: String, entryId: Int) {
            if (foldedKey.isNotEmpty()) keys.add(foldedKey to entryId)
        }

        fun build(): FoldTrie {
            keys.sortWith(compareBy({ it.first }, { it.second }))
            var cap = keys.size * 3 + 16
            var chars = CharArray(cap)
            var first = IntArray(cap)
            var next = IntArray(cap)
            var last = IntArray(cap)
            var parent = IntArray(cap)
            var term = IntArray(cap)
            var size = 1
            first[0] = -1; next[0] = -1; last[0] = -1; parent[0] = -1; term[0] = -1
            val groups = ArrayList<IntArray>()
            val stack = IntArray(256)
            var prevKey = ""
            var i = 0
            while (i < keys.size) {
                val key = keys[i].first
                var j = i
                while (j < keys.size && keys[j].first == key) j++
                val ids = IntArray(j - i) { keys[i + it].second }
                if (key.length >= stack.size - 1) { i = j; continue }
                var common = 0
                val lim = minOf(prevKey.length, key.length)
                while (common < lim && prevKey[common] == key[common]) common++
                stack[0] = 0
                var node = stack[common]
                for (d in common until key.length) {
                    if (size == cap) {
                        cap *= 2
                        chars = chars.copyOf(cap); first = first.copyOf(cap); next = next.copyOf(cap)
                        last = last.copyOf(cap); parent = parent.copyOf(cap); term = term.copyOf(cap)
                    }
                    val nn = size++
                    chars[nn] = key[d]; first[nn] = -1; next[nn] = -1; last[nn] = -1
                    parent[nn] = node; term[nn] = -1
                    if (first[node] < 0) first[node] = nn else next[last[node]] = nn
                    last[node] = nn
                    node = nn
                    stack[d + 1] = nn
                }
                term[node] = groups.size
                groups.add(ids)
                prevKey = key
                i = j
            }
            val subMax = IntArray(size)
            for (n in size - 1 downTo 0) {
                if (term[n] >= 0) for (id in groups[term[n]]) subMax[n] = maxOf(subMax[n], freqOf(id))
                val p = parent[n]
                if (p >= 0 && subMax[n] > subMax[p]) subMax[p] = subMax[n]
            }
            return FoldTrie(
                chars.copyOf(size), first.copyOf(size), next.copyOf(size), term.copyOf(size),
                subMax, groups.toTypedArray(), freqOf,
            )
        }
    }

    companion object {
        private val EMPTY = IntArray(0)
    }
}
