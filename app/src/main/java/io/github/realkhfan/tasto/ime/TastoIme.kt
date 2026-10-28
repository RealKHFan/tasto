package io.github.realkhfan.tasto.ime

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import io.github.realkhfan.tasto.data.Clip
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.LanguageProfile
import io.github.realkhfan.tasto.data.Languages
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.engine.SuggestionEngine
import io.github.realkhfan.tasto.japanese.KanaConverter
import io.github.realkhfan.tasto.japanese.KanaLogic
import io.github.realkhfan.tasto.keyboard.ClipboardPanel
import io.github.realkhfan.tasto.keyboard.EmojiPanel
import io.github.realkhfan.tasto.keyboard.Icon
import io.github.realkhfan.tasto.keyboard.Key
import io.github.realkhfan.tasto.keyboard.KeyType
import io.github.realkhfan.tasto.keyboard.KeyboardRoot
import io.github.realkhfan.tasto.keyboard.KeyboardTheme
import io.github.realkhfan.tasto.keyboard.KeyboardView
import io.github.realkhfan.tasto.keyboard.LanguagePanel
import io.github.realkhfan.tasto.keyboard.Layouts
import io.github.realkhfan.tasto.keyboard.Page
import io.github.realkhfan.tasto.keyboard.Panel
import io.github.realkhfan.tasto.keyboard.TopBarView
import io.github.realkhfan.tasto.ui.MainActivity

