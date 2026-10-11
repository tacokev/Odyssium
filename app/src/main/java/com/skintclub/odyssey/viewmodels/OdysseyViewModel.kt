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
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.ClubLink
import com.skintclub.odyssey.club.ComicSeriesSnapshot
import com.skintclub.odyssey.club.LocalPlaylistStore
import com.skintclub.odyssey.club.SecretEpisode
import com.skintclub.odyssey.club.SecretEpisodesState
import com.skintclub.odyssey.club.SecretEpisodesStore
import com.skintclub.odyssey.club.VideoShelvesSnapshot
import com.skintclub.odyssey.club.fetchVideoShelves
import com.skintclub.odyssey.club.mergeBonusVideoShelves
import com.skintclub.odyssey.club.fetchIndexedComicSeries
import com.skintclub.odyssey.club.isLocalPlaylistId
import com.skintclub.odyssey.club.isPlayableContent
import com.skintclub.odyssey.club.isSecretEpisodeId
import com.skintclub.odyssey.club.mergeComicSeries
import com.skintclub.odyssey.club.parseSecretEpisodePath
import com.skintclub.odyssey.club.secretEpisodeMap
import com.skintclub.odyssey.club.toGrouping
import com.skintclub.odyssey.constants.OdysseySortReversedKey
import com.skintclub.odyssey.constants.PlayVideosAsAudioKey
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How many random episode pages the randomizer's previous/next buttons remember. */
private const val RANDOMIZER_HISTORY_LIMIT = 100

