package io.github.realkhfan.tasto

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.engine.LearnedWord
import io.github.realkhfan.tasto.ui.Usage
import io.github.realkhfan.tasto.ui.WordFilter
import io.github.realkhfan.tasto.ui.WordSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoresTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

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

    @Test fun learnedWordsSurviveRestart() {
        val s = loadedStore()
        s.learn("de_en", "Moinsen", "moinsen", null, 1, false)
        s.learn("de_en", "Moinsen", "moinsen", null, 1, false)
        s.learn("de_en", "Leute", "Leute", "moinsen", 1, true)
        s.flushForTest()
        LearnedStore.resetForTest()
        val again = loadedStore()
        assertEquals(2, again.cache("de_en").get("moinsen")?.count)
        assertEquals(mapOf("leute" to 1), again.cache("de_en").nexts("moinsen"))
    }

    @Test fun deletingRemovesWordAndItsPhrases() {
        val s = loadedStore()
        s.learn("de_en", "Moinsen", "moinsen", null, 1, false)
        s.learn("de_en", "Leute", "Leute", "moinsen", 1, true)
        s.delete("de_en", listOf("moinsen"))
        assertNull(s.cache("de_en").get("moinsen"))
        assertTrue(s.cache("de_en").nexts("moinsen").isEmpty())
        s.flushForTest()
        LearnedStore.resetForTest()
        val again = loadedStore()
        assertNull(again.cache("de_en").get("moinsen"))
        assertEquals(1, again.cache("de_en").get("leute")?.count)
        again.deleteAll()
        again.flushForTest()
        LearnedStore.resetForTest()
        assertTrue(loadedStore().allWords().isEmpty())
    }

    @Test fun clipboardExpiresUnlessPinned() {
        val c = ClipboardStore.get(ctx)
        c.clearAll()
        c.add("alt", now = 0L)
        c.add("gepinnt", now = 0L)
        c.togglePin("gepinnt")
        c.add("neu", now = ClipboardStore.MAX_AGE_MS)
        val items = c.items(now = ClipboardStore.MAX_AGE_MS + 1)
        assertEquals(listOf("neu", "gepinnt"), items.map { it.text })
        // Survives a restart.
        ClipboardStore.resetForTest()
        assertEquals(listOf("neu", "gepinnt"), ClipboardStore.get(ctx).items(now = ClipboardStore.MAX_AGE_MS + 1).map { it.text })
    }

    @Test fun clipboardCapsUnpinnedItems() {
        val c = ClipboardStore.get(ctx)
        c.clearAll()
        repeat(30) { c.add("item $it", now = 1000L) }
        assertEquals(ClipboardStore.MAX_UNPINNED, c.items(now = 1000L).size)
        assertEquals("item 29", c.items(now = 1000L).first().text)
    }

    @Test fun wordFilterCategories() {
        fun w(word: String, count: Int, last: Long, inDict: Boolean) = "de_en" to LearnedWord(word.lowercase(), word, count, 0, last, inDict)
        val all = listOf(w("oft", 25, 3, true), w("Moinsen", 4, 9, false), w("selten", 1, 5, true), w("Tasto", 2, 7, false))
        assertEquals(listOf("oft"), WordFilter.apply(all, "", Usage.OFTEN, false, WordSort.MOST_USED).map { it.word.word })
        assertEquals(listOf("Moinsen"), WordFilter.apply(all, "", Usage.SOMETIMES, false, WordSort.MOST_USED).map { it.word.word })
        assertEquals(listOf("Tasto", "selten"), WordFilter.apply(all, "", Usage.RARELY, false, WordSort.MOST_USED).map { it.word.word })
        assertEquals(listOf("Moinsen", "Tasto"), WordFilter.apply(all, "", Usage.ALL, true, WordSort.RECENT).map { it.word.word })
        assertEquals(listOf("Moinsen", "oft", "selten", "Tasto"), WordFilter.apply(all, "", Usage.ALL, false, WordSort.AZ).map { it.word.word })
        assertEquals(listOf("Moinsen"), WordFilter.apply(all, "MOIN", Usage.ALL, false, WordSort.AZ).map { it.word.word })
        assertEquals(mapOf(Usage.ALL to 4, Usage.OFTEN to 1, Usage.SOMETIMES to 1, Usage.RARELY to 2), WordFilter.counts(all))
    }
}
