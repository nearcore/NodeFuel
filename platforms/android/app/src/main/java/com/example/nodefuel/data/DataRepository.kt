package com.example.nodefuel.data

import android.content.Context
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
)

data class NodeFuelConfig(
  val anthropicApiKey: String = "",
  val openaiApiKey: String = "",
  val anthropicSubscription: Boolean = false,
  val openaiSubscription: Boolean = false,
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
    )

  fun saveConfig(config: NodeFuelConfig) {
    prefs
      .edit()
      .putString("anthropic_api_key", config.anthropicApiKey)
      .putString("openai_api_key", config.openaiApiKey)
      .putBoolean("anthropic_subscription", config.anthropicSubscription)
      .putBoolean("openai_subscription", config.openaiSubscription)
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

  suspend fun refresh() {
    _refreshing.value = true
    try {
      val cfg = loadConfig()
      _anthropic.value = anthropicStatus(cfg)
      _openai.value = statusFor(cfg.openaiApiKey, cfg.openaiSubscription, ::fetchOpenAiBalance)
    } finally {
      _refreshing.value = false
    }
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

  private suspend fun statusFor(
    apiKey: String,
    hasSubscription: Boolean,
    fetchBalance: (String) -> Double?,
  ): ProviderStatus =
    withContext(Dispatchers.IO) {
      when {
        apiKey.isNotBlank() -> ProviderStatus(mode = "api", balance = fetchBalance(apiKey) ?: 0.0)
        hasSubscription -> ProviderStatus(mode = "subscription")
        else -> ProviderStatus(mode = "none")
      }
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

      return ProviderStatus(
        mode = "usage",
        sessionPercent = sessionPercent,
        weeklyPercent = weeklyPercent,
        resetsAtMillis = resetsAtMillis,
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

  // Same defensive multi-key-name approach as extractPercent(), for the
  // reset timestamp. Parsed with SimpleDateFormat rather than java.time,
  // since minSdk 24 predates java.time without core library desugaring.
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
