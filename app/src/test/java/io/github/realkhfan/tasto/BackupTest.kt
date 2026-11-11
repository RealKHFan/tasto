package io.github.realkhfan.tasto

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.data.Backup
import io.github.realkhfan.tasto.data.BackupData
import io.github.realkhfan.tasto.data.BackupException
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.LearnedPair
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import io.github.realkhfan.tasto.engine.LearnedWord
import io.github.realkhfan.tasto.ui.readLimited
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The backup file for moving to a new phone: encryption, checks on what is read back, merging. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val password = "correct horse".toCharArray()

    @Before fun setUp() {
        LearnedStore.resetForTest()
        ClipboardStore.resetForTest()
    }

    private fun loadedStore(): LearnedStore {
        val s = LearnedStore.get(ctx)
        val deadline = System.currentTimeMillis() + 10_000
        while (!s.loaded && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(s.loaded)
        return s
    }

    private fun word(key: String, count: Int, word: String = key) = LearnedWord(key, word, count, 1_000L, 2_000L, true)

    private val sample = BackupData(
        words = listOf("de_en" to word("danke", 41), "de_en" to word("grafeno", 7, "Grafeno"), "ja" to word("日本語", 6)),
        pairs = listOf(LearnedPair("de_en", "^", "danke", 3), LearnedPair("ja", "@にほんご", "日本語", 6)),
        settings = mapOf(Prefs.THEME to "dark", Prefs.NUMBER_ROW to true),
        pinnedClips = listOf("IBAN for the landlord"),
    )

    private fun assertFails(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected: $message")
        } catch (e: BackupException) {
            assertTrue("'${e.message}' should contain '$message'", e.message!!.contains(message))
        }
    }

    @Test fun roundTrip() {
        val file = Backup.encrypt(sample, password)
        // Nothing readable in the file.
        assertFalse(String(file, Charsets.ISO_8859_1).contains("danke"))
        val back = Backup.decrypt(file, password)
        assertEquals(sample.words, back.words)
        assertEquals(sample.pairs, back.pairs)
        assertEquals(sample.settings, back.settings)
        assertEquals(sample.pinnedClips, back.pinnedClips)
    }

    @Test fun wrongPasswordOrChangedFileIsRefused() {
        val file = Backup.encrypt(sample, password)
        assertFails("Wrong password") { Backup.decrypt(file, "wrong password".toCharArray()) }
        val changed = file.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        assertFails("Wrong password") { Backup.decrypt(changed, password) }
        // The header is protected too: a changed salt or IV fails the same way.
        val header = file.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertFails("Wrong password") { Backup.decrypt(header, password) }
    }

    @Test fun otherFilesAreRefused() {
        assertFails("isn't a Tasto backup") { Backup.decrypt("just some text, not a backup at all".toByteArray(), password) }
        assertFails("isn't a Tasto backup") { Backup.decrypt(ByteArray(0), password) }
        // A changed header asking for an absurd amount of work is refused right away.
        val file = Backup.encrypt(sample, password).also { it[8] = 0x7f }
        assertFails("damaged") { Backup.decrypt(file, password) }
    }

    @Test fun badEntriesAreSkipped() {
        val json = Backup.toJson(sample)
        json.getJSONArray("words")
            .put(JSONObject().put("lang", "../etc").put("key", "x").put("word", "x"))
            .put(JSONObject().put("lang", "de_en").put("key", "").put("word", "x"))
            .put(JSONObject().put("lang", "de_en").put("key", "a".repeat(500)).put("word", "a"))
            .put(JSONObject().put("lang", "de_en").put("key", "ok").put("word", "ok").put("count", -5).put("first", -1))
        json.getJSONArray("pairs").put(JSONObject().put("lang", "de_en").put("prev", "").put("next", "x"))
        json.getJSONObject("settings")
            .put("unknown_key", "x").put(Prefs.MANUAL_INCOGNITO, true).put(Prefs.SYSTEM_LANGUAGE, "ru")
            .put(Prefs.VIBRATE, "not a boolean")
        val data = Backup.fromJson(json)
        assertEquals(sample.words.size + 1, data.words.size)
        val ok = data.words.single { it.second.key == "ok" }.second
        assertEquals(1, ok.count)
        assertEquals(0L, ok.firstUsed)
        assertEquals(sample.pairs, data.pairs)
        assertEquals(sample.settings, data.settings)
    }

    @Test fun newerOrForeignJsonIsRefused() {
        assertFails("newer Tasto") { Backup.fromJson(Backup.toJson(sample).put("version", 2)) }
        assertFails("isn't a Tasto backup") { Backup.fromJson(JSONObject().put("app", "other")) }
    }

    @Test fun settingsMoveButIncognitoAndPhoneStateStay() {
        val old = Prefs(ctx)
        old.theme = "light"
        old.accent = "green"
        old.numberRow = true
        old.vibrate = false
        old.enabledLanguages = setOf("ja")
        old.currentLanguage = "ja"
        old.emojiRecents = listOf("😀", "👍")
        old.manualIncognito = true
        old.systemLanguage = "ja"
        val settings = Backup.collectSettings(old)
        assertFalse(Prefs.MANUAL_INCOGNITO in settings)
        assertFalse(Prefs.SYSTEM_LANGUAGE in settings)
        // The new phone: everything back to defaults, then the import.
        old.sp.edit().clear().commit()
        val fresh = Prefs(ctx)
        Backup.applySettings(fresh, Backup.decrypt(Backup.encrypt(BackupData(emptyList(), emptyList(), settings, emptyList()), password), password).settings)
        assertEquals("light", fresh.theme)
        assertEquals("green", fresh.accent)
        assertTrue(fresh.numberRow)
        assertFalse(fresh.vibrate)
        assertEquals(setOf("ja"), fresh.enabledLanguages)
        assertEquals("ja", fresh.currentLanguage)
        assertEquals(listOf("😀", "👍"), fresh.emojiRecents)
        assertFalse(fresh.manualIncognito)
        assertNull(fresh.systemLanguage)
    }

    @Test fun importKeepsTheHigherCountAndCanRunTwice() {
        val s = loadedStore()
        s.learn("de_en", "danke", "danke", null, 1, true) // 1 here, 41 in the backup
        repeat(50) { s.learn("de_en", "morgen", "morgen", null, 1, true) } // only here
        s.importLearned(sample.words, sample.pairs)
        s.importLearned(sample.words, sample.pairs)
        assertEquals(41, s.cache("de_en").get("danke")?.count)
        assertEquals(50, s.cache("de_en").get("morgen")?.count)
        assertEquals("Grafeno", s.cache("de_en").get("grafeno")?.word)
        assertEquals(mapOf("danke" to 3), s.cache("de_en").nexts("^"))
        assertEquals(mapOf("日本語" to 6), s.cache("ja").nexts("@にほんご"))
        // On disk too.
        s.flushForTest()
        LearnedStore.resetForTest()
        val again = loadedStore()
        assertEquals(41, again.cache("de_en").get("danke")?.count)
        assertEquals(6, again.cache("ja").get("日本語")?.count)
        assertEquals(mapOf("日本語" to 6), again.cache("ja").nexts("@にほんご"))
    }

    @Test fun pinnedClipsAreAddedOnce() {
        val clips = ClipboardStore.get(ctx)
        clips.add("already here")
        clips.togglePin("already here")
        clips.importPinned(listOf("already here", "IBAN for the landlord", " "))
        assertEquals(listOf("already here", "IBAN for the landlord"), clips.pinned())
        assertTrue(clips.items().all { it.pinned })
    }

    @Test fun movingToANewPhone() {
        // Old phone.
        val s = loadedStore()
        s.learn("de_en", "moinsen", "moinsen", null, 3, false)
        s.learn("de_en", "leute", "Leute", "moinsen", 1, true)
        ClipboardStore.get(ctx).apply { add("pinned note"); togglePin("pinned note"); add("not pinned") }
        Prefs(ctx).accent = "red"
        val (words, pairs) = s.snapshot()
        val file = Backup.encrypt(BackupData(words, pairs, Backup.collectSettings(Prefs(ctx)), ClipboardStore.get(ctx).pinned()), password)
        // New phone: nothing there yet.
        s.deleteAll()
        ClipboardStore.get(ctx).clearAll()
        Prefs(ctx).sp.edit().clear().commit()
        val data = Backup.decrypt(file, password)
        s.importLearned(data.words, data.pairs)
        ClipboardStore.get(ctx).importPinned(data.pinnedClips)
        Backup.applySettings(Prefs(ctx), data.settings)
        assertEquals(3, s.cache("de_en").get("moinsen")?.count)
        assertEquals(mapOf("leute" to 1), s.cache("de_en").nexts("moinsen"))
        assertEquals(listOf("pinned note"), ClipboardStore.get(ctx).items().map { it.text })
        assertEquals("red", Prefs(ctx).accent)
    }

    @Test fun hugeFilesAreRefusedWhileReading() {
        assertFails("too big") { readLimited(java.io.ByteArrayInputStream(ByteArray(2_000)), max = 1_000) }
        assertEquals(1_000, readLimited(java.io.ByteArrayInputStream(ByteArray(1_000)), max = 1_000).size)
    }
}
