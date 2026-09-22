package com.skintclub.odyssey.playback

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.di.DownloadCache
import com.skintclub.odyssey.di.PlayerCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext context: Context,
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

        init {
            val result = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
            downloads.value = result
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
