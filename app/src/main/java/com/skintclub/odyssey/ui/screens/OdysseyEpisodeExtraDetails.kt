package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.ClubContentDetails
import com.skintclub.odyssey.constants.ThumbnailCornerRadius
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.viewmodels.OdysseyViewModel.OdysseyAlbum
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

/**
 * The episode details page's extra details (air date, themes, bible verse, characters, crew,
 * devotional, recommendations), loaded from the content's full detail once the page opens.
 * Shown from the session cache immediately when it's already been fetched; otherwise fetched
 * here, and that fetch is cancelled if the page is left (or replaced, as the randomizer does)
 * before it finishes. Each field only appears when the club actually has it.
 */
@Composable
fun OdysseyEpisodeExtraDetails(
    navController: NavController,
    contentId: String,
    albums: List<OdysseyAlbum>,
) {
    val context = LocalContext.current
    val clubClient = remember { ClubClient(context) }
    var details by remember(contentId) { mutableStateOf(clubClient.cachedContentDetails(contentId)) }
    var loading by remember(contentId) { mutableStateOf(details == null) }

    LaunchedEffect(contentId) {
        if (details != null) return@LaunchedEffect
        details = try {
            clubClient.getContentDetails(contentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        loading = false
    }

    if (details == null && loading) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .size(24.dp),
        )
    }

    // Already-cached details show straight away; fetched ones fade and slide up into place.
    val visibleState = remember(contentId) { MutableTransitionState(details != null) }
    visibleState.targetState = details != null
    val slideOffset = with(LocalDensity.current) { 24.dp.roundToPx() }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { slideOffset },
    ) {
        val loaded = details ?: return@AnimatedVisibility
        ExtraDetailsContent(navController, contentId, albums, loaded)
    }
}

@Composable
private fun ExtraDetailsContent(
    navController: NavController,
    contentId: String,
    albums: List<OdysseyAlbum>,
    loaded: ClubContentDetails,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // A regular episode's air_date isn't its real air date, so it's only shown for
        // everything else (podcasts, specials, ...).
        if (!loaded.subtype.equals("Episode", ignoreCase = true)) {
            formatAirDate(loaded.airDate)?.let { DetailField("Air Date", it) }
        }

        val themes = (loaded.tags.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) } +
            listOfNotNull(loaded.longTheme?.takeIf { it.isNotBlank() }))
            .distinct()
        if (themes.isNotEmpty()) DetailField("Themes", themes.joinToString(", "))

        loaded.bibleVerse?.takeIf { it.isNotBlank() }?.let { DetailField("Bible Verse", it) }

        val characters = loaded.characters.orEmpty()
            .filter { !it.name.isNullOrBlank() }
            .distinctBy { it.id ?: it.name }
        if (characters.isNotEmpty()) {
            DetailSection("Featured Characters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(characters, key = { it.id ?: it.name!! }) { character ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(76.dp),
                        ) {
                            AsyncImage(
                                model = character.thumbnailMedium ?: character.thumbnailSmall,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = character.name!!,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        val crew = loaded.authors.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct()
        if (crew.isNotEmpty()) DetailField("Crew", crew.joinToString(", "))

        val devotional = remember(loaded.devotional) {
            loaded.devotional
                ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
                ?.takeIf { it.isNotBlank() }
        }
        if (devotional != null) {
            DetailSection("Devotional") {
                ExpandableDescription(devotional, collapsedLines = 4)
            }
        }

        val recommendations = loaded.recommendations.orEmpty()
            .filter { it.id.isNotBlank() && it.id != contentId }
            .distinctBy { it.id }
        if (recommendations.isNotEmpty()) {
            DetailSection("Recommendations") {
                RecommendationsRow(navController, recommendations, albums)
            }
        }
    }
}

/** Recommended episodes, left to right. Tapping one opens its own details page when it's in
 * an album (so the randomizer-style browsing continues), otherwise plays it. */
@Composable
private fun RecommendationsRow(
    navController: NavController,
    recommendations: List<ClubContent>,
    albums: List<OdysseyAlbum>,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val locations = remember(albums) {
        buildMap {
            albums.forEach { album ->
                album.episodes.forEachIndexed { index, episode ->
                    (episode["id"] as? String)?.let { put(it, album.name to index) }
                }
            }
        }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(recommendations, key = { it.id }) { content ->
            Column(
                modifier = Modifier
                    .width(128.dp)
                    .clickable {
                        val location = locations[content.id]
                        if (location != null) {
                            navController.navigate("odyssey_episode/${Uri.encode(location.first)}/${location.second}")
                        } else {
                            playerConnection?.playQueue(
                                OdysseyQueue(context, listOf(recommendationEpisode(content)), 0),
                            )
                        }
                    },
            ) {
                AsyncImage(
                    model = content.thumbnailMedium ?: content.thumbnailSmall,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = content.shortName?.takeIf { it.isNotBlank() } ?: content.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An album-less recommendation as a playable episode map. It has no download_url, so it plays
 * through a content request (see odysseyEpisodeToMediaItem). */
private fun recommendationEpisode(content: ClubContent): Map<String, Any> = buildMap {
    put("id", content.id)
    put("name", content.name)
    put("album_name", content.albumName ?: "")
    put("download_url", content.downloadUrl ?: "")
    put("image_small", content.thumbnailSmall ?: "")
    put("image_medium", content.thumbnailMedium ?: "")
    put("image_large", content.thumbnailMedium ?: "")
    put("is_bookmarked", content.isBookmarked)
    content.shortName?.let { put("short_name", it) }
    content.description?.let { put("description", it) }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "2020-03-09T06:00:00.000Z" -> "March 9, 2020". Only the date part is used: the club stores
 * air dates as midnight US time, so converting to local time could land on the wrong day. */
private fun formatAirDate(airDate: String?): String? {
    val date = airDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}
