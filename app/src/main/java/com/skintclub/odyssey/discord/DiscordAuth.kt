package com.skintclub.odyssey.discord

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import com.skintclub.odyssey.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/*
 * Signing in to Discord (adapted from Metrolist's Discord integration, GPL-3.0).
 *
 * Discord's own OAuth2 page opens in the browser; the user approves there, so the app never sees
 * their password. PKCE means no client secret is needed - the Discord application only has to be
 * a "Public Client" with REDIRECT_URI registered under OAuth2 > Redirects.
 */

data class DiscordTokens(val accessToken: String, val refreshToken: String, val expiresInSec: Long)

sealed class DiscordAuthException(message: String) : Exception(message) {
    class Cancelled : DiscordAuthException("Sign-in cancelled")
    class NoBrowser : DiscordAuthException("No browser to open Discord's sign-in page")
    class InvalidGrant : DiscordAuthException("Discord rejected the sign-in")
    class Failed(message: String) : DiscordAuthException(message)
}

object DiscordAuth {
    const val REDIRECT_URI = "odyssiumdiscord://oauth2/callback"
    private const val AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/v10/oauth2/token"
    private const val SCOPES = "openid sdk.social_layer_presence"
    private const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var pending: CompletableDeferred<Uri>? = null

    /** Opens Discord's sign-in page and waits for the user to come back with a code. */
    suspend fun signIn(activity: Activity): DiscordTokens {
        val verifier = randomString(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomString(16)
        val callback = CompletableDeferred<Uri>()
        pending?.cancel()
        pending = callback

        val url = AUTHORIZE_URL +
            "?client_id=${BuildConfig.DISCORD_APP_ID}" +
            "&response_type=code" +
            "&redirect_uri=${encode(REDIRECT_URI)}" +
            "&scope=${encode(SCOPES)}" +
            "&state=$state" +
            "&code_challenge_method=S256" +
            "&code_challenge=$challenge"
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            throw DiscordAuthException.NoBrowser()
        }

        val uri = try {
            withTimeout(SIGN_IN_TIMEOUT_MS) { callback.await() }
        } catch (e: Exception) {
            throw DiscordAuthException.Cancelled()
        } finally {
            if (pending === callback) pending = null
        }
        if (uri.getQueryParameter("error") != null) throw DiscordAuthException.Cancelled()
        if (uri.getQueryParameter("state") != state) throw DiscordAuthException.Failed("Sign-in state mismatch")
        val code = uri.getQueryParameter("code") ?: throw DiscordAuthException.Failed("No sign-in code")

        return requestTokens(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier,
        )
    }

    suspend fun refresh(refreshToken: String): DiscordTokens =
        requestTokens("grant_type" to "refresh_token", "refresh_token" to refreshToken)

    fun cancel() {
        pending?.cancel()
    }

    internal fun onCallback(uri: Uri) {
        pending?.complete(uri)
    }

    private suspend fun requestTokens(vararg params: Pair<String, String>): DiscordTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("client_id", BuildConfig.DISCORD_APP_ID.toString())
        params.forEach { (name, value) -> form.add(name, value) }
        val request = Request.Builder().url(TOKEN_URL).post(form.build()).build()
        val (code, body) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw DiscordAuthException.Failed("Network error: ${e.message}")
        }
        if (code in 200..299) {
            val json = JSONObject(body)
            return@withContext DiscordTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", ""),
                expiresInSec = json.optLong("expires_in", 0L),
            )
        }
        val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        if (code == 400 && error == "invalid_grant") throw DiscordAuthException.InvalidGrant()
        throw DiscordAuthException.Failed("HTTP $code ${error.ifEmpty { body.take(200) }}")
    }

    private fun randomString(bytes: Int) = base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** Where the browser lands after Discord's sign-in page (REDIRECT_URI); hands the result back. */
class DiscordAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(DiscordAuth::onCallback)
        // Back to the settings screen the sign-in started from.
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(it)
        }
        finish()
    }
}
