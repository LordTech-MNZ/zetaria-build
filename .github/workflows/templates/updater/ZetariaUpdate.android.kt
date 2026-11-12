package chat.simplex.common.views.zetaria

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.simplex.common.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * Actualizador de Zetaria (Android).
 *
 *  1. Al abrir la app consulta  https://api.github.com/repos/<REPO>/releases/latest
 *  2. Si el número de compilación de la Release (tag "zetaria-bNN") es mayor que el de esta app,
 *     muestra un aviso con las novedades y el botón «Actualizar».
 *  3. Al pulsarlo descarga el APK directamente a una sesión de PackageInstaller (sin archivos
 *     temporales ni FileProvider) y abre el instalador de Android para confirmar.
 *
 * Android solo deja instalar la actualización si está firmada con la MISMA clave que la app
 * instalada (la keystore de los secretos del repositorio).
 */

private const val ZU_TAG = "ZetariaUpdate"
private const val ZU_ACTION = "zetaria.update.INSTALL_RESULT"

private var zuYaComprobado = false

private class ZuInfo(val build: Int, val notes: String, val url: String, val size: Long)

private sealed class ZuState {
  object Idle : ZuState()
  object Checking : ZuState()
  class UpToDate(val build: Int) : ZuState()
  class CheckFailed(val message: String) : ZuState()
  class Available(val info: ZuInfo) : ZuState()
  class Downloading(val info: ZuInfo, val progress: Float) : ZuState()
  class Installing(val info: ZuInfo) : ZuState()
  class Failed(val info: ZuInfo, val message: String) : ZuState()
}

private suspend fun zuBuscarUltima(): ZuInfo = withContext(Dispatchers.IO) {
  val conn = URL("https://api.github.com/repos/${ZetariaUpdateConfig.REPO}/releases/latest").openConnection() as HttpURLConnection
  try {
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("User-Agent", "Zetaria-Updater")
    val code = conn.responseCode
    if (code != 200) {
      Log.d(ZU_TAG, "GitHub respondió $code")
      throw IllegalStateException(
        if (code == 404) "No hay versiones publicadas todavía (o el repositorio es privado)."
        else "GitHub respondió con el código $code. Inténtalo más tarde."
      )
    }
    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    val tag = json.optString("tag_name")
    val build = Regex("-b(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()
      ?: throw IllegalStateException("La última versión publicada («$tag») no tiene un número de compilación válido.")
    val assets = json.optJSONArray("assets") ?: throw IllegalStateException("La última versión no incluye archivos.")
    var found: ZuInfo? = null
    for (i in 0 until assets.length()) {
      val a = assets.getJSONObject(i)
      if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
        found = ZuInfo(build, json.optString("body").trim(), a.optString("browser_download_url"), a.optLong("size", -1L))
        break
      }
    }
    found ?: throw IllegalStateException("La última versión no incluye el APK de Zetaria.")
  } finally {
    conn.disconnect()
  }
}

// Descarga el APK escribiéndolo directo en una sesión de PackageInstaller y la confirma (commit).
private suspend fun zuDescargarEInstalar(context: Context, info: ZuInfo, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
  val installer = context.packageManager.packageInstaller
  val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
  params.setAppPackageName(context.packageName)
  if (info.size > 0) params.setSize(info.size)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
  }
  val id = installer.createSession(params)
  val session = installer.openSession(id)
  var confirmada = false
  try {
    val conn = URL(info.url).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = 20_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("User-Agent", "Zetaria-Updater")
      if (conn.responseCode != 200) throw IllegalStateException("La descarga respondió ${conn.responseCode}")
      val total = if (info.size > 0) info.size else conn.contentLengthLong
      var hecho = 0L
      conn.inputStream.use { input ->
        session.openWrite("zetaria.apk", 0, if (total > 0) total else -1L).use { out ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            hecho += n
            if (total > 0) onProgress((hecho.toFloat() / total).coerceIn(0f, 1f))
          }
          session.fsync(out)
        }
      }
      if (total > 0 && hecho != total) throw IllegalStateException("Descarga incompleta")
    } finally {
      conn.disconnect()
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    val pending = PendingIntent.getBroadcast(context, id, Intent(ZU_ACTION).setPackage(context.packageName), flags)
    session.commit(pending.intentSender)
    confirmada = true
  } finally {
    session.close()
    if (!confirmada) {
      try { installer.abandonSession(id) } catch (_: Exception) {}
    }
  }
  Unit
}

