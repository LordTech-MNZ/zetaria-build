package chat.simplex.common.views.zetaria

import SectionDividerSpaced
import SectionItemView
import SectionItemViewSpaceBetween
import SectionTextFooter
import SectionView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import chat.simplex.common.model.ChatType
import chat.simplex.common.model.ComposedMessage
import chat.simplex.common.model.GroupInfo
import chat.simplex.common.model.MsgContent
import chat.simplex.common.platform.ColumnWithScrollBar
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.settings
import chat.simplex.common.views.helpers.AppBarTitle
import chat.simplex.common.views.helpers.ModalManager
import chat.simplex.common.views.helpers.ModalView
import chat.simplex.common.views.helpers.withBGApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Llamadas grupales de Zetaria.
 *
 * SimpleX solo tiene llamadas 1 a 1, así que las llamadas grupales usan Jitsi Meet (WebRTC con servidor):
 *   1. El botón de llamada de la barra del grupo pide un servidor (por defecto el público, solo para pruebas),
 *      crea una sala con nombre aleatorio «Zetaria-xxxxxxxxxxxxxxxxxxxx» y publica el enlace en el grupo.
 *   2. Cualquier miembro que toque el enlace en Zetaria entra a la llamada DENTRO de la app (en Android).
 *      Con SimpleX original el enlace abre el navegador.
 *   3. Dentro van los controles de Jitsi: silenciar, cámara, mano alzada, participantes, chat...
 *
 * Quién ve qué: el servidor de llamadas ve quién se conecta y por él pasa el audio y el video (sin cifrado de
 * extremo a extremo). Para uso real conviene tu propio servidor Jitsi: basta cambiar la dirección aquí.
 */

// Texto aleatorio con generador criptográfico (para el nombre de la sala)
expect fun zetariaRandomToken(length: Int): String

// Pantalla de la llamada. Android: WebView con Jitsi. Escritorio: abre el navegador.
@Composable
expect fun ZetariaGroupCallScreen(url: String, displayName: String, close: () -> Unit)

object ZetariaGroupCall {
  private const val SERVER_KEY = "zetaria_call_server"
  private const val BROWSER_KEY = "zetaria_call_browser"
  const val DEFAULT_SERVER = "https://meet.jit.si"
  private val linkRegex = Regex("^https://[^/\\s]+/Zetaria-[A-Za-z0-9]{16,}(?:[/?#].*)?$")
  private val serverRegex = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")

  fun server(): String {
    val saved = settings.getString(SERVER_KEY, DEFAULT_SERVER).trim()
    return if (saved.isEmpty()) DEFAULT_SERVER else saved
  }

  fun setServer(value: String) {
    settings.putString(SERVER_KEY, value)
  }

  // Si está activado, las llamadas se abren en el navegador del teléfono en vez de dentro de la app (más compatible)
  fun useBrowser(): Boolean = settings.getBoolean(BROWSER_KEY, false)

  fun setUseBrowser(value: Boolean) {
    settings.putBoolean(BROWSER_KEY, value)
  }

