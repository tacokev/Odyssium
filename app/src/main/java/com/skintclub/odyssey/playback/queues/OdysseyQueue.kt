package com.skintclub.odyssey.playback.queues

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.skintclub.odyssey.constants.ClubAudioLoadMode
import com.skintclub.odyssey.constants.ClubAudioLoadModeKey
import com.skintclub.odyssey.constants.ShowEpisodeNumberKey
import com.skintclub.odyssey.extensions.toEnum
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.playback.ODYSSEY_MEDIA_HOST
import com.skintclub.odyssey.playback.isSignedCookiePath
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get

class OdysseyQueue(
    val context: Context,
    val episodes: List<Map<String, Any>>,
    val startIndex: Int,
    override val preloadItem: MediaMetadata? = null,
    /** Where in the starting episode to begin, in milliseconds. */
    val startPositionMs: Long = 0L,
) : Queue {
    override suspend fun getInitialStatus(): Queue.Status =
        Queue.Status(
            title = episodes.getOrNull(startIndex)?.get("album_name") as? String,
            items = episodes.map { odysseyEpisodeToMediaItem(context, it) },
            mediaItemIndex = startIndex,
            position = startPositionMs,
        )

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage(): List<MediaItem> = emptyList()
}

/** The Odyssey API's exact key for episode synopsis text isn't documented, so this tries
 * every field name it's been seen or plausibly named under. */
fun extractDescription(episode: Map<String, Any>): String? =
    listOf(
        "description",
        "long_description",
        "short_description",
        "synopsis",
        "summary",
        "episode_description",
        "content_description",
    ).firstNotNullOfOrNull { key -> (episode[key] as? String)?.takeIf { it.isNotBlank() } }

/** The contentgrouping API has no per-episode air date; the only release-date signal it
 * exposes at all is this copyright year on the episode's album, stashed onto the episode
 * map by [com.skintclub.odyssey.club.ClubClient.albumEpisodesFrom]. */
fun extractAlbumYear(episode: Map<String, Any>): String? =
    when (val value = episode["album_copyright_year"]) {
        is String -> value.takeIf { it.isNotBlank() }
        is Double -> value.toInt().takeIf { it > 0 }?.toString()
        is Int -> value.takeIf { it > 0 }?.toString()
        else -> null
    }

/** Used by the "Exclude bonus episodes" randomizer setting to keep bonus content out of the
 * random-episode pick; bonus episodes stay visible everywhere else (album lists, etc.). */
fun isBonusEpisode(episode: Map<String, Any>): Boolean =
    (episode["short_name"] as? String)?.contains("BONUS", ignoreCase = true) == true

