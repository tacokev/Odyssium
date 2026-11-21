package com.skintclub.odyssey.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.skintclub.odyssey.club.CLUB_WEB_HOST
import com.skintclub.odyssey.club.ClubBanner
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.ClubLink
import com.skintclub.odyssey.club.parseClubLink
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.ui.theme.capsuleColor
import com.skintclub.odyssey.viewmodels.HomeCategory
import com.skintclub.odyssey.viewmodels.HomeFeed
import com.skintclub.odyssey.viewmodels.NewContentKind
import com.skintclub.odyssey.viewmodels.publishedLabel
import kotlinx.coroutines.delay

/** How long each banner shows before the next slides in. */
private const val BANNER_INTERVAL_MS = 5_000L

/** The club's banners are 960x400. */
private const val BANNER_ASPECT_RATIO = 2.4f

/**
 * "Alternate home page": the club's banners, its newest episodes, podcasts, videos and comics,
 * then the usual categories as smaller tiles, two to a row.
 */
@Composable
fun AlternateHome(
    feed: HomeFeed,
    categories: List<HomeCategory>,
    navController: NavController,
    onOpenLink: (ClubLink) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(contentPadding = contentPadding, modifier = modifier) {
        if (feed.banners.isNotEmpty()) {
            item(key = "banners") {
                Banners(
                    banners = feed.banners,
                    onClick = { banner -> openBanner(context, banner, onOpenLink) },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        if (feed.newContent.isNotEmpty()) {
            item(key = "new_title") { SectionTitle("New on the Club") }
            item(key = "new") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(feed.newContent, key = { it.id }) { content ->
                        NewContentCard(
                            content = content,
                            kind = feed.kinds[content.id],
                            onClick = { onOpenLink(ClubLink.Content(content.id)) },
                        )
                    }
                }
            }
            item(key = "browse_title") { SectionTitle("Browse") }
        }

        items(categories.chunked(2), key = { row -> row.first().route }) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                row.forEach { category ->
                    CategoryTile(
                        category = category,
                        onClick = { navController.navigate(category.route) },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // An odd one out keeps its half width.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** The banners, one at a time, moving on every [BANNER_INTERVAL_MS] unless one's being
 * swiped; dots below show which one it is. */
@Composable
private fun Banners(
    banners: List<ClubBanner>,
    onClick: (ClubBanner) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { banners.size }
    LaunchedEffect(pagerState, banners.size) {
        if (banners.size < 2) return@LaunchedEffect
        while (true) {
            delay(BANNER_INTERVAL_MS)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % banners.size)
            }
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 8.dp,
            key = { banners[it].id.ifBlank { banners[it].imageUrl.orEmpty() } },
        ) { page ->
            val banner = banners[page]
            AsyncImage(
                model = banner.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(BANNER_ASPECT_RATIO)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.capsuleColor)
                    .clickable { onClick(banner) },
            )
        }
        if (banners.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                repeat(banners.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** A new content item: its cover, what it is and when it came out, and its name. */
@Composable
private fun NewContentCard(
    content: ClubContent,
    kind: NewContentKind?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail = content.thumbnailMedium?.takeIf { it.isNotBlank() } ?: content.thumbnailSmall
    val request = remember(thumbnail) {
        ImageRequest.Builder(context).data(thumbnail).size(320, 320).build()
    }
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(ThumbnailCornerRadius))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ThumbnailCornerRadius))
                .background(MaterialTheme.colorScheme.capsuleColor),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = listOfNotNull(kind?.label, publishedLabel(content)).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = content.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Opens what [banner] links to: club content and groupings in the app, anything else (the
 * radio page, the free trial...) in the browser. */
private fun openBanner(context: Context, banner: ClubBanner, onOpenLink: (ClubLink) -> Unit) {
    val link = banner.hyperlink?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val url = if (link.startsWith("/")) "https://$CLUB_WEB_HOST$link" else link
    parseClubLink(url)?.let {
        onOpenLink(it)
        return
    }
    // Straight to a browser: with "Open club links in the app" on, a plain view intent for a
    // club page would come back to the app, which can't open it.
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching {
        context.startActivity(
            Intent(view).apply {
                selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            },
        )
    }.recoverCatching { context.startActivity(view) }
}
