package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.constants.AlbumGridColumns
import com.skintclub.odyssey.constants.AlbumGridColumnsKey
import com.skintclub.odyssey.constants.AlbumGridConnectedSquaresKey
import com.skintclub.odyssey.constants.AppBarHeight
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.utils.rememberEnumPreference
import com.skintclub.odyssey.utils.rememberPreference
import com.skintclub.odyssey.viewmodels.OdysseyViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: OdysseyViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val albums by viewModel.albums.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val sortDescending by viewModel.sortDescending.collectAsState()
    val gridState = rememberLazyGridState()
    val pullRefreshState = rememberPullToRefreshState()
    val albumGridColumns by rememberEnumPreference(AlbumGridColumnsKey, defaultValue = AlbumGridColumns.TWO)
    val albumGridConnectedSquares by rememberPreference(AlbumGridConnectedSquaresKey, defaultValue = false)

    LaunchedEffect(sortDescending) {
        gridState.scrollToItem(0)
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        state = pullRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullRefreshState,
                isRefreshing = isRefreshing,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = AppBarHeight),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (albumGridColumns == AlbumGridColumns.THREE) 3 else 2),
            state = gridState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
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
    }
}

/**
 * A deliberately lightweight grid tile: a plain [Box] rather than the shared
 * `GridItem`'s `BoxWithConstraints`, and no marquee. `BoxWithConstraints` forces a
 * subcomposition pass per item, which is cheap for the handful of items a LazyRow
 * used to show but noticeably janks a full-screen 2-column grid of album covers.
 *
 * Shared between the Albums and Playlists tabs. Most tiles show a cover image
 * ([thumbnailUrl]); the Playlists tab's fixed "Liked"/"Downloaded" entries instead pass
 * [icon] (with [iconBackgroundColor]) for a big icon in place of artwork.
 */
@Composable
fun CoverGridTile(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    connectedSquare: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    iconBackgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val context = LocalContext.current

    // A 2-column grid can show 100+ of these at once as you scroll. Decoding
    // each cover at the tile's on-screen size (400-500px on a dense display)
    // means every one costs ~1MB of GPU-cached bitmap memory, which blows past
    // the cache with a large library and forces re-decodes — the actual source
    // of the frame drops. 320px is still plenty sharp for a thumbnail this size
    // and cuts that cost by more than half.
    val request = remember(thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(320, 320)
            .crossfade(false)
            .build()
    }

    @Composable
    fun Cover(modifier: Modifier) {
        if (icon != null) {
            Box(
                modifier = modifier.background(iconBackgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }
    }

    if (connectedSquare) {
        // Edge-to-edge: no gap between tiles and no rounded corners, so the covers
        // form one unbroken grid of squares. There's no room below the image for
        // the label in that layout, so it's scrimmed onto the bottom of the cover.
        Box(modifier = modifier.aspectRatio(1f)) {
            Cover(modifier = Modifier.fillMaxSize())

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        Column(modifier = modifier.padding(12.dp)) {
            Cover(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
