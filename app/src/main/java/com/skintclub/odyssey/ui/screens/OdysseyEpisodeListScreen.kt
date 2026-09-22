package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as PlainIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalDownloadUtil
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubGrouping
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu
import com.skintclub.odyssey.ui.menu.OdysseyGroupingMenu
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

/**
 * Shared episode-list screen for anything that isn't a single album: liked episodes,
 * downloaded episodes, and user playlists. [episodes] can mix content from several albums,
 * so - unlike [OdysseyAlbumScreen], which can assume every episode's position in the list is
 * also its position within one shared album - each item's "Details"/menu navigation resolves
 * its own true (album, index) from [OdysseyViewModel.albums] rather than trusting the list's
 * own index.
 *
 * [grouping] is the real [ClubGrouping] behind this list (an Album or a Playlist) when there
 * is one - it drives the cover/description header and the top bar's menu/liked/downloaded
 * indicators. Liked and Downloaded aren't real groupings, so they pass null and get neither.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OdysseyEpisodeListScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    title: String,
    episodes: List<Map<String, Any>>,
    grouping: ClubGrouping? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()

    val albums by viewModel.albums.collectAsState()
    val signedCookie by viewModel.signedCookie.collectAsState()
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()

    // Maps an episode id to where it really lives, so the "..." menu's "Details"/queue
    // actions land on the same album/episode page regardless of which list you tapped from.
    val episodeLocations = remember(albums) {
        val map = mutableMapOf<String, Pair<String, Int>>()
        albums.forEach { album ->
            album.episodes.forEachIndexed { index, episode ->
                (episode["id"] as? String)?.let { id -> map[id] = album.name to index }
            }
        }
        map
    }

    val groupingDownloads = remember(episodes, downloads) {
        episodes.mapNotNull { episode -> (episode["id"] as? String)?.let { downloads[it] } }
    }
    val anyDownloading = groupingDownloads.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }
    val anyDownloaded = groupingDownloads.any { it.state == Download.STATE_COMPLETED }
    val downloadingProgress = remember(groupingDownloads) {
        groupingDownloads
            .filter { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }
            .mapNotNull { it.percentDownloaded.takeIf { p -> p.isFinite() && p >= 0f } }
            .takeIf { it.isNotEmpty() }
            ?.let { (it.average() / 100.0).toFloat() }
    }

    if (episodes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Vertical)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Nothing here yet.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }

    LazyColumn(
        contentPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
    ) {
        if (grouping != null) {
            val description = grouping.fullDescription?.takeIf { it.isNotBlank() }
                ?: grouping.description?.takeIf { it.isNotBlank() }
            if (!grouping.imageUrl.isNullOrBlank() || description != null) {
                item {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (!grouping.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = grouping.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                            )
                            if (description != null) Spacer(Modifier.height(12.dp))
                        }
                        if (description != null) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Button(
                    onClick = {
                        playerConnection.playQueue(
                            OdysseyQueue(
                                context = context,
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
                            items = episodes.map { odysseyEpisodeToMediaItem(context, it, signedCookie) },
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
            val metadata = odysseyEpisodeToMediaMetadata(context, episode)
            val mediaItem = odysseyEpisodeToMediaItem(context, episode, signedCookie)
            val location = episodeLocations[episode["id"] as? String]

            MediaMetadataListItem(
                mediaMetadata = metadata,
                isActive = mediaMetadata?.id == metadata.id,
                isPlaying = isPlaying,
                trailingContent = {
                    val episodeDownload = (episode["id"] as? String)?.let { downloads[it] }
                    when (episodeDownload?.state) {
                        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                            val progress = episodeDownload.percentDownloaded
                                .takeIf { it.isFinite() && it >= 0f }
                                ?.div(100f)
                            if (progress != null) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }

                        Download.STATE_COMPLETED -> {
                            Icon(
                                painter = painterResource(R.drawable.offline),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(20.dp),
                            )
                        }

                        else -> {}
                    }
                    if (episode["is_bookmarked"] == true) {
                        Icon(
                            painter = painterResource(R.drawable.favorite),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        )
                    }
                    PlainIconButton(
                        onClick = {
                            menuState.show {
                                OdysseyEpisodeMenu(
                                    navController = navController,
                                    viewModel = viewModel,
                                    mediaMetadata = metadata,
                                    mediaItem = mediaItem,
                                    albumName = location?.first ?: title,
                                    episodeIndex = location?.second ?: index,
                                    isLiked = episode["is_bookmarked"] == true,
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
                                            context = context,
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
        title = { Text(title) },
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
            if (grouping != null) {
                // Groupings can't be liked themselves - only their episodes can - so this is
                // download-only, and only when something in the grouping actually is
                // downloading/downloaded (not just because the grouping exists).
                if (anyDownloading) {
                    if (downloadingProgress != null) {
                        CircularProgressIndicator(
                            progress = { downloadingProgress },
                            modifier = Modifier.padding(horizontal = 4.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 4.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else if (anyDownloaded) {
                    Icon(
                        painter = painterResource(R.drawable.offline),
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 4.dp).size(20.dp),
                    )
                }
                PlainIconButton(
                    onClick = {
                        menuState.show {
                            OdysseyGroupingMenu(
                                navController = navController,
                                viewModel = viewModel,
                                grouping = grouping,
                                episodes = episodes,
                                signedCookie = signedCookie,
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
            }
        },
    )
}

/** All bookmarked episodes across every album, newest-loaded-album-first (the same order
 * [OdysseyViewModel.albums] already sorts albums in). */
@Composable
fun OdysseyLikedScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val albums by viewModel.albums.collectAsState()
    val episodes = remember(albums) {
        albums.flatMap { it.episodes }.filter { it["is_bookmarked"] == true }
    }
    OdysseyEpisodeListScreen(navController, viewModel, "Liked", episodes)
}

/** Every episode with a completed local download. */
@Composable
fun OdysseyDownloadedScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val albums by viewModel.albums.collectAsState()
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val episodes = remember(albums, downloads) {
        albums.flatMap { it.episodes }.filter { episode ->
            val id = episode["id"] as? String
            id != null && downloads[id]?.state == Download.STATE_COMPLETED
        }
    }
    OdysseyEpisodeListScreen(navController, viewModel, "Downloaded", episodes)
}

/** A user's own playlist, sourced from the cached content groupings rather than
 * [OdysseyViewModel.albums] since a playlist isn't an album. */
@Composable
fun OdysseyPlaylistScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    playlistId: String,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()
    val grouping = remember(groupings, playlistId) { groupings.firstOrNull { it.id == playlistId } }
    val episodes = remember(groupings, playlistId) { clubClient.playlistEpisodesFrom(groupings, playlistId) }
    OdysseyEpisodeListScreen(navController, viewModel, grouping?.name ?: "Playlist", episodes, grouping)
}
