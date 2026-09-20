package com.skintclub.odyssey.lyrics

import android.content.Context
import android.util.LruCache
import com.skintclub.odyssey.constants.PreferredLyricsProvider
import com.skintclub.odyssey.constants.PreferredLyricsProviderKey
import com.skintclub.odyssey.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.skintclub.odyssey.extensions.toEnum
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LyricsHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var lyricsProviders =
            listOf(LrcLibLyricsProvider, KuGouLyricsProvider, YouTubeSubtitleLyricsProvider, YouTubeLyricsProvider)
        val preferred =
            context.dataStore.data
                .map {
                    it[PreferredLyricsProviderKey].toEnum(PreferredLyricsProvider.LRCLIB)
                }.distinctUntilChanged()
                .map {
                    lyricsProviders =
                        if (it == PreferredLyricsProvider.LRCLIB) {
                            listOf(LrcLibLyricsProvider, KuGouLyricsProvider, YouTubeSubtitleLyricsProvider, YouTubeLyricsProvider)
                        } else {
                            listOf(KuGouLyricsProvider, LrcLibLyricsProvider, YouTubeSubtitleLyricsProvider, YouTubeLyricsProvider)
                        }
                }
        private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)

        // The app plays Adventures in Odyssey episodes rather than YouTube Music songs, so
        // there's no such thing as song lyrics to look up anymore. The "lyrics" panel just
        // shows the episode's own description/synopsis instead.
        suspend fun getLyrics(mediaMetadata: MediaMetadata): String = mediaMetadata.description?.takeIf { it.isNotBlank() } ?: LYRICS_NOT_FOUND

        suspend fun getAllLyrics(
            mediaId: String,
            songTitle: String,
            songArtists: String,
            duration: Int,
            callback: (LyricsResult) -> Unit,
        ) {
            val cacheKey = "$songArtists-$songTitle".replace(" ", "")
            cache.get(cacheKey)?.let { results ->
                results.forEach {
                    callback(it)
                }
                return
            }
            val allResult = mutableListOf<LyricsResult>()
            lyricsProviders.forEach { provider ->
                if (provider.isEnabled(context)) {
                    provider.getAllLyrics(mediaId, songTitle, songArtists, duration) { lyrics ->
                        val result = LyricsResult(provider.name, lyrics)
                        allResult += result
                        callback(result)
                    }
                }
            }
            cache.put(cacheKey, allResult)
        }

        companion object {
            private const val MAX_CACHE_SIZE = 3
        }
    }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)
