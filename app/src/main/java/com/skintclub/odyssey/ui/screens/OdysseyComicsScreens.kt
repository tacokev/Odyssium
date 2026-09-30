package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.ClubGrouping
import com.skintclub.odyssey.club.ODYSSEY_COMIC_ROUTE
import com.skintclub.odyssey.club.loadComicImageUrls
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.theme.capsuleColor
import com.skintclub.odyssey.ui.utils.appBarScrollBehavior
import com.skintclub.odyssey.ui.utils.backToMain
import com.skintclub.odyssey.ui.utils.collapsingTopAppBarColors
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import kotlinx.coroutines.CancellationException

/** A comic's shape before its image has loaded - an Elsewhere in Odyssey strip's (700px
 * wide, ~4200-4300px tall), the most common kind - so placeholders take up about the space
 * the comic will. */
private const val COMIC_FALLBACK_ASPECT_RATIO = 700f / 4264f

private const val ELSEWHERE_PREFIX = "Elsewhere in Odyssey"

private fun isElsewhereSeries(series: ClubGrouping) = series.name.startsWith("$ELSEWHERE_PREFIX - ")

/** A series' name without the part every tile would repeat: `Elsewhere in Odyssey - "New
 * Talent"` -> `New Talent`. */
private fun comicSeriesTitle(series: ClubGrouping): String =
    series.name
        .removePrefix("$ELSEWHERE_PREFIX - ")
        .removeSurrounding("\"")
        .ifBlank { series.name }

/** A comic's name without the series prefix: `Elsewhere in Odyssey #179: "Planet Bog"` ->
 * `#179: "Planet Bog"`, `Elsewhere in Odyssey - "The Cow Story"` -> `"The Cow Story"`. */
private fun comicTitle(comic: ClubContent): String =
    (comic.shortName ?: comic.name).removePrefix(ELSEWHERE_PREFIX).trimStart(' ', '-', ':').ifBlank { comic.name }

private fun ClubGrouping.comicArticles() = contentList.filter { it.type == "Article" }

private fun comicRoute(series: ClubGrouping, comic: ClubContent) =
    "odyssey_comic/${Uri.encode(series.id)}/${Uri.encode(comic.id)}"

private val numberedComicRegex = Regex("""^#(\d+)\s*[:-]?\s*(.*)$""")

/** A comic's grid tile title and subtitle. A numbered strip splits into its title and number
 * (`#179: "Planet Bog"` -> `Planet Bog` / `#179`), so the one-line title isn't mostly number;
 * anything else is titled as-is under its series' name. */
private fun comicTileText(comic: ClubContent, series: ClubGrouping): Pair<String, String> {
    val title = comicTitle(comic)
    val match = numberedComicRegex.matchEntire(title)
    val rest = match?.groupValues?.get(2)?.trim()?.removeSurrounding("\"")
    return if (match != null && !rest.isNullOrBlank()) {
        rest to "#${match.groupValues[1]}"
    } else {
        title to comicSeriesTitle(series)
    }
}

/** Every comic series, in the club's own index order, reached from Home's "Comics" tile. */
@Composable
fun OdysseyComicsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val series by viewModel.comicSeries.collectAsState()

    ComicTileGrid(navController, "Comics") {
        items(items = series, key = { it.id }) { grouping ->
            val count = grouping.comicArticles().size
            ComicSeriesTile(
                title = comicSeriesTitle(grouping),
                subtitle = if (isElsewhereSeries(grouping)) "$ELSEWHERE_PREFIX · $count comics" else "$count comics",
                thumbnailUrl = grouping.imageUrl,
                modifier = Modifier.clickable {
                    navController.navigate("odyssey_comic_series/${Uri.encode(grouping.id)}")
                },
            )
        }
    }
}

/** A series cover's card shape: the tallest covers' (337x400), which most arcs since Snow Way
 * Home use - older ones run 354x400 or square. */
private const val SERIES_COVER_ASPECT_RATIO = 337f / 400f

/** [CoverGridTile], but for series covers, which (unlike the square art elsewhere) come in
 * several shapes. Every tile gets the same card, so the grid lines up, with the cover fitted
 * whole inside it - cropping cut titles off the top of tall covers, and logos and borders off
 * the sides of square ones. */
@Composable
private fun ComicSeriesTile(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(320, 380)
            .crossfade(false)
            .build()
    }

    val painter = rememberAsyncImagePainter(request)
    val intrinsicSize = painter.intrinsicSize
    val coverAspectRatio = if (intrinsicSize.isSpecified && intrinsicSize.width > 0 && intrinsicSize.height > 0) {
        intrinsicSize.width / intrinsicSize.height
    } else {
        SERIES_COVER_ASPECT_RATIO
    }
    val shape = RoundedCornerShape(ThumbnailCornerRadius)

    Column(modifier = modifier.padding(12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(SERIES_COVER_ASPECT_RATIO)
                .clip(shape)
                .background(MaterialTheme.colorScheme.capsuleColor),
        ) {
            // Sized to the cover's own shape (limited by width if it's wider than the card,
            // by height if narrower) so the cover gets rounded corners of its own, not just
            // the card around it.
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(coverAspectRatio, matchHeightConstraintsFirst = coverAspectRatio < SERIES_COVER_ASPECT_RATIO)
                    .clip(shape),
            )
        }

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

