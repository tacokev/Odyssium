package com.skintclub.odyssey.viewmodels

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadService
import coil.imageLoader
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubGrouping
import com.skintclub.odyssey.club.ClubPlaylistOption
import com.skintclub.odyssey.constants.OdysseySortReversedKey
import com.skintclub.odyssey.constants.RandomizerListMode
import com.skintclub.odyssey.di.PlayerCache
import com.skintclub.odyssey.playback.DownloadUtil
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.playback.queues.isBonusEpisode
import com.skintclub.odyssey.playback.queues.isSubsequentPart
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class OdysseyViewModel @Inject constructor(
    application: Application,
    private val downloadUtil: DownloadUtil,
    @PlayerCache private val playerCache: SimpleCache,
) : AndroidViewModel(application) {
    private val clubClient = ClubClient(application)

    private val _albums = MutableStateFlow<List<OdysseyAlbum>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _playlists = MutableStateFlow<List<ClubPlaylistOption>>(emptyList())
    val playlists = _playlists.asStateFlow()

    /** The raw groupings behind [_albums]/[_playlists]. Besides letting [pickRandomEpisode]
     * look up a selected playlist's episode membership without a separate DataStore
     * round-trip on every button press, this is also what a playlist detail screen derives
     * its episode list from (via [ClubClient.playlistEpisodesFrom]). */
    private val _groupings = MutableStateFlow<List<ClubGrouping>>(emptyList())
    val groupings = _groupings.asStateFlow()

    /** The Home screen's category tiles. Seeded from [HomeTilesSnapshot] so they (and their
     * covers, prefetched right here) are ready on a cold start before the full grouping cache
     * has even been parsed; recomputed and re-saved whenever the groupings change. */
    private val _homeCategories = MutableStateFlow(
        HomeTilesSnapshot.load(application).orEmpty().also { prefetchHomeCovers(it) },
    )
    val homeCategories = _homeCategories.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    /** Set when a refresh fails while logged in, meaning the stored tokens no longer work.
     * [odysseyClient] is logged out immediately so the app doesn't keep hitting the API with
     * a dead token; the UI observes this to send the user back through [OdysseyLoginScreen]. */
    private val _needsRelogin = MutableStateFlow(false)
    val needsRelogin = _needsRelogin.asStateFlow()

    fun onReloginHandled() {
        _needsRelogin.value = false
    }

    private val _sortDescending = MutableStateFlow(application.dataStore.get(OdysseySortReversedKey, false))
    val sortDescending = _sortDescending.asStateFlow()

    /** Albums in default order (unnumbered albums first), before [_sortDescending] is applied. */
    private var baseAlbums: List<OdysseyAlbum> = emptyList()

    init {
        viewModelScope.launch {
            checkMembershipExpiry()
            val cached = clubClient.getCachedGroupings()
            if (cached.isNotEmpty()) {
                applyGroupings(cached)
            }
            performRefresh(silent = cached.isNotEmpty())
        }
    }

    /** Checked once per app open (here, in [init]) against the membership expiry date cached
     * from the last /viewer fetch - entirely local, no network call needed to catch a lapsed
     * membership even if the app never manages to log back in. */
    private suspend fun checkMembershipExpiry() {
        val expiry = clubClient.getMembershipExpiry() ?: return
        val expiryDate = runCatching { LocalDate.parse(expiry) }.getOrNull() ?: return
        if (!LocalDate.now().isAfter(expiryDate)) return
        clearDownloadsAndCache()
    }

    /** Deletes every downloaded episode (via DownloadManager, not the raw cache - see the
     * "Clear all downloads" fix in Settings > Storage for why) and empties the player's
     * streaming cache. */
    private suspend fun clearDownloadsAndCache() {
        val context = getApplication<Application>()
        downloadUtil.downloads.value.keys.forEach { id ->
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
        }
        withContext(Dispatchers.IO) {
            playerCache.keys.toList().forEach { key -> runCatching { playerCache.removeResource(key) } }
        }
    }

    fun toggleSortOrder() {
        val descending = !_sortDescending.value
        _sortDescending.value = descending
        applySortOrder()
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().dataStore.edit {
                it[OdysseySortReversedKey] = descending
            }
        }
    }

    private fun applySortOrder() {
        _albums.value = if (_sortDescending.value) baseAlbums.reversed() else baseAlbums
    }

    /** Pull-to-refresh: always shows the refresh indicator. */
    fun refresh() {
        viewModelScope.launch { performRefresh(silent = false) }
    }

    /** [silent] skips the refresh indicator for the automatic startup refresh that runs
     * behind an already-displayed cache, so it doesn't look like the list is reloading. */
    private suspend fun performRefresh(silent: Boolean) {
        if (!silent) _isRefreshing.value = true
        try {
            if (clubClient.isLoggedIn()) {
                if (clubClient.getViewerId() == null) {
                    val profiles = clubClient.fetchProfiles()
                    if (profiles.isNotEmpty()) {
                        clubClient.selectProfile(profiles[0])
                    }
                }
                val fetchedGroupings = clubClient.fetchAllGroupings()
                // Warms the shared cookie cache (see ClubClient.getSignedCookie) so the first
                // episode played doesn't have to wait on this round trip.
                clubClient.getSignedCookie(forceRefresh = true)

                applyGroupings(fetchedGroupings)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            clubClient.logout()
            _needsRelogin.value = true
        }
        _isRefreshing.value = false
    }

    /** The exact filter combination a [nonRepeatingBag] was built from; a call with a
     * different combination invalidates the bag immediately instead of finishing it out. */
    private data class RandomizerFilter(
        val excludeBonusEpisodes: Boolean,
        val excludeSubsequentParts: Boolean,
        val listMode: RandomizerListMode,
        val playlistId: String?,
    )

    /** The shuffled, not-yet-served pool backing "Non-Repeating Randomizer": every eligible
     * (album, episode index) pair for [nonRepeatingBagFilter], consumed from the end until
     * empty, then rebuilt fresh (episodes can repeat across cycles, just not within one). */
    private var nonRepeatingBag: MutableList<Pair<OdysseyAlbum, Int>> = mutableListOf()
    private var nonRepeatingBagFilter: RandomizerFilter? = null

    private fun isEligibleEpisode(
        episode: Map<String, Any>,
        excludeBonusEpisodes: Boolean,
        excludeSubsequentParts: Boolean,
        listMode: RandomizerListMode,
        listEpisodeIds: Set<String>?,
    ): Boolean {
        val passesBonus = !excludeBonusEpisodes || !isBonusEpisode(episode)
        val passesParts = !excludeSubsequentParts || !isSubsequentPart(episode)
        val passesList = when {
            listEpisodeIds == null -> true
            listMode == RandomizerListMode.WHITELIST -> (episode["id"] as? String) in listEpisodeIds
            else -> (episode["id"] as? String) !in listEpisodeIds
        }
        return passesBonus && passesParts && passesList
    }

    /** Picks an eligible episode per the randomizer settings. Returns the album name and an
     * index into that album's full, unfiltered episode list, so bonus episodes stay visible
     * when browsing even when the randomizer setting excludes them from being picked. Null
     * if nothing is eligible.
     *
     * [listMode]/[playlistId] apply on top of the other filters: BLACKLIST excludes the
     * playlist's episodes from being picked, WHITELIST restricts picks to only that
     * playlist's episodes. A blank/unresolved [playlistId] is treated like [RandomizerListMode.OFF]
     * (no restriction) rather than picking nothing, since it means the setting isn't fully
     * configured yet.
     *
     * [nonRepeating] switches from an independent random pick every time (a random album,
     * weighted equally regardless of episode count, then a random episode within it) to
     * drawing from [nonRepeatingBag] so the same episode can't repeat until every other
     * eligible episode has been served at least once. */
    fun pickRandomEpisode(
        excludeBonusEpisodes: Boolean,
        excludeSubsequentParts: Boolean,
        listMode: RandomizerListMode,
        playlistId: String?,
        nonRepeating: Boolean,
    ): Pair<String, Int>? {
        val listEpisodeIds = if (listMode != RandomizerListMode.OFF && !playlistId.isNullOrBlank()) {
            clubClient.playlistEpisodeIds(_groupings.value, playlistId)
        } else {
            null
        }

        if (!nonRepeating) {
            val eligibleAlbums = _albums.value.mapNotNull { album ->
                val eligibleIndices = album.episodes.indices.filter { index ->
                    isEligibleEpisode(album.episodes[index], excludeBonusEpisodes, excludeSubsequentParts, listMode, listEpisodeIds)
                }
                eligibleIndices.takeIf { it.isNotEmpty() }?.let { album to it }
            }
            val (album, indices) = eligibleAlbums.randomOrNull() ?: return null
            return album.name to indices.random()
        }

        val filter = RandomizerFilter(excludeBonusEpisodes, excludeSubsequentParts, listMode, playlistId)
        if (filter != nonRepeatingBagFilter || nonRepeatingBag.isEmpty()) {
            nonRepeatingBag = _albums.value
                .flatMap { album ->
                    album.episodes.indices
                        .filter { index ->
                            isEligibleEpisode(album.episodes[index], excludeBonusEpisodes, excludeSubsequentParts, listMode, listEpisodeIds)
                        }
                        .map { index -> album to index }
                }
                .shuffled()
                .toMutableList()
            nonRepeatingBagFilter = filter
        }
        if (nonRepeatingBag.isEmpty()) return null
        val (album, index) = nonRepeatingBag.removeAt(nonRepeatingBag.lastIndex)
        return album.name to index
    }

    /** Likes/unlikes an episode with the club, then updates local state to match so every
     * screen showing that episode (its album, any playlist it's in, Liked) reflects the new
     * state immediately instead of waiting for the next full refresh. Deliberately doesn't go
     * through [applyGroupings] - that resets the randomizer's non-repeating bag, which a like
     * toggle has no business touching. */
    fun setEpisodeLiked(contentId: String, liked: Boolean) {
        viewModelScope.launch {
            try {
                if (liked) clubClient.addBookmark(contentId) else clubClient.removeBookmark(contentId)
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }
            val updatedGroupings = _groupings.value.map { grouping ->
                if (grouping.contentList.none { it.id == contentId }) {
                    grouping
                } else {
                    grouping.copy(
                        contentList = grouping.contentList.map { content ->
                            if (content.id == contentId) content.copy(isBookmarked = liked) else content
                        },
                    )
                }
            }
            _groupings.value = updatedGroupings
            applyEpisodes(clubClient.albumEpisodesFrom(updatedGroupings))
        }
    }

    /** Adds episodes to a user playlist, then patches local state to match - same reasoning
     * as [setEpisodeLiked] (immediate, without resetting the randomizer's non-repeating bag
     * via [applyGroupings]). */
    fun addEpisodesToPlaylist(playlistId: String, contentIds: List<String>) {
        viewModelScope.launch {
            val added = try {
                clubClient.addContentToPlaylist(playlistId, contentIds, _groupings.value)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (!added) return@launch

            val contentById = _groupings.value.flatMap { it.contentList }.associateBy { it.id }
            val updatedGroupings = _groupings.value.map { grouping ->
                if (grouping.id != playlistId || grouping.type != "Playlist") {
                    grouping
                } else {
                    val existingIds = grouping.contentList.map { it.id }.toMutableSet()
                    val newContent = contentIds.filter { existingIds.add(it) }.mapNotNull { contentById[it] }
                    if (newContent.isEmpty()) grouping else grouping.copy(contentList = grouping.contentList + newContent)
                }
            }
            _groupings.value = updatedGroupings
            _playlists.value = clubClient.playlistsFrom(updatedGroupings)
        }
    }

    /** Renames a playlist and/or reorders its content, then patches local state to match. */
    fun updatePlaylist(playlistId: String, name: String, orderedContentIds: List<String>) {
        viewModelScope.launch {
            val updated = try {
                clubClient.updatePlaylist(playlistId, name, orderedContentIds, _groupings.value)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (!updated) return@launch

            val contentById = _groupings.value.flatMap { it.contentList }.associateBy { it.id }
            val updatedGroupings = _groupings.value.map { grouping ->
                if (grouping.id != playlistId || grouping.type != "Playlist") {
                    grouping
                } else {
                    grouping.copy(name = name, contentList = orderedContentIds.mapNotNull { contentById[it] })
                }
            }
            _groupings.value = updatedGroupings
            _playlists.value = clubClient.playlistsFrom(updatedGroupings)
        }
    }

    /** Deletes a user-created playlist, then removes it from local state. */
    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            try {
                clubClient.deletePlaylist(playlistId)
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }
            val updatedGroupings = _groupings.value.filterNot { it.id == playlistId && it.type == "Playlist" }
            _groupings.value = updatedGroupings
            _playlists.value = clubClient.playlistsFrom(updatedGroupings)
        }
    }

    private fun applyGroupings(newGroupings: List<ClubGrouping>) {
        _groupings.value = newGroupings
        _playlists.value = clubClient.playlistsFrom(newGroupings)
        // Freshly fetched content may have changed what's eligible, so let the next pick
        // rebuild the bag from scratch rather than finishing out one built from old data.
        nonRepeatingBag = mutableListOf()
        nonRepeatingBagFilter = null
        applyEpisodes(clubClient.albumEpisodesFrom(newGroupings))
    }

    private fun applyEpisodes(episodes: List<Map<String, Any>>) {
        baseAlbums = episodes.groupBy { it["album_name"] as? String ?: "Unknown" }
            .map { (name, episodesInAlbum) ->
                // image_large is the album's own cover (see ClubClient.albumEpisodesFrom) - the same
                // one the album's page shows - whereas image_medium/small are the first
                // episode's own thumbnail, which for some albums is episode-specific art.
                val first = episodesInAlbum.firstOrNull()
                val cover = (first?.get("image_large") as? String)?.takeIf { it.isNotBlank() }
                    ?: (first?.get("image_medium") as? String)?.takeIf { it.isNotBlank() }
                    ?: (first?.get("image_small") as? String)?.takeIf { it.isNotBlank() }
                OdysseyAlbum(name, episodesInAlbum, cover)
            }
            .sortedWith(albumOrderComparator)
        applySortOrder()
        updateHomeCategories()
    }

    private fun updateHomeCategories() {
        val categories = homeCategoriesFrom(clubClient, _groupings.value, baseAlbums.size)
        if (categories == _homeCategories.value) return
        _homeCategories.value = categories
        prefetchHomeCovers(categories)
        viewModelScope.launch(Dispatchers.IO) {
            HomeTilesSnapshot.save(getApplication(), categories)
        }
    }

    /** Warms Coil's memory cache with the Home collage covers, so they're decoded by the time
     * the tiles draw rather than each one starting its own disk read + decode on first frame. */
    private fun prefetchHomeCovers(categories: List<HomeCategory>) {
        val context = getApplication<Application>()
        categories.flatMap { it.imageUrls }.forEach { url ->
            context.imageLoader.enqueue(homeCollageCoverRequest(context, url))
        }
    }

    data class OdysseyAlbum(
        val name: String,
        val episodes: List<Map<String, Any>>,
        val coverUrl: String?
    )

    companion object {
        /** Albums without a leading "#<number>" sort first; numbered albums follow, ordered by
         * that number, with each half-album right after its whole-numbered one. */
        private val albumOrderComparator =
            compareBy<OdysseyAlbum>(
                { albumNumberOf(it.name) != null },
                { albumNumberOf(it.name) ?: Double.MAX_VALUE },
                { it.name },
            )
    }
}

private val albumNumberRegex = Regex("""^#\s*(\d+)\s*(½|1/2|\.5)?""")

/** An album's "#<number>" as a sort key, with a half-album ("#75 ½") as number + 0.5 so it
 * lands between #75 and #76 - a plain name tiebreak would put "#75 ½" before "#75: ...", since
 * a space sorts before a colon. Null for an unnumbered album. */
fun albumNumberOf(name: String): Double? {
    val match = albumNumberRegex.find(name) ?: return null
    val whole = match.groupValues[1].toDoubleOrNull() ?: return null
    return if (match.groupValues[2].isNotEmpty()) whole + 0.5 else whole
}
