package chat.simplex.common.views.chat.item

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build.VERSION.SDK_INT
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.simplex.common.model.*
import chat.simplex.common.platform.androidAppContext
import chat.simplex.common.platform.chatController
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.getAppFilePath
import chat.simplex.common.platform.resizeImageToStrSize
import chat.simplex.common.views.helpers.AttachmentOption
import chat.simplex.common.views.helpers.MAX_IMAGE_SIZE_AUTO_RCV
import chat.simplex.common.views.helpers.generateNewFileName
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Buscador de GIFs con KLIPY (Tenor cerró su API el 30/06/2026).
//  - Clave: la de la compilación (secreto KLIPY_API_KEY) o la que el usuario pegue en el panel.
//  - Privacidad: la búsqueda y las miniaturas van a KLIPY (fuera de SimpleX); hay aviso previo.
//    El GIF que se envía se descarga y se manda como archivo normal: el receptor no contacta a KLIPY.
//  - Atribución exigida por KLIPY: campo "Search KLIPY" y marca "Powered by KLIPY".
// ---------------------------------------------------------------------------
private const val ZG_BUILTIN_KEY = "%%KLIPY_KEY%%"
private const val ZG_PREFS = "zetaria_gif"
private const val ZG_PER_PAGE = 24
private const val ZG_MAX_DOWNLOAD = 6_000_000

private fun zgPrefs() = androidAppContext.getSharedPreferences(ZG_PREFS, Context.MODE_PRIVATE)

private fun zgSavedKey(): String {
  val k = zgPrefs().getString("key", null)?.trim()
  return if (k.isNullOrEmpty()) ZG_BUILTIN_KEY else k
}

private val zgLoader: ImageLoader = ImageLoader.Builder(androidAppContext)
  .networkObserverEnabled(false)
  .diskCache(null)
  .components {
    if (SDK_INT >= 28) {
      add(ImageDecoderDecoder.Factory())
    } else {
      add(GifDecoder.Factory())
    }
  }
  .build()

private class ZgFile(val url: String, val size: Long, val ext: String)
private class ZgGif(val id: String, val title: String, val thumb: String, val files: List<ZgFile>)
private class ZgPage(val items: List<ZgGif>, val hasNext: Boolean)

private fun zgGet(url: String): String {
  val c = URL(url).openConnection() as HttpURLConnection
  try {
    c.connectTimeout = 10000
    c.readTimeout = 15000
    c.setRequestProperty("User-Agent", "Zetaria/1.0 (Android)")
    val code = c.responseCode
    if (code !in 200..299) throw IllegalStateException("HTTP $code")
    return c.inputStream.bufferedReader().use { it.readText() }
  } finally {
    c.disconnect()
  }
}

private fun zgDownload(url: String): ByteArray {
  val c = URL(url).openConnection() as HttpURLConnection
  try {
    c.connectTimeout = 10000
    c.readTimeout = 20000
    c.setRequestProperty("User-Agent", "Zetaria/1.0 (Android)")
    if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    c.inputStream.use { ins ->
      while (true) {
        val n = ins.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > ZG_MAX_DOWNLOAD) throw IllegalStateException("GIF demasiado grande")
      }
    }
    return out.toByteArray()
  } finally {
    c.disconnect()
  }
}

private fun zgParse(text: String): ZgPage {
  val root = JSONObject(text)
  val d = root.opt("data")
  var arr: JSONArray? = null
  var hasNext = false
  if (d is JSONObject) {
    arr = d.optJSONArray("data")
    hasNext = d.optBoolean("has_next", false)
  } else if (d is JSONArray) {
    arr = d
  }
  val out = ArrayList<ZgGif>()
  if (arr != null) {
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      val f = o.optJSONObject("file") ?: o.optJSONObject("files") ?: continue
      val files = ArrayList<ZgFile>()
      for (q in listOf("hd", "md", "sm", "xs")) {
        val fmt = f.optJSONObject(q) ?: continue
        for (t in listOf("gif", "webp")) {
          val m = fmt.optJSONObject(t) ?: continue
          val u = m.optString("url", "")
          if (u.startsWith("https://")) files.add(ZgFile(u, m.optLong("size", -1L), t))
        }
      }
      fun pick(q: String, t: String): String? =
        f.optJSONObject(q)?.optJSONObject(t)?.optString("url", "")?.takeIf { it.startsWith("https://") }
      val thumb = pick("xs", "webp") ?: pick("xs", "gif") ?: pick("sm", "webp") ?: pick("sm", "gif") ?: pick("xs", "jpg") ?: pick("sm", "jpg")
      if (thumb == null || files.isEmpty()) continue
      out.add(ZgGif(o.optString("id", "$i"), o.optString("title", "GIF"), thumb, files))
    }
  }
  return ZgPage(out, hasNext)
}

