package io.github.realkhfan.tasto.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import io.github.realkhfan.tasto.engine.LearnedCache
import io.github.realkhfan.tasto.engine.LearnedWord
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Everything Tasto learned, stored only on this device (app-private SQLite, encrypted at rest
 * by Android). The keyboard and the learned-words screen share this one instance, so the
 * in-memory caches stay in sync; writes go to disk on a background thread.
 *
 * All public methods must be called on the main thread.
 */
class LearnedStore private constructor(context: Context) {
    private val db = Db(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tasto-db").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val caches = HashMap<String, LearnedCache>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var loaded = false
        private set

    init {
        io.execute {
            val loadedCaches = HashMap<String, LearnedCache>()
            val r = db.readableDatabase
            r.rawQuery("SELECT lang, key, word, count, first_used, last_used, in_dict FROM words", null).use { c ->
                while (c.moveToNext()) {
                    loadedCaches.getOrPut(c.getString(0)) { LearnedCache() }.put(
                        LearnedWord(c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getLong(5), c.getInt(6) != 0),
                    )
                }
            }
            r.rawQuery("SELECT lang, prev, next, count FROM bigrams", null).use { c ->
                while (c.moveToNext()) {
                    loadedCaches.getOrPut(c.getString(0)) { LearnedCache() }.putBigram(c.getString(1), c.getString(2), c.getInt(3))
                }
            }
            main.post {
                // Merge into the existing caches: engines already hold references to them,
                // and words learned while loading must not be lost.
                for ((lang, src) in loadedCaches) {
                    val target = cache(lang)
                    for (w in src.all()) {
                        val cur = target.get(w.key)
                        target.put(if (cur == null) w else cur.copy(count = cur.count + w.count, firstUsed = w.firstUsed))
                    }
                    src.forEachBigram { prev, next, count -> target.putBigram(prev, next, count + target.nexts(prev)[next].let { it ?: 0 }) }
                }
                loaded = true
                notifyChanged()
            }
        }
    }

    fun cache(lang: String): LearnedCache = caches.getOrPut(lang) { LearnedCache() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { for (l in listeners) l() }

    fun learn(lang: String, word: String, display: String, prevKey: String?, increment: Int, inDictionary: Boolean) {
        val now = System.currentTimeMillis()
        val (w, bigramCount) = cache(lang).learn(word, display, prevKey, increment, inDictionary, now)
        io.execute {
            val d = db.writableDatabase
            d.insertWithOnConflict("words", null, ContentValues().apply {
                put("lang", lang); put("key", w.key); put("word", w.word); put("count", w.count)
                put("first_used", w.firstUsed); put("last_used", w.lastUsed); put("in_dict", if (w.inDictionary) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (prevKey != null && bigramCount > 0) {
                d.insertWithOnConflict("bigrams", null, ContentValues().apply {
                    put("lang", lang); put("prev", prevKey); put("next", w.key); put("count", bigramCount); put("last_used", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        notifyChanged()
    }

    /** Adds a word by hand from the learned-words screen. */
    fun addWord(lang: String, word: String, inDictionary: Boolean) {
        learn(lang, word, word, null, 2, inDictionary)
    }

    fun delete(lang: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        cache(lang).remove(keys)
        val list = keys.toList()
        io.execute {
            val d = db.writableDatabase
            d.beginTransaction()
            try {
                for (k in list) {
                    d.delete("words", "lang = ? AND key = ?", arrayOf(lang, k))
                    d.delete("bigrams", "lang = ? AND (prev = ? OR next = ?)", arrayOf(lang, k, k))
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        notifyChanged()
    }

    fun deleteAll() {
        for (c in caches.values) c.clear()
        io.execute {
            val d = db.writableDatabase
            d.delete("words", null, null)
            d.delete("bigrams", null, null)
        }
        notifyChanged()
    }

    /** All learned words with their language profile, for the manager screen. */
    fun allWords(): List<Pair<String, LearnedWord>> = caches.flatMap { (lang, c) -> c.all().map { lang to it } }

    /** Blocks until pending writes are on disk. For tests. */
    fun flushForTest() {
        io.submit {}.get()
    }

    private class Db(context: Context) : SQLiteOpenHelper(context, "learned.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE words (lang TEXT NOT NULL, key TEXT NOT NULL, word TEXT NOT NULL, " +
                    "count INTEGER NOT NULL, first_used INTEGER NOT NULL, last_used INTEGER NOT NULL, " +
                    "in_dict INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (lang, key))",
            )
            db.execSQL(
                "CREATE TABLE bigrams (lang TEXT NOT NULL, prev TEXT NOT NULL, next TEXT NOT NULL, " +
                    "count INTEGER NOT NULL, last_used INTEGER NOT NULL, PRIMARY KEY (lang, prev, next))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        @Volatile private var instance: LearnedStore? = null

        fun get(context: Context): LearnedStore =
            instance ?: synchronized(this) { instance ?: LearnedStore(context).also { instance = it } }

        /** Tests: forget the instance so the next [get] reads the database again. */
        internal fun resetForTest() {
            instance = null
        }
    }
}
