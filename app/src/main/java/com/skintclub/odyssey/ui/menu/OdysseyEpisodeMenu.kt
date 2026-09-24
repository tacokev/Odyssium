package com.skintclub.odyssey.ui.menu

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.isSecretEpisodeId
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.ui.component.GridMenu
import com.skintclub.odyssey.ui.component.GridMenuItem
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

@Composable
fun OdysseyEpisodeMenu(
    navController: NavController,
    viewModel: OdysseyViewModel,
    mediaMetadata: MediaMetadata,
    mediaItem: MediaItem,
    /** The episode's album, for "Details"; null (hiding "Details") for album-less episodes like podcasts. */
    albumName: String?,
    episodeIndex: Int,
    isLiked: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val download by LocalDownloadUtil.current.getDownload(mediaMetadata.id).collectAsState(initial = null)
    var showPlaylistPicker by remember { mutableStateOf(false) }

    if (showPlaylistPicker) {
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

    MediaMetadataListItem(
        mediaMetadata = mediaMetadata,
        modifier = Modifier,
    )

    HorizontalDivider()

    GridMenu(
        contentPadding =
            PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        GridMenuItem(
            icon = R.drawable.playlist_play,
            title = R.string.play_next,
        ) {
            onDismiss()
            playerConnection.playNext(mediaItem)
        }
        GridMenuItem(
            icon = R.drawable.queue_music,
            title = R.string.add_to_queue,
        ) {
            onDismiss()
            playerConnection.addToQueue(mediaItem)
        }
        when (download?.state) {
            Download.STATE_COMPLETED -> {
                GridMenuItem(
                    icon = R.drawable.offline,
                    title = R.string.delete,
                ) {
                    onDismiss()
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaMetadata.id, false)
                }
            }

            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                // Cancels the in-progress download - same call as deleting a finished one,
                // DownloadManager treats both as "remove this download".
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
                    onDismiss()
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaMetadata.id, false)
                }
            }

            else -> {
                GridMenuItem(
                    icon = R.drawable.download,
                    title = R.string.download,
                ) {
                    onDismiss()
                    val downloadRequest =
                        DownloadRequest
                            .Builder(mediaMetadata.id, mediaItem.localConfiguration!!.uri)
                            .setCustomCacheKey(mediaMetadata.id)
                            .setData(mediaMetadata.title.toByteArray())
                            .build()
                    DownloadService.sendAddDownload(context, ExoDownloadService::class.java, downloadRequest, false)
                }
            }
        }
        GridMenuItem(
            icon = if (isLiked) R.drawable.favorite else R.drawable.favorite_border,
            title = if (isLiked) R.string.unlike else R.string.like,
        ) {
            onDismiss()
            viewModel.setEpisodeLiked(mediaMetadata.id, !isLiked)
        }
        GridMenuItem(
            icon = R.drawable.playlist_add,
            title = R.string.add_to_playlist,
        ) {
            showPlaylistPicker = true
        }
        if (isSecretEpisodeId(mediaMetadata.id)) {
            GridMenuItem(
                icon = R.drawable.delete,
                title = R.string.remove_from_secret_episodes,
            ) {
                onDismiss()
                viewModel.removeSecretEpisode(mediaMetadata.id)
            }
        }
        if (albumName != null) {
            GridMenuItem(
                icon = R.drawable.info,
                title = R.string.details,
            ) {
                onDismiss()
                navController.navigate("odyssey_episode/${Uri.encode(albumName)}/$episodeIndex")
            }
        }
    }
}
