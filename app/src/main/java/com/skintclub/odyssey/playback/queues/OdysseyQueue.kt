package com.skintclub.odyssey.playback.queues

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.skintclub.odyssey.models.MediaMetadata

class OdysseyQueue(
    val episodes: List<Map<String, Any>>,
    val startIndex: Int,
    val signedCookie: String?,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    override suspend fun getInitialStatus(): Queue.Status =
        Queue.Status(
            title = episodes.getOrNull(startIndex)?.get("album_name") as? String,
            items = episodes.map { odysseyEpisodeToMediaItem(it, signedCookie) },
            mediaItemIndex = startIndex,
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
 * map by [com.skintclub.odyssey.club.ClubClient.fetchAlbumEpisodes]. */
fun extractAlbumYear(episode: Map<String, Any>): String? =
    when (val value = episode["album_copyright_year"]) {
        is String -> value.takeIf { it.isNotBlank() }
        is Double -> value.toInt().takeIf { it > 0 }?.toString()
        is Int -> value.takeIf { it > 0 }?.toString()
        else -> null
    }

fun odysseyEpisodeToMediaMetadata(episode: Map<String, Any>): MediaMetadata {
    val artUrl =
        (episode["image_large"] as? String)?.takeIf { it.isNotBlank() }
            ?: (episode["image_medium"] as? String)?.takeIf { it.isNotBlank() }
    val id = episode["id"] as? String ?: episode["download_url"] as? String ?: ""
    val title = episode["name"] as? String ?: "Unknown"
    val albumName = episode["album_name"] as? String ?: "Unknown"

    return MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = "Adventures in Odyssey")),
        duration = -1,
        thumbnailUrl = artUrl,
        album = MediaMetadata.Album(id = albumName, title = albumName),
        description = extractDescription(episode),
    )
}

fun odysseyEpisodeToMediaItem(
    episode: Map<String, Any>,
    signedCookie: String?,
): MediaItem {
    val urlPrefix = "https://media.adventuresinodyssey.com/"
    val downloadUrl = episode["download_url"] as? String ?: ""
    val url =
        if (signedCookie != null) {
            "$urlPrefix$downloadUrl$signedCookie"
        } else {
            "$urlPrefix$downloadUrl"
        }

    val metadata = odysseyEpisodeToMediaMetadata(episode)

    return MediaItem.Builder()
        .setMediaId(metadata.id)
        .setUri(url)
        .setCustomCacheKey(metadata.id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist("Adventures in Odyssey")
                .setArtworkUri(metadata.thumbnailUrl?.let { Uri.parse(it) })
                .setAlbumTitle(metadata.album?.title)
                .setDescription(metadata.description)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build(),
        ).build()
}