@Suppress("DEPRECATION")
private fun zuIntentDeConfirmacion(intent: Intent): Intent? =
  if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
  else intent.getParcelableExtra(Intent.EXTRA_INTENT)

// Recibe el resultado del instalador de Android.
private class ZuReceiver(val alEstado: (Int, String?) -> Unit) : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      // Android pide que el usuario confirme (y, si hace falta, permita instalar desde Zetaria)
      val confirmar = zuIntentDeConfirmacion(intent)
      if (confirmar != null) {
        confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
          context.startActivity(confirmar)
        } catch (e: Exception) {
          Log.e(ZU_TAG, "No se pudo abrir el instalador: ${e.stackTraceToString()}")
        }
      }
    }
    alEstado(status, msg)
  }
}

@Composable
actual fun ZetariaUpdateHost() {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<ZuState>(ZuState.Idle) }
  var job by remember { mutableStateOf<Job?>(null) }
  var ultima by remember { mutableStateOf<ZuInfo?>(null) }

  val receiver = remember {
    ZuReceiver { status, msg ->
      val info = ultima
      when (status) {
        PackageInstaller.STATUS_SUCCESS -> {}
        PackageInstaller.STATUS_PENDING_USER_ACTION -> if (info != null) state = ZuState.Installing(info)
        PackageInstaller.STATUS_FAILURE_ABORTED -> if (info != null) state = ZuState.Available(info)
        else -> if (info != null) state = ZuState.Failed(info, msg ?: "Error $status del instalador")
      }
    }
  }

  DisposableEffect(Unit) {
    ContextCompat.registerReceiver(context, receiver, IntentFilter(ZU_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    onDispose {
      try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
  }

  // Una sola comprobación por arranque de la app
  LaunchedEffect(Unit) {
    if (zuYaComprobado) return@LaunchedEffect
    zuYaComprobado = true
    delay(2500)
    try {
      val info = zuBuscarUltima()
      Log.d(ZU_TAG, "Compilación instalada: ${ZetariaUpdateConfig.BUILD_NUMBER}; última publicada: ${info.build}")
      if (info.build > ZetariaUpdateConfig.BUILD_NUMBER) {
        ultima = info
        state = ZuState.Available(info)
      }
    } catch (e: Exception) {
      Log.d(ZU_TAG, "No se pudo comprobar actualizaciones: ${e.message}")
    }
  }

  // Botón «Buscar actualizaciones» de Configuración: siempre da respuesta (hay versión nueva / estás al día / error)
  val pedidas = ZetariaUpdateBus.checkRequests
  LaunchedEffect(pedidas) {
    if (pedidas == 0 || state is ZuState.Downloading) return@LaunchedEffect
    state = ZuState.Checking
    try {
      val info = zuBuscarUltima()
      if (state !is ZuState.Checking) return@LaunchedEffect // el usuario canceló
      ultima = info
      state = if (info.build > ZetariaUpdateConfig.BUILD_NUMBER) ZuState.Available(info)
              else ZuState.UpToDate(ZetariaUpdateConfig.BUILD_NUMBER)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.d(ZU_TAG, "Comprobación manual fallida: ${e.stackTraceToString()}")
      if (state is ZuState.Checking) {
        val msg = e.message.orEmpty()
        state = ZuState.CheckFailed(
          if (e is IllegalStateException && msg.isNotBlank()) msg
          else "No se pudo comprobar. Revisa tu conexión a internet e inténtalo de nuevo."
        )
      }
    }
  }

  fun empezar(info: ZuInfo) {
    ultima = info
    state = ZuState.Downloading(info, 0f)
    job = scope.launch {
      try {
        zuDescargarEInstalar(context, info) { p -> state = ZuState.Downloading(info, p) }
        if (state is ZuState.Downloading) state = ZuState.Installing(info)
      } catch (e: CancellationException) {
        state = ZuState.Available(info)
        throw e
      } catch (e: Exception) {
        Log.e(ZU_TAG, "Fallo al actualizar: ${e.stackTraceToString()}")
        state = ZuState.Failed(info, e.message ?: e.toString())
      }
    }
  }

  when (val s = state) {
    is ZuState.Idle -> {}
    is ZuState.Checking -> AlertDialog(
      onDismissRequest = {},
      title = { Text("Buscando actualizaciones…") },
      text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
      confirmButton = {},
      dismissButton = { TextButton(onClick = { state = ZuState.Idle }) { Text("Cancelar") } }
    )
    is ZuState.UpToDate -> AlertDialog(
      onDismissRequest = { state = ZuState.Idle },
      title = { Text("Zetaria está al día") },
      text = { Text("Ya tienes la última versión (compilación ${s.build}).") },
      confirmButton = { TextButton(onClick = { state = ZuState.Idle }) { Text("Aceptar") } }
    )
    is ZuState.CheckFailed -> AlertDialog(
      onDismissRequest = { state = ZuState.Idle },
      title = { Text("No se pudo comprobar") },
      text = { Text(s.message) },
      confirmButton = { TextButton(onClick = { ZetariaUpdateBus.requestCheck() }) { Text("Reintentar") } },
      dismissButton = { TextButton(onClick = { state = ZuState.Idle }) { Text("Cerrar") } }
    )
    is ZuState.Available -> AlertDialog(
      onDismissRequest = { state = ZuState.Idle },
      title = { Text("Actualización disponible") },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          Text("Hay una versión nueva de Zetaria (compilación ${s.info.build}).")
          if (s.info.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Novedades:", fontWeight = FontWeight.Bold)
            Text(s.info.notes.take(700))
          }
        }
      },
      confirmButton = { TextButton(onClick = { empezar(s.info) }) { Text("Actualizar") } },
      dismissButton = { TextButton(onClick = { state = ZuState.Idle }) { Text("Más tarde") } }
    )
    is ZuState.Downloading -> AlertDialog(
      onDismissRequest = {},
      title = { Text("Descargando actualización…") },
      text = {
        Column {
          LinearProgressIndicator(progress = s.progress, modifier = Modifier.fillMaxWidth())
          Spacer(Modifier.height(8.dp))
          Text("${(s.progress * 100).toInt()} %")
        }
      },
      confirmButton = {},
      dismissButton = { TextButton(onClick = { job?.cancel() }) { Text("Cancelar") } }
    )
    is ZuState.Installing -> AlertDialog(
      onDismissRequest = { state = ZuState.Idle },
      title = { Text("Instalando actualización") },
      text = {
        Text("Confirma la instalación en la ventana de Android. Si te pide permiso para instalar apps desde Zetaria, actívalo y vuelve a pulsar «Actualizar».")
      },
      confirmButton = { TextButton(onClick = { state = ZuState.Idle }) { Text("Cerrar") } }
    )
    is ZuState.Failed -> AlertDialog(
      onDismissRequest = { state = ZuState.Idle },
      title = { Text("No se pudo actualizar") },
      text = { Text(s.message) },
      confirmButton = { TextButton(onClick = { empezar(s.info) }) { Text("Reintentar") } },
      dismissButton = { TextButton(onClick = { state = ZuState.Idle }) { Text("Cerrar") } }
    )
  }
}
