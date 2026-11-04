package com.skintclub.odyssey.mabel

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.db.eventsForCurrentProfile
import com.skintclub.odyssey.extensions.toMediaItem
import com.skintclub.odyssey.models.MediaMetadata
import com.skintclub.odyssey.playback.LocalEpisodeProgress
import com.skintclub.odyssey.playback.queues.Queue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId

/** Scheme of a clip of Mabel speaking: `mabel://clip?text=...`. It's also the clip's media id,
 * so a queue restored after a restart (which rebuilds items from their ids) still has it. */
const val MABEL_URI_SCHEME = "mabel"

fun isMabelClip(id: String?): Boolean = id?.startsWith("$MABEL_URI_SCHEME://") == true

/** Mabel's picture, for her clips. By name, not resource id, since ids change between builds
 * and a restored queue keeps the uri. */
fun mabelArtworkUri(context: Context): String = "android.resource://${context.packageName}/drawable/mabel_art"

fun mabelClipMediaItem(context: Context, text: String, vibe: String?): MediaItem {
    val id = Uri.Builder().scheme(MABEL_URI_SCHEME).authority("clip").appendQueryParameter("text", text).build().toString()
    return MediaMetadata(
        id = id,
        title = "Mabel",
        artists = listOf(MediaMetadata.Artist(id = null, name = vibe ?: "Your DJ")),
        duration = -1,
        thumbnailUrl = mabelArtworkUri(context),
        description = text,
    ).toMediaItem()
}

/** A listening session hosted by Mabel (see [MabelSession]), which goes on for as long as
 * it's played. */
class MabelQueue(
    private val context: Context,
    private val data: MabelData,
    private val voice: MabelVoice,
    private val database: MusicDatabase,
    private val pool: List<Map<String, Any>>,
) : Queue {
    override val preloadItem: MediaMetadata? = null
    private var session: MabelSession? = null

    override suspend fun getInitialStatus(): Queue.Status =
        withContext(Dispatchers.IO) {
            val session = MabelSession(data, pool, loadHistory()).also { session = it }
            val segments = session.start().let { if (it.size < 3) it + session.nextBlock() else it }
            // The first thing she says is spoken now, so playback starts right away.
            (segments.firstOrNull() as? MabelSegment.Speak)?.let { voice.speak(it.text) }
            Queue.Status(title = "Mabel", items = toItems(segments), mediaItemIndex = 0)
        }

    override fun hasNextPage(): Boolean = session != null

    /** The listener skipped [contentId] partway through (see [MabelSession.onSkipped]). */
    fun onSkipped(contentId: String) {
        if (!isMabelClip(contentId)) session?.onSkipped(contentId)
    }

    override suspend fun nextPage(): List<MediaItem> =
        withContext(Dispatchers.IO) { toItems(session?.nextBlock().orEmpty()) }

    private fun toItems(segments: List<MabelSegment>): List<MediaItem> =
        segments.map { segment ->
            when (segment) {
                is MabelSegment.Speak -> {
                    // Spoken ahead of time, while the episodes before it play.
                    voice.prepare(segment.text)
                    mabelClipMediaItem(context, segment.text, segment.vibe)
                }
                is MabelSegment.Play -> odysseyEpisodeToMediaItem(context, segment.episode)
            }
        }

    private suspend fun loadHistory(): MabelHistory {
        val zone = ZoneId.systemDefault()
        val plays = database.eventsForCurrentProfile(context).first()
            .take(500)
            .map { it.event.songId to it.event.timestamp.atZone(zone).toInstant() }
        val progress = LocalEpisodeProgress.forCurrentProfile(context).first()
        val liked = pool.filter { it["is_bookmarked"] == true }.mapNotNull { it["id"] as? String }.toSet()
        return MabelHistory(plays, progress, liked)
    }
}

/** Plays Mabel's clips ([MABEL_URI_SCHEME]), speaking them first if they haven't been yet, and
 * hands everything else to [upstream]. Sits above the cache: her clips are already files. */
@OptIn(UnstableApi::class)
class MabelDataSourceFactory(
    private val voice: MabelVoice,
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = MabelDataSource(voice, upstream.createDataSource())
}

@OptIn(UnstableApi::class)
private class MabelDataSource(
    private val voice: MabelVoice,
    private val upstream: DataSource,
) : DataSource {
    private val file = FileDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        file.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != MABEL_URI_SCHEME) {
            current = upstream
            return upstream.open(dataSpec)
        }
        val text = dataSpec.uri.getQueryParameter("text") ?: throw IOException("Nothing for Mabel to say")
        // On a loader thread, so waiting here is fine.
        val clip = runBlocking { voice.speak(text) } ?: throw IOException("Mabel couldn't say that")
        current = file
        return file.open(dataSpec.withUri(Uri.fromFile(clip)))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("Not open")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
