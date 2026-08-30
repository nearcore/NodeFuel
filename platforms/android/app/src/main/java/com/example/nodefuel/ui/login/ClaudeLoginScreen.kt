package com.example.nodefuel.ui.login

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * An in-app WebView pointed at claude.ai's real login page. Once you log
 * in normally, claude.ai sets a `sessionKey` cookie - this screen watches
 * for it via WebView's CookieManager (which, unlike Chrome Custom Tabs or
 * an external browser, this app can actually read) and reports it back
 * via onSessionCaptured the moment it appears.
 *
 * This isn't an official login API - it's the same session-cookie
 * approach CodexBar uses on desktop, just via an in-app WebView instead
 * of a desktop browser's cookie store. See platforms/android/README.md.
 */
@Composable
fun ClaudeLoginScreen(onSessionCaptured: (String) -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          webViewClient =
            object : WebViewClient() {
              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
                val sessionKey =
                  cookies
                    ?.split(";")
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("sessionKey=") }
                    ?.removePrefix("sessionKey=")
                if (!sessionKey.isNullOrBlank()) {
                  onSessionCaptured(sessionKey)
                }
              }
            }
          loadUrl("https://claude.ai/login")
        }
      },
    )
    Text(
      "Log in with your Claude account below.\nThis screen closes automatically once you're in.",
      color = Color.White,
      modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
    )
  }
}
