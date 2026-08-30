package com.example.nodefuel.ui.main

import android.app.Application
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
}
