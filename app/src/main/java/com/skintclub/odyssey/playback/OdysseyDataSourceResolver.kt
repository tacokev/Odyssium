package com.skintclub.odyssey.playback

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.playback.queues.ODYSSEY_CONTENT_URI_SCHEME
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Resolves an Odyssey [DataSpec] the same way for both playback ([MusicService]) and
 * downloads ([DownloadUtil]), so a download always uses the exact same URL/headers the player
 * would have used for that episode: follows the "Content request" audio-loading placeholder
 * (see [ODYSSEY_CONTENT_URI_SCHEME]) to a fresh download_url when that mode is active, then
 * applies the CDN's Range/Sec-Fetch-Dest header workaround. Runs on a background loader
 * thread in both cases, so the blocking network call here is intentional - that's what
 * [androidx.media3.datasource.ResolvingDataSource] is for. Throws if the placeholder can't be
 * resolved, surfacing a normal player/download error rather than silently guessing a URL.
 */
fun resolveOdysseyDataSpec(clubClient: ClubClient, dataSpec: DataSpec): DataSpec {
    val withRealUri = if (dataSpec.uri.scheme == ODYSSEY_CONTENT_URI_SCHEME) {
        val contentId = dataSpec.uri.authority
        val downloadUrl = if (contentId.isNullOrBlank()) null else runBlocking { clubClient.fetchContentDownloadUrl(contentId) }
        if (downloadUrl.isNullOrBlank()) throw IOException("Couldn't resolve a download URL for content $contentId")
        dataSpec.withUri(downloadUrl.toUri())
    } else {
        dataSpec
    }

    return if (withRealUri.uri.toString().startsWith("https://media.adventuresinodyssey.com/")) {
        // The CDN authorizes requests via the signed cookie query string already appended to
        // the URL by OdysseyQueue, not via the Salesforce API bearer token. It does, however,
        // reject requests with no Range header at all, so for a from-the-start request (no
        // seek in play, or a download starting fresh) one is forced. On a seek, or a download
        // resuming mid-file, the upstream OkHttpDataSource already appends its own Range
        // header computed from dataSpec.position/length; forcing one here too would send two
        // Range headers on the same request and break scrubbing/resuming.
        val headers = if (withRealUri.position == 0L && withRealUri.length == C.LENGTH_UNSET.toLong()) {
            mapOf("Sec-Fetch-Dest" to "audio", "Range" to "bytes=0-")
        } else {
            mapOf("Sec-Fetch-Dest" to "audio")
        }
        withRealUri.withRequestHeaders(headers)
    } else {
        withRealUri
    }
}
