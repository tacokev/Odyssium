package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as PlainIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubGrouping
import com.skintclub.odyssey.constants.AlbumGridColumns
import com.skintclub.odyssey.constants.AlbumGridColumnsKey
import com.skintclub.odyssey.constants.AlbumGridConnectedSquaresKey
import com.skintclub.odyssey.constants.CollectionsSortReversedKey
import com.skintclub.odyssey.constants.EpisodesSortReversedKey
import com.skintclub.odyssey.constants.PodcastsSortReversedKey
import com.skintclub.odyssey.constants.SeriesSortReversedKey
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeThumbnailUrl
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu
import com.skintclub.odyssey.ui.utils.appBarScrollBehavior
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.ui.utils.collapsingTopAppBarColors
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import kotlinx.coroutines.flow.drop

/** Route pattern for [OdysseyGroupingScreen]: any cached grouping (a series, a podcast year,
 * ...) shown as a plain episode list. */
const val ODYSSEY_GROUPING_ROUTE = "odyssey_grouping/{groupingId}"
const val ODYSSEY_ALBUMS_ROUTE = "odyssey_albums"

/** The album cover grid that used to be the Home screen, reached from Home's "Albums" tile. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OdysseyAlbumsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val haptic = LocalHapticFeedback.current
    val albums by viewModel.albums.collectAsState()
    val sortDescending by viewModel.sortDescending.collectAsState()
    val gridState = rememberLazyGridState()
    val scrollBehavior = appBarScrollBehavior()
    val albumGridColumns by rememberEnumPreference(AlbumGridColumnsKey, defaultValue = AlbumGridColumns.TWO)
    val albumGridConnectedSquares by rememberPreference(AlbumGridConnectedSquaresKey, defaultValue = false)

    ScrollToTopOnChange(sortDescending) { gridState.scrollToItem(0) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (albumGridColumns == AlbumGridColumns.THREE) 3 else 2),
        state = gridState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        items(
            items = albums,
            // Prefixing with sortDescending changes every key when the order flips, so the
            // grid treats it as a full item swap instead of trying to keep an existing
            // key's tile pinned in the viewport (which is what caused the jump-to-bottom).
            key = { "${sortDescending}_${it.name}" },
        ) { album ->
            CoverGridTile(
                title = album.name,
                subtitle = "${album.episodes.size} episodes",
                thumbnailUrl = album.coverUrl,
                connectedSquare = albumGridConnectedSquares,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        navController.navigate("odyssey_album/${Uri.encode(album.name)}")
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                ),
            )
        }
    }

    TopAppBar(
        title = { Text("Albums") },
        scrollBehavior = scrollBehavior,
        colors = collapsingTopAppBarColors(),
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
            SortOrderButton(reversed = sortDescending, onClick = viewModel::toggleSortOrder)
        },
    )
}

/** Every numbered episode, 1 onward, one row per 50-episode block. */
@Composable
fun OdysseyAllEpisodesScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()
    val sections = remember(groupings) {
        clubClient.sectionsFrom(groupings, clubClient.groupingsOfType(groupings, ClubClient.EPISODE_HOME_TYPE))
    }
    OdysseySectionedEpisodesScreen(navController, viewModel, "Episodes", sections, EpisodesSortReversedKey)
}

/** Every podcast, one row per year, newest year first - the latest podcasts are what you'd
 * usually come here for. */
@Composable
fun OdysseyPodcastsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()
    val sections = remember(groupings) {
        clubClient.sectionsFrom(groupings, clubClient.groupingsOfType(groupings, ClubClient.PODCAST_YEAR_TYPE).reversed())
    }
    OdysseySectionedEpisodesScreen(navController, viewModel, "Podcasts", sections, PodcastsSortReversedKey)
}

/** [sections] as one horizontally scrolling row each, headed by the section's name (tap it for
 * the full list). Rows (and the cards inside them) are lazy, so only what's on screen is ever
 * composed - unlike one flat 1000+ item list, which built every row's media item up front.
 *
 * The top bar's sort button (persisted under [sortReversedKey]) flips both the row order and
 * each row's own order. Playback is unaffected: tapping a card still queues that row in its
 * normal order, from the tapped episode on, rather than playing it backwards. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OdysseySectionedEpisodesScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    title: String,
    sections: List<Pair<ClubGrouping, List<Map<String, Any>>>>,
    sortReversedKey: Preferences.Key<Boolean>,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val albums by viewModel.albums.collectAsState()
    val signedCookie by viewModel.signedCookie.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val scrollBehavior = appBarScrollBehavior()
    val listState = rememberLazyListState()
    var sortReversed by rememberPreference(sortReversedKey, defaultValue = false)
    val shownSections = remember(sections, sortReversed) { if (sortReversed) sections.asReversed() else sections }
    ScrollToTopOnChange(sortReversed) { listState.scrollToItem(0) }

    // Where each episode really lives, for the long-press menu's "Details".
    val episodeLocations = remember(albums) {
        buildMap {
            albums.forEach { album ->
                album.episodes.forEachIndexed { index, episode ->
                    (episode["id"] as? String)?.let { put(it, album.name to index) }
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        items(
            items = shownSections,
            // Keyed by sort order too, so a flip rebuilds each row (and its scroll position)
            // rather than keeping a row scrolled to where the old order had it.
            key = { (grouping, _) -> "${sortReversed}_${grouping.id}" },
        ) { (grouping, episodes) ->
            val shownEpisodes = if (sortReversed) episodes.asReversed() else episodes
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("odyssey_grouping/${Uri.encode(grouping.id)}") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = grouping.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                    )
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = shownEpisodes,
                        key = { index, episode -> (episode["id"] as? String) ?: index },
                    ) { index, episode ->
                        val id = episode["id"] as? String
                        EpisodeCard(
                            episode = episode,
                            isActive = id != null && mediaMetadata?.id == id,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    playerConnection.playQueue(
                                        OdysseyQueue(
                                            context = context,
                                            episodes = episodes,
                                            startIndex = if (sortReversed) episodes.lastIndex - index else index,
                                            signedCookie = signedCookie,
                                        ),
                                    )
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val location = episodeLocations[id]
                                    menuState.show {
                                        OdysseyEpisodeMenu(
                                            navController = navController,
                                            viewModel = viewModel,
                                            mediaMetadata = odysseyEpisodeToMediaMetadata(context, episode),
                                            mediaItem = odysseyEpisodeToMediaItem(context, episode, signedCookie),
                                            albumName = location?.first,
                                            episodeIndex = location?.second ?: (if (sortReversed) episodes.lastIndex - index else index),
                                            isLiked = episode["is_bookmarked"] == true,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    TopAppBar(
        title = { Text(title) },
        scrollBehavior = scrollBehavior,
        colors = collapsingTopAppBarColors(),
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
            SortOrderButton(reversed = sortReversed, onClick = { sortReversed = !sortReversed })
        },
    )
}

/** A small cover + title card for [OdysseySectionedEpisodesScreen]'s rows. Deliberately plain (no
 * marquee, no BoxWithConstraints) and decodes its cover at a fixed small size, for the same
 * reasons as [CoverGridTile]. */
