package io.github.realkhfan.tasto.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/** Sends key events to the app (the InputMethodService in production). */
interface KeySender {
    fun sendKeyChar(c: Char)
    fun sendDownUpKeyEvents(keyCode: Int)
}

/** [EditorBridge] over the app's InputConnection. */
class ImeEditor(
    private val connection: () -> InputConnection?,
    info: EditorInfo,
    private val keys: KeySender,
    /** InputType.TYPE_TEXT_FLAG_CAP_* to ask about; see FieldPolicy.capsRequest. */
    private val capsRequest: Int = info.inputType,
) : EditorBridge {
    private val ic get() = connection()

    override fun textBefore(n: Int): CharSequence = ic?.getTextBeforeCursor(n, 0) ?: ""
    override fun textAfter(n: Int): CharSequence = ic?.getTextAfterCursor(n, 0) ?: ""
    override fun selectedText(): CharSequence? = ic?.getSelectedText(0)
    override fun setComposing(text: CharSequence) { ic?.setComposingText(text, 1) }
    override fun finishComposing() { ic?.finishComposingText() }
    override fun commit(text: CharSequence) { ic?.commitText(text, 1) }
    override fun deleteBefore(chars: Int) { ic?.deleteSurroundingText(chars, 0) }
    override fun capsMode(): Boolean = capsRequest != 0 && (ic?.getCursorCapsMode(capsRequest) ?: 0) != 0
    override fun enter() = keys.sendKeyChar('\n')
    override fun sendBackspaceKey() = keys.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

    override fun moveCursor(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { keys.sendDownUpKeyEvents(code) }
    }

    override fun batch(block: () -> Unit) {
        val c = ic
        c?.beginBatchEdit()
        try {
            block()
        } finally {
            c?.endBatchEdit()
        }
    }
}
