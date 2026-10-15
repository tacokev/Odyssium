package com.skintclub.odyssey.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.playback.queues.ODYSSEY_CONTENT_URI_SCHEME
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.IOException

const val ODYSSEY_MEDIA_HOST = "media.adventuresinodyssey.com"

/** The CDN's signed cookie only grants access to regular episode audio; everything else
 * (podcasts live under private/audio/podcast*) has to go through a content request instead.
 * [path] may be a download_url (relative) or a full URI path (leading slash). */
fun isSignedCookiePath(path: String): Boolean = path.trimStart('/').startsWith("private/audio/episode")

fun isSignedCookieUri(uri: Uri): Boolean = uri.host == ODYSSEY_MEDIA_HOST && isSignedCookiePath(uri.path.orEmpty())

/**
 * Resolves an Odyssey [DataSpec] the same way for both playback ([MusicService]) and
 * downloads ([DownloadUtil]), so a download always uses the exact same URL/headers the player
 * would have used for that episode:
 * - a "Content request" placeholder (see [ODYSSEY_CONTENT_URI_SCHEME]) is followed to a fresh
 *   download_url;
 * - a signed-cookie episode URL gets the current signed cookie attached here, at request time,
 *   rather than one baked in when the queue was built - so a long-lived queue, a seek, or a
 *   resumed download never goes out with an expired cookie;
 * - a bare content id (a queue restored after a restart - see
 *   [com.skintclub.odyssey.extensions.toMediaItem]) is resolved like a content request.
 * Then the CDN's Sec-Fetch-Dest header is added (its Range workaround is
 * [odysseyNetworkDataSource]'s). Runs on a background loader thread in both cases, so the
 * blocking network calls here are intentional - that's what [ResolvingDataSource] is for.
 */
fun resolveOdysseyDataSpec(clubClient: ClubClient, dataSpec: DataSpec): DataSpec {
    val uri = dataSpec.uri
    val withRealUri = when {
        // Throws if the placeholder can't be resolved, surfacing a normal player/download
        // error rather than silently guessing a URL.
        uri.scheme == ODYSSEY_CONTENT_URI_SCHEME -> {
            val contentId = uri.authority
            val downloadUrl = if (contentId.isNullOrBlank()) null else runBlocking { clubClient.getContentDownloadUrl(contentId) }
            if (downloadUrl.isNullOrBlank()) throw IOException("Couldn't resolve a download URL for content $contentId")
            dataSpec.withUri(downloadUrl.toUri())
        }
        // Unlike the placeholder, a failure here falls back to the bare id, so a downloaded or
        // fully cached episode in a restored queue still plays from cache while offline.
        uri.scheme == null && uri.toString().isNotBlank() && '/' !in uri.toString() -> {
            val downloadUrl = runCatching { runBlocking { clubClient.getContentDownloadUrl(uri.toString()) } }
                .onFailure { Timber.w(it, "Couldn't resolve restored content id $uri") }
                .getOrNull()
            if (downloadUrl.isNullOrBlank()) dataSpec else dataSpec.withUri(downloadUrl.toUri())
        }
        isSignedCookieUri(uri) -> dataSpec.withUri(withSignedCookie(clubClient, uri))
        else -> dataSpec
    }

    // The CDN authorizes requests via the signed cookie query string (or the pre-signed
    // content-request URL), not via the Salesforce API bearer token.
    return if (withRealUri.uri.host == ODYSSEY_MEDIA_HOST) {
        withRealUri.withRequestHeaders(mapOf("Sec-Fetch-Dest" to "audio"))
    } else {
        withRealUri
    }
}

/**
 * [upstream] (the network) with the CDN's Range workaround: it rejects requests with no Range
 * header at all, so a from-the-start request gets `bytes=0-`.
 *
 * This has to sit below the cache, on each request that actually goes to the network. Above it
 * (in [resolveOdysseyDataSpec]), a read from the start would carry `bytes=0-` onto the cache's
 * own request for the first part it doesn't have, alongside OkHttpDataSource's real Range
 * header. With two, the CDN serves the file from the start, and those bytes were taken (and
 * cached) as the ones at that position: garbled or silent audio from there on.
 */
fun odysseyNetworkDataSource(upstream: DataSource.Factory): DataSource.Factory =
    ResolvingDataSource.Factory(upstream) { spec ->
        if (spec.uri.host == ODYSSEY_MEDIA_HOST && spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong()) {
            spec.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
        } else {
            spec
        }
    }

/** [uri] with its query replaced by the current signed cookie. Any existing query is a stale
 * cookie baked in by an older build (e.g. a download queued before this change), so it's always
 * dropped. If no cookie can be had (offline, logged out) the bare URL is returned anyway: a
 * downloaded or cached episode is still served from cache, and anything else fails with the
 * CDN's own 403. */
private fun withSignedCookie(clubClient: ClubClient, uri: Uri): Uri {
    val cookie = runCatching { runBlocking { clubClient.getSignedCookie() } }
        .onFailure { Timber.w(it, "Couldn't get a signed cookie") }
        .getOrNull()
    val bare = uri.buildUpon().clearQuery().build().toString()
    return if (cookie == null) bare.toUri() else "$bare$cookie".toUri()
}
