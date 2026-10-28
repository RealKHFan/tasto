package io.github.realkhfan.tasto.keyboard

import io.github.realkhfan.tasto.ime.FieldKind
import io.github.realkhfan.tasto.japanese.Kana

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, LETTERS, MORE_SYMBOLS, EMOJI, TAB, SPACER,
    /** 12-key: ゛゜小 (dakuten, handakuten, small kana). */
    KANA_MOD,
    /** 12-key: move the cursor. */
    CURSOR_LEFT, CURSOR_RIGHT,
    /** 12-key: next language. */
    LANGUAGE,
}

/**
 * One key. [width] is in key units (a normal letter is 1). [popup] holds the long-press
 * alternatives, first one preselected. [hint] is drawn small in the corner.
 */
data class Key(
    val type: KeyType,
    val label: String = "",
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
    val hint: String? = null,
    /** Secondary hint text for phone keys ("ABC"). */
    val subLabel: String? = null,
    /** 12-key flick: tap, flick left, up, right, down. */
    val flick: List<String> = emptyList(),
) {
    val isFunction: Boolean
        get() = type != KeyType.CHAR && type != KeyType.SPACE && type != KeyType.SPACER
}

enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD, PHONE }

/** Letter layouts. */
enum class Alphabet { QWERTZ, JCUKEN, ROMAJI, FLICK12 }

data class KeyboardLayout(
    val page: Page,
    val rows: List<List<Key>>,
    /** Relative height of each row (1 = a normal key row). */
    val rowHeights: List<Float> = rows.map { 1f },
)

object Layouts {
    private fun c(label: String, popup: String = "", hint: String? = null) =
        Key(KeyType.CHAR, label, 1f, if (popup.isEmpty()) emptyList() else popup.split(' '), hint)

    private val LETTER_POPUPS = mapOf(
        "q" to "1", "w" to "2", "e" to "3 é è ê ë €", "r" to "4", "t" to "5", "z" to "6 ž ź ż",
        "u" to "7 ü ú ù û", "i" to "8 í ì î ï", "o" to "9 ö ó ò ô õ ø œ", "p" to "0",
        "a" to "ä á à â ã å æ", "s" to "ß ś š ş", "d" to "ð", "g" to "ğ", "l" to "ł",
        "y" to "ý ÿ", "c" to "ç ć č", "n" to "ñ ń",
    )

    private fun letterRow(letters: String, withDigitHints: Boolean) = letters.map { ch ->
        val l = ch.toString()
        val popup = LETTER_POPUPS[l] ?: ""
        val hint = if (withDigitHints) popup.split(' ').firstOrNull()?.takeIf { it.isNotEmpty() } else null
        c(l, popup, hint)
    }

    /** Bottom row of the letters page, sized like SwiftKey: 123 · emoji · , · space · . · enter. */
    private fun lettersBottomRow(kind: FieldKind, japanese: Boolean = false): List<Key> {
        val comma = when {
            japanese -> c("、", "， ・ 「 」 ,")
            kind == FieldKind.EMAIL -> c("@", "_ - ,")
            kind == FieldKind.URI -> c("/", ": - _ ,")
            else -> c(",", "; : _ '")
        }
        val period = when {
            japanese -> c("。", "？ ！ … ・ 〜 .", hint = "?!")
            kind == FieldKind.URI || kind == FieldKind.EMAIL -> c(".", ".com .de .org .net ? !")
            else -> c(".", "? ! , - ' \" … ; :", hint = ",!?")
        }
        return listOf(
            Key(KeyType.SYMBOLS, "123", 1f),
            Key(KeyType.EMOJI, width = 1f),
            comma,
            Key(KeyType.SPACE, width = 4.5f),
            period,
            Key(KeyType.ENTER, width = 1.5f),
        )
    }

    private val RU_POPUPS = mapOf(
        "й" to "1", "ц" to "2", "у" to "3", "к" to "4", "е" to "ё 5", "н" to "6", "г" to "7",
        "ш" to "8", "щ" to "9", "з" to "0", "х" to "ъ", "ь" to "ъ",
    )

