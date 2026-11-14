package com.skintclub.odyssey.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.HAS_LOGIN_FORM_JS
import com.skintclub.odyssey.ui.component.IconButton
import com.skintclub.odyssey.ui.utils.backToMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the hidden page is checked for a login form. */
private const val LOGIN_FORM_CHECK_MS = 250L

/** Shown anyway after this long, in case the form isn't recognized. */
private const val LOGIN_FORM_WAIT_MS = 10_000L
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdysseyLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clubClient = remember { ClubClient(context) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // The club website often still has a session and logs straight back in, which takes a
    // second or two of a white page. So the page stays hidden behind a spinner until it shows
    // a login form; if it goes to the callback first, it's never seen. When "Attempt auto
    // login" already found the form, it's shown straight away.
    var showWebView by remember {
        mutableStateOf(ClubClient.loginFormExpected.also { ClubClient.loginFormExpected = false })
    }
    // Whether the club's site has answered yet, for what the spinner says.
    var pageLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(webViewInstance) {
        val webView = webViewInstance ?: return@LaunchedEffect
        var waited = 0L
        while (!showWebView && waited < LOGIN_FORM_WAIT_MS) {
            delay(LOGIN_FORM_CHECK_MS)
            waited += LOGIN_FORM_CHECK_MS
            webView.evaluateJavascript(HAS_LOGIN_FORM_JS) { hasForm ->
                if (hasForm == "true") showWebView = true
            }
        }
        showWebView = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Odyssey Login") },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        contentWindowInsets = LocalPlayerAwareWindowInsets.current
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().alpha(if (showWebView && !isLoading) 1f else 0f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoaded = true
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith("https://app.adventuresinodyssey.com/callback")) {
                                    val code = request.url.getQueryParameter("code")
                                    if (code != null) {
                                        isLoading = true
                                        scope.launch {
                                            val success = clubClient.exchangeCodeForToken(code)
                                            if (success) {
                                                navController.navigate("odyssey_profile_select") {
                                                    popUpTo("odyssey_login") { inclusive = true }
                                                }
                                            } else {
                                                isLoading = false
                                            }
                                        }
                                    }
                                    return true
                                }
                                return false
                            }
                        }
                        settings.javaScriptEnabled = true
                        webViewInstance = this
                        loadUrl(clubClient.generateLoginUrl())
                    }
                }
            )

            if (isLoading || !showWebView) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = when {
                            isLoading -> "Logging you in…"
                            pageLoaded -> "Checking if you're still logged in…"
                            else -> "Connecting to the Club…"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    CircularProgressIndicator()
                }
            }
        }
    }

    BackHandler(enabled = webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }
}
