package com.skintclub.odyssey.viewmodels

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.constants.OdysseySortReversedKey
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OdysseyViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {
    private val clubClient = ClubClient(application)

    private val _albums = MutableStateFlow<List<OdysseyAlbum>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _signedCookie = MutableStateFlow<String?>(null)
    val signedCookie = _signedCookie.asStateFlow()

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
            val cached = clubClient.getCachedEpisodes()
            if (cached != null) {
                applyEpisodes(cached)
            }
            performRefresh(silent = cached != null)
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
                val episodes = clubClient.fetchAlbumEpisodes()
                _signedCookie.value = clubClient.fetchSignedCookie()

                applyEpisodes(episodes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            clubClient.logout()
            _needsRelogin.value = true
        }
        _isRefreshing.value = false
    }

    private fun applyEpisodes(episodes: List<Map<String, Any>>) {
        baseAlbums = episodes.groupBy { it["album_name"] as? String ?: "Unknown" }
            .map { (name, episodesInAlbum) ->
                val cover = (episodesInAlbum.firstOrNull()?.get("image_medium") as? String)?.takeIf { it.isNotBlank() }
                    ?: (episodesInAlbum.firstOrNull()?.get("image_small") as? String)?.takeIf { it.isNotBlank() }
                OdysseyAlbum(name, episodesInAlbum, cover)
            }
            .sortedWith(albumOrderComparator)
        applySortOrder()
    }

    data class OdysseyAlbum(
        val name: String,
        val episodes: List<Map<String, Any>>,
        val coverUrl: String?
    )

    companion object {
        private val albumNumberRegex = Regex("""^#\s*(\d+)""")

        /** Albums without a leading "#<number>" sort first; numbered albums follow, ordered by that number. */
        private val albumOrderComparator =
            compareBy<OdysseyAlbum>(
                { albumNumberRegex.find(it.name) != null },
                { albumNumberRegex.find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                { it.name },
            )
    }
}
