package io.github.realkhfan.tasto.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.realkhfan.tasto.BuildConfig
import io.github.realkhfan.tasto.R
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.Languages
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.ime.FieldKind
import io.github.realkhfan.tasto.keyboard.Key
import io.github.realkhfan.tasto.keyboard.KeyboardTheme
import io.github.realkhfan.tasto.keyboard.KeyboardView
import io.github.realkhfan.tasto.keyboard.Layouts
import io.github.realkhfan.tasto.keyboard.Page

// ---- shared building blocks ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in options) {
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun NavRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavIcon(v: ImageVector) = Icon(v, contentDescription = null, tint = MaterialTheme.colorScheme.primary)

/** Re-reads the learned words whenever they change. */
@Composable
fun rememberLearnedVersion(): Int {
    val context = LocalContext.current
    val store = remember { LearnedStore.get(context) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val l: () -> Unit = { version++ }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    return version
}

// ---- home ------------------------------------------------------------------------------

@Composable
fun HomeScreen(
    model: SettingsModel,
    resumeTick: Int,
    isEnabled: () -> Boolean,
    isSelected: () -> Boolean,
    open: (Screen) -> Unit,
) {
    val context = LocalContext.current
    val enabled = remember(resumeTick) { isEnabled() }
    val selected = remember(resumeTick) { isSelected() }
    val learnedVersion = rememberLearnedVersion()
    val wordCount = remember(learnedVersion, resumeTick) { LearnedStore.get(context).allWords().size }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Tasto", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Private keyboard · nothing leaves your phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!enabled || !selected) {
                SetupCard(enabled, selected)
                Spacer(Modifier.height(12.dp))
            }
            var tryText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = tryText,
                onValueChange = { tryText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Try Tasto here") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                ),
                minLines = 2,
                shape = RoundedCornerShape(14.dp),
            )
            if (model.manualIncognito) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Incognito is on. Tasto isn't learning anything.", Modifier.weight(1f))
                        TextButton(onClick = { model.updateManualIncognito(false) }) { Text("Turn off") }
                    }
                }
            }
            SectionTitle("Settings")
            Group {
                NavRow({ NavIcon(Icons.Filled.Star) }, "Appearance", "Theme, colour, key size") { open(Screen.APPEARANCE) }
                NavRow({ NavIcon(Icons.Filled.Edit) }, "Typing", "Autocorrect, capitals, vibration") { open(Screen.TYPING) }
                NavRow({ NavIcon(Icons.Filled.Lock) }, "Privacy & incognito", "Learning, incognito, clipboard") { open(Screen.PRIVACY) }
                NavRow(
                    { NavIcon(Icons.AutoMirrored.Filled.List) },
                    "Learned words",
                    if (wordCount == 1) "1 word" else "$wordCount words",
                ) { open(Screen.WORDS) }
                NavRow(
                    { Text("Aa", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                    "Languages",
                    languagesSummary(model.enabledLanguages),
                ) { open(Screen.LANGUAGES) }
                NavRow({ NavIcon(Icons.Filled.Info) }, "About", "Version ${BuildConfig.VERSION_NAME} · GPL-3.0") { open(Screen.ABOUT) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SetupCard(enabled: Boolean, selected: Boolean) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Set up Tasto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            SetupStep(1, "Turn on Tasto in your keyboard list", enabled) {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Spacer(Modifier.height(10.dp))
            SetupStep(2, "Switch to Tasto", selected, enabledButton = enabled) {
                context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Android shows a standard warning that keyboards can see what you type. Tasto has no internet access, so it can't send anything anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupStep(n: Int, text: String, done: Boolean, enabledButton: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text("$n", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f))
        if (!done) {
            Button(onClick = onClick, enabled = enabledButton) { Text(if (n == 1) "Open" else "Switch") }
        }
    }
}

// ---- appearance ------------------------------------------------------------------------

@Composable
fun AppearanceScreen(model: SettingsModel, onBack: () -> Unit) {
    SubScreen("Appearance", onBack) {
        KeyboardPreview(model)
        SectionTitle("Look")
        Group {
            ChoiceRow("Theme", listOf("system" to "System", "dark" to "Dark", "light" to "Light"), model.theme, model::updateTheme)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((name, color) in KeyboardTheme.ACCENTS) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(color)).clickable { model.updateAccent(name) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (model.accent == name) Icon(Icons.Filled.Check, contentDescription = name, tint = Color.White)
                        }
                    }
                }
            }
            ChoiceRow("Key size", listOf("small" to "Small", "normal" to "Normal", "large" to "Large"), model.keyHeight, model::updateKeyHeight)
            SwitchRow("Coloured Enter key", "Enter in your accent colour instead of grey", model.coloredEnter, model::updateColoredEnter)
        }
        SectionTitle("Keys")
        Group {
            SwitchRow("Number row", "Digits 1–0 above the letters", model.numberRow, model::updateNumberRow)
            SwitchRow(
                "Numbers in password fields",
                "Always show 1–0 above the letters when you type a password",
                model.numberRowPasswords,
                model::updateNumberRowPasswords,
            )
            SwitchRow("Digit hints", "Small 1–0 on the top letter row (long-press for the digit)", model.keyHints, model::updateKeyHints)
            SwitchRow("Key preview", "Show the letter above your finger", model.keyPopup, model::updateKeyPopup)
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** A live, non-interactive Tasto keyboard showing the current look. */
@Composable
private fun KeyboardPreview(model: SettingsModel) {
    val theme = if (when (model.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }) {
        KeyboardTheme.dark(model.accentColor)
    } else {
        KeyboardTheme.light(model.accentColor)
    }
    val height = when (model.keyHeight) { "small" -> 43f; "large" -> 55f; else -> 49f }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        AndroidView(
            factory = { ctx -> KeyboardView(ctx, PreviewListener) },
            update = { v ->
                v.theme = theme
                v.keyHeightDp = height
                v.showHints = model.keyHints
                v.spaceLabel = Languages.GERMAN_ENGLISH.spaceLabel
                v.layout = Layouts.letters(FieldKind.TEXT, model.numberRow, model.keyHints)
                v.coloredEnter = model.coloredEnter
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private object PreviewListener : KeyboardView.Listener {
    override fun onKeyText(text: String) = Unit
    override fun onBackspace() = Unit
    override fun onDeleteWord() = Unit
    override fun onSpace() = Unit
    override fun onEnter() = Unit
    override fun onShift() = Unit
    override fun onShiftLongPress() = Unit
    override fun onPage(page: Page) = Unit
    override fun onEmoji() = Unit
    override fun onCursorMove(steps: Int) = Unit
    override fun onLanguageSwipe(direction: Int) = Unit
    override fun onKeyFeedback(key: Key) = Unit
}

// ---- typing ----------------------------------------------------------------------------

@Composable
fun TypingScreen(model: SettingsModel, onBack: () -> Unit) {
    SubScreen("Typing", onBack) {
        SectionTitle("Corrections")
        Group {
            SwitchRow("Suggestions", "Show word suggestions above the keys", model.suggestions, model::updateSuggestions)
            SwitchRow("Autocorrect", "Fix typos when you press space. Backspace right after undoes it.", model.autocorrect, model::updateAutocorrect)
            SwitchRow("Auto-capitalisation", "Capital letter at the start of sentences", model.autoCap, model::updateAutoCap)
            SwitchRow("Double-space full stop", "Tap space twice for “. ”", model.doubleSpace, model::updateDoubleSpace)
            SwitchRow(
                "Space after punctuation",
                "Adds a space after . , ! ? : ; when they follow a word",
                model.autoSpace,
                model::updateAutoSpace,
            )
        }
        SectionTitle("Feedback")
        Group {
            SwitchRow("Vibrate on key press", null, model.vibrate, model::updateVibrate)
            SwitchRow("Sound on key press", null, model.sound, model::updateSound)
        }
        SectionTitle("Gestures")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GestureTip("Long-press a key", "Accents, umlauts and digits (q = 1 … p = 0)")
                GestureTip("Swipe the space bar", "Next or previous language")
                GestureTip("Hold the space bar, then slide", "Move the cursor")
                GestureTip("Hold backspace", "Delete whole words, one after another")
                GestureTip("Double-tap shift", "Caps lock")
                GestureTip("Long-press a suggestion", "Forget a learned word")
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun GestureTip(gesture: String, effect: String) {
    Column {
        Text(gesture, fontWeight = FontWeight.SemiBold)
        Text(effect, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- privacy ---------------------------------------------------------------------------

@Composable
fun PrivacyScreen(model: SettingsModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var confirmWipe by remember { mutableStateOf(false) }
    var clipCleared by remember { mutableStateOf(false) }
    SubScreen("Privacy & incognito", onBack) {
        Spacer(Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                "Tasto has no internet permission. Learned words, settings and clipboard history stay on this phone and are never backed up to the cloud.",
                Modifier.padding(16.dp),
            )
        }
        SectionTitle("Remembering")
        Group {
            SwitchRow("Learn from my typing", "Remember your words to suggest them later", model.learning, model::updateLearning)
        }
        SectionTitle("Incognito")
        Group {
            SwitchRow(
                "Automatic incognito",
                "In private browser tabs (Vanadium, Firefox…) and apps that ask for it. Password fields are always private.",
                model.autoIncognito,
                model::updateAutoIncognito,
            )
            SwitchRow(
                "Incognito now",
                "Stops learning everywhere until you turn it off. Same as the mask button in the keyboard.",
                model.manualIncognito,
                model::updateManualIncognito,
            )
        }
        SectionTitle("Clipboard")
        Group {
            SwitchRow("Clipboard history", "Keep copied text for one hour (pinned items stay)", model.clipboardHistory, model::updateClipboardHistory)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { ClipboardStore.get(context).clearAll(); clipCleared = true }) {
                    Text(if (clipCleared) "Clipboard history cleared" else "Clear clipboard history")
                }
            }
        }
        SectionTitle("Your data")
        Group {
            Row(Modifier.padding(16.dp)) {
                FilledTonalButton(onClick = { confirmWipe = true }) { Text("Delete all learned words") }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all learned words?") },
            text = { Text("Tasto forgets every word and phrase it learned from you. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { LearnedStore.get(context).deleteAll(); confirmWipe = false }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

// ---- languages & about -----------------------------------------------------------------

/** "German + English, Russian, Japanese" for the home screen. */
private fun languagesSummary(enabled: Set<String>): String {
    val names = mutableListOf("German + English")
    if (Languages.RUSSIAN.id in enabled) names.add("Russian")
    if (Languages.JAPANESE.id in enabled || Languages.JAPANESE_FLICK.id in enabled) names.add("Japanese")
    return names.joinToString(", ")
}

@Composable
fun LanguagesScreen(model: SettingsModel, onBack: () -> Unit) {
    SubScreen("Languages", onBack) {
        SectionTitle("Keyboard languages")
        Group {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Languages.GERMAN_ENGLISH.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "One layout, suggestions in both languages at once. Tasto leans towards the language you're writing in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.Check, contentDescription = "Always on", tint = MaterialTheme.colorScheme.primary)
            }
            SwitchRow(
                Languages.RUSSIAN.displayName,
                "Russian layout with Russian suggestions and its own learned words",
                Languages.RUSSIAN.id in model.enabledLanguages,
            ) { model.setLanguageEnabled(Languages.RUSSIAN.id, it) }
            SwitchRow(
                Languages.JAPANESE.displayName,
                "Type romaji on QWERTZ (nihongo → にほんご). Space converts to kanji, Enter accepts.",
                Languages.JAPANESE.id in model.enabledLanguages,
            ) { model.setLanguageEnabled(Languages.JAPANESE.id, it) }
            SwitchRow(
                Languages.JAPANESE_FLICK.displayName,
                "Kana keys: tap for あ, flick left/up/right/down for い う え お. ゛゜小 makes が, ぱ, っ.",
                Languages.JAPANESE_FLICK.id in model.enabledLanguages,
            ) { model.setLanguageEnabled(Languages.JAPANESE_FLICK.id, it) }
        }
        if (model.enabledLanguages.isNotEmpty()) {
            SectionTitle("Switching")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GestureTip("Swipe the space bar left or right", "Previous or next language (hold, then slide, to move the cursor)")
                    GestureTip("🌐 in the toolbar", "List of your languages — tap one (the round button above the keys opens the toolbar)")
                    GestureTip("ABC on the 12-key layout", "Straight back to German + English; hold it for the list")
                }
                val context = LocalContext.current
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    OutlinedButton(onClick = {
                        val imeId = android.content.ComponentName(context, io.github.realkhfan.tasto.ime.TastoIme::class.java).flattenToShortString()
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
                                    .putExtra(Settings.EXTRA_INPUT_METHOD_ID, imeId)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Show languages in Android's 🌐 list") }
                }
            }
        }
        if (Languages.JAPANESE.id in model.enabledLanguages || Languages.JAPANESE_FLICK.id in model.enabledLanguages) {
            SectionTitle("Japanese")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GestureTip("Tap a word in the bar", "Puts in that conversion; the rest stays to convert next")
                    GestureTip("Space (変換)", "Converts, and again for the next candidate")
                    GestureTip("Enter (確定)", "Accepts what is underlined")
                    GestureTip("Backspace while converting", "Back to the hiragana")
                    GestureTip("Hold a kana key", "Shows all five characters you can flick to")
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SubScreen("About", onBack) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Tasto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("by RealKHFan, with help from Claude", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle("License")
        Text("Tasto is free software under the GNU General Public License v3.0.")
        SectionTitle("Word lists")
        Text(
            "German and Russian: Leipzig Corpora Collection word lists (CC BY 4.0), via the HeliBoard aosp-dictionaries project.\n" +
                "English: Android Open Source Project word list (Apache 2.0), via HeliBoard aosp-dictionaries.\n" +
                "Conversational frequencies: OpenSubtitles 2018 lists by Hermit Dave (CC BY-SA 4.0).\n" +
                "Japanese: SKK-JISYO.L by the SKK Development Team (GPL-2.0-or-later); word frequencies from the OpenSubtitles lists above.\n" +
                "Emoji: Unicode emoji data (Unicode License v3).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))
    }
}