/** The Tasto keyboard service. */
class TastoIme : InputMethodService(), InputLogic.Host, KeyboardView.Listener, TopBarView.Listener,
    EmojiPanel.Listener, ClipboardPanel.Listener, LanguagePanel.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var store: LearnedStore
    private lateinit var clips: ClipboardStore
    private val logic = InputLogic(this)
    private var engine: SuggestionEngine? = null
    private var converter: KanaConverter? = null
    /** Japanese typing; used instead of [logic] while a Japanese layout is showing. */
    private val kana = KanaLogic(object : KanaLogic.Host {
        override fun converter(): KanaConverter? = converter
        override fun learnConversion(reading: String, surface: String) = learnKana(reading, surface)
        override fun onBarChanged(bar: BarState) = this@TastoIme.onBarChanged(bar)
        override fun onShiftChanged(state: ShiftState) = this@TastoIme.onShiftChanged(state)
        override fun onComposingChanged(composing: Boolean) = updateJapaneseKeys(composing)
        override val suggestionsEnabled: Boolean get() = prefs.suggestions
    })
    private var editor: EditorBridge? = null
    /** Whether [kana] (not [logic]) handles the keys right now. */
    private var japaneseActive = false
    private var profile: LanguageProfile = Languages.GERMAN_ENGLISH
    private var root: KeyboardRoot? = null
    private var page = Page.LETTERS
    private var policy = FieldPolicy.DEFAULT
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null
    private var clipboardManager: ClipboardManager? = null
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val learnedChanged: () -> Unit = { refreshBar() }
    private val keySender = object : KeySender {
        override fun sendKeyChar(c: Char) = this@TastoIme.sendKeyChar(c)
        override fun sendDownUpKeyEvents(keyCode: Int) = this@TastoIme.sendDownUpKeyEvents(keyCode)
    }

    /** Nothing is learned or remembered while this is true. */
    val incognito: Boolean
        get() = policy.secret || (prefs.autoIncognito && policy.privateField) || prefs.manualIncognito

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = LearnedStore.get(this)
        clips = ClipboardStore.get(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        store.addListener(learnedChanged)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        audio = getSystemService(AudioManager::class.java)
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager?.addPrimaryClipChangedListener(clipChanged)
        selectProfile(Languages.current(prefs))
    }

    // ---- languages ----------------------------------------------------------------------

    /**
     * Switches the typing language: layout, dictionaries and learned words. Unless the switch
     * came from Android's keyboard switcher, Android is told too, so its 🌐 list stays right.
     */
    private fun selectProfile(p: LanguageProfile, fromSystem: Boolean = false) {
        val changed = p.id != profile.id || (engine == null && converter == null)
        profile = p
        if (prefs.currentLanguage != p.id) prefs.currentLanguage = p.id
        if (changed) {
            engine = null
            if (p.isJapanese) {
                Languages.kana(this) { c ->
                    converter = c
                    if (profile.isJapanese) kana.refreshBar()
                }
            } else {
                Languages.engine(this, p) { e ->
                    if (profile.id == p.id) {
                        engine = e
                        logic.refreshBar()
                    }
                }
            }
        }
        // A new language starts on its letters (the symbol pages are shared anyway).
        if (changed && (page == Page.SYMBOLS || page == Page.SYMBOLS2)) page = basePage()
        switchLogic()
        applyLook()
        refreshBar()
        if (!fromSystem && !followingSystem) syncSystemLanguage(p)
    }

    // ---- Android's keyboard switcher (🌐) ------------------------------------------------

    private val imeId: String by lazy { ComponentName(this, TastoIme::class.java).flattenToShortString() }
    private val imm: InputMethodManager? get() = getSystemService(InputMethodManager::class.java)
    /** True while taking over a language picked in Android's switcher (don't echo it back). */
    private var followingSystem = false
    /** Languages we asked Android to select just now, so its answer isn't taken as a new choice. */
    private val requestedSystemLanguages = HashMap<String, Long>()

    private fun systemSubtypes(): List<InputMethodSubtype> {
        val info = runCatching { imm?.inputMethodList }.getOrNull()?.firstOrNull { it.id == imeId } ?: return emptyList()
        return (0 until info.subtypeCount).map { info.getSubtypeAt(it) }
    }

    private fun currentSystemLanguage(): String? =
        runCatching { imm?.currentInputMethodSubtype }.getOrNull()?.let { profileIdOf(it) }

    /** Tells Android which of Tasto's languages is showing (like the AOSP keyboard does). */
    private fun syncSystemLanguage(p: LanguageProfile) {
        if (root == null) return
        if (currentSystemLanguage() == p.id) return
        val subtype = systemSubtypes().firstOrNull { profileIdOf(it) == p.id } ?: return
        requestedSystemLanguages[p.id] = System.currentTimeMillis()
        runCatching { switchInputMethod(imeId, subtype) }
    }

    /** A language was picked in Android's keyboard switcher. */
    private fun followSystemLanguage(id: String) {
        val p = Languages.byIdOrNull(id) ?: return
        if (p.id == profile.id) return
        followingSystem = true
        try {
            // Picked there but turned off in Tasto: turn it on.
            if (p != Languages.GERMAN_ENGLISH && p.id !in prefs.enabledLanguages) {
                prefs.enabledLanguages = prefs.enabledLanguages + p.id
            }
            selectProfile(p, fromSystem = true)
        } finally {
            followingSystem = false
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val id = newSubtype?.let { profileIdOf(it) } ?: return
        prefs.systemLanguage = id
        val askedAt = requestedSystemLanguages.remove(id)
        if (askedAt != null && System.currentTimeMillis() - askedAt < 5_000) return
        followSystemLanguage(id)
    }

    /**
     * When the keyboard opens: if a language was picked in Android's switcher while Tasto
     * wasn't listening (e.g. switching from another keyboard to "Tasto: German + English"),
     * use it; otherwise make sure Android shows the language Tasto is in.
     */
    private fun checkSystemLanguage() {
        val sys = currentSystemLanguage() ?: return
        val seen = prefs.systemLanguage
        if (sys != seen) prefs.systemLanguage = sys
        when (systemLanguageStep(sys, seen, profile.id)) {
            SystemLanguageStep.FOLLOW -> followSystemLanguage(sys)
            SystemLanguageStep.TELL -> syncSystemLanguage(profile)
            SystemLanguageStep.NOTHING -> Unit
        }
    }

    /** Japanese is typed only in normal text fields; passwords, numbers, addresses use Latin keys. */
    private fun japaneseFor(p: LanguageProfile): Boolean =
        p.isJapanese && policy.kind == FieldKind.TEXT && !policy.secret && !policy.rawKeys

    /**
     * Hands the keys to the Japanese or the Latin logic, as the language and field need. What
     * was being typed stays in the field.
     */
    private fun switchLogic() {
        val want = japaneseFor(profile)
        if (want == japaneseActive) return
        val ed = editor
        if (japaneseActive) kana.finishInput() else logic.finishInput()
        japaneseActive = want
        if (ed != null) {
            if (want) kana.startInput(ed, policy) else logic.startInput(ed, policy)
        }
        updateJapaneseKeys(japaneseActive && kana.isComposing)
    }

    private fun refreshBar() = if (japaneseActive) kana.refreshBar() else logic.refreshBar()

    /** E-mail and web addresses (and, for Japanese, passwords) are typed on the Latin layout. */
    private fun layoutProfile(): LanguageProfile = when {
        profile.isJapanese && !japaneseFor(profile) -> Languages.GERMAN_ENGLISH
        policy.kind == FieldKind.EMAIL || policy.kind == FieldKind.URI -> Languages.GERMAN_ENGLISH
        else -> profile
    }

    /** Space shows 変換 (convert) and Enter 確定 (accept) while Japanese is being composed. */
    private fun updateJapaneseKeys(composing: Boolean) {
        val k = root?.keys ?: return
        k.spaceLabel = if (composing && japaneseActive) "変換" else layoutProfile().spaceLabel
        k.enterLabel = if (composing && japaneseActive) "確定" else null
    }

    /** The language the keyboard types in now. */
    val currentProfile: LanguageProfile get() = profile

    /** For tests: the Japanese logic, and whether it handles the keys. */
    internal val kanaForTest: KanaLogic get() = kana
    internal val japaneseActiveForTest: Boolean get() = japaneseActive
    internal val converterLoadedForTest: Boolean get() = converter != null

    /** For tests: what happens when Android's switcher reports [id] (null id = none). */
    internal fun systemLanguageChangedForTest(id: String) {
        prefs.systemLanguage = id
        followSystemLanguage(id)
    }

    enum class SystemLanguageStep { FOLLOW, TELL, NOTHING }

    companion object {
        /**
         * When the keyboard opens and Android's 🌐 list has [system] selected: follow it if it was
         * picked there since Tasto last looked ([seen]; null the first time after an update), else
         * tell Android the language Tasto is in ([current]) when they differ.
         */
        fun systemLanguageStep(system: String, seen: String?, current: String): SystemLanguageStep = when {
            system == current -> SystemLanguageStep.NOTHING
            system != seen -> SystemLanguageStep.FOLLOW
            else -> SystemLanguageStep.TELL
        }

        /** Which Tasto language an Android keyboard subtype stands for (see res/xml/method.xml). */
        fun profileIdOf(subtype: InputMethodSubtype): String? =
            if (subtype.containsExtraValueKey("tasto")) subtype.getExtraValueOf("tasto") else null
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        store.removeListener(learnedChanged)
        clipboardManager?.removePrimaryClipChangedListener(clipChanged)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val r = KeyboardRoot(this, this, this, this, this, this)
        root = r
        applyLook()
        return r
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        policy = FieldPolicy.from(info)
        page = basePage()
        showPanel(Panel.KEYS)
        root?.bar?.forgetWord = null
        val ed = ImeEditor({ currentInputConnection }, info, keySender, policy.capsRequest)
        editor = ed
        japaneseActive = japaneseFor(profile)
        if (japaneseActive) {
            logic.finishInput()
            kana.startInput(ed, policy)
        } else {
            kana.finishInput()
            logic.startInput(ed, policy)
        }
        applyLook()
        updateEnterIcon()
        updateIncognitoUi()
        root?.post { root?.refreshBottomSpace() }
        checkSystemLanguage()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (japaneseActive) kana.finishInput() else logic.finishInput()
        editor = null
        root?.keys?.cancelAll()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (japaneseActive) {
            kana.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        } else {
            logic.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLook()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            in Prefs.LOOK_KEYS -> applyLook()
            Prefs.ENABLED_LANGUAGES -> selectProfile(Languages.current(prefs))
            Prefs.CURRENT_LANGUAGE -> if (prefs.currentLanguage != profile.id) selectProfile(Languages.current(prefs))
            Prefs.MANUAL_INCOGNITO, Prefs.AUTO_INCOGNITO -> updateIncognitoUi()
            Prefs.SUGGESTIONS -> refreshBar()
        }
    }

    // ---- panels -------------------------------------------------------------------------

    /**
     * Switches between keys, emoji and clipboard. While the clipboard (copied text) is showing,
     * the keyboard window is marked secure so it can't be screenshotted or recorded.
     */
    private fun showPanel(p: Panel) {
        root?.showPanel(p)
        val w = window?.window ?: return
        if (p == Panel.CLIPBOARD) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ---- back ---------------------------------------------------------------------------

    /**
     * Android's Back (gesture or the ⌄ button) first steps back inside the keyboard: closes the
     * emoji/clipboard panel, the "forget word" question or the opened toolbar. Only when there is
     * nothing to go back from does it close the keyboard as usual.
     */
    private fun canGoBackInside(): Boolean {
        val r = root ?: return false
        return r.panel != Panel.KEYS || r.bar.forgetWord != null
    }

    /** Steps back inside the keyboard. Returns false if there was nothing to go back from. */
    fun goBackInside(): Boolean {
        val r = root ?: return false
        when {
            r.panel != Panel.KEYS -> showPanel(Panel.KEYS)
            r.bar.forgetWord != null -> r.bar.forgetWord = null
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Android only sends Back to the keyboard while it is showing.
        if (keyCode == KeyEvent.KEYCODE_BACK && canGoBackInside()) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking && !event.isCanceled && goBackInside()) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---- look ---------------------------------------------------------------------------

    /** Digits 1–0 above the letters: when turned on, and always in password fields (like SwiftKey). */
    private fun numberRow(): Boolean = prefs.numberRow || (policy.secret && prefs.numberRowPasswords)

    private fun basePage() = when (policy.kind) {
        FieldKind.NUMBER -> Page.NUMPAD
        FieldKind.PHONE -> Page.PHONE
        else -> Page.LETTERS
    }

    private fun applyLook() {
        val r = root ?: return
        val theme = KeyboardTheme.from(this, prefs)
        r.applyTheme(theme)
        val keyHeight = when (prefs.keyHeight) {
            "small" -> 43f
            "large" -> 55f
            else -> 49f
        }
        // Sideways the screen is short: shorter keys leave room for the app (like SwiftKey).
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        r.keys.keyHeightDp = if (landscape) keyHeight * 0.75f else keyHeight
        r.keys.showHints = prefs.keyHints
        r.keys.coloredEnter = prefs.coloredEnter
        r.bar.expanded = prefs.toolbarExpanded
        r.keys.showPreviews = prefs.keyPopup
        r.keys.languageCount = Languages.enabled(prefs).size
        r.keys.layout = Layouts.forPage(page, policy.kind, numberRow(), prefs.keyHints, layoutProfile().alphabet)
        r.keys.shiftState = if (japaneseActive) kana.shift else logic.shift
        updateJapaneseKeys(japaneseActive && kana.isComposing)
        @Suppress("DEPRECATION")
        window?.window?.navigationBarColor = theme.background
    }

    private fun updateEnterIcon() {
        root?.keys?.enterIcon = when (policy.enterAction) {
            EditorInfo.IME_ACTION_SEARCH -> Icon.SEARCH
            EditorInfo.IME_ACTION_SEND -> Icon.SEND
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icon.NEXT
            EditorInfo.IME_ACTION_DONE -> Icon.DONE
            else -> Icon.ENTER
        }
    }

    private fun updateIncognitoUi() {
        val r = root ?: return
        val on = incognito
        r.bar.incognito = on
        r.keys.incognito = on
    }

    // ---- InputLogic.Host ------------------------------------------------------------------

    override fun engine(): SuggestionEngine? = engine

    override fun learn(word: String, prev: String?, boost: Int, startedWithAutoCap: Boolean) {
        if (incognito || !prefs.learning) return
        val eng = engine ?: return
        store.learn(profile.learnedId, word, displayForm(word, startedWithAutoCap, eng), prev?.lowercase(), boost, eng.isKnown(word))
    }

    /** Remembers a Japanese conversion so it comes first next time (not in incognito). */
    private fun learnKana(reading: String, surface: String) {
        if (incognito || !prefs.learning) return
        val inDict = converter?.isDictionaryWord(reading, surface) ?: false
        store.learn(Languages.JAPANESE.learnedId, surface, surface, KanaConverter.LEARN_PREFIX + reading, 1, inDict)
    }

    /** How a learned word is shown later: dictionary spelling, or without a sentence-start capital. */
    private fun displayForm(word: String, autoCap: Boolean, eng: SuggestionEngine): String {
        eng.dictionaryForm(word)?.let { return it }
        if (word.length > 1 && SuggestionEngine.isAllCaps(word)) return word
        if (!autoCap) return word
        return store.cache(profile.learnedId).get(word.lowercase())?.word ?: word.replaceFirstChar { it.lowercaseChar() }
    }

    override fun onBarChanged(bar: BarState) {
        root?.bar?.bar = bar
    }

    override fun onShiftChanged(state: ShiftState) {
        root?.keys?.shiftState = state
    }

    override val autocorrectEnabled: Boolean get() = prefs.autocorrect
    override val autoCapEnabled: Boolean get() = prefs.autoCap
    override val doubleSpacePeriod: Boolean get() = prefs.doubleSpace
    override val autoSpaceAfterPunctuation: Boolean get() = prefs.autoSpace
    override val suggestionsEnabled: Boolean get() = prefs.suggestions

    // ---- KeyboardView.Listener -------------------------------------------------------------

    override fun onKeyText(text: String) = if (japaneseActive) kana.onText(text) else logic.onText(text)
    override fun onBackspace() = if (japaneseActive) kana.onBackspace() else logic.onBackspace()
    override fun onDeleteWord() = if (japaneseActive) kana.onDeleteWord() else logic.onDeleteWord()
    override fun onEnter() = if (japaneseActive) kana.onEnter() else logic.onEnter()
    override fun onShift() = if (japaneseActive) kana.onShiftKey() else logic.onShiftKey()
    override fun onShiftLongPress() = if (japaneseActive) kana.onShiftLongPress() else logic.onShiftLongPress()
    override fun onCursorMove(steps: Int) = if (japaneseActive) kana.onCursorMove(steps) else logic.onCursorMove(steps)
    /** Swipe on the space bar: next or previous language. */
    override fun onLanguageSwipe(direction: Int) {
        val list = Languages.enabled(prefs)
        if (list.size < 2) return
        val i = list.indexOfFirst { it.id == profile.id }.coerceAtLeast(0)
        selectProfile(list[(i + direction + list.size) % list.size])
    }

    /** ABC on the 12-key layout: straight back to German + English. */
    override fun onLatinKey() = selectProfile(Languages.GERMAN_ENGLISH)

    /** Holding ABC: the list of languages. */
    override fun onLanguageMenu() = showPanel(Panel.LANGUAGES)

    override fun onSpace() {
        if (japaneseActive) kana.onSpace() else logic.onSpace()
        if (page == Page.SYMBOLS || page == Page.SYMBOLS2) onPage(Page.LETTERS)
    }

    override fun onPage(page: Page) {
        this.page = page
        root?.keys?.layout = Layouts.forPage(page, policy.kind, numberRow(), prefs.keyHints, layoutProfile().alphabet)
    }

    override fun onEmoji() {
        showPanel(Panel.EMOJI)
    }

    override fun onKeyFeedback(key: Key) {
        if (prefs.vibrate) {
            runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
        }
        if (prefs.sound) {
            val fx = when (key.type) {
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    // ---- TopBarView.Listener ---------------------------------------------------------------

    override fun onSlot(slot: Slot) = logic.onSuggestion(slot)

    override fun onCandidate(index: Int) = kana.onCandidate(index)

    override fun onSlotLongPress(slot: Slot) {
        if (store.cache(profile.learnedId).get(slot.word.lowercase()) != null) {
            root?.bar?.forgetWord = slot.word
        }
    }

    override fun onForgetConfirmed(word: String) {
        store.delete(profile.learnedId, listOf(word.lowercase()))
        refreshBar()
    }

    override fun onToolbar(action: TopBarView.Action) {
        when (action) {
            // Tapping the clipboard icon again closes the clipboard.
            TopBarView.Action.CLIPBOARD ->
                showPanel(if (root?.panel == Panel.CLIPBOARD) Panel.KEYS else Panel.CLIPBOARD)
            TopBarView.Action.LANGUAGES ->
                showPanel(if (root?.panel == Panel.LANGUAGES) Panel.KEYS else Panel.LANGUAGES)
            TopBarView.Action.INCOGNITO -> prefs.manualIncognito = !prefs.manualIncognito
            TopBarView.Action.SETTINGS -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            TopBarView.Action.HIDE -> requestHideSelf(0)
        }
    }

    override fun onToolbarToggle() {
        prefs.toolbarExpanded = !prefs.toolbarExpanded
        root?.bar?.expanded = prefs.toolbarExpanded
    }

    override fun onBarBack() {
        showPanel(Panel.KEYS)
    }

    override fun onKeyFeedbackLight() {
        if (prefs.vibrate) runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    }

    // ---- EmojiPanel.Listener ---------------------------------------------------------------

    override fun onEmoji(emoji: String) {
        onKeyText(emoji)
        if (!incognito) prefs.emojiRecents = (listOf(emoji) + prefs.emojiRecents.filter { it != emoji }).take(32)
    }

    override fun onEmojiBackspace() = onBackspace()
    override fun onEmojiClose() { showPanel(Panel.KEYS) }
    override fun emojiRecents(): List<String> = prefs.emojiRecents

    // ---- Clipboard -------------------------------------------------------------------------

    private fun onClipChanged() {
        // The last field decides, even with the keyboard closed: a copy in a private tab stays private.
        if (!prefs.clipboardHistory || incognito) return
        val clip = clipboardManager?.primaryClip ?: return
        val sensitive = clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        if (sensitive || clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        clips.add(text)
        if (root?.panel == Panel.CLIPBOARD) root?.clipboard?.refresh()
    }

    override fun clipItems(): List<Clip> = clips.items()
    override fun clipHistoryEnabled(): Boolean = prefs.clipboardHistory
    override fun onClipPaste(text: String) = if (japaneseActive) kana.onPaste(text) else logic.onPaste(text)
    override fun onClipPin(text: String) = clips.togglePin(text)
    override fun onClipRemove(text: String) = clips.remove(text)
    override fun onClipClear() = clips.clearUnpinned()
    override fun onClipClose() { showPanel(Panel.KEYS) }

    // ---- Languages panel --------------------------------------------------------------------

    override fun languageItems(): List<LanguagePanel.Item> =
        Languages.enabled(prefs).map { LanguagePanel.Item(it.id, it.displayName, it.detail) }

    override fun currentLanguageId(): String = profile.id

    override fun onLanguagePicked(id: String) {
        selectProfile(Languages.byId(id))
        showPanel(Panel.KEYS)
    }

    override fun onLanguagesClose() { showPanel(Panel.KEYS) }
}
