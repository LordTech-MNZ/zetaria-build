package chat.simplex.common.views.zetaria

import android.Manifest
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
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

  // Modo navegador: por el ajuste de la pantalla «Llamada grupal», o porque eres el anfitrión de una sala en un
  // servidor que exige iniciar sesión para crearla (el inicio de sesión no funciona dentro de la app)
  val browserMode = remember {
    ZetariaGroupCall.useBrowser() || (ZetariaGroupCall.isHostRoom(url) && ZetariaGroupCall.needsHostLogin(url))
  }
  if (browserMode) {
    LaunchedEffect(Unit) {
      uriHandler.openUri(url)
      close()
    }
    return
  }

  val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
  val permissions = rememberMultiplePermissionsState(
    listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
  )
  val webViewHolder = remember { mutableStateOf<WebView?>(null) }
  val mounted = remember { mutableStateOf(false) }
  val lastError = remember { mutableStateOf<String?>(null) }
  val showHelp = remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    permissions.launchMultiplePermissionRequest()
  }
  // El WebView se crea un momento después de abrir la pantalla: si se crea mientras la pantalla se está animando,
  // puede quedarse en negro
  LaunchedEffect(Unit) {
    delay(450)
    mounted.value = true
  }
  // Si pasados unos segundos la página sigue vacía (negro), se ofrece abrirla en el navegador
  LaunchedEffect(mounted.value) {
    if (!mounted.value) return@LaunchedEffect
    delay(12000)
    val wv = webViewHolder.value
    if (wv == null) {
      showHelp.value = true
    } else {
      wv.evaluateJavascript(
        "(function(){var r=document.getElementById('react');return r?r.children.length:-1;})()"
      ) { value ->
        if (value == null || value == "0" || value == "-1" || value == "null") showHelp.value = true
      }
    }
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

    Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
      if (audioPermission.status is PermissionStatus.Granted) {
        if (mounted.value) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
              val wv = WebView(ctx)
              // Crear un WebView borra el idioma elegido en la app: se vuelve a aplicar
              mainActivity.get()?.applyAppLocale(appPrefs.appLanguage)
              wv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
              )
              wv.setBackgroundColor(android.graphics.Color.BLACK)
              wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
              wv.keepScreenOn = true
              val s = wv.settings
              s.javaScriptEnabled = true
              s.domStorageEnabled = true
              s.databaseEnabled = true
              s.mediaPlaybackRequiresUserGesture = false
              s.javaScriptCanOpenWindowsAutomatically = true
              s.setSupportMultipleWindows(false)
              // Jitsi (y los inicios de sesión de terceros) tratan distinto a un «WebView»: se presenta como un Chrome normal
              s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
              val cookies = CookieManager.getInstance()
              cookies.setAcceptCookie(true)
              cookies.setAcceptThirdPartyCookies(wv, true)
              wv.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                  if (request != null && request.isForMainFrame && error != null) {
                    lastError.value = "No se pudo cargar la página (" + error.errorCode + "): " + error.description
                    showHelp.value = true
                  }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                  if (request != null && request.isForMainFrame && errorResponse != null && errorResponse.statusCode >= 400) {
                    lastError.value = "El servidor respondió con el código " + errorResponse.statusCode
                    showHelp.value = true
                  }
                }
              }
              wv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                  // Micrófono y cámara solo para el servidor de la llamada
                  if (request.origin.host == allowedHost) request.grant(request.resources) else request.deny()
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                  val text = consoleMessage.message()
                  if (text.contains("membersOnly")) {
                    // Jitsi no deja crear/entrar a la sala sin que el anfitrión la abra antes e inicie sesión
                    lastError.value = "Esta sala todavía no está abierta: el anfitrión tiene que entrar primero (en meet.jit.si debe iniciar sesión y abrirla en el navegador). Inténtalo de nuevo en un momento."
                    showHelp.value = true
                  } else if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR && lastError.value == null) {
                    lastError.value = text.take(140)
                  }
                  return super.onConsoleMessage(consoleMessage)
                }
              }
              wv.loadUrl(callUrl)
              webViewHolder.value = wv
              wv
            }
          )
        }
      } else {
        Column(
          Modifier.fillMaxSize().padding(24.dp),
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

      if (!mounted.value) {
        Text(
          "Preparando la llamada…",
          color = Color.White,
          modifier = Modifier.align(Alignment.Center)
        )
      }

      if (showHelp.value) {
        Column(
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(12.dp)
            .background(Color(0xE6101018), RoundedCornerShape(12.dp))
            .padding(12.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            "Si la llamada no se ve, ábrela en el navegador.",
            color = Color.White,
            textAlign = TextAlign.Center
          )
          val err = lastError.value
          if (err != null) {
            Text(err, color = Color(0xFFB0B0C0), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
          }
          TextButton(onClick = { uriHandler.openUri(url) }) { Text("Abrir en el navegador") }
          TextButton(onClick = {
            lastError.value = null
            showHelp.value = false
            webViewHolder.value?.loadUrl(callUrl)
          }) { Text("Reintentar") }
          TextButton(onClick = { showHelp.value = false }) { Text("Seguir esperando") }
        }
      }
    }
  }
}
