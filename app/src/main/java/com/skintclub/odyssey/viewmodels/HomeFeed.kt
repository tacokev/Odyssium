package com.skintclub.odyssey.viewmodels

import android.content.Context
import com.google.gson.Gson
import com.skintclub.odyssey.club.ClubBanner
import com.skintclub.odyssey.club.ClubContent
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The alternate Home page's club feed: the banners, and the newest content. */
data class HomeFeed(
    val banners: List<ClubBanner> = emptyList(),
    val newContent: List<ClubContent> = emptyList(),
    /** Each listed item's kind, by id (see [newContentKind]). Worked out from the comic series,
     * not kept in the snapshot. */
    @Transient val kinds: Map<String, NewContentKind> = emptyMap(),
)

/** How many of the newest content items the alternate Home page lists. */
const val HOME_NEW_CONTENT_COUNT = 20

/** How many are asked for, since books, downloads and the like are left out. */
const val HOME_NEW_CONTENT_FETCH_COUNT = 50

/** What a new content item is, as the alternate Home page labels it. Content the app can't
 * open (books, downloads...) isn't any of these, and isn't listed. */
enum class NewContentKind(val label: String) {
    Episode("Episode"),
    Podcast("Podcast"),
    Video("Video"),
    Comic("Comic"),
    Article("Article"),
}

/** A numbered "Elsewhere in Odyssey" strip ("Elsewhere in Odyssey #223"), as opposed to the
 * blog posts about the strip ("Elsewhere in Odyssey - ..."). */
private val elsewhereStripRegex = Regex("""^Elsewhere in Odyssey #\d+""")

/** [content]'s kind, or null if the alternate Home page doesn't list it. Comics are articles
 * in a comic series ([comicIds]); a numbered Elsewhere in Odyssey strip counts as one even
 * before its series is known. Other articles (blog posts, "Coming Soon"...) are articles. */
fun newContentKind(content: ClubContent, comicIds: Set<String>): NewContentKind? = when (content.type) {
    "Audio" -> if (content.subtype == "Podcast") NewContentKind.Podcast else NewContentKind.Episode
    "Video" -> NewContentKind.Video
    "Article" ->
        if (content.id in comicIds || elsewhereStripRegex.containsMatchIn(content.name)) NewContentKind.Comic
        else NewContentKind.Article
    else -> null
}

private val publishedFormat = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

/** "Nov 19", from the club's "2026-11-19T00:00:00.000Z"; null if it has no date. */
fun publishedLabel(content: ClubContent): String? =
    content.lastPublishedDate?.take(10)
        ?.let { runCatching { LocalDate.parse(it).format(publishedFormat) }.getOrNull() }

/** The feed as last fetched, so the alternate Home page has it on a cold start. */
object HomeFeedSnapshot {
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, "home_feed.json")

    fun load(context: Context): HomeFeed =
        runCatching { gson.fromJson(file(context).readText(), HomeFeed::class.java) }.getOrNull()
            // Gson skips Kotlin's null checks, so an unreadable field could come back null.
            ?.let { HomeFeed(it.banners.orEmpty(), it.newContent.orEmpty()) }
            ?: HomeFeed()

    fun save(context: Context, feed: HomeFeed) {
        runCatching { file(context).writeText(gson.toJson(feed)) }
    }
}
