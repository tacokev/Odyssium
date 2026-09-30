package com.skintclub.odyssey.club

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/*
 * Comics: Articles in "Book Series" groupings - every "Elsewhere in Odyssey" arc, the free
 * monthly strips, Classic Comics, Jumpstart - each with the comic drawn as one or more images
 * in its body (a single tall strip, for Elsewhere in Odyssey). Images inside links are
 * Next/Previous buttons and portals to other content, never the comic itself, so they're
 * skipped.
 *
 * The bulk grouping search only returns the newest few arcs. The full list comes from the
 * club's own "Story Arc Selection" article, whose body links every comic series in order.
 */

const val ODYSSEY_COMICS_ROUTE = "odyssey_comics"
const val ODYSSEY_COMIC_SERIES_ROUTE = "odyssey_comic_series/{groupingId}"
const val ODYSSEY_COMIC_ROUTE = "odyssey_comic/{groupingId}/{contentId}"

/** "Elsewhere in Odyssey - Story Arc Selection": the club's index of every comic series. */
private const val COMIC_INDEX_CONTENT_ID = "a354W000004SR1FQAW"

private val contentGroupLinkRegex = Regex("""/contentGroup/(\w+)""")

/** How many series are fetched at once when rebuilding the list from the index. */
private const val SERIES_FETCH_PARALLELISM = 4

private val linkRegex = Regex("""<a\b[^>]*>.*?</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val imgSrcRegex = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** The same "turn your phone sideways" banner heads every Elsewhere in Odyssey strip's body;
 * it isn't part of the strip (and the reader already scrolls it vertically). */
private const val TURN_PHONE_BANNER = "Turn+Phone+Message"

/** Every comic series the club's index links, in its order. A series that fails to load
 * falls back to [previous]'s copy of it rather than dropping out of the list. Throws if the
 * index itself can't be read. */
suspend fun fetchIndexedComicSeries(
    clubClient: ClubClient,
    previous: List<ClubGrouping>,
): List<ClubGrouping> = coroutineScope {
    val ids = clubClient.fetchArticleBodies(COMIC_INDEX_CONTENT_ID)
        .flatMap { body -> contentGroupLinkRegex.findAll(body).map { it.groupValues[1] } }
        .distinct()
    if (ids.isEmpty()) throw IOException("Comic index lists no series")
    val previousById = previous.associateBy { it.id }
    val permits = Semaphore(SERIES_FETCH_PARALLELISM)
    ids.map { id ->
        async {
            permits.withPermit { runCatching { clubClient.fetchGrouping(id) }.getOrNull() } ?: previousById[id]
        }
    }.awaitAll().filterNotNull()
}

/** The [indexed] series, then any comic series in the grouping cache the index doesn't list
 * (an arc newer than the index's last update). */
fun mergeComicSeries(indexed: List<ClubGrouping>, cached: List<ClubGrouping>): List<ClubGrouping> {
    val indexedIds = indexed.mapTo(HashSet()) { it.id }
    return indexed + cached.filterNot { it.id in indexedIds }
}

/** The comic image(s) in Article [contentId]'s body - empty for one that has none, like a
 * story's prose version. Cached as plain text after the first fetch (a published comic's
 * images don't change), so reopening a series doesn't cost an API call per comic. */
suspend fun loadComicImageUrls(context: Context, clubClient: ClubClient, contentId: String): List<String> =
    withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, "comics"), "$contentId.images")
        if (file.exists()) return@withContext file.readLines().filter { it.isNotBlank() }
        // The first body (content_body) with any comic images; the legacy one is a fallback.
        val urls = clubClient.fetchArticleBodies(contentId)
            .map { body ->
                imgSrcRegex.findAll(linkRegex.replace(body, ""))
                    .map { it.groupValues[1] }
                    .filterNot { TURN_PHONE_BANNER in it }
                    .distinct()
                    .toList()
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        file.parentFile?.mkdirs()
        file.writeText(urls.joinToString("\n"))
        urls
    }

/**
 * The comic series list as last built from the index, in its own small file so the Comics
 * screen and Home tile have the full list on a cold start instead of waiting on the index and
 * two dozen grouping fetches.
 */
object ComicSeriesSnapshot {
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, "comic_series.json")

    fun load(context: Context): List<ClubGrouping> =
        runCatching {
            val type = object : TypeToken<List<ClubGrouping>>() {}.type
            gson.fromJson<List<ClubGrouping>>(file(context).readText(), type)
        }.getOrNull().orEmpty()

    fun save(context: Context, series: List<ClubGrouping>) {
        runCatching { file(context).writeText(gson.toJson(series)) }
    }
}
