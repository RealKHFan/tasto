package io.github.realkhfan.tasto.engine

/**
 * Folds a word into a lookup key: lowercase, accents removed, ß -> ss, apostrophes dropped.
 * "Straße" -> "strasse", "don't" -> "dont", "über" -> "uber", "Ёлка" -> "елка".
 */
object Folding {
    private val table: Array<String?> = arrayOfNulls<String>(0x180).also { t ->
        fun map(chars: String, to: String) { for (c in chars) t[c.code] = to }
        map("ÀÁÂÃÄÅàáâãäåĀāĂăĄą", "a")
        map("Ææ", "ae")
        map("ÇçĆćĈĉĊċČč", "c")
        map("ĎďĐđ", "d")
        map("ÈÉÊËèéêëĒēĔĕĖėĘęĚě", "e")
        map("ĜĝĞğĠġĢģ", "g")
        map("ĤĥĦħ", "h")
        map("ÌÍÎÏìíîïĨĩĪīĬĭĮįİı", "i")
        map("Ĵĵ", "j")
        map("Ķķ", "k")
        map("ĹĺĻļĽľĿŀŁł", "l")
        map("ÑñŃńŅņŇň", "n")
        map("ÒÓÔÕÖØòóôõöøŌōŎŏŐő", "o")
        map("Œœ", "oe")
        map("ŔŕŖŗŘř", "r")
        map("ŚśŜŝŞşŠš", "s")
        map("ß", "ss")
        map("ŢţŤťŦŧ", "t")
        map("ÙÚÛÜùúûüŨũŪūŬŭŮůŰűŲų", "u")
        map("Ŵŵ", "w")
        map("ÝýÿŶŷŸ", "y")
        map("ŹźŻżŽž", "z")
    }

    fun fold(s: CharSequence): String {
        val sb = StringBuilder(s.length + 2)
        for (ch in s) {
            when {
                ch == '\'' || ch == '’' -> Unit
                ch.code < 0x180 -> {
                    val m = table[ch.code]
                    if (m != null) sb.append(m) else sb.append(ch.lowercaseChar())
                }
                ch == 'ё' || ch == 'Ё' -> sb.append('е')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /** True for characters that can be part of a word while typing. */
    fun isWordChar(ch: Char): Boolean = Character.isLetter(ch)

    /** Characters that may appear inside a word (don't, E-Mail) but not start one. */
    fun isWordConnector(ch: Char): Boolean = ch == '\'' || ch == '’' || ch == '-'
}
