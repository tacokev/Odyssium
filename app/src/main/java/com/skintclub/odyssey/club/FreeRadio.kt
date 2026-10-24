package com.skintclub.odyssey.club

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skintclub.odyssey.constants.FreeRadioDownloadsKey
import com.skintclub.odyssey.constants.FreeRadioEpisodesKey
import com.skintclub.odyssey.playback.DownloadUtil
import com.skintclub.odyssey.playback.ExoDownloadService
import com.skintclub.odyssey.playback.queues.FREE_RADIO_EPISODE_KEY
import com.skintclub.odyssey.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The episodes anyone can listen to without logging in: the last few aired on the radio (see
 * [AIOClient.fetchAiredRadio]). They're what Home shows while logged out. The list is kept, so
 * it still shows offline, and one downloaded this way is deleted a week after it was downloaded,
 * since it's only free for a few days.
 */
@Singleton
class FreeRadio
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadUtil: DownloadUtil,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val gson = Gson()
        private val episodesType = object : TypeToken<List<RadioEpisode>>() {}.type
        private val downloadsType = object : TypeToken<Map<String, Long>>() {}.type

        /** The free episodes, newest first, as last fetched. */
        val episodes: StateFlow<List<RadioEpisode>> = context.dataStore.data
            .map { prefs -> decode<List<RadioEpisode>>(prefs[FreeRadioEpisodesKey], episodesType).orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

        /** Free episodes downloaded without an account: content id -> when it was downloaded. */
        val downloadedAt: Flow<Map<String, Long>> = context.dataStore.data
            .map { prefs -> decode<Map<String, Long>>(prefs[FreeRadioDownloadsKey], downloadsType).orEmpty() }

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing = _isRefreshing.asStateFlow()

        /** Why the last refresh failed, or null if it didn't. */
        private val _refreshError = MutableStateFlow<String?>(null)
        val refreshError = _refreshError.asStateFlow()

        init {
            scope.launch { deleteExpiredDownloads() }
        }

        fun refresh() {
            if (_isRefreshing.value) return
            scope.launch {
                _isRefreshing.value = true
                try {
                    val fetched = AIOClient.fetchAiredRadio()
                    context.dataStore.edit { it[FreeRadioEpisodesKey] = gson.toJson(fetched) }
                    _refreshError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Fetching the free radio episodes failed")
                    _refreshError.value = e.message ?: e.javaClass.simpleName
                } finally {
                    _isRefreshing.value = false
                }
            }
        }

        /** Downloads [episode], to be deleted again a week from now. */
        fun download(episode: RadioEpisode) {
            scope.launch {
                context.dataStore.edit { prefs ->
                    val downloads = decode<Map<String, Long>>(prefs[FreeRadioDownloadsKey], downloadsType).orEmpty()
                    prefs[FreeRadioDownloadsKey] = gson.toJson(downloads + (episode.id to System.currentTimeMillis()))
                }
                DownloadService.sendAddDownload(
                    context,
                    ExoDownloadService::class.java,
                    downloadUtil.odysseyDownloadRequest(episode.id, episode.toEpisodeMap()),
                    false,
                )
            }
        }

        fun removeDownload(contentId: String) {
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, contentId, false)
            scope.launch { forget(setOf(contentId)) }
        }

        /** Deletes the free downloads that are a week old, and forgets ones already gone. */
        suspend fun deleteExpiredDownloads() {
            val downloads = downloadedAt.first()
            if (downloads.isEmpty()) return
            val now = System.currentTimeMillis()
            val current = downloadUtil.downloads.value
            val expired = downloads.filterValues { now - it >= DOWNLOAD_LIFETIME_MS }.keys
            expired.forEach { id ->
                if (current[id] != null) {
                    Timber.i("Deleting free download $id, downloaded a week ago")
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
                }
            }
            val gone = downloads.keys.filter { id -> current[id]?.state.let { it == null || it == Download.STATE_FAILED } }
            forget(expired + gone)
        }

        private suspend fun forget(ids: Set<String>) {
            if (ids.isEmpty()) return
            context.dataStore.edit { prefs ->
                val downloads = decode<Map<String, Long>>(prefs[FreeRadioDownloadsKey], downloadsType).orEmpty()
                prefs[FreeRadioDownloadsKey] = gson.toJson(downloads - ids)
            }
        }

        private fun <T> decode(json: String?, type: java.lang.reflect.Type): T? =
            json?.let { runCatching { gson.fromJson<T>(it, type) }.getOrNull() }

        companion object {
            val DOWNLOAD_LIFETIME_MS = TimeUnit.DAYS.toMillis(7)
        }
    }

/** The album a free radio episode is shown under. */
const val FREE_RADIO_ALBUM_NAME = "On the radio"

/** [this] as an episode map, like every other episode list's, flagged as a free radio episode
 * so it plays (and downloads) without logging in. */
fun RadioEpisode.toEpisodeMap(): Map<String, Any> = buildMap {
    put("id", id)
    put("name", name)
    put("album_name", FREE_RADIO_ALBUM_NAME)
    put("download_url", "")
    put("image_small", thumbnailSmall ?: thumbnailMedium ?: "")
    put("image_medium", thumbnailMedium ?: thumbnailSmall ?: "")
    put("image_large", thumbnailMedium ?: thumbnailSmall ?: "")
    put("list_thumbnail", thumbnailSmall ?: thumbnailMedium ?: "")
    shortName?.let { put("short_name", it) }
    description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
    put(FREE_RADIO_EPISODE_KEY, true)
}
