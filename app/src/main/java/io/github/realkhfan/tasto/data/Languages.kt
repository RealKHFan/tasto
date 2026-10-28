package io.github.realkhfan.tasto.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.realkhfan.tasto.engine.BaseDictionary
import io.github.realkhfan.tasto.engine.KeyProximity
import io.github.realkhfan.tasto.engine.SuggestionEngine
import io.github.realkhfan.tasto.japanese.KanaConverter
import io.github.realkhfan.tasto.japanese.KanaDictionary
import io.github.realkhfan.tasto.keyboard.Alphabet
import java.util.concurrent.Executors

/** A keyboard language: which layout it uses and which dictionaries feed its suggestions. */
data class LanguageProfile(
    val id: String,
    /** Shown on the space bar. */
    val spaceLabel: String,
    /** Shown in settings. */
    val displayName: String,
    val dictionaries: List<String>,
    val proximity: KeyProximity,
    val alphabet: Alphabet,
    /** One line under the name in the keyboard's language list. */
    val detail: String = "",
    /** Learned words are kept per language; both Japanese layouts share theirs. */
    val learnedId: String = id,
) {
    val isJapanese: Boolean get() = alphabet == Alphabet.ROMAJI || alphabet == Alphabet.FLICK12
}

object Languages {
    val GERMAN_ENGLISH = LanguageProfile(
        id = "de_en",
        spaceLabel = "de/en",
        displayName = "German + English (QWERTZ)",
        dictionaries = listOf("de", "en"),
        proximity = KeyProximity.QWERTZ,
        alphabet = Alphabet.QWERTZ,
        detail = "QWERTZ · suggestions in both languages",
    )

    val RUSSIAN = LanguageProfile(
        id = "ru",
        spaceLabel = "ru",
        displayName = "Russian (ЙЦУКЕН)",
        dictionaries = listOf("ru"),
        proximity = KeyProximity.JCUKEN,
        alphabet = Alphabet.JCUKEN,
        detail = "ЙЦУКЕН · Russian suggestions",
    )

    val JAPANESE = LanguageProfile(
        id = "ja",
        spaceLabel = "日本語",
        displayName = "Japanese (romaji, QWERTZ)",
        dictionaries = emptyList(),
        proximity = KeyProximity.QWERTZ,
        alphabet = Alphabet.ROMAJI,
        detail = "nihongo → にほんご → 日本語",
    )

    val JAPANESE_FLICK = LanguageProfile(
        id = "ja_flick",
        spaceLabel = "空白",
        displayName = "Japanese (12-key flick)",
        dictionaries = emptyList(),
        proximity = KeyProximity.QWERTZ,
        alphabet = Alphabet.FLICK12,
        detail = "あかさたな · flick for い う え お",
        learnedId = "ja",
    )

    val all: List<LanguageProfile> = listOf(GERMAN_ENGLISH, RUSSIAN, JAPANESE, JAPANESE_FLICK)

    fun byId(id: String?): LanguageProfile = all.firstOrNull { it.id == id } ?: GERMAN_ENGLISH
    fun byIdOrNull(id: String?): LanguageProfile? = all.firstOrNull { it.id == id }

    /** Languages turned on in settings, in a fixed order. German + English is always on. */
    fun enabled(prefs: Prefs): List<LanguageProfile> {
        val ids = prefs.enabledLanguages
        return all.filter { it == GERMAN_ENGLISH || it.id in ids }
    }

    /** The language the keyboard is typing in now (falls back if it was turned off). */
    fun current(prefs: Prefs): LanguageProfile {
        val list = enabled(prefs)
        return list.firstOrNull { it.id == prefs.currentLanguage } ?: list.first()
    }

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "tasto-dict").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val dicts = HashMap<String, BaseDictionary>()
    private val engines = HashMap<String, SuggestionEngine>()
    @Volatile private var kanaDict: KanaDictionary? = null
    @Volatile private var kanaFreq: Map<String, Int>? = null
    private var kanaConverter: KanaConverter? = null

    /** The Japanese converter, loading the kana-kanji dictionary in the background the first time. */
    fun kana(context: Context, onReady: (KanaConverter) -> Unit) {
        kanaConverter?.let { onReady(it); return }
        val app = context.applicationContext
        loader.execute {
            val loaded = loadSafely {
                val dict = kanaDict ?: app.assets.open("dict/ja.txt").use { KanaDictionary.load(it) }.also { kanaDict = it }
                dict to (kanaFreq ?: app.assets.open("dict/ja_freq.txt").use { KanaDictionary.loadFrequencies(it) }.also { kanaFreq = it })
            } ?: return@execute
            val (dict, freq) = loaded
            main.post {
                val c = kanaConverter
                    ?: KanaConverter(dict, LearnedStore.get(app).cache(JAPANESE.learnedId), freq).also { kanaConverter = it }
                onReady(c)
            }
        }
    }

    /** The engine for [profile], loading dictionaries in the background the first time. */
    fun engine(context: Context, profile: LanguageProfile, onReady: (SuggestionEngine) -> Unit) {
        engines[profile.id]?.let { onReady(it); return }
        val app = context.applicationContext
        loader.execute {
            val loaded = loadSafely {
                profile.dictionaries.map { lang ->
                    synchronized(dicts) { dicts[lang] } ?: app.assets.open("dict/$lang.txt").use {
                        BaseDictionary.load(lang, it)
                    }.also { synchronized(dicts) { dicts[lang] = it } }
                }
            } ?: return@execute
            main.post {
                val engine = engines[profile.id]
                    ?: SuggestionEngine(loaded, LearnedStore.get(app).cache(profile.learnedId), profile.proximity)
                        .also { engines[profile.id] = it }
                onReady(engine)
            }
        }
    }

    /** Loads on the background thread; a failure leaves that language without suggestions instead of crashing the keyboard. */
    // shortcut: on running out of memory the other languages' dictionaries stay loaded; free them if low-memory phones report missing suggestions.
    internal fun <T> loadSafely(load: () -> T): T? = try {
        load()
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }

    /** Tests: drop engines bound to a previous test's learned-words store. */
    internal fun resetForTest() {
        engines.clear()
        kanaConverter = null
    }
}
