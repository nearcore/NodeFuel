package com.example.nodefuel.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.nodefuel.R
import com.example.nodefuel.data.NodeFuelConfig
import com.example.nodefuel.data.ProviderStatus
import com.example.nodefuel.theme.NodeFuelTheme
import com.example.nodefuel.ui.login.ClaudeLoginScreen
import kotlin.math.cos
import kotlin.math.sin

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
  if (viewModel.showLogin) {
    ClaudeLoginScreen(
      onSessionCaptured = viewModel::onClaudeSessionCaptured,
      onCancel = viewModel::cancelClaudeLogin,
      modifier = modifier,
    )
    return
  }

  val anthropic by viewModel.anthropic.collectAsStateWithLifecycle(ProviderStatus("none"))
  val openai by viewModel.openai.collectAsStateWithLifecycle(ProviderStatus("none"))
  val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(false)
  var showSettings by remember { mutableStateOf(false) }
  var showAbout by remember { mutableStateOf(false) }

  Box(modifier.fillMaxSize().background(DarkBg).padding(20.dp)) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Image(
            painter = painterResource(R.drawable.nodefuel_logo),
            contentDescription = null,
            modifier = Modifier.height(28.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            "NODEFUEL",
            color = NeonPink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
          )
        }
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
            modifier = Modifier.clickable { showSettings = !showSettings; showAbout = false },
          )
          Spacer(Modifier.width(16.dp))
          Text(
            "ℹ",
            color = NeonCyan,
            fontSize = 20.sp,
            modifier = Modifier.clickable { showAbout = !showAbout; showSettings = false },
          )
        }
      }

      Spacer(Modifier.height(32.dp))

      if (showSettings) {
        SettingsSection(
          initial = viewModel.loadConfig(),
          claudeLoggedIn = viewModel.isClaudeLoggedIn(),
          codexLoggedIn = viewModel.isCodexLoggedIn(),
          codexLoginStatus = viewModel.codexLoginStatus,
          onSave = { config ->
            showSettings = false
            viewModel.saveConfig(config)
          },
          onLoginClaude = viewModel::openClaudeLogin,
          onLogoutClaude = viewModel::logOutOfClaude,
          onLoginCodex = viewModel::openCodexLogin,
          onLogoutCodex = viewModel::logOutOfCodex,
        )
      } else if (showAbout) {
        AboutSection()
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
          ProviderCard("ANTHROPIC", NeonCyan, anthropic, cyberMode = viewModel.cyberMode)
          ProviderCard("OPENAI", NeonPink, openai, cyberMode = viewModel.cyberMode)
        }
      }
    }
  }
}

@Composable
private fun ProviderCard(label: String, accent: Color, status: ProviderStatus, cyberMode: Boolean = false) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    FuelRing(accent = accent, status = status, cyberMode = cyberMode)
    Spacer(Modifier.height(8.dp))
    Text(label, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    Text(
      text =
        when (status.mode) {
          "api" -> "$" + "%.2f".format(status.balance ?: 0.0)
          "usage" -> "Daily ${status.sessionPercent ?: 0}% used"
          "subscription" -> "SUBSCRIPTION"
          else -> "NOT SET UP"
        },
      color = if (status.mode == "none") Color(0xFF555555) else DimText,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
    if (status.mode == "usage" && status.weeklyPercent != null) {
      Text(
        text = "Weekly ${status.weeklyPercent}% used",
        color = Color(0xFF888888),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )
    }
    if (status.mode == "usage" && status.resetsAtMillis != null) {
      Text(
        text = "resets in ${formatCountdown(status.resetsAtMillis)}",
        color = Color(0xFF555555),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
      )
    }
    if (status.mode == "usage" && status.creditsRemaining != null && status.creditsRemaining >= 0) {
      Text(
        text = "%.1f credits".format(status.creditsRemaining),
        color = Color(0xFF555555),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
      )
    }
  }
}