@Composable
private fun EpisodeCard(
    episode: Map<String, Any>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageUrl = odysseyEpisodeThumbnailUrl(episode)
    val request = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(320, 320)
            .crossfade(false)
            .build()
    }

    Column(modifier = modifier.width(140.dp)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ThumbnailCornerRadius))
                .then(
                    if (isActive) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(ThumbnailCornerRadius))
                    } else {
                        Modifier
                    },
                ),
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = (episode["short_name"] as? String) ?: (episode["name"] as? String).orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun OdysseySeriesScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()
    val series = remember(groupings) { clubClient.groupingsOfType(groupings, ClubClient.SERIES_TYPE) }
    OdysseyGroupingGridScreen(navController, "Series & Story Arcs", series, SeriesSortReversedKey)
}

@Composable
fun OdysseyCollectionsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()
    val collections = remember(groupings) { clubClient.collectionsFrom(groupings) }
    OdysseyGroupingGridScreen(navController, "Collections", collections, CollectionsSortReversedKey)
}

/** A cover grid of [groupings], each opening [OdysseyGroupingScreen], with a top bar sort
 * button (persisted under [sortReversedKey]) that reverses it. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OdysseyGroupingGridScreen(
    navController: NavController,
    title: String,
    groupings: List<ClubGrouping>,
    sortReversedKey: Preferences.Key<Boolean>,
) {
    val haptic = LocalHapticFeedback.current
    val scrollBehavior = appBarScrollBehavior()
    val gridState = rememberLazyGridState()
    var sortReversed by rememberPreference(sortReversedKey, defaultValue = false)
    val shownGroupings = remember(groupings, sortReversed) { if (sortReversed) groupings.asReversed() else groupings }
    ScrollToTopOnChange(sortReversed) { gridState.scrollToItem(0) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        items(
            items = shownGroupings,
            // See OdysseyAlbumsScreen: a sort-prefixed key makes a flip a full swap instead of
            // the grid trying to keep an existing tile pinned in view.
            key = { "${sortReversed}_${it.id}" },
        ) { grouping ->
            CoverGridTile(
                title = grouping.name,
                subtitle = "${grouping.contentList.count { it.type == "Audio" }} episodes",
                thumbnailUrl = grouping.imageUrl,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        navController.navigate("odyssey_grouping/${Uri.encode(grouping.id)}")
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
        scrollBehavior = scrollBehavior,
        colors = collapsingTopAppBarColors(),
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
            SortOrderButton(reversed = sortReversed, onClick = { sortReversed = !sortReversed })
        },
    )
}

/** A single series, podcast year, or other non-album grouping, as an episode list. */
@Composable
fun OdysseyGroupingScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    groupingId: String,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val groupings by viewModel.groupings.collectAsState()
    val grouping = remember(groupings, groupingId) { groupings.firstOrNull { it.id == groupingId } }
    val episodes = remember(groupings, grouping) {
        grouping?.let { clubClient.groupingEpisodesFrom(groupings, it) }.orEmpty()
    }
    OdysseyEpisodeListScreen(navController, viewModel, grouping?.name ?: "", episodes, grouping)
}

/** The top bar's flip-sort-order button, shared by every browse screen. */
@Composable
private fun SortOrderButton(
    reversed: Boolean,
    onClick: () -> Unit,
) {
    PlainIconButton(onClick = onClick) {
        Icon(
            painter = painterResource(if (reversed) R.drawable.arrow_upward else R.drawable.arrow_downward),
            contentDescription = null,
        )
    }
}

/** Runs [scrollToTop] whenever [sortReversed] flips - but not on first composition, which also
 * happens when coming back to the screen and would throw away its restored scroll position. */
@Composable
private fun ScrollToTopOnChange(
    sortReversed: Boolean,
    scrollToTop: suspend () -> Unit,
) {
    val currentSortReversed by rememberUpdatedState(sortReversed)
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    LaunchedEffect(Unit) {
        snapshotFlow { currentSortReversed }
            .drop(1)
            .collect { currentScrollToTop() }
    }
}
