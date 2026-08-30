package com.example.nodefuel.ui.main

import android.app.Application
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
}
