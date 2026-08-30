package com.example.nodefuel.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.nodefuel.data.NodeFuelConfig
import com.example.nodefuel.data.ProviderStatus
import com.example.nodefuel.theme.NodeFuelTheme

private val NeonCyan = Color(0xFF00D9FF)
private val NeonPink = Color(0xFFFF006E)
private val DarkBg = Color(0xFF0A0A0A)
private val DimText = Color(0xFFAAAAAA)

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel(),
) {
  val anthropic by viewModel.anthropic.collectAsStateWithLifecycle(ProviderStatus("none"))
  val openai by viewModel.openai.collectAsStateWithLifecycle(ProviderStatus("none"))
  val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(false)
  var showSettings by remember { mutableStateOf(false) }

  Box(modifier.fillMaxSize().background(DarkBg).padding(20.dp)) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "⚡ NODEFUEL",
          color = NeonPink,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            if (refreshing) "..." else "↻",
            color = NeonCyan,
            fontSize = 20.sp,
            modifier = Modifier.clickable(enabled = !refreshing) { viewModel.refresh() },
          )
          Spacer(Modifier.width(16.dp))
          Text(
            "⚙",
            color = NeonCyan,
            fontSize = 20.sp,
            modifier = Modifier.clickable { showSettings = !showSettings },
          )
        }
      }

      Spacer(Modifier.height(32.dp))

      if (showSettings) {
        SettingsSection(
          initial = viewModel.loadConfig(),
          onSave = { config ->
            showSettings = false
            viewModel.saveConfig(config)
          },
        )
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
          ProviderCard("ANTHROPIC", NeonCyan, anthropic)
          ProviderCard("OPENAI", NeonPink, openai)
        }
      }
    }
  }
}

@Composable
private fun ProviderCard(label: String, accent: Color, status: ProviderStatus) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    FuelRing(accent = accent, status = status)
    Spacer(Modifier.height(8.dp))
    Text(label, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    Text(
      text =
        when (status.mode) {
          "api" -> "$" + "%.2f".format(status.balance ?: 0.0)
          "subscription" -> "SUBSCRIPTION"
          else -> "NOT SET UP"
        },
      color = if (status.mode == "none") Color(0xFF555555) else DimText,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
  }
}

@Composable
private fun FuelRing(accent: Color, status: ProviderStatus) {
  Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val strokeWidth = 6.dp.toPx()
      // Track
      drawArc(
        color = Color(0xFF2A2A2A),
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
      )
      // No live % source on Android (no CodexBar) - same decorative ~88%
      // sweep the desktop version uses for its api/subscription modes.
      if (status.mode != "none") {
        drawArc(
          color = accent,
          startAngle = -90f,
          sweepAngle = 360f * 0.88f,
          useCenter = false,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
      }
    }
    Text(
      text =
        when (status.mode) {
          "api" -> "$"
          "subscription" -> "⚡"
          else -> "–"
        },
      color = if (status.mode == "none") Color(0xFF555555) else accent,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 22.sp,
    )
  }
}

@Composable
private fun SettingsSection(initial: NodeFuelConfig, onSave: (NodeFuelConfig) -> Unit) {
  var anthropicKey by remember { mutableStateOf(initial.anthropicApiKey) }
  var openaiKey by remember { mutableStateOf(initial.openaiApiKey) }
  var anthropicSub by remember { mutableStateOf(initial.anthropicSubscription) }
  var openaiSub by remember { mutableStateOf(initial.openaiSubscription) }

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("ANTHROPIC", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    OutlinedTextField(
      value = anthropicKey,
      onValueChange = { anthropicKey = it },
      label = { Text("API key (blank if subscription only)") },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = anthropicSub, onCheckedChange = { anthropicSub = it })
      Spacer(Modifier.width(8.dp))
      Text("I have a Claude subscription", color = DimText, fontSize = 12.sp)
    }

    Spacer(Modifier.height(8.dp))
    Text("OPENAI / CODEX", color = NeonPink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    OutlinedTextField(
      value = openaiKey,
      onValueChange = { openaiKey = it },
      label = { Text("API key (blank if subscription only)") },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = openaiSub, onCheckedChange = { openaiSub = it })
      Spacer(Modifier.width(8.dp))
      Text("I have a ChatGPT/Codex subscription", color = DimText, fontSize = 12.sp)
    }

    Spacer(Modifier.height(12.dp))
    Button(onClick = { onSave(NodeFuelConfig(anthropicKey, openaiKey, anthropicSub, openaiSub)) }) {
      Text("SAVE")
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun MainScreenPreview() {
  NodeFuelTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
      ProviderCard("ANTHROPIC", NeonCyan, ProviderStatus("api", 12.5))
      ProviderCard("OPENAI", NeonPink, ProviderStatus("subscription"))
    }
  }
}
