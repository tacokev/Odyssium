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
 * Local playlists: made in the app and kept on this device, so they work whatever the
 * membership allows. Like a club playlist, each belongs to the profile it was made in
 * ([LocalPlaylist.viewerId]) and only shows while that profile is selected. "Sync playlists"
 * (or a playlist's "Sync to club") also keeps a copy on the club for that profile;
 * [LocalPlaylist.clubLinks] remembers it, and the copy is hidden in the app, where the local
 * playlist already shows.
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
    /** The club profile (viewer) it belongs to. Null only for one made before playlists had
     * one, until [LocalPlaylistStore.claimUnowned] gives it the selected profile. */
    val viewerId: String? = null,
    /** Club profile (viewer) id -> the copy of this playlist on the club for that profile. */
    val clubLinks: Map<String, ClubPlaylistLink> = emptyMap(),
) {
    /** What a club copy is checked against, to tell whether it's behind. */
    val syncKey: String get() = name + "\n" + contentIds.joinToString(",")
}

/** A local playlist's copy on the club: its [clubId], and the [syncKey] it was last saved with. */
data class ClubPlaylistLink(
    val clubId: String,
    val syncKey: String,
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
        @SerializedName("viewerId") val viewerId: String?,
        @SerializedName("clubLinks") val clubLinks: Map<String, PersistedLink>?,
    )

    private class PersistedLink(
        @SerializedName("clubId") val clubId: String?,
        @SerializedName("syncKey") val syncKey: String?,
    )

    val playlists: Flow<List<LocalPlaylist>> = context.dataStore.data.map { decode(it[LocalPlaylistsKey]) }

    private suspend fun update(transform: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        context.dataStore.edit { prefs ->
            val updated = transform(decode(prefs[LocalPlaylistsKey]))
            prefs[LocalPlaylistsKey] = gson.toJson(
                updated.map { playlist ->
                    PersistedPlaylist(
                        playlist.id,
                        playlist.name,
                        playlist.contentIds,
                        playlist.viewerId,
                        playlist.clubLinks.mapValues { (_, link) -> PersistedLink(link.clubId, link.syncKey) },
                    )
                },
            )
        }
    }

    /** Creates an empty playlist named [name] for the profile [viewerId] and returns its id. */
    suspend fun create(name: String, viewerId: String?): String {
        val id = LOCAL_PLAYLIST_ID_PREFIX + UUID.randomUUID()
        update { it + LocalPlaylist(id, name, viewerId = viewerId) }
        return id
    }

    /** Gives every playlist that has no profile yet (made before they had one) to [viewerId]. */
    suspend fun claimUnowned(viewerId: String) = update { playlists ->
        playlists.map { if (it.viewerId == null) it.copy(viewerId = viewerId) else it }
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

    /** Records [link] as the playlist's copy on the club for [viewerId]. */
    suspend fun setClubLink(playlistId: String, viewerId: String, link: ClubPlaylistLink) = update { playlists ->
        playlists.map { playlist ->
            if (playlist.id != playlistId) playlist else playlist.copy(clubLinks = playlist.clubLinks + (viewerId to link))
        }
    }

    private fun decode(json: String?): List<LocalPlaylist> {
        val persisted = json?.let { runCatching { gson.fromJson(it, Array<PersistedPlaylist>::class.java) }.getOrNull() }
            ?: return emptyList()
        return persisted.mapNotNull { playlist ->
            val id = playlist?.id ?: return@mapNotNull null
            val links = playlist.clubLinks.orEmpty().mapNotNull { (viewerId, link) ->
                val clubId = link?.clubId ?: return@mapNotNull null
                viewerId to ClubPlaylistLink(clubId, link.syncKey.orEmpty())
            }.toMap()
            LocalPlaylist(id, playlist.name.orEmpty(), playlist.contentIds.orEmpty(), playlist.viewerId, links)
        }
    }
}