private val partOfRegex = Regex("""Part\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)

/** Used by the "Exclude subsequent parts" randomizer setting to keep the random-episode
 * picker from landing on part 2, 3, etc. of a multi-part story - e.g. a short_name like
 * "#571: For Better or for Worse, Part 2 of 2". Part 1 (and any non-multipart episode,
 * which won't match at all) is always eligible. */
fun isSubsequentPart(episode: Map<String, Any>): Boolean {
    val shortName = episode["short_name"] as? String ?: return false
    val partNumber = partOfRegex.find(shortName)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return partNumber != 1
}

/** Prefix marking a [MediaMetadata.Artist.id] as standing in for the episode's containing
 * album/content-grouping rather than a real artist, so the player UI knows to make that name
 * clickable (opening the grouping) without mistaking it for an actual artist to navigate to. */
const val ODYSSEY_GROUPING_ARTIST_ID_PREFIX = "odyssey_grouping:"

/** [showEpisodeNumber] lets a caller building many of these (e.g. a list's rows) pass the
 * setting in once, instead of each call doing its own blocking DataStore read. */
fun odysseyEpisodeToMediaMetadata(
    context: Context,
    episode: Map<String, Any>,
    showEpisodeNumber: Boolean? = null,
): MediaMetadata {
    val artUrl =
        (episode["image_large"] as? String)?.takeIf { it.isNotBlank() }
            ?: (episode["image_medium"] as? String)?.takeIf { it.isNotBlank() }
    val id = episode["id"] as? String ?: episode["download_url"] as? String ?: ""
    // Today an episode's containing grouping is always its Album, but other grouping types
    // (playlists, themes) may become browsable later - if an episode ever appears without an
    // album, this should prefer that other grouping's name rather than falling back blindly.
    val albumName = episode["album_name"] as? String ?: "Unknown"
    val title = if (showEpisodeNumber ?: context.dataStore.get(ShowEpisodeNumberKey, false)) {
        (episode["short_name"] as? String)?.takeIf { it.isNotBlank() } ?: (episode["name"] as? String ?: "Unknown")
    } else {
        episode["name"] as? String ?: "Unknown"
    }

    return MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = "$ODYSSEY_GROUPING_ARTIST_ID_PREFIX$albumName", name = albumName)),
        duration = -1,
        thumbnailUrl = artUrl,
        album = MediaMetadata.Album(id = albumName, title = albumName),
        description = extractDescription(episode),
    )
}

/** The cover to show for [episode] in a scrolling list/grid. Prefers the album's own cover
 * (~400px): every episode in an album shares that one URL, so it's downloaded and decoded once
 * and then served from memory. The per-episode thumbnails are each a separate file (the
 * "medium" one an ~835px, 1.3MB PNG), so a row of them means a fresh download + decode per
 * card - which is what made scrolling stutter. */
fun odysseyEpisodeThumbnailUrl(episode: Map<String, Any>): String? =
    // Set only on album-less episodes (podcasts), which have no shared cover.
    (episode["list_thumbnail"] as? String)?.takeIf { it.isNotBlank() }
        ?: (episode["image_large"] as? String)?.takeIf { it.isNotBlank() }
        ?: (episode["image_small"] as? String)?.takeIf { it.isNotBlank() }

/** Scheme for a placeholder URI that stands in for an episode's real streaming URL when the
 * "Content request" audio-loading mode is active, or for content the signed cookie doesn't
 * cover (e.g. podcasts) in any mode. [MusicService]'s [ResolvingDataSource]
 * resolves it to a fresh, ready-to-stream `download_url` right before that episode actually
 * loads, via [com.skintclub.odyssey.club.ClubClient.getContentDownloadUrl] - so the extra
 * per-episode round trip only happens for episodes actually played, not the whole queue. */
const val ODYSSEY_CONTENT_URI_SCHEME = "odyssey-content"

/** In "Signed cookie" mode the URI is the bare CDN URL - [resolveOdysseyDataSpec] attaches the
 * current cookie at request time, so a queue built now doesn't carry a cookie that's expired by
 * the time it plays. Content the cookie doesn't cover (see [isSignedCookiePath]) always uses a
 * "Content request" placeholder instead. */
fun odysseyEpisodeToMediaItem(
    context: Context,
    episode: Map<String, Any>,
): MediaItem {
    val downloadUrl = episode["download_url"] as? String ?: ""
    val contentId = episode["id"] as? String
    val loadMode = context.dataStore[ClubAudioLoadModeKey].toEnum(ClubAudioLoadMode.SIGNED_COOKIE)
    val useContentRequest = loadMode == ClubAudioLoadMode.CONTENT_REQUEST || !isSignedCookiePath(downloadUrl)
    // A secret episode (see SecretEpisodes.kt) has no club content id to request, so it's
    // always streamed with the signed cookie, whatever the load mode.
    val isSecret = episode["secret"] == true
    val url =
        if (useContentRequest && !isSecret && !contentId.isNullOrBlank()) {
            "$ODYSSEY_CONTENT_URI_SCHEME://$contentId"
        } else {
            "https://$ODYSSEY_MEDIA_HOST/${downloadUrl.trimStart('/')}"
        }

    val metadata = odysseyEpisodeToMediaMetadata(context, episode)

    return MediaItem.Builder()
        .setMediaId(metadata.id)
        .setUri(url)
        .setCustomCacheKey(metadata.id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist(metadata.artists.joinToString { it.name })
                .setArtworkUri(metadata.thumbnailUrl?.let { Uri.parse(it) })
                .setAlbumTitle(metadata.album?.title)
                .setDescription(metadata.description)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build(),
        ).build()
}
