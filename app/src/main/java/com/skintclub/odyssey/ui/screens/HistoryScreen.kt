package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.extensions.togglePlayPause
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.EpisodeProgressOverlay
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.ui.component.NavigationTitle
import com.skintclub.odyssey.ui.component.SongListItem
import com.skintclub.odyssey.ui.component.episodeProgressText
import com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu
import com.skintclub.odyssey.ui.menu.SongMenu
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.utils.joinByBullet
import com.skintclub.odyssey.constants.ResumeFromHistoryKey
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.DateAgo
import com.skintclub.odyssey.viewmodels.EpisodeProgress
import com.skintclub.odyssey.viewmodels.OdysseySearchEpisode
import com.skintclub.odyssey.viewmodels.HistoryItem
import com.skintclub.odyssey.viewmodels.HistoryViewModel
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    odysseyViewModel: OdysseyViewModel,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val clubHistory by odysseyViewModel.clubHistory.collectAsState()
    LaunchedEffect(clubHistory) { viewModel.setClubHistory(clubHistory) }
    val history by viewModel.history.collectAsState()
    val resumeFromHistory by rememberPreference(ResumeFromHistoryKey, defaultValue = true)
    val searchIndex by odysseyViewModel.searchIndex.collectAsState()
    val episodeById = remember(searchIndex) { searchIndex.episodes.associateBy { it.episode["id"] as? String } }

    /** Plays [episode] in its album (or grouping), from the saved position when there's one
     * to resume. */
    fun play(episode: OdysseySearchEpisode, progress: EpisodeProgress?) {
        val position = if (resumeFromHistory && progress != null && !progress.completed) progress.positionMs else 0L
        playerConnection.playQueue(
            OdysseyQueue(context, episode.queue, episode.queueIndex, startPositionMs = position),
        )
    }

    LazyColumn(
        contentPadding =
            LocalPlayerAwareWindowInsets.current
                .only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ).asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
    ) {
        history.groups.forEach { (dateAgo, items) ->
            stickyHeader {
                NavigationTitle(
                    title =
                        when (dateAgo) {
                            DateAgo.Today -> stringResource(R.string.today)
                            DateAgo.Yesterday -> stringResource(R.string.yesterday)
                            DateAgo.ThisWeek -> stringResource(R.string.this_week)
                            DateAgo.LastWeek -> stringResource(R.string.last_week)
                            is DateAgo.Other -> dateAgo.date.format(DateTimeFormatter.ofPattern("yyyy/MM"))
                            DateAgo.Earlier -> "Earlier"
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                )
            }

            items(
                items = items,
                key = { item ->
                    when (item) {
                        is HistoryItem.Played -> item.event.event.id
                        is HistoryItem.Club -> "club_${dateAgo.hashCode()}_${item.id}"
                    }
                },
            ) { item ->
                val progress = history.progress[item.id]
                when (item) {
                    is HistoryItem.Played -> {
                        val event = item.event
                        val showMenu = {
                            menuState.show {
                                SongMenu(
                                    originalSong = event.song,
                                    event = event.event,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }
                        SongListItem(
                            song = event.song,
                            isActive = event.song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            showInLibraryIcon = true,
                            subtitle = progress?.let {
                                joinByBullet(episodeProgressText(it), event.song.artists.joinToString { artist -> artist.name })
                            },
                            thumbnailOverlay = { progress?.let { EpisodeProgressOverlay(it) } },
                            trailingContent = {
                                IconButton(
                                    onClick = showMenu,
                                    onLongClick = {},
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
                                            if (event.song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                episodeById[event.song.id]?.let { play(it, progress) }
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showMenu()
                                        },
                                    ).animateItem(),
                        )
                    }

                    is HistoryItem.Club -> {
                        val result = item.entry.episode
                        val metadata = remember(result) { odysseyEpisodeToMediaMetadata(context, result.episode) }
                        val isActive = item.id == mediaMetadata?.id
                        val showMenu = {
                            menuState.show {
                                OdysseyEpisodeMenu(
                                    navController = navController,
                                    viewModel = odysseyViewModel,
                                    mediaMetadata = metadata,
                                    mediaItem = odysseyEpisodeToMediaItem(context, result.episode),
                                    albumName = result.albumName,
                                    episodeIndex = result.queueIndex,
                                    isLiked = result.episode["is_bookmarked"] == true,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }
                        MediaMetadataListItem(
                            mediaMetadata = metadata,
                            isActive = isActive,
                            isPlaying = isPlaying,
                            subtitle = joinByBullet(
                                progress?.let(::episodeProgressText),
                                metadata.artists.joinToString { it.name },
                            ),
                            thumbnailOverlay = { progress?.let { EpisodeProgressOverlay(it) } },
                            trailingContent = {
                                IconButton(
                                    onClick = showMenu,
                                    onLongClick = {},
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
                                            if (isActive) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                play(result, progress)
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showMenu()
                                        },
                                    ).animateItem(),
                        )
                    }
                }
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.history)) },
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
