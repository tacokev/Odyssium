package com.skintclub.odyssey.ui.video

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.gson.annotations.SerializedName
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubContent
import com.skintclub.odyssey.club.ClubVideoSource
import com.skintclub.odyssey.club.videoThumbnailUrl
import com.skintclub.odyssey.playback.ODYSSEY_MEDIA_HOST
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber

/** One video in the player's list - what [VideoPlayerActivity] is handed, so it doesn't need
 * the view model (or the grouping cache) of the activity that launched it. */
data class VideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String?,
    @SerializedName("lengthMs") val lengthMs: Long,
) {
    companion object {
        fun from(content: ClubContent) = VideoItem(
            id = content.id,
            title = content.name.ifBlank { content.shortName.orEmpty() },
            description = content.description?.takeIf { it.isNotBlank() },
            thumbnailUrl = videoThumbnailUrl(content),
            lengthMs = content.mediaLength,
        )
    }
}

sealed interface VideoLoadState {
    data object Loading : VideoLoadState
    data object Ready : VideoLoadState
    data class Failed(val message: String) : VideoLoadState
}

/**
 * Owns the video [ExoPlayer] (so it survives the activity being recreated) and the list it
 * steps through. Each video's source is fetched only when it's about to play - its signed
 * cookie doesn't last long enough to fetch a whole list's up front - so the player only ever
 * holds the current one, and [player] (a [ForwardingPlayer]) makes the controller's
 * next/previous buttons step through the list instead.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val clubClient = ClubClient(application)

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos = _videos.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    private val _loadState = MutableStateFlow<VideoLoadState>(VideoLoadState.Loading)
    val loadState = _loadState.asStateFlow()

    private val _shelfTitle = MutableStateFlow("")
    val shelfTitle = _shelfTitle.asStateFlow()

    /** Read on ExoPlayer's loader threads to authorize each request. */
    @Volatile
    private var source: ClubVideoSource? = null

    /** Whether the current video is playing its [ClubVideoSource.streamUrl] (vs. download_url). */
    private var playingStream = false

    /** Whether the current video has already spent its one fresh-source retry after a 403. */
    private var retriedAfterForbidden = false

    private var loadJob: Job? = null

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(application)
        .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            // Taking audio focus is what pauses an episode playing in the music player.
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    val player: Player = object : ForwardingPlayer(exoPlayer) {
        private fun hasNextVideo() = _currentIndex.value < _videos.value.lastIndex

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNextVideo()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNextVideo())
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextVideo())
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun hasNextMediaItem() = hasNextVideo()

        override fun seekToNext() = next()

        override fun seekToNextMediaItem() = next()

        override fun seekToPrevious() = previous()

        override fun seekToPreviousMediaItem() = previous()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _loadState.value = VideoLoadState.Ready
                    retriedAfterForbidden = false
                }
                Player.STATE_ENDED -> next()
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            recover(error)
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    /** Starts [videos] at [startIndex] - once; later calls (the activity being recreated) are
     * ignored so playback carries on where it was. */
    fun start(shelfTitle: String, videos: List<VideoItem>, startIndex: Int) {
        if (_videos.value.isNotEmpty() || videos.isEmpty()) return
        _shelfTitle.value = shelfTitle
        _videos.value = videos
        play(startIndex.coerceIn(videos.indices))
    }

    fun play(index: Int, startPositionMs: Long = 0L, preferStream: Boolean = true) {
        val video = _videos.value.getOrNull(index) ?: return
        _currentIndex.value = index
        _loadState.value = VideoLoadState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            exoPlayer.stop()
            val fetched = try {
                clubClient.fetchVideoSource(video.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't fetch video source for ${video.id}")
                _loadState.value = VideoLoadState.Failed("Couldn't load this video. Check your connection and try again.")
                return@launch
            }
            if (fetched == null) {
                _loadState.value = VideoLoadState.Failed("This video isn't available to play.")
                return@launch
            }
            source = fetched
            val useStream = preferStream && fetched.streamUrl != null
            playingStream = useStream
            val url = (if (useStream) fetched.streamUrl else fetched.downloadUrl ?: fetched.streamUrl)!!
            val item = MediaItem.Builder()
                .setMediaId(video.id)
                .setUri(url.toUri())
                .apply { if (url.toUri().path.orEmpty().endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(video.title)
                        .setArtworkUri(video.thumbnailUrl?.toUri())
                        .build(),
                )
                .build()
            exoPlayer.setMediaItem(item, startPositionMs)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    fun next() {
        val index = _currentIndex.value
        if (index < _videos.value.lastIndex) play(index + 1)
    }

    /** Back to the start of this video if it's a few seconds in, else the one before it. */
    fun previous() {
        val index = _currentIndex.value
        if (exoPlayer.currentPosition > RESTART_THRESHOLD_MS || index == 0) {
            exoPlayer.seekTo(0)
        } else {
            play(index - 1)
        }
    }

    fun retry() {
        retriedAfterForbidden = false
        play(_currentIndex.value, exoPlayer.currentPosition.coerceAtLeast(0))
    }

    /** A 403 once means the signed cookie ran out mid-video: fetch a fresh one and carry on
     * from the same spot. Failing that, an HLS stream that won't play falls back to the
     * single-file download_url before giving up. */
    private fun recover(error: PlaybackException) {
        val position = exoPlayer.currentPosition.coerceAtLeast(0)
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        Timber.w(error, "Video playback error (HTTP $responseCode)")
        when {
            responseCode == 403 && !retriedAfterForbidden -> {
                retriedAfterForbidden = true
                play(_currentIndex.value, position, preferStream = playingStream)
            }
            playingStream && source?.downloadUrl != null -> play(_currentIndex.value, position, preferStream = false)
            else -> _loadState.value = VideoLoadState.Failed("This video couldn't be played.")
        }
    }

    /** Every request to the club's media hosts carries the current video's CloudFront cookies,
     * the way the club site's player sends them - including each segment of an HLS stream. */
    private fun createDataSourceFactory(): ResolvingDataSource.Factory {
        val upstream = DefaultDataSource.Factory(
            getApplication(),
            OkHttpDataSource.Factory(OkHttpClient.Builder().build()).setUserAgent(USER_AGENT),
        )
        return ResolvingDataSource.Factory(upstream) { spec ->
            val host = spec.uri.host.orEmpty()
            if (!host.endsWith(CLUB_DOMAIN, ignoreCase = true)) return@Factory spec
            val headers = buildMap {
                source?.cookieHeader?.let { put("Cookie", it) }
                put("Sec-Fetch-Dest", "video")
                // The media CDN rejects requests without a Range header (see
                // resolveOdysseyDataSpec); only forced when ExoPlayer isn't sending its own.
                if (host == ODYSSEY_MEDIA_HOST && spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong()) {
                    put("Range", "bytes=0-")
                }
            }
            spec.withAdditionalHeaders(headers)
        }
    }

    override fun onCleared() {
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val CLUB_DOMAIN = "adventuresinodyssey.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
