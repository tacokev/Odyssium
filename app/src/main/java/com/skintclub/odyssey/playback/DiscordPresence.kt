package com.skintclub.odyssey.playback

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import com.skintclub.odyssey.BuildConfig
import com.skintclub.odyssey.club.isSecretEpisodeId
import com.skintclub.odyssey.models.MediaMetadata
import timber.log.Timber

/**
 * Shows what's playing as the user's Discord status ("Listening to <episode>"), through the
 * Discord Social SDK's Rich Presence (see src/main/cpp/discord_presence.cpp).
 *
 * It goes through the Discord app installed on this phone, so there's no Discord login and
 * nothing to configure beyond turning it on. It only exists in builds made with the SDK in
 * discord_social_sdk/ (BuildConfig.DISCORD_SDK); otherwise [isAvailable] is false and every call
 * does nothing.
 *
 * All SDK calls run on one worker thread, which also pumps the SDK's callbacks while it's in use.
 */
class DiscordPresence {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastShown: Status? = null

    /** What's shown: [start]/[end] are Unix seconds, 0 when paused or unknown. */
    private data class Status(
        val title: String,
        val album: String?,
        val image: String?,
        val start: Long,
        val end: Long,
    )

    private val pump = object : Runnable {
        override fun run() {
            nativeRunCallbacks()
            handler?.postDelayed(this, CALLBACK_INTERVAL_MS)
        }
    }

    /** Shows [metadata] (playing from [positionMs] if [isPlaying]), or clears the status for null. */
    fun show(metadata: MediaMetadata?, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        if (!isAvailable) return
        val status = metadata?.let {
            val now = System.currentTimeMillis()
            val start = if (isPlaying) (now - positionMs) / 1000 else 0L
            // A secret episode stays secret: no title, album or artwork of its own.
            val secret = isSecretEpisodeId(it.id)
            Status(
                title = if (secret) SECRET_EPISODE_TITLE else it.title,
                album = if (secret) null else it.album?.title ?: it.artists.firstOrNull()?.name,
                // Discord loads artwork from the web; a thumbnail stored on the phone (a secret
                // episode's own) can't be shown, so the app's uploaded asset stands in.
                image = it.thumbnailUrl?.takeIf { url -> !secret && url.startsWith("https://") } ?: FALLBACK_IMAGE,
                start = start,
                end = if (isPlaying && durationMs > 0) start + durationMs / 1000 else 0L,
            )
        }
        // Each position update moves start a little; only resend when it's noticeably off.
        val last = lastShown
        if (status != null && last != null && status.copy(start = last.start, end = last.end) == last &&
            kotlin.math.abs(status.start - last.start) < 3 && (status.start == 0L) == (last.start == 0L)
        ) {
            return
        }
        lastShown = status
        worker().post {
            if (status == null) {
                nativeClear()
            } else {
                // Laid out like a song on Discord: title, then the "artist" line, then the album
                // (which Discord shows from the artwork's text).
                nativeUpdate(
                    details = status.title,
                    state = SHOW_NAME,
                    largeImage = status.image,
                    largeText = status.album,
                    startSeconds = status.start,
                    endSeconds = status.end,
                )
            }
        }
    }

    /** Clears the status and stops the worker thread. */
    fun release() {
        if (!isAvailable) return
        lastShown = null
        val thread = thread ?: return
        handler?.removeCallbacks(pump)
        handler?.post { nativeRelease() }
        thread.quitSafely()
        this.thread = null
        handler = null
    }

    private fun worker(): Handler {
        handler?.let { return it }
        val thread = HandlerThread("DiscordPresence").apply { start() }
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        handler.post { nativeInit(BuildConfig.DISCORD_APPLICATION_ID) }
        handler.post(pump)
        return handler
    }

    companion object {
        private const val CALLBACK_INTERVAL_MS = 500L

        /** The second line of the status, where a song would have its artist. */
        private const val SHOW_NAME = "Adventures in Odyssey"

        /** What a secret episode is shown as, in place of its title. */
        private const val SECRET_EPISODE_TITLE = "Secret Episode"

        /** An image uploaded under Rich Presence > Art Assets in the Discord Developer Portal. */
        private const val FALLBACK_IMAGE = "odyssium"

        /** Whether this build has the Discord Social SDK (and its native libraries loaded). */
        val isAvailable: Boolean by lazy {
            BuildConfig.DISCORD_SDK && runCatching { System.loadLibrary("odyssium_discord") }
                .onFailure { Timber.w(it, "Discord Social SDK not loaded") }
                .isSuccess
        }

        /** The SDK reaches the Discord app through the app's context, which it takes from an
         * Activity - so this has to be called once from MainActivity before presence can show. */
        fun attach(activity: Activity) {
            if (!isAvailable) return
            runCatching {
                // Called reflectively so builds without the SDK still compile.
                Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                    .getMethod("setEngineActivity", Activity::class.java)
                    .invoke(null, activity)
            }.onFailure { Timber.w(it, "Couldn't hand the Discord SDK its activity") }
        }

        @JvmStatic private external fun nativeInit(applicationId: Long)

        @JvmStatic private external fun nativeUpdate(
            details: String,
            state: String?,
            largeImage: String?,
            largeText: String?,
            startSeconds: Long,
            endSeconds: Long,
        )

        @JvmStatic private external fun nativeClear()

        @JvmStatic private external fun nativeRunCallbacks()

        @JvmStatic private external fun nativeRelease()
    }
}
