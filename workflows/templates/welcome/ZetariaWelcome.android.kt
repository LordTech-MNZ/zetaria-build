package chat.simplex.common.views.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import chat.simplex.common.model.SharedPreference
import chat.simplex.common.model.User
import chat.simplex.common.platform.Log

/*
 * Bienvenida de Zetaria en Android: se muestra la página HTML del voto (index integrado) dentro
 * de un WebView. La página viene empaquetada en los assets del APK como
 *   common/src/commonMain/resources/assets/zetaria_welcome.html
 * y se sirve como origen https con WebViewAssetLoader, para que el JavaScript pueda hacer
 * fetch a la IP pública y a Web3Forms sin los límites de CORS de una URL file://.
 *
 * Contrato con el HTML (ver templates/welcome/zetaria_welcome.html):
 *   window.ZetariaNative.votoEnviado(evento)
 * se llama cuando el voto se acaba de enviar ("votado") o cuando ya estaba enviado
 * ("ya-votado"). Al recibirlo, la app avanza en la bienvenida, que es lo que permite entrar a
 * Zetaria. El HTML nunca entra a la app por su cuenta: si el envío falla, solo ofrece reintentar.
 */

private const val ZETARIA_WELCOME_TAG = "ZetariaWelcome"
private const val ZETARIA_WELCOME_ASSET = "zetaria_welcome.html"
private const val ZETARIA_WELCOME_URL = "https://appassets.androidplatform.net/assets/$ZETARIA_WELCOME_ASSET"
private const val ZETARIA_FONDO_HEX = "#080118"
private val ZETARIA_FONDO = Color(0xFF080118)
private val ZETARIA_ACENTO = Color(0xFFA678FF)
private val ZETARIA_TEXTO_SUAVE = Color(0xFFA78BCA)
private val ZETARIA_ERROR = Color(0xFFFCA5A5)

@Composable
actual fun ZetariaWelcomeHost(user: User?, onboardingStage: SharedPreference<OnboardingStage>) {
  // Si el WebView no puede ni crearse, se usa la bienvenida clásica para no bloquear la app.
  var usarRespaldo by remember { mutableStateOf(false) }
  if (usarRespaldo) {
    ZetariaWelcomeFallback(user, onboardingStage)
    return
  }

  val context = LocalContext.current
  val assetLoader = remember {
    WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
      .build()
  }
  var paginaCargada by remember { mutableStateOf(false) }
  var errorDePagina by remember { mutableStateOf(false) }
  val webViewRef = remember { mutableStateOf<WebView?>(null) }

  // Único punto que avanza la bienvenida: lo llaman el puente JS y el botón de respaldo.
  val entrarALaApp: (String) -> Unit = { evento ->
    Log.d(ZETARIA_WELCOME_TAG, "Bienvenida HTML, aviso recibido: $evento")
    onboardingStage.set(
      if (user == null) OnboardingStage.Step2_CreateProfile else OnboardingStage.OnboardingComplete
    )
  }

  val puente = remember { ZetariaWelcomeBridge(entrarALaApp) }

  DisposableEffect(Unit) {
    onDispose {
      webViewRef.value?.let { webView ->
        try {
          webView.stopLoading()
          webView.removeJavascriptInterface("ZetariaNative")
          (webView.parent as? ViewGroup)?.removeView(webView)
          webView.destroy()
        } catch (e: Exception) {
          Log.e(ZETARIA_WELCOME_TAG, "Error al liberar el WebView de la bienvenida: ${e.stackTraceToString()}")
        }
      }
      webViewRef.value = null
    }
  }

  Box(Modifier.fillMaxSize().background(ZETARIA_FONDO)) {
    AndroidView(
      modifier = Modifier.fillMaxSize().systemBarsPadding(),
      factory = { webViewContext ->
        crearWebViewBienvenida(
          context = webViewContext,
          assetLoader = assetLoader,
          puente = puente,
          alCargar = { paginaCargada = true },
          alFallarLaPagina = { errorDePagina = true },
          alNoPoderCrearElWebView = {
            // El factory corre durante la composición: el cambio de estado se hace después.
            Handler(Looper.getMainLooper()).post { usarRespaldo = true }
          },
          alCrear = { webView -> webViewRef.value = webView }
        )
      }
    )

    if (!paginaCargada && !errorDePagina) {
      Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ZETARIA_ACENTO)
      }
    }

    if (errorDePagina) {
      Box(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            "No se pudo cargar la bienvenida del Reino",
            color = ZETARIA_ERROR,
            textAlign = TextAlign.Center,
            fontSize = 17.sp
          )
          Text(
            "Comprueba tu conexión y vuelve a intentarlo. El voto se envía aquí mismo, desde esta pantalla.",
            color = ZETARIA_TEXTO_SUAVE,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp)
          )
          Button(
            onClick = {
              errorDePagina = false
              paginaCargada = false
              webViewRef.value?.loadUrl(ZETARIA_WELCOME_URL)
            },
            modifier = Modifier.padding(top = 18.dp)
          ) {
            Text("Reintentar")
          }
        }
      }
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
private fun crearWebViewBienvenida(
  context: Context,
  assetLoader: WebViewAssetLoader,
  puente: ZetariaWelcomeBridge,
  alCargar: () -> Unit,
  alFallarLaPagina: () -> Unit,
  alNoPoderCrearElWebView: () -> Unit,
  alCrear: (WebView) -> Unit
): View {
  return try {
    WebView(context).apply {
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      setBackgroundColor(AndroidColor.parseColor(ZETARIA_FONDO_HEX))
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true          // localStorage: recuerda que el voto ya se envió
        allowFileAccess = false
        allowContentAccess = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
      }
      webViewClient = object : WebViewClientCompat() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
          assetLoader.shouldInterceptRequest(request.url)

        override fun onPageFinished(view: WebView, url: String) {
          super.onPageFinished(view, url)
          alCargar()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
          super.onReceivedError(view, request, error)
          if (request.isForMainFrame) alFallarLaPagina()
        }

        // El WebViewAssetLoader responde 404 (sin contenido) si el asset no está en el APK:
        // sin esto la pantalla quedaría en blanco en vez de mostrar el error con reintento.
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
          super.onReceivedHttpError(view, request, errorResponse)
          if (request.isForMainFrame) alFallarLaPagina()
        }
      }
      addJavascriptInterface(puente, "ZetariaNative")
      alCrear(this)
      loadUrl(ZETARIA_WELCOME_URL)
    }
  } catch (e: Exception) {
    Log.e(ZETARIA_WELCOME_TAG, "No se pudo crear el WebView de la bienvenida: ${e.stackTraceToString()}")
    alNoPoderCrearElWebView()
    View(context)
  }
}

/**
 * Puente que la página del voto usa para avisar a la app.
 * WebView llama a estos métodos desde su propio hilo, por eso el aviso se reenvía al hilo principal.
 */
class ZetariaWelcomeBridge(private val alAvisar: (String) -> Unit) {
  @JavascriptInterface
  fun votoEnviado(evento: String?) {
    val texto = evento ?: "ok"
    Handler(Looper.getMainLooper()).post { alAvisar(texto) }
  }
}
