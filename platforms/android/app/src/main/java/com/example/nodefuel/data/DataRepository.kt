package com.example.nodefuel.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NodeFuel"

/**
 * A provider's display status.
 * - "api": real $ balance via an API key
 * - "usage": real session/weekly % via the in-app Claude login (WebView
 *   session-cookie capture) - Anthropic only, for now
 * - "subscription": plain badge fallback (subscription toggle on, but
 *   neither an API key nor a captured session available)
 * - "none": nothing configured
 */
data class ProviderStatus(
  val mode: String,
  val balance: Double? = null,
  val sessionPercent: Int? = null,
  val weeklyPercent: Int? = null,
  val resetsAtMillis: Long? = null, // epoch millis - when the session window resets
  val creditsRemaining: Double? = null, // extra $ credits beyond the plan limit, if any (matches desktop's "X.X credits" line)
)

data class NodeFuelConfig(
  val anthropicApiKey: String = "",
  val openaiApiKey: String = "",
  val anthropicSubscription: Boolean = false,
  val openaiSubscription: Boolean = false,
  val cyberMode: Boolean = false,
  val refreshIntervalSeconds: Int = 60,
)

class DataRepository(private val context: Context) {
  private val prefs = context.getSharedPreferences("nodefuel_config", Context.MODE_PRIVATE)