  // «meet.ejemplo.com/» -> «https://meet.ejemplo.com». Devuelve null si no es una dirección https válida.
  fun normalizeServer(input: String): String? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (s.startsWith("http://")) return null
    if (!s.startsWith("https://")) s = "https://" + s
    return if (serverRegex.matches(s)) s else null
  }

  // ¿Es un enlace de llamada grupal de Zetaria? (cualquier servidor; sala «Zetaria-» + 16 o más caracteres)
  fun isCallLink(uri: String): Boolean = linkRegex.matches(uri.trim())

  fun newRoomLink(server: String): String = server + "/Zetaria-" + zetariaRandomToken(20)

  // Abre la pantalla de inicio de llamada del grupo
  fun start(groupInfo: GroupInfo) {
    val rhId = chatModel.remoteHostId()
    ModalManager.end.showCustomModal { close ->
      ZetariaGroupCallStartView(rhId, groupInfo, close)
    }
  }

  // Entra a una llamada (a pantalla completa)
  fun join(url: String) {
    val name = chatModel.currentUser.value?.displayName ?: "Zetaria"
    ModalManager.fullscreen.showCustomModal { close ->
      ModalView(close = close, showAppBar = false, background = Color.Black) {
        ZetariaGroupCallScreen(url, name, close)
      }
    }
  }

  // Publica el enlace de la llamada en el grupo
  fun announce(rhId: Long?, groupInfo: GroupInfo, link: String) {
    val text = "📞 Llamada grupal: toca el enlace para unirte\n" + link
    withBGApi {
      try {
        val items = chatModel.controller.apiSendMessages(
          rh = rhId,
          type = ChatType.Group,
          id = groupInfo.apiId,
          scope = null,
          composedMessages = listOf(ComposedMessage(null, null, MsgContent.MCText(text), emptyMap()))
        )
        if (!items.isNullOrEmpty()) {
          items.forEach { aChatItem ->
            withContext(Dispatchers.Main) {
              chatModel.chatsContext.addChatItem(rhId, aChatItem.chatInfo, aChatItem.chatItem)
            }
          }
        }
      } catch (e: Exception) {
        // si el aviso no sale, quien llamó sigue en la sala y puede compartir el enlace a mano
      }
    }
  }
}

@Composable
fun ZetariaGroupCallStartView(rhId: Long?, groupInfo: GroupInfo, close: () -> Unit) {
  var serverText by remember { mutableStateOf(ZetariaGroupCall.server()) }
  var error by remember { mutableStateOf<String?>(null) }
  var browser by remember { mutableStateOf(ZetariaGroupCall.useBrowser()) }

  ModalView(close = close, cardScreen = true) {
    ColumnWithScrollBar {
      AppBarTitle("Llamada grupal")

      SectionView("Servidor de llamadas") {
        SectionItemView {
          OutlinedTextField(
            value = serverText,
            onValueChange = { serverText = it; error = null },
            singleLine = true,
            isError = error != null,
            label = { Text("Dirección del servidor (Jitsi)") },
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
      SectionTextFooter(
        error ?: "Por defecto se usa el servidor público de Jitsi, solo para pruebas: quien lo opera ve quién se conecta, y el audio y el video pasan por él sin cifrado de extremo a extremo. Para uso real, escribe aquí la dirección de tu propio servidor."
      )
      SectionDividerSpaced()

      SectionView {
        SectionItemViewSpaceBetween(null) {
          Text("Abrir la llamada en el navegador")
          Switch(
            checked = browser,
            onCheckedChange = {
              browser = it
              ZetariaGroupCall.setUseBrowser(it)
            }
          )
        }
        SectionItemView({
          val server = ZetariaGroupCall.normalizeServer(serverText)
          if (server == null) {
            error = "Escribe una dirección https válida, por ejemplo https://meet.jit.si"
          } else {
            ZetariaGroupCall.setServer(server)
            val link = ZetariaGroupCall.newRoomLink(server)
            ZetariaGroupCall.announce(rhId, groupInfo, link)
            close()
            // se espera a que termine la animación de cierre: crear la pantalla de la llamada mientras otra pantalla
            // se mueve puede dejarla en negro
            withBGApi {
              delay(400)
              withContext(Dispatchers.Main) { ZetariaGroupCall.join(link) }
            }
          }
        }) {
          Text("Iniciar llamada y avisar al grupo", color = MaterialTheme.colors.primary)
        }
        SectionItemView({
          serverText = ZetariaGroupCall.DEFAULT_SERVER
          error = null
        }) {
          Text("Usar el servidor público de pruebas")
        }
      }
      SectionTextFooter("Se publicará en el grupo un enlace a una sala nueva; quien lo toque en Zetaria entrará a la llamada. Si la llamada se ve en negro dentro de la app, activa «Abrir la llamada en el navegador».")
    }
  }
}
