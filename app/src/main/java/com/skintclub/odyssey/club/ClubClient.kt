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
import com.skintclub.odyssey.constants.ClubPinKey
import com.skintclub.odyssey.constants.ClubRefreshTokenKey
import com.skintclub.odyssey.constants.ClubViewerIdKey
import com.skintclub.odyssey.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class ClubProfile(
    @SerializedName("viewer_id") val viewerId: String = "",
    val username: String = "",
    @SerializedName("hasPIN") val hasPin: Boolean = false,
    @SerializedName("profile_picture") val profilePictureUrl: String? = null,
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

private data class ClubProfilesResponse(val profiles: List<ClubProfile> = emptyList())

private data class ClubGroupingsPageMetadata(val totalPageCount: Int = 1)

private data class ClubGroupingsPageResponse(
    val metadata: ClubGroupingsPageMetadata? = null,
    val contentGroupings: List<ClubGrouping> = emptyList(),
)

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

    companion object {
        /** The club's own logout page. It doesn't render anything useful itself — it just
         * kicks off a server-side session teardown and, once that's done, redirects away -
         * to a Salesforce domain, or sometimes just back to the club's own homepage - which
         * is what [isLogoutComplete] watches for. */
        const val LOGOUT_URL = "https://app.adventuresinodyssey.com/logout"

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
        gson.fromJson(body, ClubProfilesResponse::class.java)?.profiles ?: emptyList()
    }

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

    /** Flattens the "Album" groupings' audio content into the simple episode-map shape the
     * rest of the app (queue building, album/episode screens) already expects. */
    private fun albumEpisodesFrom(groupings: List<ClubGrouping>): List<Map<String, Any>> {
        val episodes = mutableListOf<Map<String, Any>>()
        for (grouping in groupings) {
            if (grouping.type != "Album") continue
            val albumYear = grouping.albumCopyrightYear.toYearStringOrNull()
            for (content in grouping.contentList) {
                if (content.type != "Audio") continue
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
                albumYear?.let { episode["album_copyright_year"] = it }
                episodes.add(episode)
            }
        }
        return episodes
    }

    /** Fetches and caches every content grouping, then returns just the album episodes for
     * the album browser. [getCachedEpisodes] serves the same derived view from the cache. */
    suspend fun fetchAlbumEpisodes(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val groupings = fetchAllContentGroupings()
        setCachedContentGroupings(groupings)
        albumEpisodesFrom(groupings)
    }

    suspend fun fetchSignedCookie(): String? = withContext(Dispatchers.IO) {
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

    /** So the album list can render instantly on launch instead of waiting on
     * [fetchAlbumEpisodes]; the view model shows this immediately, then refreshes from the
     * network in the background, which keeps the cache current as a side effect. */
    suspend fun getCachedEpisodes(): List<Map<String, Any>>? = withContext(Dispatchers.IO) {
        val groupings = getCachedContentGroupings() ?: return@withContext null
        albumEpisodesFrom(groupings)
    }

    suspend fun logout() {
        context.dataStore.edit {
            it.remove(ClubAccessTokenKey)
            it.remove(ClubRefreshTokenKey)
            it.remove(ClubViewerIdKey)
            it.remove(ClubContentGroupingsCacheKey)
            it[ClubPinKey] = "0000"
        }
    }
}