@HiltViewModel
class OdysseyViewModel @Inject constructor(
    application: Application,
    private val downloadUtil: DownloadUtil,
    @PlayerCache private val playerCache: SimpleCache,
) : AndroidViewModel(application) {
    private val clubClient = ClubClient(application)

    private val _albums = MutableStateFlow<List<OdysseyAlbum>>(emptyList())
    val albums = _albums.asStateFlow()

    /** The club's own groupings, behind [_albums]; [groupings] adds the local playlists. */
    private val _groupings = MutableStateFlow<List<ClubGrouping>>(emptyList())

    private val secretEpisodesStore = SecretEpisodesStore(application)
    private val localPlaylistStore = LocalPlaylistStore(application)

    private val localPlaylists = localPlaylistStore.playlists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The club's groupings plus the local playlists (see [LocalPlaylistStore]) as "Playlist"
     * groupings of their own. Besides letting [pickRandomEpisode] look up a selected
     * playlist's episode membership without a separate DataStore round-trip on every button
     * press, this is also what a playlist detail screen derives its episode list from (via
     * [ClubClient.playlistEpisodesFrom]). */
    val groupings = combine(_groupings, localPlaylists) { club, local ->
        if (local.isEmpty()) {
            club
        } else {
            val contentById = HashMap<String, ClubContent>()
            club.forEach { grouping -> grouping.contentList.forEach { contentById.putIfAbsent(it.id, it) } }
            club + local.map { it.toGrouping(contentById) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every playlist - the club's (official and the user's own) and the local ones. */
    val playlists = groupings
        .map { clubClient.playlistsFrom(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Settings > Content > "Play videos as audio". Every episode list is built from it (see
     * [ClubClient.playVideosAsAudio]), so flipping it rebuilds them - see [init]. */
    val playVideosAsAudio = application.dataStore.data
        .map { it[PlayVideosAsAudioKey] ?: true }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, application.dataStore.get(PlayVideosAsAudioKey, true))

    /** The locally-added "Secret Episodes" (see [SecretEpisodesStore]), plus which club
     * playlists each has been added to on this device. */
    val secretEpisodesState = secretEpisodesStore.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SecretEpisodesState())

    /** [secretEpisodesState]'s episodes as episode maps, in number order. */
    val secretEpisodes = secretEpisodesState
        .map { state -> state.episodes.map(::secretEpisodeMap) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the search bar filters, rebuilt off the main thread whenever the cached groupings
     * (or the albums built from them, or the secret episodes) change. Albums are put back in
     * their default order, so the Albums screen's sort toggle doesn't also flip search results. */
    val searchIndex = combine(_groupings, _albums, secretEpisodes, playVideosAsAudio) { groupings, albums, secret, _ ->
        buildOdysseySearchIndex(clubClient, groupings, albums.sortedWith(albumOrderComparator), secret)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, OdysseySearchIndex.EMPTY)

    /** Every comic series from the club's arc index (see [fetchIndexedComicSeries]), as last
     * fetched - seeded from [ComicSeriesSnapshot] and refreshed after each grouping refresh. */
    private val indexedComicSeries = MutableStateFlow(ComicSeriesSnapshot.load(application))

    /** The Comics screen's series: [indexedComicSeries], then any comic series in the grouping
     * cache it doesn't list. */
    val comicSeries = combine(indexedComicSeries, _groupings) { indexed, groupings ->
        mergeComicSeries(indexed, clubClient.comicSeriesFrom(groupings))
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The video shelves from the club's content searches (see [fetchVideoShelves]), as last
     * fetched - seeded from [VideoShelvesSnapshot] and refreshed after each grouping refresh. */
    private val fetchedVideoShelves = MutableStateFlow(VideoShelvesSnapshot.load(application))

    /** The Videos screen's shelves: [fetchedVideoShelves] with the Bonus Video shelves rebuilt
     * from the current grouping cache. */
    val videoShelves = combine(fetchedVideoShelves, _groupings) { fetched, groupings ->
        mergeBonusVideoShelves(fetched, groupings)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        viewModelScope.launch {
            // Videos appear in (or vanish from) albums and every other list when the setting
            // flips; the first value is the current one, which startup already built with.
            playVideosAsAudio.drop(1).collect {
                if (_groupings.value.isNotEmpty()) applyGroupings(_groupings.value)
            }
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
                viewModelScope.launch { refreshComicSeries() }
                viewModelScope.launch { refreshVideoShelves() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            clubClient.logout()
            _needsRelogin.value = true
        }
        _isRefreshing.value = false
    }

    /** Rebuilds [indexedComicSeries] from the club's arc index. Failing leaves the last list
     * in place - comics aren't worth sending anyone back through login over. */
    private suspend fun refreshComicSeries() {
        val series = try {
            fetchIndexedComicSeries(clubClient, indexedComicSeries.value)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        if (series == indexedComicSeries.value) return
        indexedComicSeries.value = series
        updateHomeCategories()
        withContext(Dispatchers.IO) { ComicSeriesSnapshot.save(getApplication(), series) }
    }

    /** Rebuilds [fetchedVideoShelves] from the club's content searches. Failing leaves the last
     * shelves in place, same as comics. */
    private suspend fun refreshVideoShelves() {
        val shelves = try {
            fetchVideoShelves(clubClient, _groupings.value, fetchedVideoShelves.value)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        if (shelves == fetchedVideoShelves.value) return
        fetchedVideoShelves.value = shelves
        updateHomeCategories()
        withContext(Dispatchers.IO) { VideoShelvesSnapshot.save(getApplication(), shelves) }
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
            clubClient.playlistEpisodeIds(groupings.value, playlistId)
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

    /** The random episode pages opened this session (album name, episode index), oldest
     * first, and which one is showing - like a browser's history, but only for the
     * randomizer, so the system back button still leaves the details page in one press. */
    data class RandomizerHistory(
        val picks: List<Pair<String, Int>> = emptyList(),
        val position: Int = -1,
    ) {
        val current: Pair<String, Int>? get() = picks.getOrNull(position)
        val canGoBack: Boolean get() = position > 0
        val canGoForward: Boolean get() = position < picks.lastIndex
    }

    private val _randomizerHistory = MutableStateFlow(RandomizerHistory())
    val randomizerHistory = _randomizerHistory.asStateFlow()

    /** Records a fresh randomizer pick as the current page. Anything that was ahead of the
     * current page (after going back) is dropped, and only the newest
     * [RANDOMIZER_HISTORY_LIMIT] picks are kept. */
    fun recordRandomPick(albumName: String, episodeIndex: Int) {
        val history = _randomizerHistory.value
        val picks = (history.picks.take(history.position + 1) + (albumName to episodeIndex))
            .takeLast(RANDOMIZER_HISTORY_LIMIT)
        _randomizerHistory.value = RandomizerHistory(picks, picks.lastIndex)
    }

    /** Moves [steps] through the randomizer history (-1 = previous, 1 = next) and returns
     * the pick to show, or null at either end. Doesn't touch the non-repeating bag: going
     * back to an episode doesn't make it eligible again. */
    fun stepRandomizerHistory(steps: Int): Pair<String, Int>? {
        val history = _randomizerHistory.value
        val position = history.position + steps
        val pick = history.picks.getOrNull(position) ?: return null
        _randomizerHistory.value = history.copy(position = position)
        return pick
    }

    /** Likes/unlikes an episode with the club, then updates local state to match so every
     * screen showing that episode (its album, any playlist it's in, Liked) reflects the new
     * state immediately instead of waiting for the next full refresh. Deliberately doesn't go
     * through [applyGroupings] - that resets the randomizer's non-repeating bag, which a like
     * toggle has no business touching. */
    fun setEpisodeLiked(contentId: String, liked: Boolean) {
        if (isSecretEpisodeId(contentId)) {
            // The club doesn't know secret episodes, so their likes are local only.
            viewModelScope.launch { secretEpisodesStore.setLiked(contentId, liked) }
            return
        }
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
     * via [applyGroupings]). Secret episodes are only added locally (see
     * [SecretEpisodesStore]); the rest go to the club as usual. */
    fun addEpisodesToPlaylist(playlistId: String, ids: List<String>) {
        val (secretIds, contentIds) = ids.partition(::isSecretEpisodeId)
        if (secretIds.isNotEmpty()) {
            viewModelScope.launch { secretEpisodesStore.addToPlaylist(playlistId, secretIds) }
        }
        if (contentIds.isEmpty()) return
        if (isLocalPlaylistId(playlistId)) {
            viewModelScope.launch { localPlaylistStore.add(playlistId, contentIds) }
            return
        }
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
        }
    }

    /** Creates a local playlist named [name] (see [LocalPlaylistStore]) holding [ids] - which
     * may be empty, for one made from the library to fill later. */
    fun createPlaylist(name: String, ids: List<String> = emptyList()) {
        viewModelScope.launch {
            val playlistId = localPlaylistStore.create(name)
            if (ids.isNotEmpty()) addEpisodesToPlaylist(playlistId, ids)
        }
    }

    /** Renames a playlist and/or reorders its content, then patches local state to match. */
    fun updatePlaylist(playlistId: String, name: String, orderedContentIds: List<String>) {
        if (isLocalPlaylistId(playlistId)) {
            viewModelScope.launch { localPlaylistStore.update(playlistId, name, orderedContentIds) }
            return
        }
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
        }
    }

    /** Deletes a user-created playlist, then removes it from local state. */
    fun deletePlaylist(playlistId: String) {
        if (isLocalPlaylistId(playlistId)) {
            viewModelScope.launch {
                localPlaylistStore.delete(playlistId)
                secretEpisodesStore.forgetPlaylist(playlistId)
            }
            return
        }
        viewModelScope.launch {
            try {
                clubClient.deletePlaylist(playlistId)
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }
            val updatedGroupings = _groupings.value.filterNot { it.id == playlistId && it.type == "Playlist" }
            _groupings.value = updatedGroupings
            secretEpisodesStore.forgetPlaylist(playlistId)
        }
    }

    /** Adds the secret episode at [input] (a CDN path or URL - see [parseSecretEpisodePath])
     * to "Secret Episodes", and returns its episode map right away so the caller can play it
     * without waiting for the store to write. Null if [input] isn't a secret episode path. */
    fun addSecretEpisode(input: String): Map<String, Any>? {
        val path = parseSecretEpisodePath(input) ?: return null
        viewModelScope.launch { secretEpisodesStore.add(path) }
        val liked = secretEpisodesState.value.episodes.firstOrNull { it.path == path }?.liked == true
        return secretEpisodeMap(SecretEpisode(path, liked))
    }

    /** Removes a secret episode from "Secret Episodes" (and every local playlist it was added
     * to), deleting its download too - nothing else would list it anymore. */
    fun removeSecretEpisode(id: String) {
        DownloadService.sendRemoveDownload(getApplication(), ExoDownloadService::class.java, id, false)
        viewModelScope.launch { secretEpisodesStore.remove(id) }
    }

    /** Secret episodes added to club playlist [playlistId] on this device. */
    fun secretEpisodesInPlaylist(state: SecretEpisodesState, playlistId: String): List<Map<String, Any>> {
        val byId = state.episodes.associateBy { it.id }
        return state.playlistMembers[playlistId].orEmpty().mapNotNull { id -> byId[id]?.let(::secretEpisodeMap) }
    }

    /** What [link] points at, from the cached catalog where possible and the club's API
     * otherwise. A link can open the app cold, before the cache has loaded, so this waits
     * briefly for it before deciding nothing matches. */
    suspend fun resolveClubLink(link: ClubLink): ClubLinkTarget {
        withTimeoutOrNull(LINK_CACHE_WAIT_MS) { groupings.first { it.isNotEmpty() } }
        return when (link) {
            is ClubLink.Content -> resolveContentLink(link)
            is ClubLink.Grouping -> resolveGroupingLink(link.groupingId)
        }
    }

    private suspend fun resolveContentLink(link: ClubLink.Content): ClubLinkTarget {
        val contentId = link.contentId
        fun ClubGrouping.holds(id: String) = contentList.any { it.id == id }

        val comics = comicSeries.value
        val comicSeriesId = (comics.firstOrNull { it.id == link.groupingId && it.holds(contentId) } ?: comics.firstOrNull { it.holds(contentId) })?.id
        if (comicSeriesId != null) return ClubLinkTarget.Comic(comicSeriesId, contentId)

        // A video opens in the video player, with the rest of its shelf after it - even where
        // "Play videos as audio" would also find it in an album.
        videoShelves.value.forEach { shelf ->
            val index = shelf.videos.indexOfFirst { it.id == contentId }
            if (index >= 0) return ClubLinkTarget.Video(shelf.title, shelf.videos, index)
        }

        baseAlbums.forEach { album ->
            val index = album.episodes.indexOfFirst { it["id"] == contentId }
            if (index >= 0) return ClubLinkTarget.AlbumEpisode(album.name, album.episodes, index)
        }

        // Anything else playable the cache has (a podcast, a playlist's episode...), queued with
        // the rest of the grouping it came from - the one the link names, if it's there.
        val all = groupings.value
        val containing = (all.filter { it.id == link.groupingId } + all.filter { it.type != "Album" && it.holds(contentId) })
        containing.forEach { grouping ->
            val episodes = episodesOf(grouping)
            val index = episodes.indexOfFirst { it["id"] == contentId }
            if (index >= 0) return ClubLinkTarget.Episode(episodes, index)
        }

        // Not cached at all: ask the club. Playback needs only the content id (it's streamed
        // by content request when there's no CDN path), so the content itself is enough.
        val content = try {
            clubClient.fetchContent(contentId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return ClubLinkTarget.NotFound
        if (content.type == "Video") return ClubLinkTarget.Video(content.albumName.orEmpty(), listOf(content), 0)
        if (!isPlayableContent(content, playVideosAsAudio.value)) return ClubLinkTarget.NotFound
        val standIn = ClubGrouping(name = content.albumName.orEmpty(), imageUrl = content.thumbnailMedium, contentList = listOf(content))
        val episode = clubClient.groupingEpisodesFrom(_groupings.value, standIn).firstOrNull() ?: return ClubLinkTarget.NotFound
        return ClubLinkTarget.Episode(listOf(episode), 0)
    }

    private fun resolveGroupingLink(groupingId: String): ClubLinkTarget {
        if (comicSeries.value.any { it.id == groupingId }) return ClubLinkTarget.ComicSeries(groupingId)
        val grouping = groupings.value.firstOrNull { it.id == groupingId } ?: return ClubLinkTarget.NotFound
        return when (grouping.type) {
            "Album" -> ClubLinkTarget.Album(grouping.name)
            "Playlist" -> ClubLinkTarget.Playlist(grouping.id)
            else -> ClubLinkTarget.Grouping(grouping.id)
        }
    }

    /** Everything [grouping] plays, in order - what playing a whole grouping search result queues. */
    fun episodesOf(grouping: ClubGrouping): List<Map<String, Any>> = when (grouping.type) {
        "Album" -> baseAlbums.firstOrNull { it.name == grouping.name }?.episodes.orEmpty()
        "Playlist" -> clubClient.playlistEpisodesFrom(groupings.value, grouping.id) +
            secretEpisodesInPlaylist(secretEpisodesState.value, grouping.id)
        else -> clubClient.groupingEpisodesFrom(_groupings.value, grouping)
    }

    private fun applyGroupings(newGroupings: List<ClubGrouping>) {
        _groupings.value = newGroupings
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
        val comics = mergeComicSeries(indexedComicSeries.value, clubClient.comicSeriesFrom(_groupings.value))
        val videos = mergeBonusVideoShelves(fetchedVideoShelves.value, _groupings.value)
        val categories = homeCategoriesFrom(clubClient, _groupings.value, comics, videos, baseAlbums.size)
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
        /** How long [resolveClubLink] waits on a cold start for the grouping cache to load. */
        private const val LINK_CACHE_WAIT_MS = 10_000L

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

/** Where a club link (see [com.skintclub.odyssey.club.parseClubLink]) leads in the app. */
sealed interface ClubLinkTarget {
    /** An album's episode: [episodes] is the album's list, [index] this one. */
    data class AlbumEpisode(val albumName: String, val episodes: List<Map<String, Any>>, val index: Int) : ClubLinkTarget

    /** Any other playable episode, with the list it's queued in. */
    data class Episode(val episodes: List<Map<String, Any>>, val index: Int) : ClubLinkTarget

    data class Comic(val seriesId: String, val contentId: String) : ClubLinkTarget

    /** A video: [videos] is the shelf it's played through, [index] this one. */
    data class Video(val shelfTitle: String, val videos: List<ClubContent>, val index: Int) : ClubLinkTarget

    data class Album(val name: String) : ClubLinkTarget

    data class ComicSeries(val seriesId: String) : ClubLinkTarget

    data class Playlist(val playlistId: String) : ClubLinkTarget

    data class Grouping(val groupingId: String) : ClubLinkTarget

    /** Nothing the app can show - an article, say, or content that's gone. */
    data object NotFound : ClubLinkTarget
}
