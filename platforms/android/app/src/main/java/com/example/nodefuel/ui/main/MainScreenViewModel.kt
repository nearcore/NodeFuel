package com.example.nodefuel.ui.main

import android.app.Application
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nodefuel.data.DataRepository
import com.example.nodefuel.data.NodeFuelConfig
import kotlinx.coroutines.launch

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = DataRepository(application)

  val anthropic = repository.anthropic
  val openai = repository.openai
  val refreshing = repository.refreshing

  var showLogin by mutableStateOf(false)
    private set

  var codexLoginStatus by mutableStateOf<String?>(null) // null = idle, else a short status message
    private set

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  fun loadConfig(): NodeFuelConfig = repository.loadConfig()

  fun saveConfig(config: NodeFuelConfig) {
    repository.saveConfig(config)
    refresh()
  }

  // ---- Claude (session-cookie via in-app WebView) ----

  fun isClaudeLoggedIn(): Boolean = repository.isClaudeLoggedIn()

  fun openClaudeLogin() {
    showLogin = true
  }

  fun onClaudeSessionCaptured(sessionKey: String) {
    repository.saveClaudeSessionKey(sessionKey)
    showLogin = false
    refresh()
  }

  fun cancelClaudeLogin() {
    showLogin = false
  }

  fun logOutOfClaude() {
    repository.clearClaudeSession()
    refresh()
  }

  // ---- Codex/OpenAI (OAuth via Chrome Custom Tabs + loopback callback) ----

  fun isCodexLoggedIn(): Boolean = repository.isCodexLoggedIn()

  fun openCodexLogin() {
    val authUrl = repository.startCodexLogin()
    codexLoginStatus = "Waiting for you to finish logging in..."
    // getApplication() is an Application Context, not an Activity Context -
    // launching a Custom Tab (which is really just startActivity() under
    // the hood) from a non-Activity context requires this flag or Android
    // throws AndroidRuntimeException and the app crashes.
    val customTabsIntent = CustomTabsIntent.Builder().build()
    customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    customTabsIntent.launchUrl(getApplication(), authUrl)
    viewModelScope.launch {
      val success = repository.awaitCodexLoginResult()
      codexLoginStatus = null
      if (success) {
        refresh()
      } else {
        codexLoginStatus = "Login didn't complete - try again"
      }
    }
  }

  fun logOutOfCodex() {
    repository.clearCodexTokens()
    refresh()
  }
}