  // Separate, encrypted store for the Claude session cookie - it's
  // sensitive (equivalent to an auth token), unlike the plain config prefs.
  private val securePrefs by lazy {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(
      context,
      "nodefuel_secure",
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  private val _anthropic = MutableStateFlow(ProviderStatus(mode = "none"))
  val anthropic: Flow<ProviderStatus> = _anthropic.asStateFlow()

  private val _openai = MutableStateFlow(ProviderStatus(mode = "none"))
  val openai: Flow<ProviderStatus> = _openai.asStateFlow()

  private val _refreshing = MutableStateFlow(false)
  val refreshing: Flow<Boolean> = _refreshing.asStateFlow()

  fun loadConfig(): NodeFuelConfig =
    NodeFuelConfig(
      anthropicApiKey = prefs.getString("anthropic_api_key", "") ?: "",
      openaiApiKey = prefs.getString("openai_api_key", "") ?: "",
      anthropicSubscription = prefs.getBoolean("anthropic_subscription", false),
      openaiSubscription = prefs.getBoolean("openai_subscription", false),
      cyberMode = prefs.getBoolean("cyber_mode", false),
      refreshIntervalSeconds = prefs.getInt("refresh_interval_seconds", 60),
    )

  fun saveConfig(config: NodeFuelConfig) {
    prefs
      .edit()
      .putString("anthropic_api_key", config.anthropicApiKey)
      .putString("openai_api_key", config.openaiApiKey)
      .putBoolean("anthropic_subscription", config.anthropicSubscription)
      .putBoolean("openai_subscription", config.openaiSubscription)
      .putBoolean("cyber_mode", config.cyberMode)
      .putInt("refresh_interval_seconds", config.refreshIntervalSeconds.coerceIn(10, 3600))
      .apply()
  }

  // ---- Claude session login (in-app WebView captures the `sessionKey`
  // cookie after you log into claude.ai normally - see LoginScreen.kt) ----

  fun saveClaudeSessionKey(sessionKey: String) {
    securePrefs.edit().putString("claude_session_key", sessionKey).apply()
  }

  fun loadClaudeSessionKey(): String? = securePrefs.getString("claude_session_key", null)

  fun clearClaudeSession() {
    securePrefs.edit().remove("claude_session_key").apply()
  }

  fun isClaudeLoggedIn(): Boolean = !loadClaudeSessionKey().isNullOrBlank()

  // ---- Codex/OpenAI OAuth login (PKCE, via Chrome Custom Tabs + a local
  // loopback callback server - see CodexAuth.kt) ----

  private var codexState: String? = null
  private var codexVerifier: String? = null

  /** Kicks off a login attempt and returns the URL to open in a Custom Tab. */
  fun startCodexLogin(): Uri {
    val state = CodexAuth.newState()
    val verifier = CodexAuth.newCodeVerifier()
    codexState = state
    codexVerifier = verifier
    return CodexAuth.buildAuthUrl(state, verifier)
  }

  /** Blocks (on IO) until the callback arrives or times out. Call after startCodexLogin(). */
  suspend fun awaitCodexLoginResult(): Boolean =
    withContext(Dispatchers.IO) {
      val state = codexState ?: return@withContext false
      val verifier = codexVerifier ?: return@withContext false
      val code = CodexAuth.awaitAuthorizationCode(state) ?: return@withContext false
      val tokens = CodexAuth.exchangeCodeForTokens(code, verifier) ?: return@withContext false
      saveCodexTokens(tokens)
      true
    }

  private fun saveCodexTokens(tokens: CodexTokens) {
    securePrefs.edit().putString("codex_access_token", tokens.accessToken).putString("codex_refresh_token", tokens.refreshToken ?: "").apply()
  }

  fun loadCodexAccessToken(): String? = securePrefs.getString("codex_access_token", null)

  fun clearCodexTokens() {
    securePrefs.edit().remove("codex_access_token").remove("codex_refresh_token").apply()
  }

  fun isCodexLoggedIn(): Boolean = !loadCodexAccessToken().isNullOrBlank()

  suspend fun refresh() {
    _refreshing.value = true
    try {
      val cfg = loadConfig()
      _anthropic.value = anthropicStatus(cfg)
      _openai.value = openaiStatus(cfg)
    } finally {
      _refreshing.value = false
    }
  }

  private suspend fun openaiStatus(cfg: NodeFuelConfig): ProviderStatus =
    withContext(Dispatchers.IO) {
      if (cfg.openaiApiKey.isNotBlank()) {
        return@withContext ProviderStatus(mode = "api", balance = fetchOpenAiBalance(cfg.openaiApiKey) ?: 0.0)
      }
      val accessToken = loadCodexAccessToken()
      if (!accessToken.isNullOrBlank()) {
        val usage = CodexAuth.fetchUsage(accessToken)
        if (usage != null) return@withContext usage
        Log.w(TAG, "Codex usage fetch failed - token may have expired")
      }
      if (cfg.openaiSubscription) ProviderStatus(mode = "subscription") else ProviderStatus(mode = "none")
    }

  private suspend fun anthropicStatus(cfg: NodeFuelConfig): ProviderStatus =
    withContext(Dispatchers.IO) {
      if (cfg.anthropicApiKey.isNotBlank()) {
        return@withContext ProviderStatus(mode = "api", balance = fetchAnthropicBalance(cfg.anthropicApiKey) ?: 0.0)
      }
      val sessionKey = loadClaudeSessionKey()
      if (!sessionKey.isNullOrBlank()) {
        val usage = fetchClaudeUsageViaSession(sessionKey)
        if (usage != null) return@withContext usage
        // Session likely expired - fall through to the plain badge (or
        // "none") rather than silently showing stale/wrong data.
        Log.w(TAG, "Claude session fetch failed - session may have expired")
      }
      if (cfg.anthropicSubscription) ProviderStatus(mode = "subscription") else ProviderStatus(mode = "none")
    }

  // Mirrors backend/main.py's fetch_anthropic_balance() - official API key path
  private fun fetchAnthropicBalance(apiKey: String): Double? =
    try {
      val body = httpGet("https://api.anthropic.com/v1/account", mapOf("x-api-key" to apiKey))
      body?.let { JSONObject(it).optJSONObject("usage")?.let { u -> if (u.has("balance")) u.getDouble("balance") else null } }
    } catch (e: Exception) {
      Log.w(TAG, "fetchAnthropicBalance failed", e)
      null
    }

  // Mirrors backend/main.py's fetch_openai_balance() - official API key path
  private fun fetchOpenAiBalance(apiKey: String): Double? =
    try {
      val body = httpGet("https://api.openai.com/dashboard/billing/credit_grants", mapOf("Authorization" to "Bearer $apiKey"))
      body?.let { JSONObject(it).let { j -> if (j.has("total_available")) j.getDouble("total_available") else null } }
    } catch (e: Exception) {
      Log.w(TAG, "fetchOpenAiBalance failed", e)
      null
    }

  // Unofficial: uses the session cookie captured by the in-app Claude
  // login, calling the same internal endpoints claude.ai's own web app
  // uses (documented by the CodexBar project, which does the same thing
  // on desktop via browser cookies - see platforms/android/README.md).
  private fun fetchClaudeUsageViaSession(sessionKey: String): ProviderStatus? {
    val cookieHeader = mapOf("Cookie" to "sessionKey=$sessionKey")
    try {
      val orgsBody = httpGet("https://claude.ai/api/organizations", cookieHeader) ?: return null
      val orgs = JSONArray(orgsBody)
      if (orgs.length() == 0) return null
      val orgId = orgs.getJSONObject(0).getString("uuid")

      val usageBody = httpGet("https://claude.ai/api/organizations/$orgId/usage", cookieHeader) ?: return null
      Log.d(TAG, "Claude usage response: $usageBody") // grab via `adb logcat` if % parsing below needs adjusting

      val usageJson = JSONObject(usageBody)
      val sessionPercent = extractPercent(usageJson, "five_hour", "session")
      val weeklyPercent = extractPercent(usageJson, "seven_day", "weekly", "week")
      val resetsAtMillis = extractResetMillis(usageJson, "five_hour", "session")
      val creditsRemaining = extractClaudeCreditsRemaining(usageJson)

      return ProviderStatus(
        mode = "usage",
        sessionPercent = sessionPercent,
        weeklyPercent = weeklyPercent,
        resetsAtMillis = resetsAtMillis,
        creditsRemaining = creditsRemaining,
      )
    } catch (e: Exception) {
      Log.w(TAG, "fetchClaudeUsageViaSession failed", e)
      return null
    }
  }

  // The exact response shape isn't fully documented publicly, so this
  // tries a few plausible key names/nesting patterns defensively instead
  // of assuming one. Logs the raw response above either way, so field
  // names can be corrected against a real response if none of these hit.
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

  // Extra $ credits beyond the plan limit - only present for accounts that
  // have "usage credits" enabled (see claude.ai's own billing settings).
  // This account's own response has both fields disabled/null, so this is
  // best-effort/untested against a live positive value; mirrors codexbar's
  // "credits.remaining" field, which is what the desktop widget shows.
  private fun extractClaudeCreditsRemaining(json: JSONObject): Double? {
    val spend = json.optJSONObject("spend")
    if (spend != null && spend.has("balance") && !spend.isNull("balance")) {
      (spend.opt("balance") as? Number)?.toDouble()?.let { return it }
    }
    val extra = json.optJSONObject("extra_usage")
    if (extra != null && extra.optBoolean("is_enabled", false)) {
      val limit = extra.opt("monthly_limit") as? Number
      val used = extra.opt("used_credits") as? Number
      if (limit != null && used != null) return (limit.toDouble() - used.toDouble()).coerceAtLeast(0.0)
    }
    return null
  }

  // Same defensive multi-key-name approach as extractPercent(), for the
  // reset timestamp. Parsed with SimpleDateFormat rather than java.time,
  // since minSdk 24 predates java.time without core library desugaring.
  private fun extractResetMillis(json: JSONObject, vararg keys: String): Long? {
    for (key in keys) {
      val obj = json.optJSONObject(key) ?: continue
      for (field in listOf("resets_at", "resetsAt", "reset_at", "resetAt")) {
        val raw = obj.optString(field, "")
        if (raw.isNotBlank()) {
          parseIsoToMillis(raw)?.let { return it }
        }
      }
    }
    return null
  }

  // Claude's actual shape is "2026-08-30T22:09:59.526454+00:00" (fractional
  // seconds + a numeric "+00:00" offset) - a literal-'Z'-suffix pattern
  // silently fails to parse that (caught above, returns null), which is
  // why "resets in" was missing for Anthropic while Codex's raw-epoch
  // reset_at worked fine. Every observed response uses +00:00 (UTC), so
  // this trims the fractional part and any "Z"/offset suffix and parses
  // the remaining "yyyy-MM-dd'T'HH:mm:ss" as UTC - handles that shape,
  // a plain "...Z" suffix, and a bare "+00:00" offset without fractional
  // seconds, all the same way.
  private fun parseIsoToMillis(raw: String): Long? =
    try {
      val datePart = raw.substringBefore('.').substringBefore('Z').substringBefore('+')
      val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
      fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
      fmt.parse(datePart)?.time
    } catch (e: Exception) {
      null
  }

  private fun httpGet(urlString: String, headers: Map<String, String>): String? {
    val conn = URL(urlString).openConnection() as HttpURLConnection
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    return if (conn.responseCode == 200) {
      conn.inputStream.bufferedReader().use { it.readText() }
    } else {
      Log.w(TAG, "GET $urlString -> HTTP ${conn.responseCode}")
      null
    }
  }
}
