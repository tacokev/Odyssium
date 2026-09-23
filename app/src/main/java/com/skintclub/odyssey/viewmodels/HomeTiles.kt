package com.skintclub.odyssey.viewmodels

import android.content.Context
import coil.request.ImageRequest
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubGrouping
import java.io.File

/** One of the Home screen's category tiles. Serialized names are pinned so a minified build
 * can still read a snapshot written by a previous version. */
data class HomeCategory(
    @SerializedName("route") val route: String,
    @SerializedName("title") val title: String,
    @SerializedName("subtitle") val subtitle: String,
    @SerializedName("imageUrls") val imageUrls: List<String>,
)

/** How many covers each Home tile's collage shows. */
const val HOME_COLLAGE_COVER_COUNT = 6

fun homeCategoriesFrom(
    clubClient: ClubClient,
    groupings: List<ClubGrouping>,
    albumCount: Int,
): List<HomeCategory> {
    val episodeHomes = clubClient.groupingsOfType(groupings, ClubClient.EPISODE_HOME_TYPE)
    val series = clubClient.groupingsOfType(groupings, ClubClient.SERIES_TYPE)
    val collections = clubClient.collectionsFrom(groupings)
    val podcastYears = clubClient.groupingsOfType(groupings, ClubClient.PODCAST_YEAR_TYPE)
    fun List<ClubGrouping>.covers() =
        mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }.distinct().take(HOME_COLLAGE_COVER_COUNT)
    return listOf(
        HomeCategory(
            route = "odyssey_albums",
            title = "Albums",
            subtitle = "$albumCount albums",
            // The newest numbered albums, so the collage changes as new albums land. Uses the
            // album's own ~400px cover rather than its episodes' 835px thumbnails.
            imageUrls = groupings
                .filter { it.type == "Album" }
                .mapNotNull { album ->
                    albumNumberOf(album.name)?.let { it to album }
                }
                .sortedByDescending { (number, _) -> number }
                .map { (_, album) -> album }
                .covers(),
        ),
        HomeCategory(
            route = "odyssey_episodes",
            title = "Episodes",
            subtitle = "${episodeHomes.sumOf { home -> home.contentList.count { it.type == "Audio" } }} episodes",
            imageUrls = episodeHomes.covers(),
        ),
        HomeCategory(
            route = "odyssey_series",
            title = "Series & Story Arcs",
            subtitle = "${series.size} series",
            imageUrls = series.covers(),
        ),
        HomeCategory(
            route = "odyssey_collections",
            title = "Collections",
            subtitle = "${collections.size} collections",
            imageUrls = collections.covers(),
        ),
        HomeCategory(
            route = "odyssey_podcasts",
            title = "Podcasts",
            subtitle = "${podcastYears.sumOf { year -> year.contentList.count { it.type == "Audio" } }} podcasts",
            // The years all share one podcast logo, so use the latest podcasts' own art.
            imageUrls = podcastYears.asReversed()
                .flatMap { year -> year.contentList.filter { it.type == "Audio" } }
                .mapNotNull { it.thumbnailSmall?.takeIf(String::isNotBlank) }
                .distinct()
                .take(HOME_COLLAGE_COVER_COUNT),
        ),
    )
}

/** The request for one Home collage cover. Shared by the collage itself and
 * [OdysseyViewModel]'s startup prefetch, so a prefetched cover lands under the exact memory
 * cache key the collage then asks for. */
fun homeCollageCoverRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(240, 240)
        .build()

/**
 * The Home tiles as last shown, kept in their own few-KB file so a cold start can draw them
 * (and start fetching their covers) immediately - instead of waiting on the multi-MB content
 * grouping cache in DataStore to be read and parsed before the tiles even know their covers.
 */
object HomeTilesSnapshot {
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, "home_tiles.json")

    fun load(context: Context): List<HomeCategory>? =
        runCatching {
            val type = object : TypeToken<List<HomeCategory>>() {}.type
            val categories = gson.fromJson<List<HomeCategory>>(file(context).readText(), type)
            // Gson skips Kotlin's null checks, so a malformed/outdated file could otherwise
            // hand back "non-null" fields that are actually null. Treat that as no snapshot.
            @Suppress("SENSELESS_COMPARISON")
            categories.takeIf { list ->
                list.all { it.route != null && it.title != null && it.subtitle != null && it.imageUrls != null }
            }
        }.getOrNull()

    fun save(context: Context, categories: List<HomeCategory>) {
        runCatching { file(context).writeText(gson.toJson(categories)) }
    }
}
