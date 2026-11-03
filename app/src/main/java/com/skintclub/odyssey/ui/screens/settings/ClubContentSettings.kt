package com.skintclub.odyssey.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.ClubAudioLoadMode
import com.skintclub.odyssey.constants.ClubAudioLoadModeKey
import com.skintclub.odyssey.constants.ShowEpisodeNumberKey
import com.skintclub.odyssey.constants.TranscriptsEnabledKey
import com.skintclub.odyssey.transcripts.Transcripts
import com.skintclub.odyssey.ui.component.EnumListPreference
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.PreferenceEntry
import com.skintclub.odyssey.ui.component.PreferenceGroupTitle
import com.skintclub.odyssey.ui.component.SwitchPreference
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubContentSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioLoadMode, onAudioLoadModeChange) = rememberEnumPreference(ClubAudioLoadModeKey, defaultValue = ClubAudioLoadMode.SIGNED_COOKIE)
    val (showEpisodeNumber, onShowEpisodeNumberChange) = rememberPreference(ShowEpisodeNumberKey, defaultValue = false)
    val (transcriptsEnabled, onTranscriptsEnabledChange) = rememberPreference(TranscriptsEnabledKey, defaultValue = false)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val transcriptEntries by remember { Transcripts.episodeIds(context) }.collectAsState()
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importing = true
        coroutineScope.launch {
            val message = try {
                val count = Transcripts.import(context, uri)
                "Imported transcriptions for $count episodes"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                "Couldn't import: ${e.message ?: "unknown error"}"
            }
            importing = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        EnumListPreference(
            title = { Text("Load audio from:") },
            icon = { Icon(painterResource(R.drawable.play), null) },
            selectedValue = audioLoadMode,
            valueText = {
                when (it) {
                    ClubAudioLoadMode.SIGNED_COOKIE -> "Signed cookie - Fast but unstable"
                    ClubAudioLoadMode.CONTENT_REQUEST -> "Content request - Slow but stable"
                }
            },
            onValueSelected = onAudioLoadModeChange,
        )
        SwitchPreference(
            title = { Text("Show episode number") },
            description = "Use the episode's numbered short title (e.g. \"#571: For Better or for Worse\") instead of its plain name, everywhere an episode title is shown - including the randomizer.",
            checked = showEpisodeNumber,
            onCheckedChange = onShowEpisodeNumberChange,
        )

        PreferenceGroupTitle(title = "Transcriptions")
        SwitchPreference(
            title = { Text("Transcriptions") },
            description = "Show what's being said, and who's saying it, in place of the cover art in the player. Turn it on from the player's menu.",
            icon = { Icon(painterResource(R.drawable.lyrics), null) },
            checked = transcriptsEnabled,
            onCheckedChange = onTranscriptsEnabledChange,
        )
        if (transcriptsEnabled) {
            val count = transcriptEntries?.size
            PreferenceEntry(
                title = { Text(if (count != null && count > 0) "Replace transcriptions file" else "Import transcriptions file") },
                description = when {
                    importing -> "Importing…"
                    count == null -> "Loading…"
                    count == 0 -> "Pick the transcripts .zip file (e.g. aio_transcripts.zip)."
                    else -> "Transcriptions for $count episodes."
                },
                icon = { Icon(painterResource(R.drawable.library_add), null) },
                isEnabled = !importing,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            )
            if (count != null && count > 0) {
                PreferenceEntry(
                    title = { Text("Remove transcriptions") },
                    description = "Delete the imported file from this device.",
                    icon = { Icon(painterResource(R.drawable.delete), null) },
                    isEnabled = !importing,
                    onClick = { coroutineScope.launch { Transcripts.remove(context) } },
                )
            }
        }
    }

    TopAppBar(
        title = { Text("Content") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