    /** Russian ЙЦУКЕН like SwiftKey's: 11 keys in the top two rows, so every key is 10/11 wide. */
    private fun russian(kind: FieldKind, numberRow: Boolean, hints: Boolean): KeyboardLayout {
        val w = 10f / 11f
        fun row(letters: String, withHints: Boolean) = letters.map { ch ->
            val l = ch.toString()
            val popup = RU_POPUPS[l] ?: ""
            val hint = if (withHints) popup.split(' ').firstOrNull()?.takeIf { it.isNotEmpty() } else null
            c(l, popup, hint).copy(width = w)
        }
        val rows = ArrayList<List<Key>>()
        val heights = ArrayList<Float>()
        if (numberRow) {
            rows.add("1234567890".map { c(it.toString()) })
            heights.add(0.82f)
        }
        rows.add(row("йцукенгшщзх", hints && !numberRow))
        rows.add(row("фывапролджэ", false))
        rows.add(listOf(Key(KeyType.SHIFT, width = w)) + row("ячсмитьбю", false) + Key(KeyType.BACKSPACE, width = w))
        rows.add(lettersBottomRow(kind))
        heights.addAll(listOf(1f, 1f, 1f, 1f))
        return KeyboardLayout(Page.LETTERS, rows, heights)
    }

    /**
     * Japanese 12-key flick, like Gboard's: ◀ ▶ move the cursor, あかさ / たなは / まやら / わ,
     * ゛゜小, 、。?!, space (変換 while typing) and a key for the next language.
     */
    private fun flick12(): KeyboardLayout {
        val side = 1.6f
        val mid = (10f - 2 * side) / 3f
        fun f(main: String, label: String = main) = Key(KeyType.CHAR, label, mid, flick = Kana.FLICK.getValue(main))
        return KeyboardLayout(
            Page.LETTERS,
            listOf(
                listOf(Key(KeyType.CURSOR_LEFT, width = side), f("あ"), f("か"), f("さ"), Key(KeyType.BACKSPACE, width = side)),
                listOf(Key(KeyType.CURSOR_RIGHT, width = side), f("た"), f("な"), f("は"), Key(KeyType.SPACE, width = side)),
                listOf(Key(KeyType.SYMBOLS, "123", side), f("ま"), f("や"), f("ら"), Key(KeyType.LANGUAGE, "ABC", side)),
                listOf(Key(KeyType.EMOJI, width = side), Key(KeyType.KANA_MOD, Kana.MODIFY, mid), f("わ"), f("、", "、。?!"), Key(KeyType.ENTER, width = side)),
            ),
        )
    }

    fun letters(kind: FieldKind, numberRow: Boolean, hints: Boolean, alphabet: Alphabet = Alphabet.QWERTZ): KeyboardLayout {
        if (alphabet == Alphabet.JCUKEN) return russian(kind, numberRow, hints)
        if (alphabet == Alphabet.FLICK12) return flick12()
        val japanese = alphabet == Alphabet.ROMAJI
        val rows = ArrayList<List<Key>>()
        val heights = ArrayList<Float>()
        if (numberRow) {
            rows.add("1234567890".map { c(it.toString()) })
            heights.add(0.82f)
        }
        rows.add(letterRow("qwertzuiop", hints && !numberRow))
        if (japanese) {
            // Like Gboard's Japanese QWERTY: ー (long vowel) at the end of the middle row.
            rows.add(letterRow("asdfghjkl", false) + c("ー", "- 〜 ～"))
        } else {
            rows.add(listOf(Key(KeyType.SPACER, width = 0.5f)) + letterRow("asdfghjkl", false) + Key(KeyType.SPACER, width = 0.5f))
        }
        rows.add(
            listOf(Key(KeyType.SHIFT, width = 1.3f), Key(KeyType.SPACER, width = 0.2f)) +
                letterRow("yxcvbnm", false) +
                listOf(Key(KeyType.SPACER, width = 0.2f), Key(KeyType.BACKSPACE, width = 1.3f)),
        )
        rows.add(lettersBottomRow(kind, japanese))
        heights.addAll(listOf(1f, 1f, 1f, 1f))
        return KeyboardLayout(Page.LETTERS, rows, heights)
    }

    /** First symbol page, laid out like SwiftKey's: digits, then common symbols. */
    fun symbols(@Suppress("UNUSED_PARAMETER") kind: FieldKind, lettersLabel: String = "abc"): KeyboardLayout = KeyboardLayout(
        Page.SYMBOLS,
        listOf(
            listOf(c("1", "¹ ½ ⅓ ¼"), c("2", "² ⅔"), c("3", "³ ¾"), c("4", "⁴"), c("5"), c("6"), c("7"), c("8"), c("9"), c("0", "ⁿ ∅")),
            listOf(c("@"), c("#"), c("£", "€ $ ¥ ¢ ₽"), c("&"), c("_"), c("-", "– — ·"), c("(", "< [ {"), c(")", "> ] }"), c("=", "≠ ≈"), c("%", "‰")),
            listOf(Key(KeyType.MORE_SYMBOLS, "{&=", 1f)) +
                listOf(c("\"", "„ “ ” « »"), c("*", "★ †"), c("'", "‚ ‘ ’ ‹ ›"), c(":"), c("/", "\\"), c("!", "¡"), c("?", "¿"), c("+", "±")) +
                listOf(Key(KeyType.BACKSPACE, width = 1f)),
            listOf(
                Key(KeyType.LETTERS, lettersLabel, 1.3f),
                c(",").copy(width = 1.2f),
                Key(KeyType.SPACE, width = 5f),
                c(".").copy(width = 1.2f),
                Key(KeyType.ENTER, width = 1.3f),
            ),
        ),
    )

