package com.example.nodefuel.data

import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

private const val TAG = "NodeFuel"

// OAuth 2.0 + PKCE against Codex CLI's own public client - these exact
// values (client_id, endpoints, redirect port/path, scopes) come straight
// from OpenAI's own open-source Codex CLI (codex-rs/login/src/server.rs),
// not guesswork. CodexBar itself doesn't perform this login at all - it
// just reads the token file the real Codex CLI's login produces; we do
// the actual OAuth dance ourselves since there's no CLI to shell out to
// on Android.
private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
private const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
private const val REDIRECT_PORT = 1455
private const val REDIRECT_PATH = "/auth/callback"
private const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT$REDIRECT_PATH"
private const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"

data class CodexTokens(val accessToken: String, val refreshToken: String?)

object CodexAuth {

  private fun randomUrlSafeString(bytes: Int = 32): String {
    val buf = ByteArray(bytes)
    SecureRandom().nextBytes(buf)
    return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
  }

  private fun codeChallengeFor(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
  }

  fun buildAuthUrl(state: String, codeVerifier: String): Uri {
    val challenge = codeChallengeFor(codeVerifier)
    return Uri.parse(AUTH_ENDPOINT)
      .buildUpon()
      .appendQueryParameter("response_type", "code")
      .appendQueryParameter("client_id", CLIENT_ID)
      .appendQueryParameter("redirect_uri", REDIRECT_URI)
      .appendQueryParameter("scope", SCOPE)
      .appendQueryParameter("code_challenge", challenge)
      .appendQueryParameter("code_challenge_method", "S256")
      .appendQueryParameter("id_token_add_organizations", "true")
      .appendQueryParameter("codex_cli_simplified_flow", "true")
      .appendQueryParameter("state", state)
      .appendQueryParameter("originator", "codex_cli_rs")
      .build()
  }

  fun newState(): String = randomUrlSafeString()

  fun newCodeVerifier(): String = randomUrlSafeString()

