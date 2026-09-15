package com.example.nodefuel.ui.main

import android.content.Intent
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

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
        text = "daily resets ${formatCountdown(status.resetsAtMillis)}",
        color = Color(0xFF555555),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
      )
    }
    if (status.mode == "usage" && status.weeklyResetsAtMillis != null) {
      Text(
        text = "weekly resets ${formatCountdown(status.weeklyResetsAtMillis)}",
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

// "in Xd Yh" / "in Xh Ym" / "in Xm" / "resetting now" - same shape as the
// desktop widget's live countdown (day-scale needed for the weekly reset,
// which can be days out), computed once per fetch rather than ticking
// continuously (this screen re-renders on refresh, not idle).
private fun formatCountdown(resetsAtMillis: Long): String {
  val diffMs = resetsAtMillis - System.currentTimeMillis()
  if (diffMs <= 0) return "resetting now"
  val diffMin = diffMs / 60_000
  val d = diffMin / 1440
  val h = (diffMin % 1440) / 60
  val m = diffMin % 60
  return when {
    d > 0 -> "in ${d}d ${h}h"
    h > 0 -> "in ${h}h ${m}m"
    else -> "in ${m}m"
  }
}

// One lightning "surge" slot - ported field-for-field from StatusRing.qml's
// `surges` entries. Plain mutable fields, not Compose State: they're only
// ever mutated from the single LaunchedEffect tick loop below, and read
// from the Canvas draw phase, which already redraws every frame because it
// reads the animated `lightAngle` state - no separate observability needed.
private class LightningSlot {
  var active = false
  var points: List<Offset> = emptyList()
  var startTime = 0L
  var growDuration = 0L
  var fadeDuration = 0L
  var nextSpawnAt = 0L
}

// 0 (dim rim / facing away from the light) - 1 (bright, facing the light).
// Direct port of StatusRing.qml's surfaceBrightness().
private fun surfaceBrightness(p: Offset, cx: Float, cy: Float, r: Float, lightAngle: Float): Float {
  val dx = p.x - cx
  val dy = p.y - cy
  val d = kotlin.math.sqrt(dx * dx + dy * dy) / r
  val limb = kotlin.math.sqrt((1f - d * d * 0.85f).coerceAtLeast(0f))
  val lightDot = cos(atan2(dy, dx) - lightAngle)
  val lightBias = 0.55f + 0.45f * lightDot.coerceAtLeast(0f)
  return limb * lightBias
}

// Keeps a point within an outer band so surges hug the sphere's surface
// and stay clear of the centered percentage text. Port of clampAnnulus().
private fun clampAnnulus(x: Float, y: Float, cx: Float, cy: Float, minR: Float, maxR: Float): Offset {
  val dx = x - cx
  val dy = y - cy
  val d = kotlin.math.sqrt(dx * dx + dy * dy).let { if (it == 0f) 1f else it }
  val clamped = d.coerceIn(minR, maxR)
  return Offset(cx + dx / d * clamped, cy + dy / d * clamped)
}

// A jagged 3-4 segment zigzag path between two random points on the sphere.
// Port of makePath().
private fun makeSurgePath(cx: Float, cy: Float, r: Float): List<Offset> {
  val minR = r * 0.5f
  val maxR = r * 0.92f
  val a1 = (Math.random() * Math.PI * 2).toFloat()
  val a2 = a1 + (Math.PI * (0.4 + Math.random() * 0.9)).toFloat()
  val x0 = cx + r * cos(a1) * 0.85f
  val y0 = cy + r * sin(a1) * 0.85f
  val x1 = cx + r * cos(a2) * 0.85f
  val y1 = cy + r * sin(a2) * 0.85f

  val segments = 3 + (Math.random() * 2).toInt()
  val points = mutableListOf(Offset(x0, y0))
  val dx = x1 - x0
  val dy = y1 - y0
  val len = kotlin.math.sqrt(dx * dx + dy * dy).let { if (it == 0f) 1f else it }
  val nx = -dy / len
  val ny = dx / len

  for (i in 1 until segments) {
    val t = i.toFloat() / segments
    val mx = x0 + dx * t
    val my = y0 + dy * t
    val offset = ((Math.random() - 0.5) * r * 0.3).toFloat()
    points.add(clampAnnulus(mx + nx * offset, my + ny * offset, cx, cy, minR, maxR))
  }
  points.add(Offset(x1, y1))
  return points
}

private fun spawnSurge(slot: LightningSlot, now: Long, cx: Float, cy: Float, r: Float) {
  slot.active = true
  slot.points = makeSurgePath(cx, cy, r)
  slot.startTime = now
  slot.growDuration = (150 + Math.random() * 250).toLong()
  slot.fadeDuration = (150 + Math.random() * 200).toLong()
}

// Progressively reveals each active slot's path, jitters it fresh every
// call (this runs once per drawn frame, same as the desktop's per-tick
// re-roll), and strokes a wide translucent glow pass plus a thin bright
// core pass. Port of drawSurge().
private fun DrawScope.drawLightningSurges(slots: List<LightningSlot>, accent: Color, cx: Float, cy: Float, r: Float, lightAngle: Float) {
  val now = System.currentTimeMillis()
  for (slot in slots) {
    if (!slot.active) continue
    val elapsed = now - slot.startTime
    val growT = (elapsed.toFloat() / slot.growDuration).coerceIn(0f, 1f)
    val totalSegs = slot.points.size - 1
    val revealSegs = growT * totalSegs
    val fullIdx = revealSegs.toInt().coerceAtMost(totalSegs)
    val frac = revealSegs - fullIdx

    val drawPoints = slot.points.subList(0, fullIdx + 1).toMutableList()
    if (fullIdx < totalSegs) {
      val p0 = slot.points[fullIdx]
      val p1 = slot.points[fullIdx + 1]
      drawPoints.add(Offset(p0.x + (p1.x - p0.x) * frac, p0.y + (p1.y - p0.y) * frac))
    }
    if (drawPoints.size < 2) continue

    var fadeAlpha = 1f
    if (elapsed > slot.growDuration) {
      fadeAlpha = (1f - (elapsed - slot.growDuration).toFloat() / slot.fadeDuration).coerceIn(0f, 1f)
    }
    if (fadeAlpha <= 0f) continue

    val midIdx = drawPoints.size / 2
    val brightness = surfaceBrightness(drawPoints[midIdx], cx, cy, r, lightAngle) * fadeAlpha

    val jitter = r * 0.05f
    val jittered = drawPoints.map { p -> Offset(p.x + ((Math.random() - 0.5) * jitter).toFloat(), p.y + ((Math.random() - 0.5) * jitter).toFloat()) }

    val path = Path().apply {
      moveTo(jittered[0].x, jittered[0].y)
      for (i in 1 until jittered.size) lineTo(jittered[i].x, jittered[i].y)
    }

    val glowWidth = (r * 0.09f).coerceAtLeast(1.1f)
    val coreWidth = (r * 0.032f).coerceAtLeast(0.5f)

    drawPath(path, color = accent, alpha = 0.45f * brightness, style = Stroke(width = glowWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(
      path,
      color = Color.White,
      alpha = (0.3f + 0.65f * brightness).coerceIn(0f, 1f),
      style = Stroke(width = coreWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }
}

// Qt.lighter()/Qt.darker() work in HSV space, scaling the V channel by a
// factor - these mirror that so the sphere's gradient stops match the
// desktop widget's exactly, not an approximation.
private fun Color.qtLighter(factor: Float): Color {
  val hsv = FloatArray(3)
  AndroidColor.colorToHSV(this.toArgb(), hsv)
  hsv[2] = (hsv[2] * factor).coerceAtMost(1f)
  return Color(AndroidColor.HSVToColor(hsv))
}

private fun Color.qtDarker(factor: Float): Color {
  val hsv = FloatArray(3)
  AndroidColor.colorToHSV(this.toArgb(), hsv)
  hsv[2] = hsv[2] / factor
  return Color(AndroidColor.HSVToColor(hsv))
}

@Composable
private fun FuelRing(accent: Color, status: ProviderStatus, cyberMode: Boolean = false) {
  val active = cyberMode && status.mode != "none"
  val density = LocalDensity.current
  val ringPx = with(density) { 76.dp.toPx() }
  val cx = ringPx / 2f
  val cy = ringPx / 2f
  val sphereR = ringPx / 2f - with(density) { 4.dp.toPx() }

  // Light source angle - one full turn every 13s, matching the desktop's
  // "very slowly spinning sphere" illusion. Drives the gradient focal
  // point, the specular highlight, and the lightning's brightness bias in
  // sync, exactly like StatusRing.qml's `lightAngle` property.
  val lightAngleTransition = rememberInfiniteTransition(label = "cyberLight")
  val lightAngleDeg by
    lightAngleTransition.animateFloat(
      initialValue = 225f,
      targetValue = 225f + 360f,
      animationSpec = infiniteRepeatable(animation = tween(13000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
      label = "lightAngle",
    )
  val lightAngle = Math.toRadians(lightAngleDeg.toDouble()).toFloat()

  // 4 independent lightning surge slots, ticking ~25fps - port of the
  // desktop's Canvas+Timer tick()/spawn() pair. The Canvas below redraws
  // every frame anyway (it reads the animated lightAngle), so this loop
  // only needs to own the spawn/expire state machine.
  val slots = remember { List(4) { LightningSlot() } }
  LaunchedEffect(active) {
    if (!active) return@LaunchedEffect
    val now0 = System.currentTimeMillis()
    slots.forEachIndexed { i, s -> s.nextSpawnAt = now0 + i * 120 + (Math.random() * 200).toLong() }
    while (true) {
      delay(40)
      val now = System.currentTimeMillis()
      for (s in slots) {
        if (!s.active) {
          if (now >= s.nextSpawnAt) spawnSurge(s, now, cx, cy, sphereR)
        } else if (now - s.startTime > s.growDuration + s.fadeDuration) {
          s.active = false
          s.nextSpawnAt = now + 120 + (Math.random() * 500).toLong()
        }
      }
      if (slots.all { !it.active }) spawnSurge(slots[0], now, cx, cy, sphereR)
    }
  }

  Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val strokeWidth = 6.dp.toPx()
      val center = Offset(size.width / 2f, size.height / 2f)

      if (active) {
        // True radial-gradient sphere: bright off-center highlight fading
        // through the accent color to a near-black limb, focal point
        // sliding with lightAngle - port of StatusRing.qml's RadialGradient.
        val focal = Offset(center.x + cos(lightAngle) * sphereR * 0.45f, center.y + sin(lightAngle) * sphereR * 0.45f)
        drawCircle(
          brush =
            Brush.radialGradient(
              colorStops =
                arrayOf(
                  0.0f to accent.qtLighter(2.1f),
                  0.35f to accent,
                  0.75f to accent.qtDarker(3.5f),
                  1.0f to Color(0xFF020204),
                ),
              center = focal,
              radius = sphereR,
            ),
          radius = sphereR,
          center = center,
        )

        // Specular glint - 3 nested, shrinking, increasingly-opaque white
        // circles offset toward the light, faking a soft glossy highlight.
        val gx = center.x + cos(lightAngle) * (ringPx * 0.22f)
        val gy = center.y + sin(lightAngle) * (ringPx * 0.22f)
        for ((glintSize, alpha) in listOf(0.20f to 0.10f, 0.13f to 0.18f, 0.07f to 0.30f)) {
          drawCircle(color = Color.White.copy(alpha = alpha), radius = ringPx * glintSize / 2f, center = Offset(gx, gy))
        }

        drawLightningSurges(slots, accent, cx, cy, sphereR, lightAngle)
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
    }
    val centerText =
      when {
        status.mode == "api" -> "$"
        status.mode == "usage" && status.sessionPercent != null -> "${100 - status.sessionPercent}%"
        status.mode == "subscription" -> "⚡"
        else -> "–"
      }
    if (active) {
      // Cheap 4-direction-ish outline so the percentage stays legible over
      // the lightning - port of StatusRing.qml's offset black duplicate.
      Text(
        text = centerText,
        color = Color.Black.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier.offset(x = 1.dp, y = 1.dp),
      )
    }
    Text(
      text = centerText,
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
