package com.skintclub.odyssey.odyssey

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skintclub.odyssey.constants.*
import com.skintclub.odyssey.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import androidx.datastore.preferences.core.edit

class OdysseyClient(private val context: Context) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun getAccessToken(): String? = context.dataStore.data.first()[OdysseyAccessTokenKey]
    private suspend fun setAccessToken(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(OdysseyAccessTokenKey) else it[OdysseyAccessTokenKey] = value
    }

    private suspend fun getRefreshToken(): String? = context.dataStore.data.first()[OdysseyRefreshTokenKey]
    private suspend fun setRefreshToken(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(OdysseyRefreshTokenKey) else it[OdysseyRefreshTokenKey] = value
    }

    suspend fun getViewerId(): String? = context.dataStore.data.first()[OdysseyViewerIdKey]
    private suspend fun setViewerId(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(OdysseyViewerIdKey) else it[OdysseyViewerIdKey] = value
    }

    private suspend fun getPin(): String = context.dataStore.data.first()[OdysseyPinKey] ?: "0000"
    private suspend fun setPin(value: String) = context.dataStore.edit { it[OdysseyPinKey] = value }

    private val clientId = "3MVG9l2zHsylwlpTFc1ZB3ryOQlpLYIqNo0UV4d0lBRjkbb6TXbw9UNhdcJfom2nnbB.AbNpkRbGoTfruF0gB"
    private val clientSecret = "B25FC7FE3E4C155E77C73EA2AC72D410E0762C897798816FC257F0C8FA3618AD"
    private val apiBase = "https://fotf.my.site.com/aio/services/"
    private val redirectUrl = "https://app.adventuresinodyssey.com/callback"

    var codeVerifier: String? = null

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
            if (response.isSuccessful) {
                val body = response.body?.string()
                val map: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)
                setAccessToken(map["access_token"] as? String)
                setRefreshToken(map["refresh_token"] as? String)
                true
            } else {
                false
            }
        } catch (e: IOException) {
            false
        }
    }

    suspend fun fetchProfiles(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val request = Request.Builder()
            .url(apiBase + "apexrest/v1/viewer")
            .header("Authorization", "Bearer $token")
            .header("x-experience-name", "Adventures In Odyssey")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("fetchProfiles failed: HTTP ${response.code}")
        val body = response.body?.string()
        val map: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)
        map["profiles"] as? List<Map<String, Any>> ?: emptyList()
    }

    fun profileRequiresPin(profile: Map<String, Any>): Boolean {
        return (profile["pin_required"] as? Boolean)
            ?: (profile["has_pin"] as? Boolean)
            ?: false
    }

    fun profileDisplayName(profile: Map<String, Any>): String {
        return (profile["name"] as? String)
            ?: (profile["display_name"] as? String)
            ?: (profile["first_name"] as? String)
            ?: (profile["viewer_id"] as? String)
            ?: "Profile"
    }

    suspend fun selectProfile(profile: Map<String, Any>, enteredPin: String? = null) {
        setViewerId(profile["viewer_id"] as? String)
        if (enteredPin != null) {
            setPin(enteredPin)
        }
    }

    private fun extractImageUrls(source: Map<String, Any>): Map<String, String?> {
        fun firstNonNull(vararg keys: String) = keys.firstNotNullOfOrNull { source[it] as? String }

        (source["images"] as? Map<*, *>)?.let { nested ->
            return mapOf(
                "image_small" to (firstNonNull("thumbnail_small") ?: nested["small"] as? String),
                "image_medium" to (firstNonNull("thumbnail_medium") ?: nested["medium"] as? String),
                "image_large" to (firstNonNull("thumbnail_large") ?: nested["large"] as? String)
            )
        }

        return mapOf(
            "image_small" to firstNonNull("thumbnail_small", "image_small_url", "small_image_url", "thumbnailUrl", "thumbnail_url"),
            "image_medium" to firstNonNull("thumbnail_medium", "image_medium_url", "medium_image_url", "imageUrl", "image_url"),
            "image_large" to firstNonNull("thumbnail_large", "image_large_url", "large_image_url", "coverUrl", "cover_url")
        )
    }

    suspend fun fetchEpisodes(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val episodes = mutableListOf<Map<String, Any>>()
        var currentPage = 1
        var totalPages = 1
        val token = getAccessToken()
        val vid = getViewerId()
        val p = getPin()

        while (currentPage <= totalPages) {
            val payload = mapOf(
                "type" to "Album",
                "community" to "Adventures in Odyssey",
                "pageNumber" to currentPage,
                "pageSize" to 200
            )

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = gson.toJson(payload).toRequestBody(mediaType)

            val request = Request.Builder()
                .url(apiBase + "apexrest/v1/contentgrouping/search")
                .header("Authorization", "Bearer $token")
                .header("x-experience-name", "Adventures In Odyssey")
                .header("x-viewer-id", vid ?: "")
                .header("x-pin", p)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("fetchEpisodes failed: HTTP ${response.code}")
            val body = response.body?.string()
            val data: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)

            if (currentPage == 1) {
                val metadata = data["metadata"] as? Map<String, Any>
                totalPages = (metadata?.get("totalPageCount") as? Double)?.toInt() ?: 1
            }

            val groupings = data["contentGroupings"] as? List<Map<String, Any>> ?: emptyList()
            for (grouping in groupings) {
                val albumName = grouping["name"] as? String ?: "Unknown"
                val albumImages = extractImageUrls(grouping)
                // The API has no per-episode air date; the closest release-date signal
                // it exposes at all is this copyright year on the album grouping.
                val albumCopyrightYear = grouping["album_copyright_year"]
                val contentList = grouping["contentList"] as? List<Map<String, Any>> ?: emptyList()
                for (episode in contentList) {
                    if (episode["type"] == "Audio") {
                        val episodeImages = extractImageUrls(episode)
                        val mutableEp = episode.toMutableMap()
                        mutableEp["album_name"] = albumName
                        if (albumCopyrightYear != null) {
                            mutableEp["album_copyright_year"] = albumCopyrightYear
                        }
                        mutableEp["image_small"] = episodeImages["image_small"] ?: albumImages["image_small"] ?: ""
                        mutableEp["image_medium"] = episodeImages["image_medium"] ?: albumImages["image_medium"] ?: ""
                        mutableEp["image_large"] = episodeImages["image_large"] ?: albumImages["image_large"] ?: ""
                        episodes.add(mutableEp)
                    }
                }
            }
            currentPage++
        }
        episodes
    }

    suspend fun fetchSignedCookie(): String? = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val vid = getViewerId()
        val p = getPin()
        val request = Request.Builder()
            .url(apiBase + "apexrest/v1/content/a354W0000046V5fQAE?tag=true&series=true&recommendations=true&player=true&parent=true")
            .header("Authorization", "Bearer $token")
            .header("x-experience-name", "Adventures In Odyssey")
            .header("x-viewer-id", vid ?: "")
            .header("x-pin", p)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("fetchSignedCookie failed: HTTP ${response.code}")
        val body = response.body?.string()
        val data: Map<String, Any> = gson.fromJson(body, object : TypeToken<Map<String, Any>>() {}.type)
        val signedCookie = data["signed_cookie"] as? String
        val url = signedCookie?.toHttpUrlOrNull()
        if (url?.query != null) "?" + url.query else null
    }

    suspend fun isLoggedIn(): Boolean {
        return getAccessToken() != null
    }

    /** So the episode list can render instantly on launch instead of waiting on
     * [fetchEpisodes]; [OdysseyViewModel] shows this immediately, then refreshes from the
     * network in the background and calls [setCachedEpisodes] to keep it current. */
    suspend fun getCachedEpisodes(): List<Map<String, Any>>? = withContext(Dispatchers.IO) {
        val json = context.dataStore.data.first()[OdysseyEpisodesCacheKey] ?: return@withContext null
        runCatching {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            gson.fromJson<List<Map<String, Any>>>(json, type)
        }.getOrNull()
    }

    suspend fun setCachedEpisodes(episodes: List<Map<String, Any>>) = withContext(Dispatchers.IO) {
        context.dataStore.edit { it[OdysseyEpisodesCacheKey] = gson.toJson(episodes) }
    }

    suspend fun logout() {
        context.dataStore.edit {
            it.remove(OdysseyAccessTokenKey)
            it.remove(OdysseyRefreshTokenKey)
            it.remove(OdysseyViewerIdKey)
            it.remove(OdysseyEpisodesCacheKey)
            it[OdysseyPinKey] = "0000"
        }
    }
}
