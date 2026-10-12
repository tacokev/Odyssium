package com.skintclub.odyssey.viewmodels

import com.skintclub.odyssey.club.ClubGrouping
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** How far the selected profile got into an episode, from the club's progress on it or the
 * app's own (LocalEpisodeProgress), and when that was saved (null if unknown). */
data class EpisodeProgress(
    val completed: Boolean,
    val positionMs: Long,
    val lengthMs: Long,
    val updatedAt: Instant? = null,
) {
    /** How much of the episode was listened to, 0 to 1. */
    val fraction: Float
        get() = when {
            completed -> 1f
            lengthMs > 0 -> (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f)
            else -> 0f
        }
}

/** An episode the selected profile listened to on the club, and when it last did. [time] is
 * null for progress the club saved without a time. */
data class ClubHistoryEntry(
    val episode: OdysseySearchEpisode,
    val time: LocalDateTime?,
)

/** The selected profile's listening on the club, read from the progress in the cached
 * groupings: the episodes it started, newest first, and its progress on every content item. */
data class ClubHistory(
    val entries: List<ClubHistoryEntry>,
    val progress: Map<String, EpisodeProgress>,
) {
    companion object {
        val EMPTY = ClubHistory(emptyList(), emptyMap())
    }
}

/** [searchIndex] has every playable episode once, with what playing it queues; content it
 * doesn't have (articles, or videos while "Play videos as audio" is off) still gets progress
 * but isn't listed. */
fun buildClubHistory(groupings: List<ClubGrouping>, searchIndex: OdysseySearchIndex): ClubHistory {
    val episodeById = searchIndex.episodes.associateBy { it.episode["id"] as? String }
    val progress = HashMap<String, EpisodeProgress>()
    val entries = mutableListOf<ClubHistoryEntry>()
    for (grouping in groupings) {
        for (content in grouping.contentList) {
            if (content.id in progress) continue
            val clubProgress = content.progress ?: continue
            val completed = clubProgress.status?.startsWith("Complete", ignoreCase = true) == true
            // "New", or started and left at the very beginning: nothing listened to.
            if (!completed && clubProgress.current <= 0) continue
            val updatedAt = clubProgress.timestamp?.toInstant()
            progress[content.id] = EpisodeProgress(completed, clubProgress.current, content.mediaLength, updatedAt)
            val episode = episodeById[content.id] ?: continue
            entries += ClubHistoryEntry(episode, updatedAt?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) })
        }
    }
    entries.sortWith(compareBy(nullsLast(reverseOrder())) { it.time })
    return ClubHistory(entries, progress)
}

private fun String.toInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

/** [club] and [local] progress per content id, the newer of the two where both have one.
 * Local progress without a length takes the club's (the app doesn't always know it). */
fun mergeProgress(
    club: Map<String, EpisodeProgress>,
    local: Map<String, EpisodeProgress>,
): Map<String, EpisodeProgress> {
    if (local.isEmpty()) return club
    val merged = HashMap(club)
    local.forEach { (id, mine) ->
        val theirs = club[id]
        val clubTime = theirs?.updatedAt
        val localTime = mine.updatedAt
        if (theirs == null || clubTime == null || (localTime != null && localTime > clubTime)) {
            merged[id] = if (mine.lengthMs <= 0 && theirs != null) mine.copy(lengthMs = theirs.lengthMs) else mine
        }
    }
    return merged
}
