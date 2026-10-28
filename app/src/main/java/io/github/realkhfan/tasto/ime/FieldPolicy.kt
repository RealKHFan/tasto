package io.github.realkhfan.tasto.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class FieldKind { TEXT, EMAIL, URI, NUMBER, PHONE }

/** How Tasto behaves in the current text field, derived from what the app tells us. */
data class FieldPolicy(
    val kind: FieldKind,
    /** Password or PIN field: never suggest, learn or keep anything. */
    val secret: Boolean,
    /** The app asked for no personalised learning (private/incognito browser tabs). */
    val privateField: Boolean,
    val showSuggestions: Boolean,
    val autocorrect: Boolean,
    /** Apps with no real text editor (terminals) only understand key events. */
    val rawKeys: Boolean,
    val multiLine: Boolean,
    val enterAction: Int,
    /**
     * Which capitals to ask the editor about (InputType.TYPE_TEXT_FLAG_CAP_*). Normal text
     * fields that don't ask for anything still get sentence capitals, like SwiftKey does.
     */
    val capsRequest: Int = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
) {
    val learning: Boolean get() = !privateField && !secret && kind == FieldKind.TEXT
    val useComposing: Boolean get() = showSuggestions && !rawKeys && !secret

    companion object {
        val DEFAULT = FieldPolicy(FieldKind.TEXT, false, false, true, true, false, false, EditorInfo.IME_ACTION_NONE)

        fun from(info: EditorInfo?): FieldPolicy {
            if (info == null) return DEFAULT
            val type = info.inputType
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            val flags = type and InputType.TYPE_MASK_FLAGS
            val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            val action = if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
                EditorInfo.IME_ACTION_NONE
            } else {
                info.imeOptions and EditorInfo.IME_MASK_ACTION
            }
            return when (cls) {
                InputType.TYPE_CLASS_NUMBER -> {
                    val pin = variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    FieldPolicy(FieldKind.NUMBER, pin, noLearning || pin, false, false, false, false, action, 0)
                }
                InputType.TYPE_CLASS_PHONE ->
                    FieldPolicy(FieldKind.PHONE, false, noLearning, false, false, false, false, action, 0)
                InputType.TYPE_CLASS_DATETIME ->
                    FieldPolicy(FieldKind.NUMBER, false, noLearning, false, false, false, false, action, 0)
                InputType.TYPE_CLASS_TEXT -> {
                    val secret = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    val kind = when (variation) {
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                        InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                        else -> FieldKind.TEXT
                    }
                    val noSuggestions = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
                    val suggestions = kind == FieldKind.TEXT && !secret && !noSuggestions
                    val autocorrect = suggestions &&
                        variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME &&
                        variation != InputType.TYPE_TEXT_VARIATION_FILTER &&
                        variation != InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
                    val multiLine = (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                    val capsFlags = flags and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                    val proseVariation = variation == InputType.TYPE_TEXT_VARIATION_NORMAL ||
                        variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
                        variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
                        variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
                    val caps = when {
                        kind != FieldKind.TEXT || secret -> 0
                        capsFlags != 0 -> capsFlags
                        proseVariation && !noSuggestions -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        else -> 0
                    }
                    FieldPolicy(kind, secret, noLearning || secret, suggestions, autocorrect, false, multiLine, action, caps)
                }
                else -> FieldPolicy(FieldKind.TEXT, false, noLearning, false, false, true, false, action, 0)
            }
        }
    }
}
