package com.skintclub.odyssey.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.R
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.menu.OdysseyPlaylistPickerDialog
import com.skintclub.odyssey.ui.player.rememberEpisodeLiked
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/**
 * Download, like and add-to-playlist on an episode's page (the randomizer's and "Details"),
 * so they don't need the episode's menu. Same actions as [com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu].
 */
@Composable
internal fun EpisodePageActions(
    viewModel: OdysseyViewModel,
    episode: Map<String, Any>,
) {
    val context = LocalContext.current
    val metadata = remember(episode) { odysseyEpisodeToMediaMetadata(context, episode) }
    val episodeId = metadata.id
    val download by LocalDownloadUtil.current.getDownload(episodeId).collectAsState(initial = null)
    val isLiked = rememberEpisodeLiked(viewModel, episodeId)
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var confirmRemoveDownload by remember { mutableStateOf(false) }

    if (showPlaylistPicker) {
        OdysseyPlaylistPickerDialog(
            viewModel = viewModel,
            episodeIds = listOf(episodeId),
            onDismiss = { showPlaylistPicker = false },
            onDone = { showPlaylistPicker = false },
        )
    }

    if (confirmRemoveDownload) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownload = false },
            title = { Text(stringResource(R.string.remove_download)) },
            text = { Text(metadata.title) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveDownload = false
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, episodeId, false)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveDownload = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (download?.state) {
            Download.STATE_COMPLETED -> EpisodePageAction(
                icon = { ActionIcon(R.drawable.offline) },
                label = stringResource(R.string.downloaded),
                // A finished download is removed only after a confirm, so a stray tap doesn't lose it.
                onClick = { confirmRemoveDownload = true },
            )

            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                val progress = download?.percentDownloaded?.takeIf { it.isFinite() && it >= 0f }?.div(100f)
                EpisodePageAction(
                    icon = {
                        if (progress != null) {
                            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    },
                    label = stringResource(R.string.downloading),
                    // Tapping an in-progress download cancels it, as in the episode menu.
                    onClick = {
                        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, episodeId, false)
                    },
                )
            }

            else -> EpisodePageAction(
                icon = { ActionIcon(R.drawable.download) },
                label = stringResource(R.string.download),
                onClick = {
                    val uri = odysseyEpisodeToMediaItem(context, episode).localConfiguration?.uri ?: return@EpisodePageAction
                    val request = DownloadRequest
                        .Builder(episodeId, uri)
                        .setCustomCacheKey(episodeId)
                        .setData(metadata.title.toByteArray())
                        .build()
                    DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
                },
            )
        }

        EpisodePageAction(
            icon = { ActionIcon(if (isLiked) R.drawable.favorite else R.drawable.favorite_border) },
            label = stringResource(if (isLiked) R.string.liked else R.string.like),
            onClick = { viewModel.setEpisodeLiked(episodeId, !isLiked) },
        )

        EpisodePageAction(
            icon = { ActionIcon(R.drawable.playlist_add) },
            label = stringResource(R.string.add_to_playlist),
            onClick = { showPlaylistPicker = true },
        )
    }
}

@Composable
private fun ActionIcon(@DrawableRes id: Int) {
    Icon(painter = painterResource(id), contentDescription = null, modifier = Modifier.size(24.dp))
}

/** One icon-over-label button in [EpisodePageActions]. */
@Composable
private fun EpisodePageAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}
