package chat.simplex.common.views.zetaria

import androidx.compose.runtime.Composable

// Actualizaciones automáticas de Zetaria.
// Este archivo lo copia updater.py dentro del código de SimpleX y sustituye los dos marcadores
// de abajo por el número de compilación y por el repositorio de GitHub (usuario/repositorio).
object ZetariaUpdateConfig {
  const val BUILD_NUMBER = __ZETARIA_BUILD__
  const val REPO = "__ZETARIA_REPO__"
}

// Android: comprueba la última Release del repositorio y ofrece actualizar. Escritorio: no hace nada.
@Composable
expect fun ZetariaUpdateHost()
