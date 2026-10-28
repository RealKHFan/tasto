package io.github.realkhfan.tasto.engine

/**
 * Which letters sit next to each other on the keyboard. Typing a neighbouring key by
 * mistake is the most common typo, so such substitutions cost less (6 instead of 10).
 */
class KeyProximity private constructor(private val neighbours: Map<Char, Set<Char>>) {

    fun substitutionCost(typed: Char, candidate: Char): Int = when {
        typed == candidate -> 0
        neighbours[typed]?.contains(candidate) == true -> 6
        else -> 10
    }

    companion object {
        /**
         * [rows] are the letter rows top to bottom, [offsets] the horizontal shift of each row
         * in key widths. Keys are neighbours when they are in the same row and adjacent, or in
         * an adjacent row less than one key width apart.
         */
        fun fromRows(rows: List<String>, offsets: List<Float>): KeyProximity {
            data class P(val c: Char, val x: Float, val y: Int)
            val pts = rows.flatMapIndexed { r, row -> row.mapIndexed { i, c -> P(c, offsets[r] + i, r) } }
            val map = HashMap<Char, MutableSet<Char>>()
            for (a in pts) for (b in pts) {
                if (a.c == b.c) continue
                val dy = kotlin.math.abs(a.y - b.y)
                val dx = kotlin.math.abs(a.x - b.x)
                val near = (dy == 0 && dx <= 1.01f) || (dy == 1 && dx < 1.0f)
                if (near) map.getOrPut(a.c) { HashSet() }.add(b.c)
            }
            return KeyProximity(map)
        }

        val QWERTZ: KeyProximity by lazy {
            fromRows(listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"), listOf(0f, 0.5f, 1.5f))
        }

        val JCUKEN: KeyProximity by lazy {
            fromRows(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), listOf(0f, 0f, 1f))
        }
    }
}
