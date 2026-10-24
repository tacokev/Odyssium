package com.skintclub.odyssey.club

import android.content.Context
import com.skintclub.odyssey.constants.ClubViewerIdKey
import com.skintclub.odyssey.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

sealed interface PlaylistSyncResult {
    /** The club copy was made, or brought up to date. */
    data object Synced : PlaylistSyncResult

    /** The club copy already matched. */
    data object UpToDate : PlaylistSyncResult

    /** The playlist has a secret episode, which the club doesn't know, so it stays local. */
    data object HasSecretEpisodes : PlaylistSyncResult

    data object NotLoggedIn : PlaylistSyncResult

    data class Failed(val message: String) : PlaylistSyncResult
}

/**
 * Copies local playlists (see [LocalPlaylistStore]) to the club, for the selected profile:
 * the first sync creates a club playlist, later ones overwrite it with the local name and
 * content. Only ever this way round - changes made to the copy on the club are replaced by the
 * next sync. Which copy belongs to which profile is kept in [LocalPlaylist.clubLinks].
 */
class PlaylistClubSync(
    private val context: Context,
    private val clubClient: ClubClient,
    private val playlists: LocalPlaylistStore,
    private val secretEpisodes: SecretEpisodesStore,
) {
    /** Brings [playlistId]'s club copy up to date, creating it if there's none yet (or the
     * one there was has been deleted on the club). [contentById] supplies the cover. */
    suspend fun sync(playlistId: String, contentById: Map<String, ClubContent>): PlaylistSyncResult =
        // One at a time across the whole app (there can be more than one view model doing
        // this), with the playlist re-read inside the lock, so a playlist is never created twice.
        lock.withLock {
            try {
                syncLocked(playlistId, contentById)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClubAuthException) {
                PlaylistSyncResult.NotLoggedIn
            } catch (e: Exception) {
                Timber.w(e, "Syncing playlist $playlistId to the club failed")
                PlaylistSyncResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

    private suspend fun syncLocked(playlistId: String, contentById: Map<String, ClubContent>): PlaylistSyncResult {
        val viewerId = context.dataStore.data.first()[ClubViewerIdKey]
        if (viewerId.isNullOrBlank() || !clubClient.isLoggedIn()) return PlaylistSyncResult.NotLoggedIn
        val playlist = playlists.playlists.first().firstOrNull { it.id == playlistId }
            ?: return PlaylistSyncResult.Failed("The playlist no longer exists")
        if (playlist.viewerId != null && playlist.viewerId != viewerId) {
            return PlaylistSyncResult.Failed("The playlist belongs to another profile")
        }
        if (secretEpisodes.state.first().playlistMembers[playlistId].orEmpty().isNotEmpty()) {
            return PlaylistSyncResult.HasSecretEpisodes
        }
        val link = playlist.clubLinks[viewerId]
        if (link?.syncKey == playlist.syncKey) return PlaylistSyncResult.UpToDate

        val cover = playlist.contentIds.firstNotNullOfOrNull { id ->
            contentById[id]?.thumbnailMedium?.takeIf { it.isNotBlank() }
        }
        val clubId = link?.clubId?.takeIf { replace(it, playlist, cover) }
            ?: clubClient.createPlaylist(playlist.name, cover, playlist.contentIds).also {
                Timber.i("Made club copy $it of ${playlist.id}")
            }
        playlists.setClubLink(playlist.id, viewerId, ClubPlaylistLink(clubId, playlist.syncKey))
        Timber.i("Synced ${playlist.id} (${playlist.contentIds.size} episodes) to club playlist $clubId")
        return PlaylistSyncResult.Synced
    }

    /** Overwrites the club copy [clubId]. False when the club turns it down as missing or
     * invalid (it was deleted on the club), so a new copy is made; other failures throw. */
    private suspend fun replace(clubId: String, playlist: LocalPlaylist, cover: String?): Boolean =
        try {
            clubClient.replacePlaylist(clubId, playlist.name, cover, playlist.contentIds)
            true
        } catch (e: ClubHttpException) {
            if (e.code !in 400..499) throw e
            Timber.i("Club copy $clubId of ${playlist.id} is gone (HTTP ${e.code}), making a new one")
            false
        }

    /** Deletes [playlist]'s club copy for the selected profile, if it has one. Failures are
     * only logged: the local playlist is gone either way. */
    suspend fun deleteClubCopy(playlist: LocalPlaylist) = lock.withLock {
        val viewerId = context.dataStore.data.first()[ClubViewerIdKey] ?: return@withLock
        val clubId = playlist.clubLinks[viewerId]?.clubId ?: return@withLock
        try {
            clubClient.deletePlaylist(clubId)
            Timber.i("Deleted club copy $clubId of ${playlist.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Deleting club copy $clubId of ${playlist.id} failed")
        }
    }

    private companion object {
        val lock = Mutex()
    }
}