private fun zgFetch(apiKey: String, q: String, page: Int): ZgPage {
  val base = "https://api.klipy.com/api/v1/" + URLEncoder.encode(apiKey, "UTF-8") + "/gifs/" +
    (if (q.isEmpty()) "trending" else "search")
  var url = "$base?page=$page&per_page=$ZG_PER_PAGE&customer_id=zetaria"
  if (q.isNotEmpty()) url += "&q=" + URLEncoder.encode(q, "UTF-8")
  return zgParse(zgGet(url))
}

private fun zgErrorText(e: Exception): String {
  val m = e.message ?: ""
  return when {
    m.contains("HTTP 401") || m.contains("HTTP 403") -> "La clave de KLIPY no es válida o no tiene permiso"
    m.contains("HTTP 429") -> "Demasiadas búsquedas seguidas; espera un momento"
    m.startsWith("HTTP") -> "KLIPY respondió con un error ($m)"
    else -> "No se pudo conectar con KLIPY"
  }
}

/** Orden de preferencia: GIF que quepa en la descarga automática, luego WebP, luego lo más pequeño */
private fun zgCandidates(g: ZgGif): List<ZgFile> {
  val limit = MAX_IMAGE_SIZE_AUTO_RCV - 20_000
  val gifs = g.files.filter { it.ext == "gif" && it.size in 1..limit }.sortedByDescending { it.size }
  val webps = g.files.filter { it.ext == "webp" && it.size in 1..limit }.sortedByDescending { it.size }
  val rest = g.files.filter { it !in gifs && it !in webps }.sortedBy { if (it.size > 0) it.size else Long.MAX_VALUE / 2 }
  return (gifs + webps + rest).take(4)
}

private suspend fun zgSendGif(chat: Chat, chatsCtx: ChatModel.ChatsContext, g: ZgGif): Boolean {
  var best: ByteArray? = null
  var bestExt = "gif"
  for (f in zgCandidates(g)) {
    val bytes = try {
      zgDownload(f.url)
    } catch (e: Exception) {
      continue
    }
    if (best == null || bytes.size < best.size) {
      best = bytes
      bestExt = f.ext
    }
    if (bytes.size <= MAX_IMAGE_SIZE_AUTO_RCV) break
  }
  val bytes = best ?: return false
  val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
  val preview = resizeImageToStrSize(bmp.asImageBitmap(), 14000)
  val destName = generateNewFileName("gif", bestExt, File(getAppFilePath("")))
  val destFile = File(getAppFilePath(destName))
  val cf: CryptoFile = if (chatController.appPrefs.privacyEncryptLocalFiles.get()) {
    CryptoFile(destName, writeCryptoFile(destFile.absolutePath, bytes))
  } else {
    destFile.writeBytes(bytes)
    CryptoFile.plain(destName)
  }
  val cInfo = chat.chatInfo
  val composed = listOf(ComposedMessage(cf, null, MsgContent.MCImage("", preview), emptyMap()))
  val sent = if (cInfo.chatType == ChatType.Local) {
    chatModel.controller.apiCreateChatItems(rh = chat.remoteHostId, noteFolderId = cInfo.apiId, composedMessages = composed)
  } else {
    chatModel.controller.apiSendMessages(
      rh = chat.remoteHostId,
      type = cInfo.chatType,
      id = cInfo.apiId,
      scope = cInfo.groupChatScope(),
      sendAsGroup = cInfo.sendAsGroup,
      live = false,
      ttl = null,
      sign = false,
      composedMessages = composed
    )
  }
  if (sent.isNullOrEmpty()) return false
  for (a in sent) {
    withContext(Dispatchers.Main) {
      chatsCtx.addChatItem(chat.remoteHostId, a.chatInfo, a.chatItem)
    }
  }
  return true
}

// ---------------------------------------------------------------------------
// Interfaz
// ---------------------------------------------------------------------------
@Composable
actual fun ZetariaGifHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {
  var show by remember { mutableStateOf(false) }
  LaunchedEffect(attachmentOption.value) {
    if (attachmentOption.value == AttachmentOption.Gifs) {
      attachmentOption.value = null
      show = true
    }
  }
  if (show) ZetariaGifPanel(chatsCtx, chat) { show = false }
}

