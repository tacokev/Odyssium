package com.skintclub.odyssey.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.constants.SyncEpisodeProgressKey
import com.skintclub.odyssey.utils.dataStore
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** How often the playing episode's position is sent while it plays. */
private const val PROGRESS_SYNC_INTERVAL_MS = 30_000L

/** Club content ids are Salesforce ids (15 or 18 letters/digits). Anything else the player
 * holds - a secret episode's CDN URL, say - isn't something the club can save progress for. */
private val clubContentIdRegex = Regex("""[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?""")

/**
 * Settings > Account > "Sync episode progress": keeps the club's listening progress for the
 * playing episode up to date, like the website's player does - its position every
 * [PROGRESS_SYNC_INTERVAL_MS] while it plays, once more when it's paused (so stopping between
 * two syncs isn't lost), and "Completed" when it plays through to the end.
 *
 * [scope] is read on each use rather than captured, since [MusicService] replaces its scope
 * after it's been cancelled.
 */
class EpisodeProgressSync(
    private val context: Context,
    private val player: Player,
    private val clubClient: ClubClient,
    private val scope: () -> CoroutineScope,
) : Player.Listener {
    private var tickJob: Job? = null

    /** The episode [player] is on, remembered so a transition can still report the one it left. */
    private var currentId: String? = player.currentMediaItem?.mediaId

    init {
        player.addListener(this)
        if (player.isPlaying) startTicking()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startTicking()
            return
        }
        tickJob?.cancel()
        // isPlaying also drops while buffering; only an actual pause is worth a report.
        if (!player.playWhenReady && player.playbackState == Player.STATE_READY) {
            send(currentId, player.currentPosition, STATUS_IN_PROGRESS)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Moving on by itself means the previous episode played to its end.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) send(currentId, null, STATUS_COMPLETED)
        currentId = mediaItem?.mediaId
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The last episode in the queue finishing doesn't transition anywhere.
        if (playbackState == Player.STATE_ENDED) send(currentId, null, STATUS_COMPLETED)
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope().launch {
            while (isActive) {
                delay(PROGRESS_SYNC_INTERVAL_MS)
                if (player.isPlaying) send(currentId, player.currentPosition, STATUS_IN_PROGRESS)
            }
        }
    }

    /** Sends one update in the background, if the setting is on and there's a club session.
     * Never throws: the service's scope has no SupervisorJob, so a failure here must not
     * escape and cancel everything else running in it. */
    private fun send(contentId: String?, positionMs: Long?, status: String) {
        if (contentId == null || !clubContentIdRegex.matches(contentId)) return
        val timestamp = Instant.now()
        scope().launch {
            try {
                val enabled = context.dataStore.data.first()[SyncEpisodeProgressKey] ?: true
                if (!enabled || !clubClient.isLoggedIn()) return@launch
                clubClient.updateContentProgress(contentId, positionMs, status, timestamp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't sync progress for $contentId")
            }
        }
    }

    private companion object {
        const val STATUS_IN_PROGRESS = "In Progress"
        const val STATUS_COMPLETED = "Completed"
    }
}
