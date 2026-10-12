package com.skintclub.odyssey.club

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How long the hidden login page gets to reach the callback before giving up. */
private const val SILENT_LOGIN_TIMEOUT_MS = 15_000L

/**
 * Tries to log back in without showing anything, for when the refresh token stops working.
 *
 * The login screen's WebView keeps the club website's session in its cookies, and that session
 * can outlive the refresh token. This loads the login page in a WebView that's never shown,
 * asking the club not to show a login form ([ClubClient.generateLoginUrl] with `immediate`).
 * If the session is still alive, the page goes straight to the callback with a new code, which
 * is exchanged for tokens like a normal login. Returns whether that worked; if not, the user
 * has to log in for real.
 */
suspend fun ClubClient.tryLoginSilently(context: Context): Boolean {
    val code = withTimeoutOrNull(SILENT_LOGIN_TIMEOUT_MS) {
        withContext(Dispatchers.Main) { loadLoginCode(context, this@tryLoginSilently) }
    } ?: return false
    return exchangeCodeForToken(code)
}

/** Loads the login page off screen and returns the code it comes back with, or null when it
 * comes back with an error instead (no session) or fails to load. Must run on the main thread. */
@SuppressLint("SetJavaScriptEnabled")
private suspend fun loadLoginCode(context: Context, clubClient: ClubClient): String? =
    suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        val webView = WebView(context.applicationContext)
        var done = false

        fun finish(code: String?) {
            if (done) return
            done = true
            if (continuation.isActive) continuation.resume(code)
            // Not from inside the WebView's own callback.
            mainHandler.post { webView.destroy() }
        }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (!clubClient.isLoginCallback(url.toString())) return false
                finish(url.getQueryParameter("code"))
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) finish(null)
            }
        }
        continuation.invokeOnCancellation {
            mainHandler.post {
                if (!done) {
                    done = true
                    webView.destroy()
                }
            }
        }
        webView.loadUrl(clubClient.generateLoginUrl(immediate = true))
    }
