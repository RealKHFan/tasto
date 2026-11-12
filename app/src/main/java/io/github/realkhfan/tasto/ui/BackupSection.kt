package io.github.realkhfan.tasto.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.realkhfan.tasto.data.Backup
import io.github.realkhfan.tasto.data.BackupData
import io.github.realkhfan.tasto.data.BackupException
import io.github.realkhfan.tasto.data.ClipboardStore
import io.github.realkhfan.tasto.data.LearnedStore
import io.github.realkhfan.tasto.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDate

/**
 * Export and import of one encrypted backup file, to move Tasto to a new phone. The file is
 * chosen first (Android's file picker, no storage permission), then the password.
 */
@Composable
fun BackupSection(model: SettingsModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The picked file survives the activity being recreated; the password is never saved.
    var exportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { exportUri = it }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Move Tasto to a new phone: one encrypted file with your learned words, settings and pinned clips. " +
                "Android's own phone transfer leaves Tasto out on purpose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val picking = exportUri != null || importUri != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { status = null; create.launch("tasto-backup-${LocalDate.now()}.tasto") }, enabled = !picking) {
                Text("Export backup")
            }
            OutlinedButton(onClick = { status = null; open.launch(arrayOf("*/*")) }, enabled = !picking) {
                Text("Import backup")
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("backupStatus")) }

        exportUri?.let { uri ->
            PasswordForm(
                title = "Protect the backup",
                text = "You'll need this password to import the backup. It can't be recovered if you forget it.",
                confirm = "Export",
                repeat = true,
                busy = busy,
                error = error,
                onCancel = {
                    // The picker already made an empty file; don't leave it behind.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    exportUri = null
                    error = null
                },
                onConfirm = { password ->
                    busy = true
                    scope.launch {
                        status = try {
                            exportTo(context, model.prefs, uri, password)
                        } catch (_: Exception) {
                            "Couldn't save the backup there. Try another place."
                        } finally {
                            password.fill(' ')
                            busy = false
                            exportUri = null
                        }
                    }
                },
            )
        }

        importUri?.let { uri ->
            PasswordForm(
                title = "Unlock the backup",
                text = "Enter the password you chose when exporting. Words you already have keep the higher count; settings are replaced.",
                confirm = "Import",
                repeat = false,
                busy = busy,
                error = error,
                onCancel = { importUri = null; error = null },
                onConfirm = { password ->
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            status = importFrom(context, model.prefs, uri, password)
                            model.reload()
                            importUri = null
                        } catch (e: BackupException) {
                            error = e.message
                        } catch (_: Exception) {
                            error = "Couldn't read this file."
                        } finally {
                            password.fill(' ')
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

/** Shown under the buttons once a file is picked (inline, like "Add a word"). */
@Composable
private fun PasswordForm(
    title: String,
    text: String,
    confirm: String,
    repeat: Boolean,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = repeat && password.length < Backup.MIN_PASSWORD
    val ok = password.isNotEmpty() && !tooShort && (!repeat || password == again)
    val submit = { if (ok && !busy) onConfirm(password.toCharArray()) }
    // The form opens at the bottom of the screen: focus the password (the keyboard comes up)
    // and keep the whole form, buttons included, in view above the keyboard.
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(keyboardHeight, error) { bring.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(bring).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PasswordField(password, "Password", "password", !busy, last = !repeat, submit, Modifier.focusRequester(focus)) { password = it }
        if (repeat) PasswordField(again, "Repeat password", "password2", !busy, last = true, submit) { again = it }
        val hint = when {
            busy -> null
            error != null -> error
            repeat && password.isNotEmpty() && tooShort -> "At least ${Backup.MIN_PASSWORD} characters"
            repeat && again.isNotEmpty() && again != password -> "The passwords don't match"
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(if (repeat) "Encrypting…" else "Decrypting…", Modifier.weight(1f))
            } else {
                Text(hint ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).testTag("passwordHint"))
            }
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            TextButton(onClick = submit, enabled = ok && !busy) { Text(confirm) }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    label: String,
    tag: String,
    enabled: Boolean,
    last: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // Enter goes to the repeat field, then exports/imports.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

private fun summary(data: BackupData): String {
    fun n(count: Int, one: String) = "%,d %s".format(count, if (count == 1) one else one + "s")
    return "${n(data.words.size, "word")}, ${n(data.pairs.size, "phrase")}, settings and ${n(data.pinnedClips.size, "pinned clip")}"
}

/** Waits until the learned words are read from disk, so nothing is missed or counted twice. */
private suspend fun loadedStore(context: Context): LearnedStore {
    val store = LearnedStore.get(context)
    while (!store.loaded) delay(50)
    return store
}

private suspend fun exportTo(context: Context, prefs: Prefs, uri: Uri, password: CharArray): String {
    val (words, pairs) = loadedStore(context).snapshot()
    val data = BackupData(words, pairs, Backup.collectSettings(prefs), ClipboardStore.get(context).pinned())
    val bytes = withContext(Dispatchers.Default) { Backup.encrypt(data, password) }
    withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
        out.use { it.write(bytes) }
    }
    return "Backup saved: ${summary(data)}."
}

private suspend fun importFrom(context: Context, prefs: Prefs, uri: Uri, password: CharArray): String {
    val bytes = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { readLimited(it) } ?: throw BackupException("Couldn't open this file.")
    }
    val data = withContext(Dispatchers.Default) { Backup.decrypt(bytes, password) }
    loadedStore(context).importLearned(data.words, data.pairs)
    ClipboardStore.get(context).importPinned(data.pinnedClips)
    Backup.applySettings(prefs, data.settings)
    return "Imported: ${summary(data)}."
}

/** Reads a picked file, refusing anything much bigger than a backup can be. */
internal fun readLimited(input: InputStream, max: Int = Backup.MAX_FILE_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        out.write(buf, 0, n)
        if (out.size() > max) throw BackupException("This file is too big to be a Tasto backup.")
    }
}
