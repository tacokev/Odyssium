package com.skintclub.odyssey.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.isPlayableContent
import com.skintclub.odyssey.constants.ListThumbnailSize
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaMetadata
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.component.ListItem
import com.skintclub.odyssey.ui.component.LocalMenuState
import com.skintclub.odyssey.ui.component.MediaMetadataListItem
import com.skintclub.odyssey.ui.menu.OdysseyEpisodeMenu
import com.skintclub.odyssey.utils.joinByBullet
import com.skintclub.odyssey.viewmodels.OdysseySearchResults
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import com.skintclub.odyssey.viewmodels.odysseyGroupingRoute
import com.skintclub.odyssey.viewmodels.odysseyGroupingTypeLabel

/** The search bar's results: matching episodes first, then matching groupings (albums,
 * series, playlists, ...). Opening a grouping or playing an episode closes search via [onDismiss]. */
@Composable
fun OdysseySearchResultsList(
    results: OdysseySearchResults,
    navController: NavController,
    viewModel: OdysseyViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val playVideosAsAudio by viewModel.playVideosAsAudio.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (results.episodes.isNotEmpty()) {
            item(key = "episodes_header") { SearchSectionHeader("Episodes") }
        }
        items(
            items = results.episodes,
            key = { "episode_${it.episode["id"]}" },
        ) { result ->
            val episode = result.episode
            val metadata = odysseyEpisodeToMediaMetadata(context, episode)
            val mediaItem = odysseyEpisodeToMediaItem(context, episode)

            MediaMetadataListItem(
                mediaMetadata = metadata,
                trailingContent = {
                    IconButton(
                        onClick = {
                            menuState.show {
                                OdysseyEpisodeMenu(
                                    navController = navController,
                                    viewModel = viewModel,
                                    mediaMetadata = metadata,
                                    mediaItem = mediaItem,
                                    albumName = result.albumName,
                                    episodeIndex = result.queueIndex,
                                    isLiked = episode["is_bookmarked"] == true,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        playerConnection?.playQueue(
                            OdysseyQueue(
                                context = context,
                                episodes = result.queue,
                                startIndex = result.queueIndex,
                            ),
                        )
                        onDismiss()
                    },
            )
        }

        if (results.groupings.isNotEmpty()) {
            item(key = "groupings_header") { SearchSectionHeader("Groupings") }
        }
        items(
            items = results.groupings,
            key = { "grouping_${it.type}_${it.id}" },
        ) { grouping ->
            ListItem(
                title = grouping.name,
                subtitle = joinByBullet(
                    odysseyGroupingTypeLabel(grouping.type),
                    "${grouping.contentList.count { isPlayableContent(it, playVideosAsAudio) }} episodes",
                ),
                thumbnailContent = {
                    AsyncImage(
                        model = grouping.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(ListThumbnailSize)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        navController.navigate(odysseyGroupingRoute(grouping))
                    },
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
