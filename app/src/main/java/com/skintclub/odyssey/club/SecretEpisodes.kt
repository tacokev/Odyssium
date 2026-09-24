package com.skintclub.odyssey.club

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.skintclub.odyssey.constants.SecretEpisodesKey
import com.skintclub.odyssey.playback.ODYSSEY_MEDIA_HOST
import com.skintclub.odyssey.utils.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/*
 * "Secret Episodes": episode audio the club's catalog doesn't list, added by pasting its CDN
 * path (or URL) into the search bar. They exist only on this device - liking one or adding it
 * to a playlist is stored locally and never sent to the club, whose API wouldn't know the id.
 *
 * A secret episode's id is its bare CDN URL (no signed-cookie query). That one string is its
 * media id, cache key and download id, and since a queue restored after a restart uses the id
 * as the URI (see MediaMetadata.toMediaItem), it resolves through the normal signed-cookie
 * path in resolveOdysseyDataSpec with no special casing.
 */

const val SECRET_EPISODES_TITLE = "Secret Episodes"
const val SECRET_EPISODES_ROUTE = "odyssey_secret"

private const val SECRET_EPISODE_PATH_PREFIX = "private/audio/episode/"
private const val SECRET_EPISODE_ID_PREFIX = "https://$ODYSSEY_MEDIA_HOST/$SECRET_EPISODE_PATH_PREFIX"

/** A full episode path or URL: optional CDN origin, then private/audio/episode/.../<file>.<ext>,
 * with any query (e.g. a pasted signed URL) or fragment ignored. Requiring the file extension
 * means a path being typed out by hand doesn't fire on its first few characters. */
private val secretEpisodeInputRegex = Regex(
    """^(?:https://${Regex.escape(ODYSSEY_MEDIA_HOST)}/|/)?(${Regex.escape(SECRET_EPISODE_PATH_PREFIX)}[^?#\s]*/[^/?#\s]+\.[A-Za-z0-9]{2,5})(?:[?#]\S*)?$""",
)

/** The relative CDN path (`private/audio/episode/...`) in [input], or null if it isn't one. */
fun parseSecretEpisodePath(input: String): String? = secretEpisodeInputRegex.matchEntire(input.trim())?.groupValues?.get(1)

fun isSecretEpisodeId(id: String?): Boolean = id?.startsWith(SECRET_EPISODE_ID_PREFIX) == true

fun secretEpisodeId(path: String): String = "https://$ODYSSEY_MEDIA_HOST/$path"

data class SecretEpisode(val path: String, val liked: Boolean = false) {
    val id get() = secretEpisodeId(path)
}

data class SecretEpisodesState(
    val episodes: List<SecretEpisode> = emptyList(),
    /** Club playlist id -> secret episode ids added to it, in the order they were added. */
    val playlistMembers: Map<String, List<String>> = emptyMap(),
)

private val leadingNumberRegex = Regex("""^(\d+)""")
private val minorTitleWords = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with")

/** The episode number at the start of the file name ("1041-the-measure-of-a-patriot-part-2_Mp4_Aac_256Kbps.mp4" -> 1041). */
fun secretEpisodeNumber(path: String): Long? =
    leadingNumberRegex.find(path.substringAfterLast('/'))?.groupValues?.get(1)?.toLongOrNull()

/** A readable title from the file name: "1041-the-measure-of-a-patriot-part-2_Mp4_..." ->
 * "The Measure of a Patriot Part 2". The path is all there is to go on - the club has no
 * metadata for these. */
private fun secretEpisodeTitle(path: String): String {
    val fileName = path.substringAfterLast('/')
    val slug = fileName.substringBefore('_').substringBeforeLast('.').replace(leadingNumberRegex, "")
    val words = slug.split('-', ' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return fileName
    return words.mapIndexed { index, word ->
        if (index > 0 && word.lowercase() in minorTitleWords) word.lowercase() else word.replaceFirstChar { it.uppercase() }
    }.joinToString(" ")
}

/** The same episode-map shape the club's own episodes use, so every list, menu, queue and
 * download path handles a secret episode like any other. */
fun secretEpisodeMap(episode: SecretEpisode): Map<String, Any> {
    val title = secretEpisodeTitle(episode.path)
    val number = secretEpisodeNumber(episode.path)
    return mapOf(
        "id" to episode.id,
        "name" to title,
        "short_name" to (number?.let { "#$it: $title" } ?: title),
        "album_name" to SECRET_EPISODES_TITLE,
        "download_url" to episode.path,
        "image_small" to "",
        "image_medium" to "",
        "image_large" to "",
        "is_bookmarked" to episode.liked,
        "secret" to true,
    )
}

/** Numbered episodes in number order, anything unnumbered after them. */
private val secretEpisodeOrder =
    compareBy<SecretEpisode>({ secretEpisodeNumber(it.path) ?: Long.MAX_VALUE }, { it.path })

/** Persists [SecretEpisodesState] as JSON in the app's DataStore. */
class SecretEpisodesStore(private val context: Context) {
    private val gson = Gson()

    // Gson fills fields reflectively, so a missing one comes back null even where the Kotlin
    // type says otherwise - hence nullable fields here, normalized in [decode].
    private class PersistedEpisode(val path: String?, val liked: Boolean?)
    private class Persisted(val episodes: List<PersistedEpisode>?, val playlistMembers: Map<String, List<String>>?)

    val state: Flow<SecretEpisodesState> = context.dataStore.data.map { decode(it[SecretEpisodesKey]) }

    suspend fun update(transform: (SecretEpisodesState) -> SecretEpisodesState) {
        context.dataStore.edit { prefs ->
            val updated = transform(decode(prefs[SecretEpisodesKey]))
            prefs[SecretEpisodesKey] = gson.toJson(
                Persisted(
                    episodes = updated.episodes.map { PersistedEpisode(it.path, it.liked) },
                    playlistMembers = updated.playlistMembers,
                ),
            )
        }
    }

    /** Adds [path] (kept in number order) unless it's already there. */
    suspend fun add(path: String) = update { state ->
        if (state.episodes.any { it.path == path }) {
            state
        } else {
            state.copy(episodes = (state.episodes + SecretEpisode(path)).sortedWith(secretEpisodeOrder))
        }
    }

    /** Removes an episode, and with it every local playlist membership it had. */
    suspend fun remove(id: String) = update { state ->
        state.copy(
            episodes = state.episodes.filterNot { it.id == id },
            playlistMembers = state.playlistMembers
                .mapValues { (_, ids) -> ids - id }
                .filterValues { it.isNotEmpty() },
        )
    }

    suspend fun setLiked(id: String, liked: Boolean) = update { state ->
        state.copy(episodes = state.episodes.map { if (it.id == id) it.copy(liked = liked) else it })
    }

    suspend fun addToPlaylist(playlistId: String, ids: List<String>) = update { state ->
        val existing = state.playlistMembers[playlistId].orEmpty()
        state.copy(playlistMembers = state.playlistMembers + (playlistId to (existing + ids).distinct()))
    }

    suspend fun forgetPlaylist(playlistId: String) = update { state ->
        state.copy(playlistMembers = state.playlistMembers - playlistId)
    }

    private fun decode(json: String?): SecretEpisodesState {
        val persisted = json?.let { runCatching { gson.fromJson(it, Persisted::class.java) }.getOrNull() }
            ?: return SecretEpisodesState()
        return SecretEpisodesState(
            episodes = persisted.episodes.orEmpty().mapNotNull { episode ->
                episode.path?.let { SecretEpisode(it, episode.liked == true) }
            },
            playlistMembers = persisted.playlistMembers.orEmpty(),
        )
    }
}
