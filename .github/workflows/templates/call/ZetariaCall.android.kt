package chat.simplex.common.views.zetaria

import android.Manifest
import android.net.Uri
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chat.simplex.common.helpers.applyAppLocale
import chat.simplex.common.helpers.showAllowPermissionInSettingsAlert
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.mainActivity
import chat.simplex.res.MR
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import dev.icerock.moko.resources.compose.painterResource
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

@Composable
actual fun ZetariaGroupCallScreen(url: String, displayName: String, close: () -> Unit) {
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
  val permissions = rememberMultiplePermissionsState(
    listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
  )
  val webViewHolder = remember { mutableStateOf<WebView?>(null) }

  LaunchedEffect(Unit) {
    permissions.launchMultiplePermissionRequest()
  }
  DisposableEffect(Unit) {
    onDispose {
      // Cierra la llamada (libera micrófono y cámara) al salir de la pantalla
      val wv = webViewHolder.value
      if (wv != null) {
        wv.loadUrl("about:blank")
        wv.destroy()
        webViewHolder.value = null
      }
    }
  }

  // Ajustes que Jitsi lee de la parte «#» de la dirección: sin pantalla previa, sin aviso de «usa la app» y con tu nombre
  val callUrl = url + "#config.prejoinConfig.enabled=false&config.disableDeepLinking=true" +
    "&userInfo.displayName=%22" + Uri.encode(displayName) + "%22"
  val allowedHost = Uri.parse(url).host

  Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
    Row(
      Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = close) {
        Icon(painterResource(MR.images.ic_close), "Salir de la llamada", tint = Color.White)
      }
      Text(
        "Llamada grupal",
        color = Color.White,
        maxLines = 1,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
      )
      IconButton(onClick = { uriHandler.openUri(url) }) {
        Icon(painterResource(MR.images.ic_open_in_new), "Abrir en el navegador", tint = Color.White)
      }
    }

    if (audioPermission.status is PermissionStatus.Granted) {
      AndroidView(
        modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
        factory = { ctx ->
          val wv = WebView(ctx)
          // Crear un WebView borra el idioma elegido en la app: se vuelve a aplicar
          mainActivity.get()?.applyAppLocale(appPrefs.appLanguage)
          wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          wv.setBackgroundColor(android.graphics.Color.BLACK)
          wv.keepScreenOn = true
          val s = wv.settings
          s.javaScriptEnabled = true
          s.domStorageEnabled = true
          s.mediaPlaybackRequiresUserGesture = false
          s.setSupportMultipleWindows(false)
          wv.webViewClient = WebViewClient()
          wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
              // Micrófono y cámara solo para el servidor de la llamada
              if (request.origin.host == allowedHost) request.grant(request.resources) else request.deny()
            }
          }
          wv.loadUrl(callUrl)
          webViewHolder.value = wv
          wv
        }
      )
    } else {
      Column(
        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          "Zetaria necesita acceso al micrófono (y a la cámara, si quieres video) para la llamada.",
          color = Color.White,
          textAlign = TextAlign.Center
        )
        TextButton(onClick = { permissions.launchMultiplePermissionRequest() }) { Text("Permitir") }
        TextButton(onClick = { context.showAllowPermissionInSettingsAlert() }) { Text("Abrir ajustes de la app") }
      }
    }
  }
}