  /**
   * Blocks (call from a background thread/coroutine) waiting for exactly
   * one GET request on http://localhost:1455/auth/callback, then returns
   * the `code` query param if `state` matches, or null on mismatch/error/
   * timeout. Mirrors the loopback-server redirect pattern the real Codex
   * CLI uses - Android can bind 127.0.0.1 ports the same way a desktop
   * app can.
   */
  fun awaitAuthorizationCode(expectedState: String, timeoutMillis: Int = 180_000): String? {
    val serverSocket =
      try {
        ServerSocket(REDIRECT_PORT)
      } catch (e: Exception) {
        Log.w(TAG, "Codex OAuth: couldn't bind port $REDIRECT_PORT", e)
        return null
      }
    serverSocket.soTimeout = timeoutMillis
    try {
      val client = serverSocket.accept()
      client.use { socket ->
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return null
        Log.d(TAG, "Codex OAuth callback request: $requestLine")
        val path = requestLine.split(" ").getOrNull(1) ?: return null
        if (!path.startsWith(REDIRECT_PATH)) return null

        val query = path.substringAfter("?", "")
        val params =
          query
            .split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
              val parts = pair.split("=", limit = 2)
              val key = parts[0]
              val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
              key to value
            }

        val html =
          "<html><body style='font-family:sans-serif;background:#0a0a0a;color:#fff;text-align:center;padding-top:80px'>" +
            "<h2>NodeFuel login complete</h2><p>You can close this tab and return to the app.</p></body></html>"
        val response =
          "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
        socket.getOutputStream().write(response.toByteArray())
        socket.getOutputStream().flush()

        val state = params["state"]
        val code = params["code"]
        if (state != expectedState) {
          Log.w(TAG, "Codex OAuth: state mismatch (possible CSRF or stale request)")
          return null
        }
        if (code.isNullOrBlank()) {
          Log.w(TAG, "Codex OAuth: callback had no code - params were: $params")
          return null
        }
        return code
      }
    } catch (e: Exception) {
      Log.w(TAG, "Codex OAuth: callback wait failed/timed out", e)
      return null
    } finally {
      serverSocket.close()
    }
  }

  /**
   * The instant the Custom Tab opens, our app is backgrounded - and some
   * OEM skins (Samsung's One UI included) briefly block a backgrounded
   * app's outbound network/DNS until it's back in the foreground or a
   * moment has passed. That window lands right here: the browser posts
   * the callback to our loopback server the instant login finishes, so
   * this call can fire before the OS lifts that block. A few retries with
   * a short backoff ride out that window instead of failing on attempt 1.
   */
  fun exchangeCodeForTokens(code: String, codeVerifier: String, maxAttempts: Int = 5): CodexTokens? {
    val body =
      listOf(
          "grant_type" to "authorization_code",
          "code" to code,
          "redirect_uri" to REDIRECT_URI,
          "client_id" to CLIENT_ID,
          "code_verifier" to codeVerifier,
        )
        .joinToString("&") { (k, v) -> "${Uri.encode(k)}=${Uri.encode(v)}" }

    for (attempt in 1..maxAttempts) {
      try {
        val conn = URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray()) }

        if (conn.responseCode != 200) {
          val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
          Log.w(TAG, "Codex OAuth token exchange failed: HTTP ${conn.responseCode} $err")
          return null // a real HTTP error response - retrying won't help
        }
        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isBlank()) {
          Log.w(TAG, "Codex OAuth: token response had no access_token: $responseBody")
          return null
        }
        val refreshToken = json.optString("refresh_token", "").ifBlank { null }
        return CodexTokens(accessToken = accessToken, refreshToken = refreshToken)
      } catch (e: java.io.IOException) {
        // Network-layer failure (DNS/connect/etc) - most likely the
        // backgrounded-app network block above. Back off and retry.
        Log.w(TAG, "Codex OAuth token exchange network error (attempt $attempt/$maxAttempts)", e)
        if (attempt == maxAttempts) return null
        Thread.sleep(attempt * 600L) // 600ms, 1200ms, 1800ms, 2400ms
      } catch (e: Exception) {
        Log.w(TAG, "Codex OAuth token exchange error", e)
        return null // not a network error - retrying won't help
      }
    }
    return null
  }

  /**
   * Fetches Codex/ChatGPT usage. Endpoint is confirmed (from the Codex CLI
   * source), but its exact response field names aren't - same defensive,
   * log-and-try-a-few-shapes approach as the Claude usage parser.
   */
  fun fetchUsage(accessToken: String): ProviderStatus? {
    return try {
      val conn = URL("https://chatgpt.com/backend-api/wham/usage").openConnection() as HttpURLConnection
      conn.setRequestProperty("Authorization", "Bearer $accessToken")
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000
      if (conn.responseCode != 200) {
        Log.w(TAG, "Codex usage fetch: HTTP ${conn.responseCode}")
        return null
      }
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      Log.d(TAG, "Codex usage response: $body") // grab via `adb logcat -s NodeFuel` if parsing below needs adjusting

      val json = JSONObject(body)
      val sessionPercent = extractPercent(json, "five_hour", "session", "primary")
      val weeklyPercent = extractPercent(json, "seven_day", "weekly", "week", "secondary")
      val resetsAtMillis = extractResetMillis(json, "five_hour", "session", "primary")

      ProviderStatus(mode = "usage", sessionPercent = sessionPercent, weeklyPercent = weeklyPercent, resetsAtMillis = resetsAtMillis)
    } catch (e: Exception) {
      Log.w(TAG, "Codex usage fetch error", e)
      null
    }
  }

  private fun extractPercent(json: JSONObject, vararg keys: String): Int? {
    for (key in keys) {
      val obj = json.optJSONObject(key) ?: continue
      for (field in listOf("utilization", "used_percent", "usedPercent", "percent_used", "percent")) {
        if (obj.has(field)) {
          val raw = obj.opt(field)
          val value = (raw as? Number)?.toDouble() ?: (raw as? String)?.toDoubleOrNull()
          if (value != null) return (if (value <= 1.0) value * 100 else value).toInt()
        }
      }
    }
    return null
  }

  private fun extractResetMillis(json: JSONObject, vararg keys: String): Long? {
    for (key in keys) {
      val obj = json.optJSONObject(key) ?: continue
      for (field in listOf("resets_at", "resetsAt", "reset_at", "resetAt")) {
        val raw = obj.optString(field, "")
        if (raw.isNotBlank()) {
          try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(raw)?.let { return it.time }
          } catch (e: Exception) {
            // try the next key/field combination
          }
        }
      }
    }
    return null
  }
}
