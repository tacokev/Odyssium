package com.skintclub.odyssey.viewmodels

import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubGrouping
import com.skintclub.odyssey.club.isPlayableContent
import com.skintclub.odyssey.viewmodels.OdysseyViewModel.OdysseyAlbum

/** One searchable episode. [queue]/[queueIndex] are what tapping it plays: its album when it
 * has one (so it plays exactly like browsing the album), otherwise the first grouping it was
 * found in (e.g. a podcast's year). */
data class OdysseySearchEpisode(
    val episode: Map<String, Any>,
    val queue: List<Map<String, Any>>,
    val queueIndex: Int,
    /** Set only for album episodes - what the episode menu's "Details" needs. */
    val albumName: String?,
    val number: Int?,
)

/** Everything search looks through, precomputed once per grouping-cache change so each
 * keystroke is just a filter: every grouping of every type, and every episode found in any of
 * them exactly once (the same episode is usually in its album, a series and several
 * playlists). */
class OdysseySearchIndex(
    val groupings: List<ClubGrouping>,
    val episodes: List<OdysseySearchEpisode>,
) {
    fun search(rawQuery: String): OdysseySearchResults {
        val query = rawQuery.trim()
        if (query.isEmpty()) return OdysseySearchResults.EMPTY
        // "100" or "#100" means episode/album number 100, not every title that happens to
        // contain those digits ("#1001", "Episodes 51 to 100", ...).
        val number = numberQueryRegex.matchEntire(query)?.groupValues?.get(1)?.toIntOrNull()

        val groupingMatches = groupings.filter { grouping ->
            if (number != null) {
                albumNumberOf(grouping.name)?.toInt() == number
            } else {
                grouping.name.contains(query, ignoreCase = true)
            }
        }
        val episodeMatches = if (number != null) {
            // Exact number first, then any title that genuinely has the number in it.
            episodes.filter { it.number == number } +
                episodes.filter { it.number != number && it.name.contains(query, ignoreCase = true) }
        } else {
            episodes.filter {
                it.name.contains(query, ignoreCase = true) || it.shortName.contains(query, ignoreCase = true)
            }
        }
        return OdysseySearchResults(groupingMatches, episodeMatches)
    }

    companion object {
        val EMPTY = OdysseySearchIndex(emptyList(), emptyList())

        private val numberQueryRegex = Regex("""#?\s*(\d+)""")
    }
}

data class OdysseySearchResults(
    val groupings: List<ClubGrouping>,
    val episodes: List<OdysseySearchEpisode>,
) {
    companion object {
        val EMPTY = OdysseySearchResults(emptyList(), emptyList())
    }
}

private val OdysseySearchEpisode.name get() = episode["name"] as? String ?: ""
private val OdysseySearchEpisode.shortName get() = episode["short_name"] as? String ?: ""

private val episodeNumberRegex = Regex("""^#\s*(\d+)""")

/** [albums] are the already-built, already-ordered album episode lists, reused so album
 * episodes come out in album order with the same maps (and indexes) as the Albums screen.
 * [secretEpisodes] are searchable too, and play within the Secret Episodes list. */
fun buildOdysseySearchIndex(
    clubClient: ClubClient,
    groupings: List<ClubGrouping>,
    albums: List<OdysseyAlbum>,
    secretEpisodes: List<Map<String, Any>>,
): OdysseySearchIndex {
    val seenIds = HashSet<String>()
    val episodes = mutableListOf<OdysseySearchEpisode>()
    fun add(episode: Map<String, Any>, queue: List<Map<String, Any>>, index: Int, albumName: String?) {
        val id = episode["id"] as? String ?: return
        if (!seenIds.add(id)) return
        val number = (episode["short_name"] as? String)
            ?.let { episodeNumberRegex.find(it) }
            ?.groupValues?.get(1)?.toIntOrNull()
        episodes.add(OdysseySearchEpisode(episode, queue, index, albumName, number))
    }

    albums.forEach { album ->
        album.episodes.forEachIndexed { index, episode -> add(episode, album.episodes, index, album.name) }
    }
    // Anything not in an album (podcasts, mostly) - each grouping's episodes are built in one
    // pass here rather than per grouping, which would rebuild the album lookup every time.
    val nonAlbumGroupings = groupings.filter { it.type != "Album" }
    clubClient.sectionsFrom(groupings, nonAlbumGroupings).forEach { (_, groupingEpisodes) ->
        groupingEpisodes.forEachIndexed { index, episode -> add(episode, groupingEpisodes, index, null) }
    }
    secretEpisodes.forEachIndexed { index, episode -> add(episode, secretEpisodes, index, null) }

    val playVideosAsAudio = clubClient.playVideosAsAudio()
    val searchableGroupings = groupings
        .filter { grouping -> grouping.contentList.any { isPlayableContent(it, playVideosAsAudio) } }
        .distinctBy { it.id }
        .sortedWith(
            compareBy(
                { groupingTypeOrder.indexOf(it.type).let { rank -> if (rank < 0) groupingTypeOrder.size else rank } },
                { albumNumberOf(it.name) ?: Double.MAX_VALUE },
                { it.name },
            ),
        )
    return OdysseySearchIndex(searchableGroupings, episodes)
}

/** Grouping results are listed in this type order (anything else after), then by number/name. */
private val groupingTypeOrder = listOf(
    "Album",
    ClubClient.SERIES_TYPE,
    ClubClient.COLLECTION_TYPE,
    ClubClient.PODCAST_YEAR_TYPE,
    "Playlist",
    ClubClient.EPISODE_HOME_TYPE,
)

/** How a grouping's type reads in a search result's subtitle. */
fun odysseyGroupingTypeLabel(type: String): String = when (type) {
    ClubClient.EPISODE_HOME_TYPE -> "Episodes"
    else -> type
}

/** Where tapping a grouping result goes: albums and playlists have their own screens. */
fun odysseyGroupingRoute(grouping: ClubGrouping): String = when (grouping.type) {
    "Album" -> "odyssey_album/${android.net.Uri.encode(grouping.name)}"
    "Playlist" -> "odyssey_playlist/${android.net.Uri.encode(grouping.id)}"
    else -> "odyssey_grouping/${android.net.Uri.encode(grouping.id)}"
}
