package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as PlainIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OdysseyAlbumScreen(
    navController: NavController,
    albumName: String,
    viewModel: OdysseyViewModel,
) {
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()

    val albums by viewModel.albums.collectAsState()
    val signedCookie by viewModel.signedCookie.collectAsState()
    val album = albums.find { it.name == albumName }
    val episodes = album?.episodes.orEmpty()

    LazyColumn(
        contentPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Button(
                    onClick = {
                        playerConnection.playQueue(
                            OdysseyQueue(
                                episodes = episodes,
                                startIndex = 0,
                                signedCookie = signedCookie,
                            ),
                        )
                    },
                    enabled = episodes.isNotEmpty(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.play))
                }

                OutlinedButton(
                    onClick = {
                        playerConnection.addToQueue(
                            items = episodes.map { odysseyEpisodeToMediaItem(it, signedCookie) },
                        )
                    },
                    enabled = episodes.isNotEmpty(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.add_to_queue))
                }
            }
        }

        itemsIndexed(
            items = episodes,
            key = { index, episode -> (episode["id"] as? String) ?: (episode["download_url"] as? String) ?: index },
        ) { index, episode ->
            val metadata = odysseyEpisodeToMediaMetadata(episode)
            val mediaItem = odysseyEpisodeToMediaItem(episode, signedCookie)

            MediaMetadataListItem(
                mediaMetadata = metadata,
                isActive = mediaMetadata?.id == metadata.id,
                isPlaying = isPlaying,
                trailingContent = {
                    PlainIconButton(
                        onClick = {
                            menuState.show {
                                OdysseyEpisodeMenu(
                                    navController = navController,
                                    mediaMetadata = metadata,
                                    mediaItem = mediaItem,
                                    albumName = albumName,
                                    episodeIndex = index,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (mediaMetadata?.id == metadata.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        OdysseyQueue(
                                            episodes = episodes,
                                            startIndex = index,
                                            signedCookie = signedCookie,
                                        ),
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        ),
            )
        }
    }

    TopAppBar(
        title = { Text(albumName) },
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
