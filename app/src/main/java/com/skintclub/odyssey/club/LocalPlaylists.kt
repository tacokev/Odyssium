package com.skintclub.odyssey.club

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.skintclub.odyssey.constants.LocalPlaylistsKey
import com.skintclub.odyssey.utils.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/*
 * Local playlists: made in the app and kept only on this device - the club never hears about
 * them, so they work whatever the membership allows and whether or not "Sync playlists" is on.
 *
 * Each one is presented to the rest of the app as an ordinary user "Playlist" grouping (see
 * [toGrouping]), so the playlist screen, its menu, the edit screen and the randomizer's
 * playlist filter all handle it like a club playlist. Only its content ids are stored; the
 * episodes themselves are looked up in the club's cached content. Secret episodes added to one
 * go through [SecretEpisodesStore], same as for a club playlist.
 */

private const val LOCAL_PLAYLIST_ID_PREFIX = "local:"

/** Stands in for a club viewer id, so a local playlist counts as user-created everywhere. */
private const val LOCAL_PLAYLIST_VIEWER_ID = "local"

fun isLocalPlaylistId(id: String?): Boolean = id?.startsWith(LOCAL_PLAYLIST_ID_PREFIX) == true

data class LocalPlaylist(
    val id: String,
    val name: String,
    val contentIds: List<String> = emptyList(),
)

/** [playlist] as a user "Playlist" grouping, its content resolved from [contentById] (the
 * club's cached content). Content no longer in the cache is left out rather than dropped from
 * the playlist, so it comes back if the club lists it again. */
fun LocalPlaylist.toGrouping(contentById: Map<String, ClubContent>): ClubGrouping {
    val content = contentIds.mapNotNull { contentById[it] }
    return ClubGrouping(
        id = id,
        type = "Playlist",
        name = name,
        viewerId = LOCAL_PLAYLIST_VIEWER_ID,
        imageUrl = content.firstNotNullOfOrNull { it.thumbnailMedium?.takeIf(String::isNotBlank) },
        isEditable = true,
        contentList = content,
    )
}

/** Persists the local playlists as JSON in the app's DataStore. */
class LocalPlaylistStore(private val context: Context) {
    private val gson = Gson()

    // Gson fills fields reflectively, so a missing one comes back null even where the Kotlin
    // type says otherwise - hence nullable fields here, normalized in [decode]. Names are
    // pinned so a minified build still reads what an earlier one wrote.
    private class PersistedPlaylist(
        @SerializedName("id") val id: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("contentIds") val contentIds: List<String>?,
    )

    val playlists: Flow<List<LocalPlaylist>> = context.dataStore.data.map { decode(it[LocalPlaylistsKey]) }

    private suspend fun update(transform: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        context.dataStore.edit { prefs ->
            val updated = transform(decode(prefs[LocalPlaylistsKey]))
            prefs[LocalPlaylistsKey] = gson.toJson(updated.map { PersistedPlaylist(it.id, it.name, it.contentIds) })
        }
    }

    /** Creates an empty playlist named [name] and returns its id. */
    suspend fun create(name: String): String {
        val id = LOCAL_PLAYLIST_ID_PREFIX + UUID.randomUUID()
        update { it + LocalPlaylist(id, name) }
        return id
    }

    /** Appends [contentIds] to a playlist, skipping any already in it. */
    suspend fun add(playlistId: String, contentIds: List<String>) = update { playlists ->
        playlists.map { playlist ->
            if (playlist.id != playlistId) playlist else playlist.copy(contentIds = (playlist.contentIds + contentIds).distinct())
        }
    }

    /** Renames a playlist and replaces its content with [orderedContentIds]. */
    suspend fun update(playlistId: String, name: String, orderedContentIds: List<String>) = update { playlists ->
        playlists.map { playlist ->
            if (playlist.id != playlistId) playlist else playlist.copy(name = name, contentIds = orderedContentIds.distinct())
        }
    }

    suspend fun delete(playlistId: String) = update { playlists -> playlists.filterNot { it.id == playlistId } }

    private fun decode(json: String?): List<LocalPlaylist> {
        val persisted = json?.let { runCatching { gson.fromJson(it, Array<PersistedPlaylist>::class.java) }.getOrNull() }
            ?: return emptyList()
        return persisted.mapNotNull { playlist ->
            val id = playlist?.id ?: return@mapNotNull null
            LocalPlaylist(id, playlist.name.orEmpty(), playlist.contentIds.orEmpty())
        }
    }
}
