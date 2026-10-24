package com.skintclub.odyssey.club

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** One of the radio episodes [AIOClient.fetchAiredRadio] lists. */
data class RadioEpisode(
    val id: String = "",
    val name: String = "",
    @SerializedName("short_name") val shortName: String? = null,
    val description: String? = null,
    @SerializedName("thumbnail_small") val thumbnailSmall: String? = null,
    @SerializedName("thumbnail_medium") val thumbnailMedium: String? = null,
    @SerializedName("media_length") val mediaLength: Long? = null,
    /** When it last aired, as an ISO date-time ("2026-10-24T00:00:00.000Z"). */
    @SerializedName("recent_air_date") val recentAirDate: String? = null,
)

/**
 * The club's API without logging in, like adventuresinodyssey-py's AIOClient: what the website
 * shows anyone, such as the episodes aired on the radio in the last few days, which are free to
 * listen to. Every request only needs the `x-experience-name` header.
 */
object AIOClient {
    private const val API_BASE = "https://fotf.my.site.com/aio/services/apexrest/v1/"
    private const val EXPERIENCE_NAME = "Adventures In Odyssey"

    /** How long a radio episode's download URL is reused. The club signs them for about a day. */
    private const val DOWNLOAD_URL_MAX_AGE_MS = 30 * 60 * 1000L

    private val client by lazy { OkHttpClient() }
    private val gson = Gson()
    private val downloadUrls = ConcurrentHashMap<String, Pair<String, Long>>()

    private class SearchResponse(val results: List<RadioEpisode>?)

    private class ContentResponse(@SerializedName("download_url") val downloadUrl: String?)

    /** The [count] most recently aired radio episodes, newest first, as the website's radio
     * page lists them. These are the ones anyone can listen to. */
    suspend fun fetchAiredRadio(count: Int = 5): List<RadioEpisode> {
        val url = (API_BASE + "content/search").toHttpUrl().newBuilder()
            .addQueryParameter("content_type", "Audio")
            .addQueryParameter("content_subtype", "Episode")
            .addQueryParameter("community", EXPERIENCE_NAME)
            .addQueryParameter("orderby", "Recent_Air_Date__c DESC")
            .addQueryParameter("pagenum", "1")
            .addQueryParameter("pagecount", count.toString())
            .addQueryParameter("radio_page_type", "aired")
            .build()
        val response = gson.fromJson(get(url.toString()), SearchResponse::class.java)
        return response?.results.orEmpty().filter { it.id.isNotBlank() }
    }

    /** A freshly signed URL for the radio episode [contentId]'s audio, as the website's player
     * gets it. Only works while the episode is one of the free ones. */
    suspend fun fetchRadioDownloadUrl(contentId: String): String {
        downloadUrls[contentId]?.let { (url, fetchedAt) ->
            if (System.currentTimeMillis() - fetchedAt < DOWNLOAD_URL_MAX_AGE_MS) return url
        }
        val url = (API_BASE + "content/$contentId").toHttpUrl().newBuilder()
            .addQueryParameter("player", "true")
            .addQueryParameter("radio_page_type", "aired")
            .build()
        val downloadUrl = gson.fromJson(get(url.toString()), ContentResponse::class.java)?.downloadUrl
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("$contentId isn't free to listen to right now")
        downloadUrls[contentId] = downloadUrl to System.currentTimeMillis()
        return downloadUrl
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("x-experience-name", EXPERIENCE_NAME)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Empty response for $url")
        }
    }
}