// "resets in Xh Ym" / "resets in Xm" / "resetting..." - same shape as the
// desktop widget's live countdown, computed once per fetch rather than
// ticking continuously (this screen re-renders on refresh, not idle).
private fun formatCountdown(resetsAtMillis: Long): String {
  val diffMs = resetsAtMillis - System.currentTimeMillis()
  if (diffMs <= 0) return "resetting..."
  val diffMin = diffMs / 60_000
  val h = diffMin / 60
  val m = diffMin % 60
  return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun FuelRing(accent: Color, status: ProviderStatus, cyberMode: Boolean = false) {
  // Orbiting sparks (Cyber Mode) - points drifting around the ring,
  // mirroring the desktop widget's "electricity traveling around the
  // sphere" without the full lightning-bolt render. Cheap enough to just
  // always run; only drawn when cyberMode is on.
  val orbitTransition = rememberInfiniteTransition(label = "cyberOrbit")
  val orbitAngle by
    orbitTransition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(animation = tween(5200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
      label = "orbitAngle",
    )

  Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val strokeWidth = 6.dp.toPx()
      val radius = size.minDimension / 2f - strokeWidth
      val center = Offset(size.width / 2f, size.height / 2f)

      if (cyberMode && status.mode != "none") {
        // Glossy orb: a radial gradient with the highlight offset toward
        // one corner, like light catching a sphere.
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(Color.White.copy(alpha = 0.35f), accent.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.65f)),
              center = Offset(center.x - radius * 0.35f, center.y - radius * 0.4f),
              radius = radius * 1.6f,
            ),
          radius = radius - strokeWidth / 2f,
          center = center,
        )
      }

      // Track
      drawArc(
        color = Color(0xFF2A2A2A),
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
      )
      if (status.mode == "usage" && status.sessionPercent != null) {
        // Real data: fuel LEFT, not used - a full ring means plenty left,
        // matching the desktop version's tank metaphor.
        val left = (100 - status.sessionPercent).coerceIn(0, 100)
        drawArc(
          color = accent,
          startAngle = -90f,
          sweepAngle = 360f * (left / 100f),
          useCenter = false,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
      } else if (status.mode != "none") {
        // No live % (api/subscription) - decorative ~88% sweep, same as desktop
        drawArc(
          color = accent,
          startAngle = -90f,
          sweepAngle = 360f * 0.88f,
          useCenter = false,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
      }

      if (cyberMode && status.mode != "none") {
        // 3 sparks drifting around the ring, 120deg apart, each with a
        // soft glow behind a bright core.
        val ringRadius = size.minDimension / 2f - strokeWidth / 2f
        for (i in 0 until 3) {
          val angleRad = Math.toRadians((orbitAngle + i * 120f).toDouble())
          val sx = center.x + ringRadius * cos(angleRad).toFloat()
          val sy = center.y + ringRadius * sin(angleRad).toFloat()
          drawCircle(color = accent.copy(alpha = 0.35f), radius = strokeWidth * 1.3f, center = Offset(sx, sy))
          drawCircle(color = Color.White, radius = strokeWidth * 0.35f, center = Offset(sx, sy))
        }
      }
    }
    Text(
      text =
        when {
          status.mode == "api" -> "$"
          status.mode == "usage" && status.sessionPercent != null -> "${100 - status.sessionPercent}%"
          status.mode == "subscription" -> "⚡"
          else -> "–"
        },
      color = if (status.mode == "none") Color(0xFF555555) else accent,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
    )
  }
}

