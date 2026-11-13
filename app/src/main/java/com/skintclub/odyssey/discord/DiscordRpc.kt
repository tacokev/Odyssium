package com.skintclub.odyssey.discord

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Base64
import com.skintclub.odyssey.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What to show as the Discord status. Times are Unix milliseconds; null when paused. */
data class DiscordStatus(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val imageText: String?,
    val startMs: Long?,
    val endMs: Long?,
)

/**
 * Shows what's playing as the user's Discord status ("Listening to <episode>"), signed in with
 * their Discord account through the browser (see [DiscordAuth]).
 * (Adapted from Metrolist's Discord integration, GPL-3.0.)
 */
object DiscordRpc {
    private const val ACTIVITY_NAME = "Odyssium"
    private const val TYPE_LISTENING = 2
    private const val DISPLAY_DETAILS = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val gateway = DiscordGateway(scope) { DiscordTokenStore.accessToken }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val imageCache = ConcurrentHashMap<String, String>()

    @Volatile private var initialized = false
    @Volatile private var wanted: DiscordStatus? = null
    @Volatile private var sent: DiscordStatus? = null
    @Volatile private var imageJob: Job? = null

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** The signed-in user's display name, once known. */
    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        DiscordTokenStore.init(context)
        _signedIn.value = DiscordTokenStore.accessToken != null
        scope.launch {
            gateway.events.collect { event ->
                when (event) {
                    GatewayEvent.Ready -> {
                        _connected.value = true
                        _error.value = null
                        sent = null
                        pushStatus()
                        if (_userName.value == null) fetchUserName()
                    }
                    is GatewayEvent.Disconnected -> {
                        _connected.value = false
                        if (event.fatal) _error.value = "Discord closed the connection (${event.code})"
                    }
                    GatewayEvent.TokenExpired -> scope.launch { connect(forceRefresh = true) }
                }
            }
        }
    }

    /** Opens Discord's sign-in page, then connects. */
    fun signIn(activity: Activity) {
        _error.value = null
        scope.launch {
            try {
                DiscordTokenStore.save(DiscordAuth.signIn(activity))
                _signedIn.value = true
                _userName.value = null
                connect()
            } catch (e: DiscordAuthException.Cancelled) {
                // Nothing to report.
            } catch (e: Exception) {
                Timber.w(e, "Discord sign-in")
                _error.value = e.message
            }
        }
    }

    fun signOut() {
        disconnect()
        DiscordTokenStore.clear()
        _signedIn.value = false
        _userName.value = null
        _error.value = null
    }

    /** Connects if signed in and not already connected. */
    fun start() {
        if (_signedIn.value && !gateway.isOpen) scope.launch { connect() }
    }

    /** Clears the status and closes the connection (the sign-in is kept). */
    fun disconnect() {
        wanted = null
        imageJob?.cancel()
        if (gateway.isOpen) gateway.send(presenceFrame(null))
        gateway.close(1000, "done")
        _connected.value = false
        sent = null
    }

    /** Shows [status], or clears it for null. Sent once connected if not yet. */
    fun show(status: DiscordStatus?) {
        wanted = status
        scope.launch { pushStatus() }
    }

    private suspend fun connect(forceRefresh: Boolean = false) = connectLock.withLock {
        if (gateway.isOpen && !forceRefresh) return@withLock
        val refresh = DiscordTokenStore.refreshToken
        val expiresAt = DiscordTokenStore.expiresAt
        if (refresh != null && (forceRefresh || (expiresAt > 0 && expiresAt - System.currentTimeMillis() / 1000 < 3600))) {
            try {
                DiscordTokenStore.save(DiscordAuth.refresh(refresh))
            } catch (e: DiscordAuthException.InvalidGrant) {
                signOut()
                _error.value = "Discord sign-in expired. Sign in again."
                return@withLock
            } catch (e: Exception) {
                Timber.w(e, "Discord token refresh")
                // Discord already turned this token down; connecting with it again would just loop.
                if (forceRefresh) {
                    _error.value = "Couldn't reach Discord"
                    return@withLock
                }
            }
        } else if (forceRefresh) {
            signOut()
            _error.value = "Discord sign-in expired. Sign in again."
            return@withLock
        }
        if (DiscordTokenStore.accessToken == null) return@withLock
        runCatching { gateway.connect() }.onFailure {
            Timber.w(it, "Discord connect")
            _error.value = "Couldn't reach Discord"
        }
    }

    @Synchronized
    private fun pushStatus() {
        if (!_connected.value) return
        val status = wanted
        if (status == sent) return
        // Each position update nudges the start time; only resend when it's noticeably off.
        val last = sent
        if (status != null && last != null && status.copy(startMs = last.startMs, endMs = last.endMs) == last &&
            (status.startMs == null) == (last.startMs == null) &&
            // The length often arrives just after the episode starts; that has to go out.
            (status.endMs == null) == (last.endMs == null) &&
            kotlin.math.abs((status.startMs ?: 0) - (last.startMs ?: 0)) < 3000
        ) return
        sent = status
        imageJob?.cancel()
        val cached = status?.imageUrl?.let { imageCache[it] }
        gateway.send(presenceFrame(status, cached))
        val url = status?.imageUrl
        if (url == null) return
        if (cached != null) return
        // Discord only shows outside images it has proxied; ask for that, then resend with it.
        imageJob = scope.launch {
            val asset = resolveImage(url) ?: return@launch
            if (sent === status) gateway.send(presenceFrame(status, asset))
        }
    }

    private fun presenceFrame(status: DiscordStatus?, image: String? = null): String {
        val activities = JSONArray()
        if (status != null) {
            val activity = JSONObject()
                .put("name", ACTIVITY_NAME)
                .put("application_id", BuildConfig.DISCORD_APP_ID.toString())
                .put("type", TYPE_LISTENING)
                .put("status_display_type", DISPLAY_DETAILS)
                .put("details", status.title)
                .put("state", status.subtitle)
            if (status.startMs != null) {
                activity.put("timestamps", JSONObject().put("start", status.startMs).apply { status.endMs?.let { put("end", it) } })
            }
            if (image != null) {
                activity.put("assets", JSONObject().put("large_image", image).apply { status.imageText?.let { put("large_text", it) } })
            }
            activities.put(activity)
        }
        val d = JSONObject().put("since", 0).put("activities", activities).put("status", "online").put("afk", false)
        return JSONObject().put("op", DiscordGateway.OP_PRESENCE_UPDATE).put("d", d).toString()
    }

    private suspend fun resolveImage(url: String): String? = withContext(Dispatchers.IO) {
        val token = DiscordTokenStore.accessToken ?: return@withContext null
        // Discord wants a properly encoded address: the club's has spaces in it ("content grouping").
        val encoded = url.toHttpUrlOrNull()?.toString() ?: return@withContext null
        val body = JSONObject().put("urls", JSONArray().put(encoded)).toString()
        val request = Request.Builder()
            .url("https://discord.com/api/v9/applications/${BuildConfig.DISCORD_APP_ID}/external-assets")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("X-Super-Properties", superProperties)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Timber.w("Discord external-assets HTTP %d: %s", response.code, text.take(200))
                    return@use null
                }
                JSONArray(text).optJSONObject(0)?.optString("external_asset_path")?.takeIf { it.isNotEmpty() }
                    ?.let { "mp:$it" }
                    ?.also { imageCache[url] = it }
            }
        }.onFailure { Timber.w(it, "Discord external-assets") }.getOrNull()
    }

    private suspend fun fetchUserName() = withContext(Dispatchers.IO) {
        val token = DiscordTokenStore.accessToken ?: return@withContext
        val request = Request.Builder().url("https://discord.com/api/v10/users/@me").header("Authorization", "Bearer $token").build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val json = JSONObject(response.body?.string().orEmpty())
                _userName.value = json.optString("global_name").takeIf { it.isNotEmpty() && it != "null" }
                    ?: json.optString("username").takeIf { it.isNotEmpty() }
            }
        }
    }

    // Discord's API expects the client to describe itself like its own Android app does.
    private const val USER_AGENT = "Discord-Android/314013;RNA"
    private val superProperties: String by lazy {
        val json = JSONObject()
            .put("os", "Android")
            .put("browser", "Discord Android")
            .put("device", Build.DEVICE)
            .put("system_locale", Locale.getDefault().toString())
            .put("client_version", "314.13 - Stable")
            .put("release_channel", "googleRelease")
            .put("client_uuid", UUID.randomUUID().toString())
            .put("os_version", Build.VERSION.RELEASE)
            .put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            .put("client_build_number", 314013)
        Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }
}
