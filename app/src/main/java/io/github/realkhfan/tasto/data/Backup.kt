package io.github.realkhfan.tasto.data

import io.github.realkhfan.tasto.engine.LearnedWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A learned phrase: [next] came after [prev] ("^" = start of a sentence, "@reading" = a Japanese conversion). */
data class LearnedPair(val lang: String, val prev: String, val next: String, val count: Int)

/** What a backup holds: everything Tasto learned, its settings and the pinned clips. */
class BackupData(
    val words: List<Pair<String, LearnedWord>>,
    val pairs: List<LearnedPair>,
    val settings: Map<String, Any>,
    val pinnedClips: List<String>,
)

class BackupException(message: String) : Exception(message)

/**
 * Tasto's backup file, for moving to another phone (Android's own phone transfer skips Tasto on
 * purpose). The data is gzip'd JSON, encrypted with AES-256-GCM under a key made from the
 * user's password with PBKDF2-SHA256. Layout: "TASTOBK1", iterations (4 bytes), salt (16),
 * IV (12), then the ciphertext; the header is authenticated too.
 *
 * Everything read back is checked: the file may have been changed outside Tasto.
 */
object Backup {
    private val MAGIC = "TASTOBK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val HEADER = 8 + 4 + 16 + 12
    const val MIN_PASSWORD = 8
    const val MAX_FILE_BYTES = 64 shl 20
    private const val MAX_JSON_BYTES = 256 shl 20
    private const val MAX_WORD = 64
    private const val MAX_PREV = 80
    private const val MAX_CLIPS = 500
    private val LANG = Regex("[a-z_]{1,16}")

    /** Settings that move with you; the incognito switch and Android's 🌐 list state stay per phone. */
    private val BOOLEAN_SETTINGS = listOf(
        Prefs.KEY_HINTS, Prefs.KEY_POPUP, Prefs.NUMBER_ROW, Prefs.NUMBER_ROW_PASSWORDS, Prefs.COLORED_ENTER,
        Prefs.TOOLBAR_EXPANDED, Prefs.AUTO_SPACE, Prefs.AUTOCORRECT, Prefs.AUTO_CAP, Prefs.DOUBLE_SPACE,
        Prefs.SUGGESTIONS, Prefs.VIBRATE, Prefs.SOUND, Prefs.LEARNING, Prefs.AUTO_INCOGNITO, Prefs.CLIPBOARD_HISTORY,
    )
    private val STRING_SETTINGS = listOf(
        Prefs.THEME, Prefs.ACCENT, Prefs.KEY_HEIGHT, Prefs.EMOJI_RECENTS, Prefs.ENABLED_LANGUAGES,
        Prefs.KNOWN_LANGUAGES, Prefs.CURRENT_LANGUAGE,
    )

    // ---- file -----------------------------------------------------------------------------

    fun encrypt(data: BackupData, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).array()
        val plain = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(toJson(data).toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(file: ByteArray, password: CharArray): BackupData {
        if (file.size <= HEADER || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This isn't a Tasto backup file.")
        }
        val buf = ByteBuffer.wrap(file, MAGIC.size, HEADER - MAGIC.size)
        val iterations = buf.int
        // A changed file could ask for absurd work; Tasto itself only writes ITERATIONS.
        if (iterations !in 100_000..10_000_000) throw BackupException("This backup file is damaged.")
        val salt = ByteArray(16).also { buf.get(it) }
        val iv = ByteArray(12).also { buf.get(it) }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(file, 0, HEADER)
            cipher.doFinal(file, HEADER, file.size - HEADER)
        } catch (_: AEADBadTagException) {
            throw BackupException("Wrong password, or the file was changed.")
        }
        val json = try {
            JSONObject(gunzip(plain).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw BackupException("This backup file is damaged.")
        }
        return fromJson(json)
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_JSON_BYTES) throw BackupException("This backup file is too big.")
            }
        }
        return out.toByteArray()
    }

    // ---- JSON -----------------------------------------------------------------------------

    fun toJson(data: BackupData): JSONObject {
        val words = JSONArray()
        for ((lang, w) in data.words) {
            words.put(
                JSONObject().put("lang", lang).put("key", w.key).put("word", w.word).put("count", w.count)
                    .put("first", w.firstUsed).put("last", w.lastUsed).put("dict", w.inDictionary),
            )
        }
        val pairs = JSONArray()
        for (p in data.pairs) pairs.put(JSONObject().put("lang", p.lang).put("prev", p.prev).put("next", p.next).put("count", p.count))
        val settings = JSONObject()
        for ((k, v) in data.settings) settings.put(k, v)
        return JSONObject()
            .put("app", "tasto").put("version", 1)
            .put("words", words).put("pairs", pairs).put("settings", settings)
            .put("clips", JSONArray(data.pinnedClips))
    }

    /** Reads a backup, skipping anything that doesn't look like what Tasto writes. */
    fun fromJson(json: JSONObject): BackupData {
        if (json.optString("app") != "tasto") throw BackupException("This isn't a Tasto backup file.")
        if (json.optInt("version") != 1) throw BackupException("This backup is from a newer Tasto. Update Tasto first.")
        val words = ArrayList<Pair<String, LearnedWord>>()
        json.optJSONArray("words")?.forEachObject { o ->
            val lang = o.optString("lang")
            val key = o.optString("key")
            val word = o.optString("word")
            if (!LANG.matches(lang) || key.length !in 1..MAX_WORD || word.length !in 1..MAX_WORD) return@forEachObject
            val first = o.optLong("first").coerceAtLeast(0)
            words.add(
                lang to LearnedWord(
                    key, word, o.optInt("count", 1).coerceIn(1, 10_000_000),
                    first, o.optLong("last").coerceAtLeast(first), o.optBoolean("dict"),
                ),
            )
        }
        val pairs = ArrayList<LearnedPair>()
        json.optJSONArray("pairs")?.forEachObject { o ->
            val p = LearnedPair(o.optString("lang"), o.optString("prev"), o.optString("next"), o.optInt("count", 1).coerceIn(1, 10_000_000))
            if (LANG.matches(p.lang) && p.prev.length in 1..MAX_PREV && p.next.length in 1..MAX_WORD) pairs.add(p)
        }
        val settings = HashMap<String, Any>()
        json.optJSONObject("settings")?.let { s ->
            for (k in BOOLEAN_SETTINGS) (s.opt(k) as? Boolean)?.let { settings[k] = it }
            for (k in STRING_SETTINGS) (s.opt(k) as? String)?.takeIf { it.length <= 10_000 }?.let { settings[k] = it }
        }
        val clips = ArrayList<String>()
        json.optJSONArray("clips")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_CLIPS)) {
                (arr.opt(i) as? String)?.takeIf { it.isNotBlank() }?.let { clips.add(it.take(ClipboardStore.MAX_CHARS)) }
            }
        }
        return BackupData(words, pairs, settings, clips)
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    // ---- settings -------------------------------------------------------------------------

    /** The settings you changed (defaults stay defaults on the new phone). */
    fun collectSettings(prefs: Prefs): Map<String, Any> {
        val all = prefs.sp.all
        return (BOOLEAN_SETTINGS + STRING_SETTINGS).mapNotNull { k -> all[k]?.let { k to it } }.toMap()
    }

    fun applySettings(prefs: Prefs, settings: Map<String, Any>) {
        val e = prefs.sp.edit()
        for (k in BOOLEAN_SETTINGS) (settings[k] as? Boolean)?.let { e.putBoolean(k, it) }
        for (k in STRING_SETTINGS) (settings[k] as? String)?.let { e.putString(k, it) }
        e.apply()
    }
}
