package com.example.nodefuel.ui.login

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *
 * Known limitation: Google actively blocks OAuth sign-in inside generic
 * WebViews (an anti-phishing policy on Google's end, not something this
 * app controls) - "Sign in with Google" may fail or hang here. Email/
 * password login isn't subject to that restriction and should work
 * reliably.
 */
@Composable
fun ClaudeLoginScreen(onSessionCaptured: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  BackHandler(onBack = onCancel)

  var pageLoading by remember { mutableStateOf(true) }

  Box(modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          webViewClient =
            object : WebViewClient() {
              override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageLoading = true
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoading = false
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

    // Always-visible status bar - the WebView can go blank/white for a
    // while mid-redirect (especially right after logging in, or if
    // Google's OAuth block kicks in), so this makes clear the screen
    // hasn't frozen and tells you how to back out.
    Row(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (pageLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), color = Color(0xFF00D9FF))
      }
      Text(
        if (pageLoading) {
          "Loading... use email/password if \"Sign in with Google\" hangs here (Google blocks it in embedded views)"
        } else {
          "Log in above, then this closes automatically. Tap back to cancel."
        },
        color = Color.White,
        fontSize = 12.sp,
        textAlign = TextAlign.Start,
      )
    }
  }
}
