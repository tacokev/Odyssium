package com.skintclub.odyssey.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.constants.BulkDownloadIdsKey
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.di.DownloadCache
import com.skintclub.odyssey.di.PlayerCache
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: SimpleCache,
        @PlayerCache val playerCache: SimpleCache,
    ) {
        private val clubClient = ClubClient(context)

        // Resolves the same way MusicService's playback DataSource does (see
        // resolveOdysseyDataSpec), so an Odyssey download follows a "Content request"
        // placeholder to its real URL and gets the same CDN headers a playback request would -
        // otherwise a download would either fetch the wrong thing or get rejected outright.
        private val dataSourceFactory =
            ResolvingDataSource.Factory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            OkHttpClient
                                .Builder()
                                .build(),
                        ).setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    ),
            ) { dataSpec ->
                resolveOdysseyDataSpec(clubClient, dataSpec)
            }
        val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
        val downloadManager: DownloadManager =
            DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
                maxParallelDownloads = 3
                addListener(
                    ExoDownloadService.TerminalStateNotificationHelper(
                        context = context,
                        notificationHelper = downloadNotificationHelper,
                        nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1,
                    ),
                )
            }
        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** The running bulk download's episode ids (see [BulkDownloadIdsKey]); empty when none. */
        private val bulkIds = context.dataStore.data
            .map { prefs -> prefs[BulkDownloadIdsKey]?.split(',')?.filter { it.isNotEmpty() }.orEmpty() }
            .distinctUntilChanged()

        /** How the running bulk download is going, or null when there isn't one. */
        val bulkDownload: StateFlow<BulkDownloadProgress?> =
            combine(bulkIds, downloads) { ids, downloads ->
                if (ids.isEmpty()) return@combine null
                var finished = 0
                var failed = 0
                var current: Download? = null
                for (id in ids) {
                    val download = downloads[id]
                    when (download?.state) {
                        Download.STATE_COMPLETED, Download.STATE_REMOVING -> finished++
                        Download.STATE_FAILED -> {
                            finished++
                            failed++
                        }
                        Download.STATE_DOWNLOADING -> if (current == null) current = download
                        else -> {}
                    }
                }
                BulkDownloadProgress(
                    total = ids.size,
                    finished = finished,
                    failed = failed,
                    currentEpisode = current?.let { Util.fromUtf8Bytes(it.request.data) },
                    currentPercent = current?.percentDownloaded?.takeIf { it >= 0 } ?: 0f,
                )
            }.stateIn(scope, SharingStarted.Eagerly, null)

        /**
         * Downloads every one of [episodes] that isn't downloaded or downloading already, and
         * tracks them all as one bulk download ([bulkDownload]) until each has finished or
         * failed. Replaces any bulk download already running (its episodes keep downloading).
         */
        fun startBulkDownload(episodes: List<Map<String, Any>>) {
            val byId = episodes.mapNotNull { episode -> (episode["id"] as? String)?.takeIf { it.isNotEmpty() }?.let { it to episode } }.toMap()
            if (byId.isEmpty()) return
            scope.launch {
                context.dataStore.edit { it[BulkDownloadIdsKey] = byId.keys.joinToString(",") }
                val current = downloads.value
                byId.forEach { (id, episode) ->
                    val state = current[id]?.state
                    if (state == Download.STATE_COMPLETED || state == Download.STATE_QUEUED ||
                        state == Download.STATE_DOWNLOADING
                    ) {
                        return@forEach
                    }
                    downloadManager.addDownload(odysseyDownloadRequest(id, episode))
                }
                // Added straight to the manager rather than one service intent per episode
                // (a thousand of them). This starts the service, which keeps its foreground
                // notification up for as long as anything is downloading.
                DownloadService.sendResumeDownloads(context, ExoDownloadService::class.java, false)
            }
        }

        /** Stops the bulk download: whatever of it hasn't finished is removed, and what has
         * stays downloaded. */
        fun cancelBulkDownload() {
            scope.launch {
                val ids = bulkIds.first()
                context.dataStore.edit { it.remove(BulkDownloadIdsKey) }
                val current = downloads.value
                ids.forEach { id ->
                    if (current[id]?.state != Download.STATE_COMPLETED) downloadManager.removeDownload(id)
                }
            }
        }

        /** The same request as downloading [episode] from its menu. */
        fun odysseyDownloadRequest(id: String, episode: Map<String, Any>): DownloadRequest =
            DownloadRequest
                .Builder(id, odysseyEpisodeToMediaItem(context, episode).localConfiguration!!.uri)
                .setCustomCacheKey(id)
                .setData((episode["name"] as? String ?: "").toByteArray())
                .build()

        init {
            val result = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
            downloads.value = result
            // Once every episode of a bulk download has finished (or failed), it's over.
            scope.launch {
                bulkDownload.collect { progress ->
                    if (progress != null && progress.finished >= progress.total) {
                        context.dataStore.edit { it.remove(BulkDownloadIdsKey) }
                    }
                }
            }
            downloadManager.addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }
                    }
                },
            )
        }
    }

/** A bulk download's progress: [finished] of [total] episodes are done ([failed] of them
 * failed), and [currentEpisode] is the one downloading now, [currentPercent] of the way. */
data class BulkDownloadProgress(
    val total: Int,
    val finished: Int,
    val failed: Int,
    val currentEpisode: String?,
    val currentPercent: Float,
) {
    /** 0 to 1, counting the downloading episode's own progress. */
    val fraction: Float
        get() = if (total == 0) 1f else ((finished + currentPercent / 100f) / total).coerceIn(0f, 1f)
}
