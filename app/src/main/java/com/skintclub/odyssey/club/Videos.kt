package com.skintclub.odyssey.club

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

/*
 * Videos: the club's "Video" content, shelved the way the club site's own video pages are -
 * Animated Series and Video Podcasts are `content/search` queries by subtype (the site's
 * /animations and /videopodcasts pages), Bonus Videos are the "Bonus Video Home" groupings
 * (its /bonusvideos page), and anything else of type Video lands in "More Videos". Played in
 * [com.skintclub.odyssey.ui.video.VideoPlayerActivity], separately from the audio player.
 */

const val ODYSSEY_VIDEOS_ROUTE = "odyssey_videos"
const val ODYSSEY_VIDEO_SHELF_ROUTE = "odyssey_video_shelf/{shelfId}"

private const val VIDEO_TYPE = "Video"

/** One row of the Videos screen. [id] is stable across refreshes, for navigation. */
data class VideoShelf(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("videos") val videos: List<ClubContent>,
)

private data class VideoSearch(val id: String, val title: String, val subtype: String?, val orderBy: String)

private val videoSearches = listOf(
    VideoSearch("animations", "Animated Series", "Animation", "Episode_Number__c ASC"),
    VideoSearch("video_podcasts", "Video Podcasts", "Podcast", "Last_Published_Date__c DESC"),
)

private val allVideosSearch = VideoSearch("more_videos", "More Videos", null, "Last_Published_Date__c DESC")

/** The Bonus Videos shelves, from the grouping cache - no request needed. */
fun bonusVideoShelves(groupings: List<ClubGrouping>): List<VideoShelf> =
    groupings
        .filter { it.type == ClubClient.BONUS_VIDEO_HOME_TYPE }
        .map { grouping ->
            VideoShelf(
                id = "grouping_${grouping.id}",
                title = grouping.name.ifBlank { "Bonus Videos" },
                videos = grouping.contentList.filter { it.type == VIDEO_TYPE }.distinctBy { it.id },
            )
        }
        .filter { it.videos.isNotEmpty() }

/** Every video shelf: the searched ones, the Bonus Videos from [groupings], then "More Videos"
 * for whatever of type Video none of those hold. A search that fails keeps [previous]'s copy
 * of its shelf. Throws only if every search failed, so a dead connection doesn't wipe the
 * snapshot. */
suspend fun fetchVideoShelves(
    clubClient: ClubClient,
    groupings: List<ClubGrouping>,
    previous: List<VideoShelf>,
): List<VideoShelf> = coroutineScope {
    val previousById = previous.associateBy { it.id }
    val searches = (videoSearches + allVideosSearch).map { search ->
        search to async {
            runCatching { clubClient.searchContent(VIDEO_TYPE, search.subtype, search.orderBy) }
                .onFailure { it.printStackTrace() }
                .getOrNull()
        }
    }.map { (search, result) -> search to result.await() }
    if (searches.all { (_, result) -> result == null }) throw java.io.IOException("Every video search failed")

    fun shelfOf(search: VideoSearch, videos: List<ClubContent>?) =
        videos?.let { VideoShelf(search.id, search.title, it.filter { video -> video.type == VIDEO_TYPE }) }
            ?: previousById[search.id]

    val searched = searches.dropLast(1).mapNotNull { (search, videos) -> shelfOf(search, videos) }
    val bonus = bonusVideoShelves(groupings)
    val shownIds = (searched + bonus).flatMapTo(HashSet()) { shelf -> shelf.videos.map { it.id } }
    val more = searches.last().let { (search, videos) ->
        shelfOf(search, videos)?.let { shelf -> shelf.copy(videos = shelf.videos.filterNot { it.id in shownIds }) }
    }
    (searched + bonus + listOfNotNull(more)).filter { it.videos.isNotEmpty() }
}

/** Bonus Video shelves come from the grouping cache, so they're rebuilt from it here rather
 * than waiting on the next [fetchVideoShelves] - the rest keep their last-fetched copy. */
fun mergeBonusVideoShelves(shelves: List<VideoShelf>, groupings: List<ClubGrouping>): List<VideoShelf> {
    val bonus = bonusVideoShelves(groupings)
    if (bonus.isEmpty()) return shelves
    val bonusById = bonus.associateBy { it.id }
    val kept = shelves.map { bonusById[it.id] ?: it }
    val keptIds = kept.mapTo(HashSet()) { it.id }
    val newBonus = bonus.filterNot { it.id in keptIds }
    // New bonus shelves go before "More Videos", which is always last.
    val moreIndex = kept.indexOfFirst { it.id == allVideosSearch.id }.takeIf { it >= 0 } ?: kept.size
    return kept.take(moreIndex) + newBonus + kept.drop(moreIndex)
}

/** "12:34" / "1:02:03" for a length in milliseconds - the unit of the club's media_length,
 * as its own site's player reads it; null for none. */
fun formatVideoLength(lengthMs: Long): String? {
    val seconds = lengthMs / 1000
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The widest thumbnail a video has, for its 16:9 card. */
fun videoThumbnailUrl(video: ClubContent): String? =
    video.thumbnailMedium?.takeIf { it.isNotBlank() } ?: video.thumbnailSmall?.takeIf { it.isNotBlank() }

/**
 * The video shelves as last fetched, in their own file so the Videos screen and Home tile are
 * ready on a cold start instead of waiting on a handful of searches.
 */
object VideoShelvesSnapshot {
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, "video_shelves.json")

    fun load(context: Context): List<VideoShelf> =
        runCatching {
            val type = object : TypeToken<List<VideoShelf>>() {}.type
            gson.fromJson<List<VideoShelf>>(file(context).readText(), type)
        }.getOrNull().orEmpty()
            // Gson skips Kotlin's null checks; drop anything a malformed file left half-filled.
            .filter {
                @Suppress("SENSELESS_COMPARISON")
                it.id != null && it.title != null && it.videos != null
            }

    fun save(context: Context, shelves: List<VideoShelf>) {
        runCatching { file(context).writeText(gson.toJson(shelves)) }
    }
}
