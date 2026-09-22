package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as PlainIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.extensions.move
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Renaming and reordering for a user-created playlist. Removing individual episodes isn't
 * part of this screen - just what was asked for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdysseyPlaylistEditScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    playlistId: String,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()

    // Snapshotted once per playlist rather than kept reactive to `groupings`, so a background
    // refresh (or the save this screen itself triggers) can't clobber an in-progress edit.
    var name by remember(playlistId) {
        mutableStateOf(groupings.firstOrNull { it.id == playlistId }?.name.orEmpty())
    }
    val episodes = remember(playlistId) {
        mutableStateListOf<Map<String, Any>>().apply {
            addAll(clubClient.playlistEpisodesFrom(groupings, playlistId))
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        episodes.move(from.index, to.index)
    }

    fun save() {
        viewModel.updatePlaylist(playlistId, name.trim(), episodes.mapNotNull { it["id"] as? String })
        navController.navigateUp()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Playlist name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            items(
                items = episodes,
                key = { (it["id"] as? String) ?: it.hashCode() },
            ) { episode ->
                ReorderableItem(
                    reorderableState,
                    key = (episode["id"] as? String) ?: episode.hashCode(),
                ) { _ ->
                    val metadata = odysseyEpisodeToMediaMetadata(context, episode)
                    MediaMetadataListItem(
                        mediaMetadata = metadata,
                        modifier = Modifier.fillMaxWidth(),
                        trailingContent = {
                            PlainIconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.drag_handle),
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    TopAppBar(
        title = { Text("Edit Playlist") },
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
        actions = {
            PlainIconButton(
                onClick = ::save,
                enabled = name.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.done),
                    contentDescription = null,
                )
            }
        },
    )
}
