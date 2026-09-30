package com.skintclub.odyssey.club

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Links into the club's web app (app.adventuresinodyssey.com), which the app opens itself -
 * from anywhere on the phone (see the manifest's intent filter) or pasted into the search bar.
 * The web app's routes: /content/{id}[/{scrollTo}], /contentGroup/{id}, and
 * /contentGroup/{groupingId}/content/{contentId} (a content item opened from its grouping).
 */

const val CLUB_WEB_HOST = "app.adventuresinodyssey.com"

sealed interface ClubLink {
    /** A content item - an episode, podcast, comic, article... - and, if the link came through
     * one, the grouping it was opened from. */
    data class Content(val contentId: String, val groupingId: String? = null) : ClubLink

    data class Grouping(val groupingId: String) : ClubLink
}

private val clubIdRegex = Regex("""^[A-Za-z0-9]{15,18}$""")

/** [input] as a club link, or null if it isn't one. Tolerates surrounding whitespace and a
 * missing scheme, since a link pasted into search may have either. */
fun parseClubLink(input: String): ClubLink? {
    val trimmed = input.trim()
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (!url.host.equals(CLUB_WEB_HOST, ignoreCase = true)) return null
    val segments = url.pathSegments.filter { it.isNotBlank() }
    fun idAt(index: Int) = segments.getOrNull(index)?.takeIf { clubIdRegex.matches(it) }
    return when (segments.firstOrNull()?.lowercase()) {
        "content" -> idAt(1)?.let { ClubLink.Content(it) }
        "contentgroup" -> {
            val groupingId = idAt(1) ?: return null
            if (segments.getOrNull(2)?.lowercase() == "content") {
                idAt(3)?.let { ClubLink.Content(it, groupingId) }
            } else {
                ClubLink.Grouping(groupingId)
            }
        }
        else -> null
    }
}
