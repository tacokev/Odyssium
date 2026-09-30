package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.VideoShelf
import com.skintclub.odyssey.club.formatVideoLength
import com.skintclub.odyssey.club.videoThumbnailUrl
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.utils.appBarScrollBehavior
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.ui.utils.collapsingTopAppBarColors
import com.skintclub.odyssey.ui.video.VideoLengthBadge
import com.skintclub.odyssey.ui.video.VideoPlayerActivity
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

private fun videoShelfRoute(shelf: VideoShelf) = "odyssey_video_shelf/${Uri.encode(shelf.id)}"

/** How many of a shelf's videos its row shows before "See all" is the way to the rest. */
private const val SHELF_ROW_LIMIT = 20

private val SHELF_CARD_WIDTH = 220.dp

/** Every video shelf as a row, reached from Home's "Videos" tile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdysseyVideosScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val shelves by viewModel.videoShelves.collectAsState()
    val scrollBehavior = appBarScrollBehavior()

    if (shelves.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            items(items = shelves, key = { it.id }) { shelf ->
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    ShelfHeader(
                        title = shelf.title,
                        count = shelf.videos.size,
                        onSeeAll = { navController.navigate(videoShelfRoute(shelf)) },
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(
                            items = shelf.videos.take(SHELF_ROW_LIMIT),
                            key = { _, video -> video.id },
                        ) { index, video ->
                            VideoCard(
                                video = video,
                                width = SHELF_CARD_WIDTH,
                                onClick = {
                                    context.startActivity(VideoPlayerActivity.intent(context, shelf.title, shelf.videos, index))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    VideoTopBar(navController, "Videos", scrollBehavior)
}

@Composable
private fun ShelfHeader(title: String, count: Int, onSeeAll: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSeeAll)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (count == 1) "1 video" else "$count videos",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Icon(
            painterResource(R.drawable.arrow_forward),
            contentDescription = "See all",
        )
    }
}

/** One shelf's videos, all of them, as a grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdysseyVideoShelfScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    shelfId: String,
) {
    val context = LocalContext.current
    val shelves by viewModel.videoShelves.collectAsState()
    val shelf = shelves.firstOrNull { it.id == shelfId } ?: return
    val scrollBehavior = appBarScrollBehavior()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        itemsIndexed(items = shelf.videos, key = { _, video -> video.id }) { index, video ->
            VideoCard(
                video = video,
                width = null,
                onClick = {
                    context.startActivity(VideoPlayerActivity.intent(context, shelf.title, shelf.videos, index))
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }

    VideoTopBar(navController, shelf.title, scrollBehavior)
}

/** A video's 16:9 thumbnail (with a play badge and its length) over its title. [width] null
 * fills the space given, for grid cells. */
@Composable
private fun VideoCard(
    video: ClubContent,
    width: Dp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbnailUrl = videoThumbnailUrl(video)
    val request = remember(thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(480, 270)
            .crossfade(false)
            .build()
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
            ) {
                Icon(painterResource(R.drawable.play), contentDescription = null, tint = Color.White)
            }
            formatVideoLength(video.mediaLength)?.let { length ->
                VideoLengthBadge(
                    length,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.name.ifBlank { video.shortName.orEmpty() },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTopBar(
    navController: NavController,
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
) {
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
    )
}
