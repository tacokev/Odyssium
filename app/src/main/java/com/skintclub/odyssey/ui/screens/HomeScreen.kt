package com.skintclub.odyssey.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.constants.AppBarHeight
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.ui.theme.capsuleColor
import com.skintclub.odyssey.ui.utils.appBarScrollBehavior
import com.skintclub.odyssey.ui.utils.collapsingTopAppBarColors
import com.skintclub.odyssey.viewmodels.HomeCategory
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import com.skintclub.odyssey.viewmodels.homeCollageCoverRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: OdysseyViewModel = hiltViewModel(),
) {
    val categories by viewModel.homeCategories.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()
    val scrollBehavior = appBarScrollBehavior()

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
        LazyColumn(
            // Each tile pads itself 8dp top and bottom (16dp between tiles), so the extra 8dp
            // here gives the first and last tiles the same 16dp from the bars.
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                .add(WindowInsets(top = 8.dp, bottom = 8.dp))
                .asPaddingValues(),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            items(
                items = categories,
                key = { it.route },
            ) { category ->
                CategoryTile(
                    category = category,
                    onClick = { navController.navigate(category.route) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.home)) },
        actions = {
            IconButton(onClick = { navController.navigate("stats") }) {
                Icon(
                    painter = painterResource(R.drawable.trending_up),
                    contentDescription = stringResource(R.string.stats),
                )
            }
            IconButton(onClick = { navController.navigate("history") }) {
                Icon(
                    painter = painterResource(R.drawable.history),
                    contentDescription = stringResource(R.string.history),
                )
            }
            IconButton(onClick = { navController.navigate("settings") }) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = collapsingTopAppBarColors(),
    )
}

private const val COLLAGE_COLUMNS = 3
private const val COLLAGE_ROWS = 2

/** The gap between neighboring covers - and between the outer covers and the tile's edge - as a
 * fraction of the tile's height. The covers grow to fill whatever height is left. */
private const val COLLAGE_GAP_FRACTION = 0.05f

/** A full-width 2:1 tile: a grid of the category's covers on the right, faded
 * out toward the left into a solid panel carrying the name and size. */
@Composable
private fun CategoryTile(
    category: HomeCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileColor = MaterialTheme.colorScheme.capsuleColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(RoundedCornerShape(16.dp))
            .background(tileColor)
            .clickable(onClick = onClick),
    ) {
        if (category.imageUrls.isNotEmpty()) {
            CoverCollage(
                imageUrls = category.imageUrls,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to tileColor,
                        0.3f to tileColor,
                        0.75f to tileColor.copy(alpha = 0f),
                    ),
                ),
        )

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxHeight()
                // Stops short of the first collage column, so long names wrap instead of
                // running over a cover.
                .fillMaxWidth(0.4f)
                .padding(start = 20.dp),
        ) {
            Text(
                text = category.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = category.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [imageUrls] as an evenly spaced grid of equal squares, aligned to the tile's right edge. */
@Composable
private fun CoverCollage(
    imageUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val gap = maxHeight * COLLAGE_GAP_FRACTION
        val coverSize = (maxHeight - gap * (COLLAGE_ROWS + 1)) / COLLAGE_ROWS
        repeat(COLLAGE_ROWS) { row ->
            repeat(COLLAGE_COLUMNS) { column ->
                // Fewer covers than cells (a small category) just repeats them.
                val url = imageUrls[(row * COLLAGE_COLUMNS + column) % imageUrls.size]
                CollageCover(
                    url = url,
                    modifier = Modifier
                        .offset(
                            x = maxWidth - (coverSize + gap) * (COLLAGE_COLUMNS - column),
                            y = gap + (coverSize + gap) * row,
                        )
                        .size(coverSize)
                        .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

/** One collage cover, decoded at a small fixed size: the Home screen shows up to 30 of these
 * at once, each under half the tile's height. */
@Composable
private fun CollageCover(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Usually already in memory (prefetched at startup), which draws instantly; the image
    // loader's default crossfade only kicks in for one that still has to be loaded, so a
    // straggler eases in instead of popping.
    val request = remember(url) { homeCollageCoverRequest(context, url) }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
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
