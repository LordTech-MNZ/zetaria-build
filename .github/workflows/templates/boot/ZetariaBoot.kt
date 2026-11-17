package chat.simplex.common.views.zetaria

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import chat.simplex.common.platform.settings
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// Animación de arranque de Zetaria: fondo morado cósmico con estrellas y el símbolo del movimiento en HD.
//  - LENTA  la primera vez que se abre la app tras instalarla (o tras borrar sus datos).
//  - RÁPIDA en los arranques siguientes (cuando el sistema abre la app desde cero).
// Se muestra una sola vez por arranque del proceso: al volver a la app desde segundo plano no se repite.
object ZetariaBoot {
  private const val SEEN_KEY = "zetaria_boot_seen_v1"
  @Volatile var played = false

  fun isFirstRun(): Boolean = !settings.getBoolean(SEEN_KEY, false)
  fun markSeen() { settings.putBoolean(SEEN_KEY, true) }
}

private val BASE = Color(0xFF12062A) // mismo color que el fondo de la pantalla de inicio del sistema
private val TINTS = listOf(Color(0xFFFFFFFF), Color(0xFFE6D9FF), Color(0xFFFFD6F2), Color(0xFFCFE0FF))
private const val TWO_PI = 6.2831855f

private class BootStar(
  val x: Float, val y: Float, val r: Float, val phase: Float, val k: Int,
  val base: Float, val tint: Color, val big: Boolean
)

private val STARS: List<BootStar> by lazy {
  val rnd = Random(20260228)
  List(190) { i ->
    val big = i % 16 == 0
    BootStar(
      x = rnd.nextFloat(), y = rnd.nextFloat(),
      r = if (big) 1.5f + rnd.nextFloat() * 1.3f else 0.45f + rnd.nextFloat() * 0.95f,
      phase = rnd.nextFloat() * TWO_PI, k = 1 + rnd.nextInt(3),
      base = 0.4f + rnd.nextFloat() * 0.6f,
      tint = TINTS[rnd.nextInt(TINTS.size)], big = big
    )
  }
}

private fun seg(p: Float, a: Float, b: Float): Float = ((p - a) / (b - a)).coerceIn(0f, 1f)

private fun easeOutBack(x: Float, c1: Float): Float {
  val c3 = c1 + 1f
  val t = x - 1f
  return 1f + c3 * t * t * t + c1 * t * t
}

private fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)

@Composable
fun ZetariaBootOverlay() {
  val play = remember {
    val p = !ZetariaBoot.played
    ZetariaBoot.played = true
    p
  }
  val slow = remember {
    val s = play && ZetariaBoot.isFirstRun()
    if (s) ZetariaBoot.markSeen()
    s
  }
  var done by remember { mutableStateOf(false) }
  if (play && !done) {
    ZetariaBootContent(slow) { done = true }
  }
}

