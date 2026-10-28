package io.github.realkhfan.tasto.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.realkhfan.tasto.data.Languages
import io.github.realkhfan.tasto.data.LearnedStore

/**
 * Everything Tasto learned from you, with how often you used each word. Filter by usage,
 * search, sort, delete single words, several at once, or everything.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LearnedWordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LearnedStore.get(context) }
    // Your learned words are private: no screenshots, screen recording or recents preview here.
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val version = rememberLearnedVersion()
    val all = remember(version) { store.allWords() }
    var query by rememberSaveable { mutableStateOf("") }
    var usage by rememberSaveable { mutableStateOf(Usage.ALL) }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(WordSort.MOST_USED) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() } // "lang|key"
    val rows = remember(all, query, usage, newOnly, sort, language) { WordFilter.apply(all, query, usage, newOnly, sort, language) }
    val languagesWithWords = remember(all) { all.map { it.first }.distinct().sortedBy { id -> Languages.all.indexOfFirst { it.id == id } } }
    val counts = remember(all) { WordFilter.counts(all) }
    val selecting = selected.isNotEmpty()

    fun deleteSelected() {
        selected.groupBy({ it.substringBefore('|') }, { it.substringAfter('|') }).forEach { (lang, keys) -> store.delete(lang, keys) }
        selected.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "Learned words") },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) selected.clear() else onBack() }) {
                        if (selecting) Icon(Icons.Filled.Close, "Cancel selection") else Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { deleteSelected() }) { Icon(Icons.Filled.Delete, "Delete selected") }
                    } else {
                        IconButton(onClick = { adding = !adding }) { Icon(Icons.Filled.Add, "Add a word") }
                        IconButton(onClick = { confirmAll = true }, enabled = all.isNotEmpty()) { Icon(Icons.Filled.Delete, "Delete all") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
                placeholder = { Text("Search learned words") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (adding) {
                var newWord by remember { mutableStateOf("") }
                val valid = newWord.isNotEmpty() && newWord.none { it.isWhitespace() }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWord,
                        onValueChange = { newWord = it.trim() },
                        placeholder = { Text("New word") },
                        supportingText = { Text("Tasto will suggest it and never autocorrect it.") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).testTag("newWord"),
                    )
                    TextButton(
                        enabled = valid,
                        onClick = {
                            // Added to the language picked above, or the one you type in now. Japanese
                            // words need a reading to be suggested, so those go to German + English.
                            val lang = (language ?: Languages.current(io.github.realkhfan.tasto.data.Prefs(context)).learnedId)
                                .takeIf { it != Languages.JAPANESE.learnedId } ?: Languages.GERMAN_ENGLISH.id
                            store.addWord(lang, newWord, inDictionary = false)
                            newWord = ""
                            adding = false
                        },
                    ) { Text("Add") }
                    IconButton(onClick = { adding = false }) { Icon(Icons.Filled.Close, "Cancel adding") }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (u in Usage.entries) {
                    FilterChip(
                        selected = usage == u,
                        onClick = { usage = u },
                        label = { Text("${u.label} · ${counts[u] ?: 0}") },
                    )
                }
                FilterChip(selected = newOnly, onClick = { newOnly = !newOnly }, label = { Text("New words only") })
            }
            if (languagesWithWords.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = language == null, onClick = { language = null }, label = { Text("All languages") })
                    for (id in languagesWithWords) {
                        val p = Languages.byId(id)
                        FilterChip(selected = language == id, onClick = { language = id }, label = { Text(p.spaceLabel) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (usage == Usage.ALL) "${rows.size} words" else "${rows.size} words · ${usage.range}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sort.label) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        for (s in WordSort.entries) {
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false })
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            all.isEmpty() && !store.loaded -> "Loading…"
                            all.isEmpty() -> "Nothing learned yet.\nWords you type with Tasto show up here."
                            else -> "No words match."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.lang + "|" + it.word.key }) { row ->
                    val id = row.lang + "|" + row.word.key
                    val isSelected = id in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (selecting) { if (isSelected) selected.remove(id) else selected.add(id) } },
                                onLongClick = { if (!isSelected) selected.add(id) },
                            )
                            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            Checkbox(checked = isSelected, onCheckedChange = { if (it) selected.add(id) else selected.remove(id) })
                            Spacer(Modifier.width(4.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(row.word.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            val now = System.currentTimeMillis()
                            val when_ = if (now - row.word.lastUsed < DateUtils.MINUTE_IN_MILLIS) {
                                "just now"
                            } else {
                                DateUtils.getRelativeTimeSpanString(row.word.lastUsed, now, DateUtils.MINUTE_IN_MILLIS)
                            }
                            val lang = Languages.byId(row.lang).spaceLabel
                            val extra = if (row.word.inDictionary) "" else " · new word"
                            Text(
                                "Used ${row.word.count}× · $when_ · $lang$extra",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!selecting) {
                            IconButton(onClick = { store.delete(row.lang, listOf(row.word.key)) }) {
                                Icon(Icons.Filled.Delete, "Delete ${row.word.word}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all learned words?") },
            text = { Text("Tasto forgets all ${all.size} words and the phrases it learned. This can't be undone.") },
            confirmButton = { TextButton(onClick = { store.deleteAll(); confirmAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}
