package com.skintclub.odyssey.ui.screens.settings

import android.media.MediaPlayer
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skintclub.odyssey.LocalMabelLibrary
import com.skintclub.odyssey.LocalMabelVoice
import com.skintclub.odyssey.constants.MabelDjKey
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.ui.component.PreferenceGroupTitle
import kotlinx.coroutines.launch

/** Randomizer's "Mabel" section: turning her on, importing her voice and lines, and trying
 * her voice out. */
@Composable
fun MabelPreferences() {
    val voice = LocalMabelVoice.current
    val library = LocalMabelLibrary.current
    val (mabelDj, onMabelDjChange) = rememberPreference(MabelDjKey, defaultValue = false)
    val lines by library.data.collectAsState()
    val linesImporting by library.importing.collectAsState()
    val linesError by library.importError.collectAsState()
    val pickLines = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(library::import)
    }
    val isAvailable by voice.isAvailable.collectAsState()
    val importing by voice.importing.collectAsState()
    val importError by voice.importError.collectAsState()
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(voice::importZip)
    }
    var showTest by remember { mutableStateOf(false) }

    PreferenceGroupTitle(title = "Mabel")

    SwitchPreference(
        title = { Text("Mabel DJ") },
        description = when {
            !isAvailable && lines == null -> "Import her voice and her lines below to use her."
            !isAvailable -> "Import her voice below to use her."
            lines == null -> "Import her lines below to use her."
            else -> "The random button starts a listening session hosted by Mabel: runs of episodes that go together, picked from what you've been enjoying, with her introducing them."
        },
        checked = mabelDj,
        onCheckedChange = onMabelDjChange,
    )

    Text(
        text = when {
            importing -> "Importing Mabel's voice…"
            importError != null -> "Couldn't import the voice: $importError"
            isAvailable -> "Mabel's voice is imported."
            else -> "Import Mabel's voice (a zip of the voice model) to use her."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (importError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )

    PreferenceEntry(
        title = { Text(if (isAvailable) "Replace voice" else "Import voice") },
        onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        isEnabled = !importing,
    )
    if (isAvailable) {
        PreferenceEntry(
            title = { Text("Try her voice") },
            onClick = { showTest = true },
            isEnabled = !importing,
        )
        PreferenceEntry(
            title = { Text("Delete voice") },
            onClick = voice::delete,
            isEnabled = !importing,
        )
    }

    Text(
        text = when {
            linesImporting -> "Importing Mabel's lines…"
            linesError != null -> "Couldn't import the lines: $linesError"
            lines != null -> "Mabel knows ${lines!!.episodes.size} episodes and ${lines!!.vibes.size} vibes."
            else -> "Import her lines (mabel.json) so she knows the episodes."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (linesError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    PreferenceEntry(
        title = { Text(if (lines != null) "Replace lines" else "Import lines") },
        onClick = { pickLines.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        isEnabled = !linesImporting,
    )
    if (lines != null) {
        PreferenceEntry(
            title = { Text("Delete lines") },
            onClick = library::delete,
            isEnabled = !linesImporting,
        )
    }

    if (showTest) VoiceTestDialog(onDismiss = { showTest = false })
}

/** Type something for Mabel to say; shows how long it took to generate. */
@Composable
private fun VoiceTestDialog(onDismiss: () -> Unit) {
    val voice = LocalMabelVoice.current
    val coroutineScope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Alright, let's switch it up with a Novacom vibe. Here's Red Herring.") }
    var status by remember { mutableStateOf<String?>(null) }
    var speaking by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player.value?.release() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Try Mabel's voice") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !speaking && text.isNotBlank(),
                onClick = {
                    speaking = true
                    status = "Generating…"
                    coroutineScope.launch {
                        val started = SystemClock.elapsedRealtime()
                        val file = voice.speak(text.trim())
                        val took = SystemClock.elapsedRealtime() - started
                        speaking = false
                        if (file == null) {
                            status = "Couldn't generate it."
                            return@launch
                        }
                        player.value?.release()
                        player.value = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            prepare()
                            status = "Generated in $took ms for ${duration / 100 / 10.0} s of speech."
                            start()
                        }
                    }
                },
            ) { Text("Speak") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
