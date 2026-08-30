package com.example.nodefuel.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A provider's display status - mirrors backend/main.py's get_provider_status()
 * modes, minus "usage": there's no CodexBar-equivalent CLI for Android, so
 * subscription-only accounts fall back straight to the plain badge (the
 * same fallback the desktop version uses when CodexBar itself is missing).
 */
data class ProviderStatus(
  val mode: String, // "api" | "subscription" | "none"
  val balance: Double? = null,
)

data class NodeFuelConfig(
  val anthropicApiKey: String = "",
  val openaiApiKey: String = "",
  val anthropicSubscription: Boolean = false,
  val openaiSubscription: Boolean = false,
)

class DataRepository(context: Context) {
  private val prefs = context.getSharedPreferences("nodefuel_config", Context.MODE_PRIVATE)

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

  suspend fun refresh() {
    _refreshing.value = true
    try {
      val cfg = loadConfig()
      _anthropic.value = statusFor(cfg.anthropicApiKey, cfg.anthropicSubscription, ::fetchAnthropicBalance)
      _openai.value = statusFor(cfg.openaiApiKey, cfg.openaiSubscription, ::fetchOpenAiBalance)
    } finally {
      _refreshing.value = false
    }
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

  // Mirrors backend/main.py's fetch_anthropic_balance()
  private fun fetchAnthropicBalance(apiKey: String): Double? =
    try {
      val conn = URL("https://api.anthropic.com/v1/account").openConnection() as HttpURLConnection
      conn.setRequestProperty("x-api-key", apiKey)
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000
      if (conn.responseCode == 200) {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        JSONObject(body).optJSONObject("usage")?.let { if (it.has("balance")) it.getDouble("balance") else null }
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }

  // Mirrors backend/main.py's fetch_openai_balance()
  private fun fetchOpenAiBalance(apiKey: String): Double? =
    try {
      val conn = URL("https://api.openai.com/dashboard/billing/credit_grants").openConnection() as HttpURLConnection
      conn.setRequestProperty("Authorization", "Bearer $apiKey")
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000
      if (conn.responseCode == 200) {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        if (json.has("total_available")) json.getDouble("total_available") else null
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }
}
