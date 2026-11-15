package chat.simplex.common.views.zetaria

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalUriHandler
import java.security.SecureRandom

actual fun zetariaRandomToken(length: Int): String {
  val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  val random = SecureRandom()
  val sb = StringBuilder()
  for (i in 0 until length) {
    sb.append(alphabet[random.nextInt(alphabet.length)])
  }
  return sb.toString()
}

// En escritorio la llamada se abre en el navegador
@Composable
actual fun ZetariaGroupCallScreen(url: String, displayName: String, close: () -> Unit) {
  val uriHandler = LocalUriHandler.current
  LaunchedEffect(Unit) {
    uriHandler.openUri(url)
    close()
  }
}