    /** Second symbol page, laid out like SwiftKey's: currencies, brackets, rarer symbols. */
    fun symbols2(@Suppress("UNUSED_PARAMETER") kind: FieldKind, lettersLabel: String = "abc"): KeyboardLayout {
        val m = 7.4f / 7f // the seven middle keys share what the two wide edge keys leave
        fun s(label: String, popup: String = "") = c(label, popup).copy(width = m)
        return KeyboardLayout(
            Page.SYMBOLS2,
            listOf(
                listOf(c("$").copy(width = 1.3f)) +
                    listOf(s("€"), s("¥"), s("¢"), s("©"), s("®"), s("™"), s("~")) +
                    listOf(c("¿").copy(width = 1.3f)),
                listOf(Key(KeyType.TAB, width = 1.3f)) +
                    listOf(s("["), s("]"), s("{"), s("}"), s("<", "‹ «"), s(">", "› »"), s("^")) +
                    listOf(c("¡").copy(width = 1.3f)),
                listOf(Key(KeyType.SYMBOLS, "123", 1.3f)) +
                    listOf(s("`"), s(";"), s("÷"), s("\\"), s("|"), s("¦"), s("¬")) +
                    listOf(Key(KeyType.BACKSPACE, width = 1.3f)),
                listOf(Key(KeyType.LETTERS, lettersLabel, 1.3f), Key(KeyType.SPACE, width = 3f * m)) +
                    listOf(s("×"), s("§"), s("¶"), s("°")) +
                    listOf(Key(KeyType.ENTER, width = 1.3f)),
            ),
        )
    }

    fun numpad(): KeyboardLayout = KeyboardLayout(
        Page.NUMPAD,
        listOf(
            listOf(c("1"), c("2"), c("3"), c("-", "+ −")),
            listOf(c("4"), c("5"), c("6"), c(",")),
            listOf(c("7"), c("8"), c("9"), Key(KeyType.BACKSPACE)),
            listOf(c("."), c("0"), Key(KeyType.SPACE), Key(KeyType.ENTER)),
        ),
        listOf(1.1f, 1.1f, 1.1f, 1.1f),
    )

    fun phone(): KeyboardLayout {
        fun p(d: String, sub: String?, popup: String = "") =
            Key(KeyType.CHAR, d, 1f, if (popup.isEmpty()) emptyList() else popup.split(' '), null, sub)
        return KeyboardLayout(
            Page.PHONE,
            listOf(
                listOf(p("1", null), p("2", "ABC"), p("3", "DEF"), p("-", null, "( ) /")),
                listOf(p("4", "GHI"), p("5", "JKL"), p("6", "MNO"), p("+", null)),
                listOf(p("7", "PQRS"), p("8", "TUV"), p("9", "WXYZ"), Key(KeyType.BACKSPACE)),
                listOf(p("*", null, "#"), p("0", "+", "+"), p("#", null), Key(KeyType.ENTER)),
            ),
            listOf(1.1f, 1.1f, 1.1f, 1.1f),
        )
    }

    /** The key on the symbol pages that goes back to the letters. */
    private fun lettersLabel(alphabet: Alphabet) = when (alphabet) {
        Alphabet.QWERTZ, Alphabet.JCUKEN -> "abc"
        Alphabet.ROMAJI, Alphabet.FLICK12 -> "あいう"
    }

    fun forPage(
        page: Page,
        kind: FieldKind,
        numberRow: Boolean,
        hints: Boolean,
        alphabet: Alphabet = Alphabet.QWERTZ,
    ): KeyboardLayout = when (page) {
        Page.LETTERS -> letters(kind, numberRow, hints, alphabet)
        Page.SYMBOLS -> symbols(kind, lettersLabel(alphabet))
        Page.SYMBOLS2 -> symbols2(kind, lettersLabel(alphabet))
        Page.NUMPAD -> numpad()
        Page.PHONE -> phone()
    }
}