@Composable
private fun ZetariaBootContent(slow: Boolean, onDone: () -> Unit) {
  val total = if (slow) 4800 else 1150
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    progress.animateTo(1f, tween(total, easing = LinearEasing))
    onDone()
  }
  val twinkle by rememberInfiniteTransition().animateFloat(
    initialValue = 0f,
    targetValue = TWO_PI,
    animationSpec = infiniteRepeatable(tween(if (slow) 7000 else 3500, easing = LinearEasing))
  )

  val p = progress.value
  val bgIn = easeInOut(seg(p, 0f, if (slow) 0.18f else 0.14f))
  val logoStart = if (slow) 0.08f else 0.05f
  val logoIn = seg(p, logoStart, if (slow) 0.46f else 0.38f)
  val out = easeInOut(seg(p, if (slow) 0.80f else 0.72f, 1f))
  val overlayAlpha = 1f - out
  val startScale = if (slow) 0.55f else 0.86f
  val logoScale = (startScale + (1f - startScale) * easeOutBack(logoIn, if (slow) 1.5f else 1.1f)) *
    (1f + 0.07f * out) * (1f + (if (slow) 0.012f else 0f) * sin(twinkle))
  val logoAlpha = easeInOut(logoIn)

  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .graphicsLayer { alpha = overlayAlpha }
      .pointerInput(Unit) { detectTapGestures { } } // no deja pasar toques mientras dura la animación
  ) {
    val logoSize = minOf(maxWidth * 0.68f, 340.dp)

    Canvas(Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val c = Offset(w / 2f, h / 2f)
      val d = density
      drawRect(BASE)
      // Resplandor morado central
      drawRect(
        Brush.radialGradient(
          listOf(Color(0xFF5A2BA3).copy(alpha = 0.85f * bgIn), Color(0xFF2A1058).copy(alpha = 0.7f * bgIn), Color.Transparent),
          center = c, radius = max(w, h) * 0.75f
        )
      )
      // Nebulosas (magenta arriba a la izquierda, azul-violeta abajo a la derecha)
      val n1 = Offset(w * 0.12f, h * 0.18f)
      drawCircle(
        Brush.radialGradient(listOf(Color(0xFFC03CA6).copy(alpha = 0.30f * bgIn), Color.Transparent), center = n1, radius = w * 0.8f),
        radius = w * 0.8f, center = n1
      )
      val n2 = Offset(w * 0.9f, h * 0.82f)
      drawCircle(
        Brush.radialGradient(listOf(Color(0xFF3B3FD0).copy(alpha = 0.26f * bgIn), Color.Transparent), center = n2, radius = w * 0.85f),
        radius = w * 0.85f, center = n2
      )
      // Estrellas (detrás del símbolo)
      for (s in STARS) {
        val a = (s.base * (0.6f + 0.4f * sin(s.phase + s.k * twinkle)) * bgIn).coerceIn(0f, 1f)
        val sx = (s.x + 0.003f * sin(s.phase + twinkle)) * w
        val sy = s.y * h
        val rr = s.r * d
        if (s.big) {
          drawCircle(
            Brush.radialGradient(listOf(s.tint.copy(alpha = a * 0.5f), Color.Transparent), center = Offset(sx, sy), radius = rr * 5f),
            radius = rr * 5f, center = Offset(sx, sy)
          )
        }
        drawCircle(s.tint.copy(alpha = a), radius = rr, center = Offset(sx, sy))
      }
      // Estrella fugaz (solo en la animación lenta)
      if (slow) {
        val t = seg(p, 0.42f, 0.62f)
        if (t > 0f && t < 1f) {
          val dir = Offset(-0.8f, 0.6f)
          val head = Offset(w * 0.95f, h * 0.08f) + Offset(dir.x * w * 0.9f * t, dir.y * w * 0.9f * t)
          val tail = Offset(head.x - dir.x * w * 0.22f, head.y - dir.y * w * 0.22f)
          drawLine(
            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * (1f - t))), start = tail, end = head),
            start = tail, end = head, strokeWidth = 2.2f * d, cap = StrokeCap.Round
          )
        }
      }
      // Halo detrás del símbolo
      val glowR = logoSize.toPx() * 0.95f
      drawCircle(
        Brush.radialGradient(
          listOf(Color(0xFFB04BFF).copy(alpha = (0.50f + 0.12f * sin(twinkle)) * logoIn), Color(0xFF7A2FD0).copy(alpha = 0.22f * logoIn), Color.Transparent),
          center = c, radius = glowR
        ),
        radius = glowR, center = c
      )
      // Ondas que salen del símbolo al aparecer
      val ringCount = if (slow) 2 else 1
      for (i in 0 until ringCount) {
        val rp = seg(p, logoStart + 0.12f * i, logoStart + (if (slow) 0.42f else 0.30f) + 0.12f * i)
        if (rp > 0f && rp < 1f) {
          drawCircle(
            Color(0xFFD9B8FF).copy(alpha = 0.55f * (1f - rp)),
            radius = logoSize.toPx() * (0.46f + 0.95f * rp), center = c,
            style = Stroke(width = 2f * d)
          )
        }
      }
    }

    // Símbolo del movimiento en HD (imagen de 1536 px)
    Image(
      painterResource(MR.images.zetaria_boot),
      contentDescription = null,
      modifier = Modifier
        .align(Alignment.Center)
        .size(logoSize)
        .graphicsLayer {
          alpha = logoAlpha
          scaleX = logoScale
          scaleY = logoScale
        },
      contentScale = ContentScale.Fit
    )
  }
}
