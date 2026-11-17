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

/** How long the hidden login page gets to reach the callback or the login form. */
private const val SILENT_LOGIN_TIMEOUT_MS = 15_000L

/** How often the hidden page is checked for the login form. */
private const val LOGIN_FORM_CHECK_MS = 250L

/** True once the club's login page shows its email and password form. */
const val HAS_LOGIN_FORM_JS = "!!document.querySelector('input[type=password]')"

enum class SilentLoginResult {
    /** The club still had a website session and gave a new login. */
    LoggedIn,

    /** The club wants the email and password: the user has to log in. */
    NeedsLogin,

    /** Neither happened in time, the page failed to load, or the code was turned down. */
    Failed,
}

/**
 * Tries to log back in without showing anything, for when the refresh token stops working
 * ("Attempt auto login").
 *
 * The login screen's WebView keeps the club website's session in its cookies, and that session
 * can outlive the refresh token. This loads the login page in a WebView that's never shown. If
 * the session is still alive, the page goes straight to the callback with a new code, which is
 * exchanged for tokens like a normal login. If it shows the email and password form instead,
 * the user has to log in for real.
 */
suspend fun ClubClient.tryLoginSilently(context: Context): SilentLoginResult {
    val outcome = withTimeoutOrNull(SILENT_LOGIN_TIMEOUT_MS) {
        withContext(Dispatchers.Main) { loadLoginPage(context, this@tryLoginSilently) }
    } ?: return SilentLoginResult.Failed
    return when (outcome) {
        is PageOutcome.Code ->
            if (exchangeCodeForToken(outcome.code)) SilentLoginResult.LoggedIn else SilentLoginResult.Failed
        PageOutcome.LoginForm -> SilentLoginResult.NeedsLogin
        PageOutcome.Failed -> SilentLoginResult.Failed
    }
}

private sealed interface PageOutcome {
    class Code(val code: String) : PageOutcome
    data object LoginForm : PageOutcome
    data object Failed : PageOutcome
}

/** Loads the login page off screen until it reaches the callback or shows the login form.
 * Must run on the main thread. */
@SuppressLint("SetJavaScriptEnabled")
private suspend fun loadLoginPage(context: Context, clubClient: ClubClient): PageOutcome =
    suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        val webView = WebView(context.applicationContext)
        var done = false

        fun finish(outcome: PageOutcome) {
            if (done) return
            done = true
            if (continuation.isActive) continuation.resume(outcome)
            // Not from inside the WebView's own callback.
            mainHandler.post { webView.destroy() }
        }

        // The form is drawn by the page's scripts, after it's "finished" loading, so it's
        // looked for until something else ends this.
        val checkForForm = object : Runnable {
            override fun run() {
                if (done) return
                webView.evaluateJavascript(HAS_LOGIN_FORM_JS) { hasForm ->
                    if (hasForm == "true") finish(PageOutcome.LoginForm)
                }
                mainHandler.postDelayed(this, LOGIN_FORM_CHECK_MS)
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (!clubClient.isLoginCallback(url.toString())) return false
                finish(url.getQueryParameter("code")?.let { PageOutcome.Code(it) } ?: PageOutcome.Failed)
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) finish(PageOutcome.Failed)
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
        webView.loadUrl(clubClient.generateLoginUrl())
        mainHandler.postDelayed(checkForForm, LOGIN_FORM_CHECK_MS)
    }