@Composable
private fun SettingsSection(
  initial: NodeFuelConfig,
  claudeLoggedIn: Boolean,
  codexLoggedIn: Boolean,
  codexLoginStatus: String?,
  onSave: (NodeFuelConfig) -> Unit,
  onLoginClaude: () -> Unit,
  onLogoutClaude: () -> Unit,
  onLoginCodex: () -> Unit,
  onLogoutCodex: () -> Unit,
) {
  var anthropicKey by remember { mutableStateOf(initial.anthropicApiKey) }
  var openaiKey by remember { mutableStateOf(initial.openaiApiKey) }
  var anthropicSub by remember { mutableStateOf(initial.anthropicSubscription) }
  var openaiSub by remember { mutableStateOf(initial.openaiSubscription) }
  var cyberMode by remember { mutableStateOf(initial.cyberMode) }
  var refreshIntervalText by remember { mutableStateOf(initial.refreshIntervalSeconds.toString()) }

  Column(
    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("DISPLAY", color = DimText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = cyberMode, onCheckedChange = { cyberMode = it })
      Spacer(Modifier.width(8.dp))
      Text("⚡ Cyber Mode - glossy orbs with orbiting sparks", color = DimText, fontSize = 12.sp)
    }
    Spacer(Modifier.height(4.dp))

    Text("ANTHROPIC", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    OutlinedTextField(
      value = anthropicKey,
      onValueChange = { anthropicKey = it },
      label = { Text("API key (optional)") },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = anthropicSub, onCheckedChange = { anthropicSub = it })
      Spacer(Modifier.width(8.dp))
      Text("I have a Claude subscription", color = DimText, fontSize = 12.sp)
    }
    if (claudeLoggedIn) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓ Logged in - showing real usage %", color = Color(0xFF00FF88), fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onLogoutClaude) { Text("Log out") }
      }
    } else {
      OutlinedButton(onClick = onLoginClaude) { Text("Log in with Claude for real usage %") }
    }

    Spacer(Modifier.height(8.dp))
    Text("OPENAI / CODEX", color = NeonPink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    OutlinedTextField(
      value = openaiKey,
      onValueChange = { openaiKey = it },
      label = { Text("API key (optional)") },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = openaiSub, onCheckedChange = { openaiSub = it })
      Spacer(Modifier.width(8.dp))
      Text("I have a ChatGPT/Codex subscription", color = DimText, fontSize = 12.sp)
    }
    if (codexLoggedIn) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓ Logged in - showing real usage %", color = Color(0xFF00FF88), fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onLogoutCodex) { Text("Log out") }
      }
    } else if (codexLoginStatus != null) {
      Text(codexLoginStatus, color = NeonPink, fontSize = 12.sp)
    } else {
      OutlinedButton(onClick = onLoginCodex) { Text("Log in with ChatGPT/Codex for real usage %") }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
      value = refreshIntervalText,
      onValueChange = { new -> if (new.all { it.isDigit() }) refreshIntervalText = new },
      label = { Text("Refresh interval (seconds)") },
      modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    Button(
      onClick = {
        val interval = refreshIntervalText.toIntOrNull()?.coerceIn(10, 3600) ?: 60
        onSave(NodeFuelConfig(anthropicKey, openaiKey, anthropicSub, openaiSub, cyberMode, interval))
      }
    ) {
      Text("SAVE")
    }
  }
}

@Composable
private fun AboutSection() {
  val context = LocalContext.current
  var showLicense by remember { mutableStateOf(false) }
  fun openUrl(url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  Column(
    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Image(painter = painterResource(R.drawable.nodefuel_logo), contentDescription = null, modifier = Modifier.height(48.dp))
      Spacer(Modifier.width(12.dp))
      Column {
        Text("NodeFuel 0.1.0", color = NeonPink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Track your AI API credits from Anthropic and OpenAI", color = DimText, fontSize = 12.sp)
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
      "Website",
      color = NeonCyan,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.clickable { openUrl("https://github.com/nearcore/NodeFuel") },
    )
    Text(
      "Get Help",
      color = NeonCyan,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.clickable { openUrl("https://github.com/nearcore/NodeFuel/issues") },
    )
    Text("© 2026 Mark Nephi Javier", color = DimText, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("License: MIT", color = DimText, fontSize = 12.sp)
      Spacer(Modifier.width(12.dp))
      OutlinedButton(onClick = { showLicense = true }) { Text("Read License") }
    }
    Spacer(Modifier.height(8.dp))
    Text("Authors", color = DimText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("👤", fontSize = 12.sp)
      Spacer(Modifier.width(6.dp))
      Text("Mark Nephi Javier", color = NeonCyan, fontSize = 12.sp)
    }
  }

  if (showLicense) {
    AlertDialog(
      onDismissRequest = { showLicense = false },
      confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } },
      title = { Text("MIT License") },
      text = {
        Text(
          "Copyright (c) 2026 Mark Nephi Javier\n\n" +
            "Permission is hereby granted, free of charge, to any person obtaining a copy " +
            "of this software and associated documentation files (the \"Software\"), to deal " +
            "in the Software without restriction, including without limitation the rights " +
            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
            "copies of the Software, and to permit persons to whom the Software is " +
            "furnished to do so, subject to the following conditions:\n\n" +
            "The above copyright notice and this permission notice shall be included in " +
            "all copies or substantial portions of the Software.\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
            "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN " +
            "THE SOFTWARE.",
          fontSize = 11.sp,
        )
      },
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun MainScreenPreview() {
  NodeFuelTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
      ProviderCard("ANTHROPIC", NeonCyan, ProviderStatus("usage", sessionPercent = 12, weeklyPercent = 4))
      ProviderCard("OPENAI", NeonPink, ProviderStatus("subscription"))
    }
  }
}
