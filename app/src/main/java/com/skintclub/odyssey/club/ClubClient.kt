package com.skintclub.odyssey.club

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.skintclub.odyssey.constants.ClubAccessTokenKey
import com.skintclub.odyssey.constants.ClubContentGroupingsCacheKey
import com.skintclub.odyssey.constants.ClubMembershipExpiresKey
import com.skintclub.odyssey.constants.ClubPinKey
import com.skintclub.odyssey.constants.ClubRefreshTokenKey
import com.skintclub.odyssey.constants.ClubViewerIdKey
import com.skintclub.odyssey.constants.PlayVideosAsAudioKey
import com.skintclub.odyssey.playback.ODYSSEY_MEDIA_HOST
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ClubProfile(
    @SerializedName("viewer_id") val viewerId: String = "",
    val username: String = "",
    @SerializedName("hasPIN") val hasPin: Boolean = false,
    @SerializedName("profile_picture") val profilePictureUrl: String? = null,
)

private data class ClubMembershipStatus(
    @SerializedName("membership_expires") val membershipExpires: String? = null,
)

private data class ClubViewerMetadata(
    val membershipStatus: ClubMembershipStatus? = null,
)

data class ClubTag(
    @SerializedName("topic_id") val topicId: String? = null,
    val name: String? = null,
)

data class ClubProgress(
    val status: String? = null,
    @SerializedName("current_progress") val current: Long = 0,
)

data class ClubContent(
    val id: String = "",
    val type: String = "",
    val subtype: String? = null,
    val name: String = "",
    @SerializedName("short_name") val shortName: String? = null,
    val description: String? = null,
    @SerializedName("thumbnail_small") val thumbnailSmall: String? = null,
    @SerializedName("thumbnail_medium") val thumbnailMedium: String? = null,
    @SerializedName("media_variant") val mediaVariant: String? = null,
    @SerializedName("media_length") val mediaLength: Long = 0,
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("album_name") val albumName: String? = null,
    val progress: ClubProgress? = null,
    @SerializedName("is_bookmarked") val isBookmarked: Boolean = false,
    val tags: List<ClubTag> = emptyList(),
)

data class ClubGrouping(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    // Present only on playlists a viewer created themselves (as opposed to e.g. official
    // "Playlist"-type groupings), used to sort those to the top of the playlist picker.
    @SerializedName("viewer_id") val viewerId: String? = null,
    val description: String? = null,
    @SerializedName("full_description") val fullDescription: String? = null,
    @SerializedName("imageURL") val imageUrl: String? = null,
    @SerializedName("total_runtime") val totalRuntime: Long = 0,
    @SerializedName("is_bookmarked") val isBookmarked: Boolean = false,
    @SerializedName("is_editable") val isEditable: Boolean = false,
    @SerializedName("contentList") val contentList: List<ClubContent> = emptyList(),
    // The API returns this as either a number or a string depending on endpoint, so it's kept
    // as a raw JsonElement and normalized by [toYearStringOrNull] where it's actually used.
    @SerializedName("album_copyright_year") val albumCopyrightYear: JsonElement? = null,
)

/** A "Playlist"-type grouping as offered to the randomizer's blacklist/whitelist picker and
 * the Playlists tab. */
data class ClubPlaylistOption(
    val id: String,
    val name: String,
    val isUserCreated: Boolean,
    val imageUrl: String?,
    val episodeCount: Int,
) {
    /** Made in the app and kept on this device, rather than on the club. */
    val isLocal: Boolean get() = isLocalPlaylistId(id)
}

private data class ClubProfilesResponse(
    val profiles: List<ClubProfile> = emptyList(),
    val metadata: ClubViewerMetadata? = null,
)

private data class ClubArticleBody(
    @SerializedName("content_body") val contentBody: String? = null,
    @SerializedName("content_body_html") val contentBodyHtml: String? = null,
)

private data class ClubGroupingsPageMetadata(val totalPageCount: Int = 1)

private data class ClubGroupingsPageResponse(
    val metadata: ClubGroupingsPageMetadata? = null,
    val contentGroupings: List<ClubGrouping> = emptyList(),
)

/** A signed CDN credential (cookie query string or pre-signed URL) and when it stops working. */
/** Whether [content] is offered for playback: audio always, and video (played as audio only)
 * while "Play videos as audio" is on. Everything else - articles, mostly - never is. */
