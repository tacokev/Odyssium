package com.skintclub.odyssey.ui.menu

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalDatabase
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.ui.component.BigSeekBar
import com.skintclub.odyssey.ui.component.BottomSheetState
import com.skintclub.odyssey.ui.component.GridMenu
import com.skintclub.odyssey.ui.component.GridMenuItem
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    isQueueTrigger: Boolean? = false,
    viewModel: OdysseyViewModel? = null,
    onShowDetailsDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val playerVolume = playerConnection.service.playerVolume.collectAsState()
    val activityResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val download by LocalDownloadUtil.current.getDownload(mediaMetadata.id).collectAsState(initial = null)

    // If this is an Odyssey episode, the raw episode map (needed for a correct download URL
    // and the like/share/add-to-playlist actions below) rather than just the generic
    // MediaMetadata the player otherwise works with.
    val odysseyAlbums = viewModel?.albums?.collectAsState()?.value.orEmpty()
    val currentEpisode = remember(odysseyAlbums, mediaMetadata.id) {
        odysseyAlbums.firstNotNullOfOrNull { album -> album.episodes.firstOrNull { (it["id"] as? String) == mediaMetadata.id } }
    }
    val showOdysseyActions = viewModel != null && isQueueTrigger != true && currentEpisode != null
    val isLiked = currentEpisode?.get("is_bookmarked") == true

    var showPlaylistPicker by remember { mutableStateOf(false) }

    if (isQueueTrigger != true) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp, bottom = 6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.volume_up),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )

            BigSeekBar(
                progressProvider = playerVolume::value,
                onProgressChange = { playerConnection.service.playerVolume.value = it },
                modifier = Modifier.weight(1f),
            )
        }
    }

    var showPitchTempoDialog by remember {
        mutableStateOf(false)
    }

    if (showPitchTempoDialog) {
        TempoPitchDialog(
            onDismiss = { showPitchTempoDialog = false },
        )
    }

    if (showPlaylistPicker && viewModel != null) {
        OdysseyPlaylistPickerDialog(
            viewModel = viewModel,
            onDismiss = { showPlaylistPicker = false },
            onPlaylistSelected = { playlistId ->
                viewModel.addEpisodesToPlaylist(playlistId, listOf(mediaMetadata.id))
                showPlaylistPicker = false
                onDismiss()
            },
        )
    }

    GridMenu(
        contentPadding =
            PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        when (download?.state) {
            Download.STATE_COMPLETED -> {
                GridMenuItem(
                    icon = R.drawable.offline,
                    title = R.string.delete,
                ) {
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaMetadata.id, false)
                }
            }

            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                val progress = download?.percentDownloaded?.takeIf { it.isFinite() && it >= 0f }?.div(100f)
                GridMenuItem(
                    icon = {
                        if (progress != null) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    title = R.string.downloading,
                ) {
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaMetadata.id, false)
                }
            }

            else -> {
                GridMenuItem(
                    icon = R.drawable.download,
                    title = R.string.download,
                ) {
                    // An Odyssey episode needs its real (possibly signed-cookie or
                    // content-request-resolved) URL, not the generic mediaMetadata.id - only
                    // odysseyEpisodeToMediaItem builds that correctly. A YouTube song has no
                    // such episode map, and keeps its own existing behavior: a DB row so the
                    // rest of the library's download-aware screens can find it, and the id
                    // itself as the download "uri" (resolved elsewhere, same as playback).
                    val uri = if (currentEpisode != null) {
                        odysseyEpisodeToMediaItem(context, currentEpisode).localConfiguration!!.uri
                    } else {
                        database.transaction { insert(mediaMetadata) }
                        mediaMetadata.id.toUri()
                    }
                    val downloadRequest =
                        DownloadRequest
                            .Builder(mediaMetadata.id, uri)
                            .setCustomCacheKey(mediaMetadata.id)
                            .setData(mediaMetadata.title.toByteArray())
                            .build()
                    DownloadService.sendAddDownload(
                        context,
                        ExoDownloadService::class.java,
                        downloadRequest,
                        false,
                    )
                }
            }
        }
        if (showOdysseyActions) {
            GridMenuItem(
                icon = if (isLiked) R.drawable.favorite else R.drawable.favorite_border,
                title = if (isLiked) R.string.unlike else R.string.like,
            ) {
                viewModel?.setEpisodeLiked(mediaMetadata.id, !isLiked)
            }
            GridMenuItem(
                icon = R.drawable.playlist_add,
                title = R.string.add_to_playlist,
            ) {
                showPlaylistPicker = true
            }
            GridMenuItem(
                icon = R.drawable.share,
                title = R.string.share,
            ) {
                onDismiss()
                val intent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://app.adventuresinodyssey.com/content/${mediaMetadata.id}")
                    }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
        if (isQueueTrigger != true) {
            GridMenuItem(
                icon = R.drawable.info,
                title = R.string.details,
            ) {
                onShowDetailsDialog()
                onDismiss()
            }
            GridMenuItem(
                icon = R.drawable.equalizer,
                title = R.string.equalizer,
            ) {
                val intent =
                    android.content.Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(
                            AudioEffect.EXTRA_AUDIO_SESSION,
                            playerConnection.player.audioSessionId,
                        )
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                if (intent.resolveActivity(context.packageManager) != null) {
                    activityResultLauncher.launch(intent)
                }
                onDismiss()
            }
            GridMenuItem(
                icon = R.drawable.tune,
                title = R.string.advanced,
            ) {
                showPitchTempoDialog = true
            }
        }
    }
}

@Composable
fun TempoPitchDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var tempo by remember {
        mutableStateOf(playerConnection.player.playbackParameters.speed)
    }
    var transposeValue by remember {
        mutableStateOf(round(12 * log2(playerConnection.player.playbackParameters.pitch)).toInt())
    }
    val updatePlaybackParameters = {
        playerConnection.player.playbackParameters = PlaybackParameters(tempo, 2f.pow(transposeValue.toFloat() / 12))
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.tempo_and_pitch))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    transposeValue = 0
                    updatePlaybackParameters()
                },
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column {
                ValueAdjuster(
                    icon = R.drawable.speed,
                    currentValue = tempo,
                    values = (0..35).map { round((0.25f + it * 0.05f) * 100) / 100 },
                    onValueUpdate = {
                        tempo = it
                        updatePlaybackParameters()
                    },
                    valueText = { "x$it" },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ValueAdjuster(
                    icon = R.drawable.discover_tune,
                    currentValue = transposeValue,
                    values = (-12..12).toList(),
                    onValueUpdate = {
                        transposeValue = it
                        updatePlaybackParameters()
                    },
                    valueText = { "${if (it > 0) "+" else ""}$it" },
                )
            }
        },
    )
}

@Composable
fun <T> ValueAdjuster(
    @DrawableRes icon: Int,
    currentValue: T,
    values: List<T>,
    onValueUpdate: (T) -> Unit,
    valueText: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )

        IconButton(
            enabled = currentValue != values.first(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) - 1])
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.remove),
                contentDescription = null,
            )
        }

        Text(
            text = valueText(currentValue),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp),
        )

        IconButton(
            enabled = currentValue != values.last(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) + 1])
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.add),
                contentDescription = null,
            )
        }
    }
}
