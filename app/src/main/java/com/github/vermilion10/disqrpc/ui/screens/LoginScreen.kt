package com.github.vermilion10.disqrpc.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean

private const val LOGIN_URL = "https://discord.com/login"

/**
 * Discord's own login page in a WebView. The app never sees the password: it waits for the
 * signed-in page to make its first API call and takes the token from that request's
 * Authorization header (or, as a fallback, from the page's storage). The WebView's cookies
 * and storage are wiped before and after, so no second session is kept on the device.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onToken: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    val captured = remember { AtomicBoolean(false) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun deliver(raw: String?) {
        val token = raw?.trim()?.removeSurrounding("\"")
        if (token.isNullOrBlank() || token.startsWith("Bot ", ignoreCase = true)) return
        if (captured.compareAndSet(false, true)) main.post { onToken(token) }
    }

    DisposableEffect(Unit) {
        clearWebSession()
        onDispose { clearWebSession() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(
                text = "Sign in on Discord's page below. disqRPC only keeps the session token, " +
                    "encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val url = request.url
                                if (url.host?.endsWith("discord.com") == true &&
                                    url.path?.startsWith("/api/") == true
                                ) {
                                    val auth = request.requestHeaders.entries
                                        .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                        ?.value
                                    deliver(auth)
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                // Signed in: the page has left /login. Read the token from
                                // storage through a fresh iframe in case no API call was seen.
                                if (url != null && !url.contains("/login") && !url.contains("/register")) {
                                    view.evaluateJavascript(TOKEN_FROM_STORAGE_JS) { deliver(decodeJsString(it)) }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                // Stay on Discord; everything else (help links etc.) is blocked.
                                val host = request.url.host.orEmpty()
                                return !(host == "discord.com" || host.endsWith(".discord.com"))
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}

/** Evaluates to the JSON string stored under `token`, or null. */
private const val TOKEN_FROM_STORAGE_JS =
    "(function(){try{var f=document.createElement('iframe');f.style.display='none';" +
        "document.body.appendChild(f);var t=f.contentWindow.localStorage.getItem('token');" +
        "f.remove();return t;}catch(e){return null;}})()"

/**
 * evaluateJavascript returns a JSON literal: `null`, or a quoted string whose content is
 * itself the JSON-quoted token as Discord stores it.
 */
private fun decodeJsString(result: String?): String? {
    if (result == null || result == "null") return null
    return runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrNull()
}

private fun clearWebSession() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}