fun isPlayableContent(content: ClubContent, playVideosAsAudio: Boolean): Boolean =
    content.type == "Audio" || (playVideosAsAudio && content.type == "Video")

private class CachedSigned(val value: String, val expiresAtMillis: Long)

private fun JsonElement?.toYearStringOrNull(): String? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    return if (primitive.isNumber) {
        primitive.asNumber.toInt().takeIf { it > 0 }?.toString()
    } else {
        primitive.asString.takeIf { it.isNotBlank() }
    }
}

/**
 * Talks to the Adventures in Odyssey Club API: OAuth2 + PKCE login, profile/PIN selection,
 * and content browsing. An unauthenticated counterpart for browsing without an account may
 * follow later, hence the "Club" (rather than app-specific) naming.
 */
class ClubClient(private val context: Context) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun getAccessToken(): String? = context.dataStore.data.first()[ClubAccessTokenKey]
    private suspend fun setAccessToken(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(ClubAccessTokenKey) else it[ClubAccessTokenKey] = value
    }

    private suspend fun getRefreshToken(): String? = context.dataStore.data.first()[ClubRefreshTokenKey]
    private suspend fun setRefreshToken(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(ClubRefreshTokenKey) else it[ClubRefreshTokenKey] = value
    }

    suspend fun getViewerId(): String? = context.dataStore.data.first()[ClubViewerIdKey]
    private suspend fun setViewerId(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(ClubViewerIdKey) else it[ClubViewerIdKey] = value
    }

    private suspend fun getPin(): String = context.dataStore.data.first()[ClubPinKey] ?: "0000"
    private suspend fun setPin(value: String) = context.dataStore.edit { it[ClubPinKey] = value }

    private val clientId = "3MVG9l2zHsylwlpTFc1ZB3ryOQlpLYIqNo0UV4d0lBRjkbb6TXbw9UNhdcJfom2nnbB.AbNpkRbGoTfruF0gB"
    private val clientSecret = "B25FC7FE3E4C155E77C73EA2AC72D410E0762C897798816FC257F0C8FA3618AD"
    private val apiBase = "https://fotf.my.site.com/aio/services/"
    private val redirectUrl = "https://app.adventuresinodyssey.com/callback"
    private val experienceName = "Adventures In Odyssey"

    var codeVerifier: String? = null

    /** The "Play videos as audio" setting, read once per list built (not per item - each
     * read is a blocking DataStore lookup). */
    fun playVideosAsAudio(): Boolean = context.dataStore.get(PlayVideosAsAudioKey, true)

    private fun playableFilter(playVideosAsAudio: Boolean = playVideosAsAudio()): (ClubContent) -> Boolean =
        { isPlayableContent(it, playVideosAsAudio) }

    companion object {
        /** The club's own logout page. It doesn't render anything useful itself — it just
         * kicks off a server-side session teardown and, once that's done, redirects away -
         * to a Salesforce domain, or sometimes just back to the club's own homepage - which
         * is what [isLogoutComplete] watches for. */
        const val LOGOUT_URL = "https://app.adventuresinodyssey.com/logout"

        /** Grouping types behind the Home screen's category tiles. */
        const val EPISODE_HOME_TYPE = "Episode Home"
        const val SERIES_TYPE = "Series"
        const val PODCAST_YEAR_TYPE = "Podcast Year"
        const val COLLECTION_TYPE = "Collection"
        const val BOOK_SERIES_TYPE = "Book Series"

        private val firstNumberRegex = Regex("""\d+""")

        /** The CDN's signed cookie and content-request URLs (keyed by content id), shared
         * process-wide: MusicService, DownloadUtil and the view model each build their own
         * ClubClient, but all stream from the same CDN. */
        @Volatile
        private var cachedSignedCookie: CachedSigned? = null
        private val signedCookieMutex = Mutex()
        private val cachedContentDownloadUrls = ConcurrentHashMap<String, CachedSigned>()

        /** Refresh this long before a signature's own expiry, so a request that starts just
         * before it doesn't get cut off partway through loading. */
        private const val SIGNED_REFRESH_MARGIN_MS = 2 * 60 * 1000L

        /** Assumed lifetime when a signature can't be parsed for an expiry. */
        private const val SIGNED_FALLBACK_TTL_MS = 30 * 60 * 1000L

        private val policyExpiryRegex = Regex(""""DateLessThan"\s*:\s*\{\s*"AWS:EpochTime"\s*:\s*(\d+)""")

        /** Forgets every cached signed cookie/URL so the next request fetches new ones - for
         * when the CDN rejects one before its signature said it would expire. */
        fun invalidateSignedUrls() {
            cachedSignedCookie = null
            cachedContentDownloadUrls.clear()
        }

        private fun CachedSigned.isFresh() = System.currentTimeMillis() < expiresAtMillis - SIGNED_REFRESH_MARGIN_MS

        /** When a CloudFront-signed [url] stops being valid: its canned-policy `Expires`, or
         * else the `DateLessThan` inside its custom `Policy` (CloudFront's URL-safe base64:
         * `-`/`_`/`~` stand in for `+`/`=`/`/`). */
        private fun signedExpiryMillis(url: HttpUrl?): Long {
            url?.queryParameter("Expires")?.toLongOrNull()?.let { return it * 1000 }
            val policyJson = url?.queryParameter("Policy")?.let { policy ->
                runCatching {
                    String(Base64.decode(policy.replace('-', '+').replace('_', '=').replace('~', '/'), Base64.DEFAULT))
                }.getOrNull()
            }
            val epochSeconds = policyJson?.let { policyExpiryRegex.find(it) }?.groupValues?.get(1)?.toLongOrNull()
            return epochSeconds?.times(1000) ?: (System.currentTimeMillis() + SIGNED_FALLBACK_TTL_MS)
        }

        /** True once the logout redirect chain has landed somewhere other than the logout
         * page itself, meaning the server-side session is gone and it's safe to clear local
         * state. */
        fun isLogoutComplete(url: String): Boolean {
            val httpUrl = url.toHttpUrlOrNull() ?: return false
            if (httpUrl.host.endsWith("salesforce.com", ignoreCase = true) ||
                httpUrl.host.endsWith("force.com", ignoreCase = true)
            ) {
                return true
            }
            return httpUrl.host.equals("app.adventuresinodyssey.com", ignoreCase = true) &&
                (httpUrl.encodedPath == "/" || httpUrl.encodedPath.isEmpty())
        }
    }

    fun generateLoginUrl(): String {
        val sr = SecureRandom()
        val code = ByteArray(32)
        sr.nextBytes(code)
        codeVerifier = Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val md = MessageDigest.getInstance("SHA-256")
        val challengeBytes = md.digest(codeVerifier!!.toByteArray())
        val codeChallenge = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        return apiBase + "oauth2/authorize?" +
                "response_type=code&" +
                "client_id=$clientId&" +
                "redirect_uri=$redirectUrl&" +
                "code_challenge=$codeChallenge&" +
                "code_challenge_method=S256&" +
                "scope=api web refresh_token"
    }

    suspend fun exchangeCodeForToken(code: String): Boolean = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUrl)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code_verifier", codeVerifier ?: "")
            .build()

        val request = Request.Builder()
            .url(apiBase + "oauth2/token")
            .post(formBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return@withContext false
                val body = it.body?.string()
                val map: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)
                setAccessToken(map["access_token"] as? String)
                setRefreshToken(map["refresh_token"] as? String)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /** Exchanges the stored refresh token for a new access token (and possibly a rotated
     * refresh token). Used by [authorizedCall] to transparently recover from an expired
     * access token instead of forcing a full re-login on every request that outlives it. */
    private suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken() ?: return@withContext false
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()
        val request = Request.Builder().url(apiBase + "oauth2/token").post(formBody).build()
        try {
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return@withContext false
                val body = it.body?.string()
                val map: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)
                val newAccessToken = map["access_token"] as? String ?: return@withContext false
                setAccessToken(newAccessToken)
                (map["refresh_token"] as? String)?.let { setRefreshToken(it) }
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /** Runs an authenticated request built from the current access token. On a 401, refreshes
     * the access token once and retries before giving up, so a merely-expired token doesn't
     * force the caller to treat the whole session as invalid. The caller owns the returned
     * [Response] and must close it. */
    private suspend fun authorizedCall(label: String, build: (accessToken: String) -> Request): Response =
        withContext(Dispatchers.IO) {
            val token = getAccessToken() ?: throw IOException("$label failed: not logged in")
            var response = client.newCall(build(token)).execute()
            if (response.code == 401) {
                response.close()
                val refreshedToken = if (refreshAccessToken()) getAccessToken() else null
                if (refreshedToken != null) {
                    response = client.newCall(build(refreshedToken)).execute()
                }
            }
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw IOException("$label failed: HTTP $code")
            }
            response
        }

    suspend fun fetchProfiles(): List<ClubProfile> = withContext(Dispatchers.IO) {
        val response = authorizedCall("fetchProfiles") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/viewer")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .build()
        }
        val body = response.use { it.body?.string() }
        val parsed = gson.fromJson(body, ClubProfilesResponse::class.java)
        parsed?.metadata?.membershipStatus?.membershipExpires
            ?.takeIf { it.isNotBlank() }
            ?.let { setMembershipExpiry(it) }
        parsed?.profiles ?: emptyList()
    }

    private suspend fun setMembershipExpiry(isoDate: String) = context.dataStore.edit {
        it[ClubMembershipExpiresKey] = isoDate
    }

    /** The cached membership expiry date (YYYY-MM-DD) from the last /viewer fetch, or null if
     * never fetched. Read locally so it can be checked on app open without a network call. */
    suspend fun getMembershipExpiry(): String? = context.dataStore.data.first()[ClubMembershipExpiresKey]

    /** Verifies a profile's PIN against the server. Returns false (rather than throwing) when
     * the server reports the PIN is wrong, so the caller can show an inline error and let the
     * user retry instead of being bounced back through the full login flow. */
    suspend fun verifyPin(viewerId: String, pin: String): Boolean = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: throw IOException("verifyPin failed: not logged in")
        val request = Request.Builder()
            .url(apiBase + "apexrest/v1/viewer/verifyPIN")
            .header("Authorization", "Bearer $token")
            .header("x-experience-name", experienceName)
            .header("x-viewer-id", viewerId)
            .header("x-pin", pin)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            when {
                it.isSuccessful -> true
                it.code == 403 -> false
                else -> throw IOException("verifyPin failed: HTTP ${it.code}")
            }
        }
    }

    /** Bookmarks (likes) a piece of content. */
    suspend fun addBookmark(contentId: String) = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        val payload = mapOf(
            "subject_id" to contentId,
            "bookmark_type" to "Bookmark",
            "subject_type" to "Content__c",
        )
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = gson.toJson(payload).toRequestBody(mediaType)
        authorizedCall("addBookmark") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/bookmark")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .post(requestBody)
                .build()
        }.close()
    }

    /** Removes a bookmark (unlikes). */
    suspend fun removeBookmark(contentId: String) = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        authorizedCall("removeBookmark") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/bookmark?subject_type=Content__c&subject_id=$contentId&bookmark_type=bookmark")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .delete()
                .build()
        }.close()
    }

    /** Overwrites a playlist's name/cover/content list in one PUT - what [addContentToPlaylist],
     * [updatePlaylist] and playlist creation all boil down to at the API level. */
    private suspend fun putPlaylist(playlistId: String, name: String, imageUrl: String?, contentIds: List<String>) {
        val vid = getViewerId()
        val p = getPin()
        val payload = mapOf(
            "contentGroupings" to listOf(
                mapOf(
                    "id" to playlistId,
                    "name" to name,
                    "imageURL" to (imageUrl ?: ""),
                    "contentList" to contentIds.map { mapOf("id" to it) },
                ),
            ),
        )
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = gson.toJson(payload).toRequestBody(mediaType)
        authorizedCall("putPlaylist") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/contentgrouping/$playlistId")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .put(requestBody)
                .build()
        }.close()
    }

    /** Adds content to an existing user playlist. Unlike the reference client, this skips
     * re-fetching the playlist first - the local grouping cache already has its name/cover/
     * content list, which is all the PUT body needs. */
    suspend fun addContentToPlaylist(playlistId: String, contentIds: List<String>, groupings: List<ClubGrouping>): Boolean {
        val playlist = groupings.firstOrNull { it.type == "Playlist" && it.id == playlistId } ?: return false
        val mergedIds = playlist.contentList.map { it.id }.toMutableList()
        val existingIds = mergedIds.toMutableSet()
        contentIds.forEach { id -> if (existingIds.add(id)) mergedIds.add(id) }
        putPlaylist(playlistId, playlist.name, playlist.imageUrl, mergedIds)
        return true
    }

    /** Renames a playlist and/or reorders its content in one save. */
    suspend fun updatePlaylist(playlistId: String, name: String, orderedContentIds: List<String>, groupings: List<ClubGrouping>): Boolean {
        val playlist = groupings.firstOrNull { it.type == "Playlist" && it.id == playlistId } ?: return false
        putPlaylist(playlistId, name, playlist.imageUrl, orderedContentIds)
        return true
    }

    /** Deletes a user-created playlist. */
    suspend fun deletePlaylist(playlistId: String) {
        val vid = getViewerId()
        val p = getPin()
        authorizedCall("deletePlaylist") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/contentgrouping/$playlistId")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .delete()
                .build()
        }.close()
    }

    suspend fun selectProfile(profile: ClubProfile, enteredPin: String? = null) {
        setViewerId(profile.viewerId)
        if (enteredPin != null) {
            setPin(enteredPin)
        }
    }

    private suspend fun fetchContentGroupingsPage(pageNumber: Int, pageSize: Int = 300): Pair<List<ClubGrouping>, Int> {
        val vid = getViewerId()
        val p = getPin()
        val payload = mapOf(
            "community" to "Adventures in Odyssey",
            "pageNumber" to pageNumber,
            "pageSize" to pageSize,
        )
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = gson.toJson(payload).toRequestBody(mediaType)

        val response = authorizedCall("fetchContentGroupings") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/contentgrouping/search")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .post(requestBody)
                .build()
        }
        val body = response.use { it.body?.string() }
        val page = gson.fromJson(body, ClubGroupingsPageResponse::class.java)
        return page.contentGroupings to (page.metadata?.totalPageCount ?: 1)
    }

    /** Fetches every content grouping the club exposes (albums, playlists, themes, etc.),
     * not just albums, so the local cache can grow to cover more than the album browser this
     * screen currently renders. */
    private suspend fun fetchAllContentGroupings(): List<ClubGrouping> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ClubGrouping>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val (groupings, pages) = fetchContentGroupingsPage(page)
            all += groupings
            if (page == 1) totalPages = pages
            page++
        }
        all
    }

    /** Fetches and caches every content grouping. Callers derive whatever view they need
     * (albums via [albumEpisodesFrom], playlists via [playlistsFrom]) from the result. */
    suspend fun fetchAllGroupings(): List<ClubGrouping> = withContext(Dispatchers.IO) {
        val groupings = fetchAllContentGroupings()
        setCachedContentGroupings(groupings)
        groupings
    }

    /** Flattens the "Album" groupings' audio content into the simple episode-map shape the
     * rest of the app (queue building, album/episode screens) already expects. */
    fun albumEpisodesFrom(groupings: List<ClubGrouping>): List<Map<String, Any>> {
        val isPlayable = playableFilter()
        val episodes = mutableListOf<Map<String, Any>>()
        for (grouping in groupings) {
            if (grouping.type != "Album") continue
            val albumYear = grouping.albumCopyrightYear.toYearStringOrNull()
            for (content in grouping.contentList) {
                if (!isPlayable(content)) continue
                val episode = mutableMapOf<String, Any>(
                    "id" to content.id,
                    "name" to content.name,
                    "album_name" to grouping.name,
                    "download_url" to (content.downloadUrl ?: ""),
                    "image_small" to (content.thumbnailSmall ?: grouping.imageUrl ?: ""),
                    "image_medium" to (content.thumbnailMedium ?: grouping.imageUrl ?: ""),
                    "image_large" to (grouping.imageUrl ?: content.thumbnailMedium ?: ""),
                    "is_bookmarked" to content.isBookmarked,
                )
                content.description?.let { episode["description"] = it }
                content.shortName?.let { episode["short_name"] = it }
                albumYear?.let { episode["album_copyright_year"] = it }
                episodes.add(episode)
            }
        }
        return episodes
    }

    /** The "Playlist"-type groupings, offered to the randomizer's blacklist/whitelist
     * picker and the Playlists tab. User-created playlists (which carry a viewer_id) sort
     * first. */
    fun playlistsFrom(groupings: List<ClubGrouping>): List<ClubPlaylistOption> =
        groupings
            .filter { it.type == "Playlist" }
            .map {
                ClubPlaylistOption(
                    id = it.id,
                    name = it.name,
                    isUserCreated = !it.viewerId.isNullOrBlank(),
                    imageUrl = it.imageUrl,
                    episodeCount = it.contentList.size,
                )
            }
            .sortedWith(compareByDescending<ClubPlaylistOption> { it.isUserCreated }.thenBy { it.name })

    /** The episode ids belonging to a given playlist, for the randomizer's blacklist/whitelist
     * filter to check episode membership against. */
    fun playlistEpisodeIds(groupings: List<ClubGrouping>, playlistId: String): Set<String> =
        groupings
            .firstOrNull { it.type == "Playlist" && it.id == playlistId }
            ?.contentList
            ?.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
            ?.toSet()
            ?: emptySet()

    /** Flattens a playlist's audio content into the same episode-map shape [albumEpisodesFrom]
     * produces. Unlike an album, a playlist's episodes can come from different albums - and
     * the API's own `album_name` on a playlist's content items is unreliable (it's been seen
     * always echoing the *playlist's* name instead), so each episode's real album is instead
     * resolved by cross-referencing every cached Album grouping's own content by id. Only
     * falls back to the playlist's name if an episode genuinely isn't in any cached album.
     * [playVideosAsAudio] defaults to the setting; the playlist editor passes true so saving
     * an edit can't silently drop videos the setting happens to be hiding. */
    fun playlistEpisodesFrom(
        groupings: List<ClubGrouping>,
        playlistId: String,
        playVideosAsAudio: Boolean = playVideosAsAudio(),
    ): List<Map<String, Any>> {
        val grouping = groupings.firstOrNull { it.type == "Playlist" && it.id == playlistId } ?: return emptyList()
        val albumNameByContentId = buildMap {
            groupings.filter { it.type == "Album" }.forEach { album ->
                album.contentList.forEach { content -> put(content.id, album.name) }
            }
        }
        // A playlist can reference the same content more than once (seen on at least one
        // debug playlist on the club) - the episode list is keyed by content id downstream
        // (LazyColumn keys, download cache keys, etc.), so duplicates need to go here rather
        // than wherever it's consumed.
        return grouping.contentList.filter(playableFilter(playVideosAsAudio)).distinctBy { it.id }.map { content ->
            val episode = mutableMapOf<String, Any>(
                "id" to content.id,
                "name" to content.name,
                "album_name" to (albumNameByContentId[content.id] ?: grouping.name),
                "download_url" to (content.downloadUrl ?: ""),
                "image_small" to (content.thumbnailSmall ?: grouping.imageUrl ?: ""),
                "image_medium" to (content.thumbnailMedium ?: grouping.imageUrl ?: ""),
                "image_large" to (grouping.imageUrl ?: content.thumbnailMedium ?: ""),
                "is_bookmarked" to content.isBookmarked,
            )
            content.description?.let { episode["description"] = it }
            content.shortName?.let { episode["short_name"] = it }
            episode
        }
    }

    /** Every grouping of [type] (e.g. "Series", "Podcast Year"), ordered by the first number in
     * its name ("Episodes 51 to 100", "2007") so numbered groupings come out in sequence
     * regardless of API order; unnumbered ones follow, alphabetically. */
    fun groupingsOfType(groupings: List<ClubGrouping>, type: String): List<ClubGrouping> =
        groupings
            .filter { it.type == type }
            .sortedWith(
                compareBy<ClubGrouping>(
                    { firstNumberRegex.find(it.name)?.value?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.name },
                ),
            )

    /** The "Collection" groupings (soundtracks, themed sets, ...) that have anything playable,
     * in the API's own order - their names mix numbering schemes ("Season 3 Soundtrack",
     * "Vol. 1"), so [groupingsOfType]'s number sort would scramble them. A couple are all
     * articles (or videos, while "Play videos as audio" is off), which would only open an
     * empty episode list. */
    fun collectionsFrom(groupings: List<ClubGrouping>): List<ClubGrouping> {
        val isPlayable = playableFilter()
        return groupings.filter { grouping ->
            grouping.type == COLLECTION_TYPE && grouping.contentList.any(isPlayable)
        }
    }

    /** The comics in the grouping cache: the "Elsewhere in Odyssey" arc groupings (type "Book
     * Series", like the prose books, store listings and audiobooks it's otherwise used for).
     * The cache only holds the newest few arcs - the full, ordered list comes from the club's
     * arc index (see [com.skintclub.odyssey.club.fetchIndexedComicSeries]), which this is
     * merged under. */
    fun comicSeriesFrom(groupings: List<ClubGrouping>): List<ClubGrouping> =
        groupings.filter { grouping ->
            grouping.type == BOOK_SERIES_TYPE &&
                grouping.name.startsWith("Elsewhere in Odyssey", ignoreCase = true) &&
                grouping.contentList.any { it.type == "Article" }
        }

    /** A single content grouping by id - for groupings the bulk search leaves out, like all
     * but the newest few comic arcs. */
    suspend fun fetchGrouping(groupingId: String): ClubGrouping? = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        val response = authorizedCall("fetchGrouping") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/contentgrouping/$groupingId")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .build()
        }
        val body = response.use { it.body?.string() }
        gson.fromJson(body, ClubGroupingsPageResponse::class.java)?.contentGroupings?.firstOrNull()
    }

    /** A single content item by id - for a link to something the grouping cache doesn't hold. */
    suspend fun fetchContent(contentId: String): ClubContent? = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        val response = authorizedCall("fetchContent") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/content/$contentId")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .build()
        }
        val body = response.use { it.body?.string() }
        gson.fromJson(body, ClubContent::class.java)?.takeIf { it.id.isNotBlank() }
    }

    /** An Article's HTML body (both the current and legacy renderings, either of which may be
     * missing), for pulling out whatever it links to. */
    suspend fun fetchArticleBodies(contentId: String): List<String> = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        val response = authorizedCall("fetchArticleBodies") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/content/$contentId")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .build()
        }
        val body = response.use { it.body?.string() }
        val article = gson.fromJson(body, ClubArticleBody::class.java)
        listOfNotNull(article?.contentBody, article?.contentBodyHtml).filter { it.isNotBlank() }
    }

    /** Flattens any grouping's audio content into the episode-map shape. Episodes that also
     * live in an Album reuse that album's own episode map (real album name, album cover as
     * the player artwork, copyright year) so they look identical to browsing the album;
     * anything album-less - podcasts, mostly - is built from the grouping itself. */
    fun groupingEpisodesFrom(groupings: List<ClubGrouping>, grouping: ClubGrouping): List<Map<String, Any>> {
        val albumEpisodeById = albumEpisodesFrom(groupings).associateBy { it["id"] as String }
        return groupingEpisodesFrom(grouping, albumEpisodeById, playableFilter())
    }

    private fun groupingEpisodesFrom(
        grouping: ClubGrouping,
        albumEpisodeById: Map<String, Map<String, Any>>,
        isPlayable: (ClubContent) -> Boolean,
    ): List<Map<String, Any>> =
        grouping.contentList.filter(isPlayable).distinctBy { it.id }.map { content ->
            albumEpisodeById[content.id] ?: mutableMapOf<String, Any>(
                "id" to content.id,
                "name" to content.name,
                "album_name" to grouping.name,
                "download_url" to (content.downloadUrl ?: ""),
                "image_small" to (content.thumbnailSmall ?: grouping.imageUrl ?: ""),
                "image_medium" to (content.thumbnailMedium ?: grouping.imageUrl ?: ""),
                "image_large" to (grouping.imageUrl ?: content.thumbnailMedium ?: ""),
                "is_bookmarked" to content.isBookmarked,
            ).apply {
                // No shared album cover to fall back on, so scrolling rows show the episode's
                // own small thumbnail rather than the grouping's cover on every card.
                content.thumbnailSmall?.let { put("list_thumbnail", it) }
                content.description?.let { put("description", it) }
                content.shortName?.let { put("short_name", it) }
            }
        }

    /** Pairs each of [sections] (e.g. the "Episodes 1 to 50", ... blocks, or the podcast
     * years) with its episodes, dropping any with nothing playable. [groupings] is the full
     * cache, used to resolve album-backed episodes. */
    fun sectionsFrom(
        groupings: List<ClubGrouping>,
        sections: List<ClubGrouping>,
    ): List<Pair<ClubGrouping, List<Map<String, Any>>>> {
        val albumEpisodeById = albumEpisodesFrom(groupings).associateBy { it["id"] as String }
        val isPlayable = playableFilter()
        return sections
            .map { it to groupingEpisodesFrom(it, albumEpisodeById, isPlayable) }
            .filter { (_, episodes) -> episodes.isNotEmpty() }
    }

    /** A content item's download_url, ready to stream from as-is (no signed-cookie query
     * string needed). Used by the "Content request" audio-loading mode, which trades an extra
     * round-trip per episode for not depending on the shared signed cookie that
     * [fetchSignedCookie] hands out. Cached per content id until its signature nears expiry,
     * since the player resolves a URL for every range request (each seek, each chunk). */
    suspend fun getContentDownloadUrl(contentId: String): String? {
        cachedContentDownloadUrls[contentId]?.takeIf { it.isFresh() }?.let { return it.value }
        val url = fetchContentDownloadUrl(contentId) ?: return null
        cachedContentDownloadUrls[contentId] = CachedSigned(url, signedExpiryMillis(url.toHttpUrlOrNull()))
        return url
    }

    private suspend fun fetchContentDownloadUrl(contentId: String): String? = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        val response = authorizedCall("fetchContentDownloadUrl") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/content/$contentId?tag=true&series=true&recommendations=true&player=true&parent=true")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .build()
        }
        val body = response.use { it.body?.string() }
        gson.fromJson(body, ClubContent::class.java)?.downloadUrl
    }

    /** The CDN's signed cookie as a `?Policy=...&Signature=...&Key-Pair-Id=...` query string,
     * from the process-wide cache unless it's missing, within [SIGNED_REFRESH_MARGIN_MS] of
     * expiring, or [forceRefresh] is set. Only grants access to
     * [com.skintclub.odyssey.playback.isSignedCookiePath] paths. */
    suspend fun getSignedCookie(forceRefresh: Boolean = false): String? {
        fun freshCached() = cachedSignedCookie?.takeIf { it.isFresh() }?.value
        if (!forceRefresh) freshCached()?.let { return it }
        return signedCookieMutex.withLock {
            // Another caller may have refreshed it while this one waited on the lock.
            if (!forceRefresh) freshCached()?.let { return@withLock it }
            val query = fetchSignedCookie()
            cachedSignedCookie = query?.let { CachedSigned(it, signedExpiryMillis("https://$ODYSSEY_MEDIA_HOST/$it".toHttpUrlOrNull())) }
            query
        }
    }

    private suspend fun fetchSignedCookie(): String? = withContext(Dispatchers.IO) {
        val vid = getViewerId()
        val p = getPin()
        val response = authorizedCall("fetchSignedCookie") { token ->
            Request.Builder()
                .url(apiBase + "apexrest/v1/content/a354W0000046V5fQAE?tag=true&series=true&recommendations=true&player=true&parent=true")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", experienceName)
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .build()
        }
        val body = response.use { it.body?.string() }
        val data: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)
        val signedCookie = data["signed_cookie"] as? String
        val url = signedCookie?.toHttpUrlOrNull()
        if (url?.query != null) "?" + url.query else null
    }

    suspend fun isLoggedIn(): Boolean {
        return getAccessToken() != null
    }

    /** Reactive counterpart to [isLoggedIn], for UI that should update immediately after a
     * login or logout instead of only on next composition/refresh. */
    fun isLoggedInFlow(): Flow<Boolean> = context.dataStore.data.map { it[ClubAccessTokenKey] != null }

    private suspend fun getCachedContentGroupings(): List<ClubGrouping>? = withContext(Dispatchers.IO) {
        val json = context.dataStore.data.first()[ClubContentGroupingsCacheKey] ?: return@withContext null
        runCatching {
            val type = object : TypeToken<List<ClubGrouping>>() {}.type
            gson.fromJson<List<ClubGrouping>>(json, type)
        }.getOrNull()
    }

    private suspend fun setCachedContentGroupings(groupings: List<ClubGrouping>) = withContext(Dispatchers.IO) {
        context.dataStore.edit { it[ClubContentGroupingsCacheKey] = gson.toJson(groupings) }
    }

    /** So the album/playlist state can render instantly on launch instead of waiting on
     * [fetchAllGroupings]; the view model shows this immediately, then refreshes from the
     * network in the background, which keeps the cache current as a side effect. */
    suspend fun getCachedGroupings(): List<ClubGrouping> = withContext(Dispatchers.IO) {
        getCachedContentGroupings() ?: emptyList()
    }

    suspend fun logout() {
        invalidateSignedUrls()
        context.dataStore.edit {
            it.remove(ClubAccessTokenKey)
            it.remove(ClubRefreshTokenKey)
            it.remove(ClubViewerIdKey)
            it.remove(ClubContentGroupingsCacheKey)
            it[ClubPinKey] = "0000"
        }
    }
}
