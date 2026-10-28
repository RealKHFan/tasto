package io.github.realkhfan.tasto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Clip(val text: String, val time: Long, val pinned: Boolean)

/**
 * Recently copied text. Unpinned items disappear after an hour; pinned ones stay until
 * removed. Kept in app-private storage on this device only.
 */
class ClipboardStore private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tasto_clipboard", Context.MODE_PRIVATE)
    private val items = ArrayList<Clip>()

    init {
        val raw = sp.getString("items", null)
        if (raw != null) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(Clip(o.getString("t"), o.getLong("at"), o.optBoolean("p")))
                }
            }
        }
    }

    fun items(now: Long = System.currentTimeMillis()): List<Clip> {
        val before = items.size
        items.removeAll { !it.pinned && now - it.time > MAX_AGE_MS }
        if (items.size != before) save()
        return items.toList()
    }

    fun add(text: String, now: Long = System.currentTimeMillis()) {
        val t = text.take(MAX_CHARS)
        if (t.isBlank()) return
        val existing = items.firstOrNull { it.text == t }
        items.removeAll { it.text == t }
        items.add(0, Clip(t, now, existing?.pinned == true))
        var unpinned = 0
        val iter = items.listIterator()
        while (iter.hasNext()) {
            val c = iter.next()
            if (!c.pinned && ++unpinned > MAX_UNPINNED) iter.remove()
        }
        save()
    }

    fun togglePin(text: String) {
        val i = items.indexOfFirst { it.text == text }
        if (i >= 0) {
            items[i] = items[i].copy(pinned = !items[i].pinned)
            save()
        }
    }

    fun remove(text: String) {
        items.removeAll { it.text == text }
        save()
    }

    fun clearUnpinned() {
        items.removeAll { !it.pinned }
        save()
    }

    fun clearAll() {
        items.clear()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        for (c in items) arr.put(JSONObject().put("t", c.text).put("at", c.time).put("p", c.pinned))
        sp.edit().putString("items", arr.toString()).apply()
    }

    companion object {
        const val MAX_AGE_MS = 60 * 60 * 1000L
        const val MAX_UNPINNED = 20
        const val MAX_CHARS = 5000

        @Volatile private var instance: ClipboardStore? = null

        fun get(context: Context): ClipboardStore =
            instance ?: synchronized(this) { instance ?: ClipboardStore(context).also { instance = it } }

        internal fun resetForTest() {
            instance = null
        }
    }
}
