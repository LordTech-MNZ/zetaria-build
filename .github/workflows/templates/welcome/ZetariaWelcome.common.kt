package chat.simplex.common.views.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import kotlin.random.Random

/**
 * Bienvenida de Zetaria.
 *
 * - Android: la página HTML del voto (index integrado) dentro de un WebView. El HTML avisa a
 *   la app con el puente JS `ZetariaNative.votoEnviado(evento)` cuando el voto se envió (o ya
 *   estaba enviado) y entonces la app avanza al siguiente paso de la bienvenida.
 * - Escritorio: se mantiene el diseño cósmico clásico como respaldo (ZetariaWelcomeFallback).
 *
 * El código de cada plataforma está en ZetariaWelcome.android.kt / ZetariaWelcome.desktop.kt.
 */
@Composable
fun ZetariaWelcome(user: User?, onboardingStage: SharedPreference<OnboardingStage>) {
  ZetariaWelcomeHost(user, onboardingStage)
}

@Composable
expect fun ZetariaWelcomeHost(user: User?, onboardingStage: SharedPreference<OnboardingStage>)

// Respaldo de la bienvenida: diseño cósmico anterior. Se usa en escritorio y, en Android, solo
// si el WebView no pudiera mostrarse (así la app nunca se queda inutilizable).
@Composable
fun ZetariaWelcomeFallback(user: User?, onboardingStage: SharedPreference<OnboardingStage>) {
  Box(Modifier.fillMaxSize()) {
    CosmicBackground()
    Column(
      Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = DEFAULT_ONBOARDING_HORIZONTAL_PADDING),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(Modifier.padding(top = DEFAULT_PADDING).widthIn(max = 185.dp), contentAlignment = Alignment.Center) {
        Image(
          painter = painterResource(MR.images.zetaria_logo_light),
          contentDescription = null,
          contentScale = ContentScale.FillWidth,
          modifier = Modifier.fillMaxWidth()
        )
      }
      Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          stringResource(MR.strings.zetaria_welcome_title),
          style = MaterialTheme.typography.h1,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          textAlign = TextAlign.Center,
          lineHeight = 42.sp,
          modifier = Modifier.padding(top = DEFAULT_PADDING)
        )
        Text(
          stringResource(MR.strings.zetaria_welcome_text),
          style = MaterialTheme.typography.body1,
          color = Color(0xFFE9DDFF),
          textAlign = TextAlign.Start,
          lineHeight = 22.sp,
          modifier = Modifier.padding(top = DEFAULT_PADDING)
        )
        Text(
          stringResource(MR.strings.zetaria_welcome_credit),
          style = MaterialTheme.typography.caption,
          color = Color(0xFFB9A6E0),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(vertical = DEFAULT_PADDING)
        )
      }
      Column(Modifier.widthIn(max = 450.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OnboardingActionButton(user, onboardingStage)
        TextButtonBelowOnboardingButton(
          stringResource(MR.strings.why_simplex_is_built),
          icon = painterResource(MR.images.ic_info),
          onClick = { ModalManager.fullscreen.showModal { HowItWorks(user, onboardingStage) } }
        )
      }
    }
  }
}

// Fondo cósmico: degradado morado oscuro, nebulosas y estrellas que titilan.
@Composable
fun CosmicBackground() {
  val transition = rememberInfiniteTransition()
  val twinkle by transition.animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse)
  )
  Canvas(Modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF080118), Color(0xFF1E0845), Color(0xFF34105E), Color(0xFF0E0224))))
    val c1 = Offset(w * 0.85f, h * 0.2f)
    drawCircle(Brush.radialGradient(listOf(Color(0x668E2DE2), Color(0x00000000)), center = c1, radius = w * 0.8f), radius = w * 0.8f, center = c1)
    val c2 = Offset(w * 0.1f, h * 0.7f)
    drawCircle(Brush.radialGradient(listOf(Color(0x55C026D3), Color(0x00000000)), center = c2, radius = w * 0.7f), radius = w * 0.7f, center = c2)
    val rnd = Random(770)
    for (i in 0 until 170) {
      val x = rnd.nextFloat() * w
      val y = rnd.nextFloat() * h
      val r = (0.6f + rnd.nextFloat() * 1.8f) * density
      val base = 0.35f + rnd.nextFloat() * 0.65f
      val blink = if (i % 3 == 0) twinkle else 1f
      drawCircle(Color.White.copy(alpha = (base * blink).coerceIn(0f, 1f)), radius = r, center = Offset(x, y))
    }
  }
}
