package chat.simplex.common.views.zetaria

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Actualizaciones automáticas de Zetaria.
// Este archivo lo copia updater.py dentro del código de SimpleX y sustituye los dos marcadores
// de abajo por el número de compilación y por el repositorio de GitHub (usuario/repositorio).
object ZetariaUpdateConfig {
  const val BUILD_NUMBER = __ZETARIA_BUILD__
  const val REPO = "__ZETARIA_REPO__"
}

// Canal entre el botón «Buscar actualizaciones» de Configuración y ZetariaUpdateHost (que vive en la pantalla
// principal): cada pulsación suma 1 y el Host hace la comprobación y muestra el resultado.
object ZetariaUpdateBus {
  var checkRequests by mutableStateOf(0)
    private set

  fun requestCheck() {
    checkRequests += 1
  }
}

// Android: comprueba la última Release del repositorio y ofrece actualizar. Escritorio: no hace nada.
@Composable
expect fun ZetariaUpdateHost()
