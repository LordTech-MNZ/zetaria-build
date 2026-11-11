package chat.simplex.common.views.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import chat.simplex.common.model.ChatItem

// Efectos de movimiento de Zetaria (estilo Telegram). Solo modifican el dibujo: no cambian el diseño ni la posición de nada.

// Mensajes que ya se animaron en esta sesión (para no repetir la animación al desplazarse por la lista)
private val zfxShown = HashSet<Long>()

/**
 * Mensajes recién llegados o enviados: entran desde abajo con un rebote suave, creciendo desde la esquina
 * del lado del que son (derecha si los enviaste tú, izquierda si los recibiste).
 * Los mensajes antiguos (los que ya estaban al abrir el chat) no se animan.
 */
fun Modifier.zetariaPopIn(item: ChatItem): Modifier = composed {
  val id = item.id
  val fresh = remember(id) {
    val ageMs = System.currentTimeMillis() - item.meta.createdAt.toEpochMilliseconds()
    val isNew = !zfxShown.contains(id) && ageMs in -3_000L..3_500L
    if (zfxShown.size > 4000) zfxShown.clear()
    zfxShown.add(id)
    isNew
  }
  val p = remember(id) { Animatable(if (fresh) 0f else 1f) }
  LaunchedEffect(id) {
    if (p.value < 1f) p.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f))
  }
  val v = p.value
  if (v >= 1f) {
    this
  } else {
    val sent = item.chatDir.sent
    this.graphicsLayer {
      alpha = v.coerceIn(0f, 1f)
      translationY = (1f - v) * 32.dp.toPx()
      val s = 0.9f + 0.1f * v
      scaleX = s
      scaleY = s
      transformOrigin = TransformOrigin(if (sent) 1f else 0f, 1f)
    }
  }
}

/** Botón de enviar: aparece con un pequeño rebote cuando sustituye al micrófono (como en Telegram) */
fun Modifier.zetariaPop(): Modifier = composed {
  val p = remember { Animatable(0.5f) }
  LaunchedEffect(Unit) {
    p.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 520f))
  }
  this.graphicsLayer {
    scaleX = p.value
    scaleY = p.value
    alpha = ((p.value - 0.5f) * 2f).coerceIn(0f, 1f)
  }
}