/** One comic series as a grid of its comics, each opening [OdysseyComicReaderScreen]. */
@Composable
fun OdysseyComicSeriesScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    groupingId: String,
) {
    val allSeries by viewModel.comicSeries.collectAsState()
    val series = allSeries.firstOrNull { it.id == groupingId } ?: return

    ComicTileGrid(navController, comicSeriesTitle(series)) {
        items(items = series.comicArticles(), key = { it.id }) { comic ->
            val (title, subtitle) = comicTileText(comic, series)
            CoverGridTile(
                title = title,
                subtitle = subtitle,
                thumbnailUrl = comic.thumbnailSmall ?: series.imageUrl,
                modifier = Modifier.clickable {
                    navController.navigate(comicRoute(series, comic))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicTileGrid(
    navController: NavController,
    title: String,
    content: LazyGridScope.() -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        content = content,
    )

    ComicTopBar(navController, title, scrollBehavior)
}

private sealed interface ComicImagesState {
    data object Loading : ComicImagesState
    data object Failed : ComicImagesState
    class Ready(val imageUrls: List<String>) : ComicImagesState
}

/** One comic's images, full width top to bottom. Ends with a button on to the next comic -
 * the rest of the series, then the start of the next series. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdysseyComicReaderScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    groupingId: String,
    contentId: String,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    val allSeries by viewModel.comicSeries.collectAsState()
    val series = allSeries.firstOrNull { it.id == groupingId } ?: return
    val comics = series.comicArticles()
    val comic = comics.firstOrNull { it.id == contentId } ?: return
    val next = remember(allSeries, series, comic) {
        comics.getOrNull(comics.indexOf(comic) + 1)?.let { series to it }
            ?: allSeries.getOrNull(allSeries.indexOf(series) + 1)
                ?.let { nextSeries -> nextSeries.comicArticles().firstOrNull()?.let { nextSeries to it } }
    }
    val scrollBehavior = appBarScrollBehavior()
    val listModifier = Modifier
        .fillMaxSize()
        .nestedScroll(scrollBehavior.nestedScrollConnection)
    val contentPadding = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
        .asPaddingValues()
    val nextButton: LazyListScope.() -> Unit = {
        next?.let { (nextSeries, nextComic) ->
            item(key = "next") {
                NextComicButton(comicTitle(nextComic)) {
                    navController.navigate(comicRoute(nextSeries, nextComic)) {
                        // Back from the next comic returns to the grid, not through every
                        // comic read along the way.
                        popUpTo(ODYSSEY_COMIC_ROUTE) { inclusive = true }
                    }
                }
            }
        }
    }

    ComicImagesContent(clubClient, contentId, listModifier, contentPadding, nextButton)
    // Warm the next comic's image list so the Next button opens straight to it.
    LaunchedEffect(next) {
        next?.let { (_, nextComic) ->
            runCatching { loadComicImageUrls(context, clubClient, nextComic.id) }
        }
    }

    ComicTopBar(navController, comicTitle(comic), scrollBehavior)
}

@Composable
private fun ComicImagesContent(
    clubClient: ClubClient,
    contentId: String,
    modifier: Modifier,
    contentPadding: PaddingValues,
    footer: LazyListScope.() -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<ComicImagesState>(ComicImagesState.Loading, contentId, attempt) {
        value = ComicImagesState.Loading
        value = try {
            ComicImagesState.Ready(loadComicImageUrls(context, clubClient, contentId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ComicImagesState.Failed
        }
    }

    when (val current = state) {
        ComicImagesState.Loading -> CenteredLoading()
        ComicImagesState.Failed -> CenteredRetry("Couldn't load this comic") { attempt++ }
        is ComicImagesState.Ready -> LazyColumn(contentPadding = contentPadding, modifier = modifier) {
            if (current.imageUrls.isEmpty()) {
                // A story's prose version: an Article in the series with no comic in it.
                item(key = "empty") {
                    Text(
                        text = "There's no comic to show for this one.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            itemsIndexed(current.imageUrls, key = { index, _ -> "image_$index" }) { _, url ->
                ComicImage(url, contentDescription = null)
            }
            footer()
        }
    }
}

@Composable
private fun NextComicButton(title: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        FilledTonalButton(onClick = onClick) {
            Text("Next: $title", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ComicPlaceholder(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(COMIC_FALLBACK_ASPECT_RATIO),
    ) {
        content()
    }
}

/** One comic image at full width. It's decoded at its original size (700px wide for a strip)
 * rather than the screen's, which for a ~4300px-tall strip would be a ~28MB bitmap for no
 * extra detail. */
@Composable
private fun ComicImage(
    url: String,
    contentDescription: String?,
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val request = remember(url, attempt) {
        ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .setParameter("attempt", attempt, memoryCacheKey = null)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val painterState = painter.state
    val intrinsicSize = painter.intrinsicSize
    val aspectRatio = if (intrinsicSize.isSpecified && intrinsicSize.width > 0 && intrinsicSize.height > 0) {
        intrinsicSize.width / intrinsicSize.height
    } else {
        COMIC_FALLBACK_ASPECT_RATIO
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio),
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxSize(),
        )
        when (painterState) {
            is AsyncImagePainter.State.Loading -> CircularProgressIndicator()
            is AsyncImagePainter.State.Error -> TextButton(onClick = { attempt++ }) { Text("Couldn't load comic - retry") }
            else -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicTopBar(
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
