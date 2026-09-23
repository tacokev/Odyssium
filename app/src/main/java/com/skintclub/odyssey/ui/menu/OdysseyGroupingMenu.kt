package com.skintclub.odyssey.ui.menu

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubGrouping
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.ui.component.DefaultDialog
import com.skintclub.odyssey.ui.component.GridMenu
import com.skintclub.odyssey.ui.component.GridMenuItem
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/** The grouping-level "..." menu next to an album/playlist's title. Mirrors
 * [OdysseyEpisodeMenu] but acts on every episode in [episodes] at once. */
@Composable
fun OdysseyGroupingMenu(
    navController: NavController,
    viewModel: OdysseyViewModel,
    grouping: ClubGrouping,
    episodes: List<Map<String, Any>>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isUserPlaylist = !grouping.viewerId.isNullOrBlank()
    val downloads by downloadUtil.downloads.collectAsState()
    val allDownloaded = remember(episodes, downloads) {
        episodes.isNotEmpty() &&
            episodes.all { episode -> (episode["id"] as? String)?.let { downloads[it]?.state == Download.STATE_COMPLETED } == true }
    }

    if (showPlaylistPicker) {
        OdysseyPlaylistPickerDialog(
            viewModel = viewModel,
            onDismiss = { showPlaylistPicker = false },
            onPlaylistSelected = { playlistId ->
                viewModel.addEpisodesToPlaylist(playlistId, episodes.mapNotNull { it["id"] as? String })
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
        if (allDownloaded) {
            // Every episode is already downloaded, so this switches to deleting them all -
            // while any are still missing, the button stays "Download all" and fills in the
            // rest instead.
            GridMenuItem(
                icon = R.drawable.offline,
                title = R.string.delete_all,
            ) {
                onDismiss()
                episodes.forEach { episode ->
                    val id = episode["id"] as? String ?: return@forEach
                    if (downloads[id]?.state == Download.STATE_COMPLETED) {
                        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
                    }
                }
            }
        } else {
            GridMenuItem(
                icon = R.drawable.download,
                title = R.string.download_all,
            ) {
                onDismiss()
                episodes.forEach { episode ->
                    val id = episode["id"] as? String ?: return@forEach
                    val state = downloads[id]?.state
                    if (state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING) {
                        return@forEach
                    }
                    val mediaItem = odysseyEpisodeToMediaItem(context, episode)
                    val downloadRequest =
                        DownloadRequest
                            .Builder(id, mediaItem.localConfiguration!!.uri)
                            .setCustomCacheKey(id)
                            .setData((episode["name"] as? String ?: "").toByteArray())
                            .build()
                    DownloadService.sendAddDownload(context, ExoDownloadService::class.java, downloadRequest, false)
                }
            }
        }
        GridMenuItem(
            icon = R.drawable.playlist_add,
            title = R.string.add_to_playlist,
        ) {
            showPlaylistPicker = true
        }
        if (isUserPlaylist) {
            GridMenuItem(
                icon = R.drawable.edit,
                title = R.string.edit,
            ) {
                onDismiss()
                navController.navigate("odyssey_playlist_edit/${Uri.encode(grouping.id)}")
            }
            GridMenuItem(
                icon = R.drawable.delete,
                title = R.string.delete,
            ) {
                showDeleteConfirm = true
            }
        } else {
            // A user-created playlist has no public page to share.
            GridMenuItem(
                icon = R.drawable.share,
                title = R.string.share,
            ) {
                onDismiss()
                val intent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://app.adventuresinodyssey.com/contentGroup/${grouping.id}")
                    }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }

    if (showDeleteConfirm) {
        DefaultDialog(
            onDismiss = { showDeleteConfirm = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, grouping.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDismiss()
                        viewModel.deletePlaylist(grouping.id)
                        navController.navigateUp()
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }
}