@Composable
private fun ZetariaGifPanel(chatsCtx: ChatModel.ChatsContext, chat: Chat, onDismiss: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var consent by remember { mutableStateOf(zgPrefs().getBoolean("consent", false)) }
  var apiKey by remember { mutableStateOf(zgSavedKey()) }
  var editKey by remember { mutableStateOf(false) }
  var keyInput by remember { mutableStateOf("") }
  var query by remember { mutableStateOf("") }
  val results = remember { mutableStateListOf<ZgGif>() }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var page by remember { mutableStateOf(1) }
  var hasNext by remember { mutableStateOf(false) }
  var sending by remember { mutableStateOf(false) }
  val gen = remember { intArrayOf(0) }
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary

  fun load(reset: Boolean) {
    if (!reset && (loading || !hasNext)) return
    val myGen = if (reset) ++gen[0] else gen[0]
    val q = query.trim()
    val p = if (reset) 1 else page + 1
    val k = apiKey
    loading = true
    scope.launch(Dispatchers.IO) {
      try {
        val r = zgFetch(k, q, p)
        withContext(Dispatchers.Main) {
          if (myGen == gen[0]) {
            if (reset) results.clear()
            results.addAll(r.items)
            page = p
            hasNext = r.hasNext
            error = null
          }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) { if (myGen == gen[0]) error = zgErrorText(e) }
      } finally {
        withContext(Dispatchers.Main) { if (myGen == gen[0]) loading = false }
      }
    }
  }

  LaunchedEffect(consent, apiKey, query) {
    if (consent && apiKey.isNotBlank()) {
      if (results.isNotEmpty() || query.isNotEmpty()) delay(450)
      load(true)
    }
  }

  fun sendGif(g: ZgGif) {
    if (sending) return
    sending = true
    scope.launch(Dispatchers.IO) {
      val ok = try {
        zgSendGif(chat, chatsCtx, g)
      } catch (e: Exception) {
        false
      }
      withContext(Dispatchers.Main) {
        sending = false
        if (ok) onDismiss() else error = "No se pudo enviar el GIF"
      }
    }
  }

  // 1) aviso de privacidad (una sola vez)
  if (!consent) {
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Buscador de GIFs") },
      text = {
        Text(
          "Los GIFs se buscan en KLIPY, un servicio externo. Para mostrarte resultados, KLIPY recibirá lo que busques y tu dirección IP, fuera de la red de SimpleX.\n\n" +
            "Tus mensajes no pasan por KLIPY: el GIF que envíes se descarga y se manda como archivo normal."
        )
      },
      confirmButton = {
        TextButton(onClick = {
          zgPrefs().edit().putBoolean("consent", true).apply()
          consent = true
        }) { Text("Continuar") }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
    return
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("GIFs", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
          TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
        if (apiKey.isBlank() || editKey) {
          Text(
            "Para buscar GIFs hace falta una clave gratuita de KLIPY.\n\n" +
              "1. Entra en partner.klipy.com y crea una cuenta.\n" +
              "2. Añade una plataforma y pulsa «Create Key».\n" +
              "3. Pega aquí la clave.",
            fontSize = 13.sp,
            color = secondary
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            keyInput,
            { keyInput = it.trim() },
            label = { Text("Clave de KLIPY") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            if (editKey) TextButton(onClick = { editKey = false }) { Text("Cancelar") }
            TextButton(
              enabled = keyInput.matches(Regex("[A-Za-z0-9_\\-]{8,}")),
              onClick = {
                zgPrefs().edit().putString("key", keyInput).apply()
                apiKey = keyInput
                editKey = false
                keyInput = ""
                error = null
              }
            ) { Text("Guardar") }
          }
        } else {
          OutlinedTextField(
            query,
            { query = it },
            placeholder = { Text("Search KLIPY") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          error?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colors.error, modifier = Modifier.padding(vertical = 6.dp))
          }
          if (sending) {
            Text("Enviando…", fontSize = 12.sp, color = primary, modifier = Modifier.padding(vertical = 6.dp))
          }
          if (results.isEmpty() && !loading && error == null) {
            Text("Sin resultados", fontSize = 13.sp, color = secondary, modifier = Modifier.padding(vertical = 16.dp))
          }
          LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 6.dp)) {
            items(results.toList().chunked(2)) { row ->
              Row(Modifier.fillMaxWidth()) {
                for (g in row) {
                  Box(
                    Modifier
                      .weight(1f)
                      .height(120.dp)
                      .padding(3.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .background(MaterialTheme.colors.onBackground.copy(alpha = 0.06f))
                      .clickable(enabled = !sending) { sendGif(g) }
                  ) {
                    Image(
                      rememberAsyncImagePainter(
                        ImageRequest.Builder(ctx).data(g.thumb).build(),
                        imageLoader = zgLoader
                      ),
                      contentDescription = g.title,
                      modifier = Modifier.fillMaxSize(),
                      contentScale = ContentScale.Crop
                    )
                  }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
              }
            }
            item {
              if (loading) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                  CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
              } else if (hasNext && results.isNotEmpty()) {
                LaunchedEffect(results.size) { load(false) }
              }
            }
          }
          Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Powered by KLIPY", Modifier.weight(1f), fontSize = 11.sp, color = secondary)
            Text(
              "Cambiar clave",
              fontSize = 11.sp,
              color = primary,
              modifier = Modifier.clickable { editKey = true }.padding(4.dp)
            )
          }
        }
      }
    }
  }
}
