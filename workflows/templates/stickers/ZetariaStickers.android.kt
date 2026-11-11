package chat.simplex.common.views.chat.item

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
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
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.resizeImageToStrSize
import chat.simplex.common.views.helpers.AttachmentOption
import chat.simplex.common.views.helpers.generateNewFileName
import chat.simplex.common.views.helpers.getLoadedImage
import chat.simplex.common.views.helpers.rememberGetMultipleContentsLauncher
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// ---------------------------------------------------------------------------
// Biblioteca de stickers (almacenamiento privado de la app)
//   files/zetaria_stickers/<paquete>/meta.txt   (primera línea = nombre)
//   files/zetaria_stickers/<paquete>/<md5>.<ext> (el hash evita duplicados)
//   files/zetaria_stickers/recent.txt            (últimos enviados)
// ---------------------------------------------------------------------------
private val ZS_EXT = setOf("webp", "png", "gif", "jpg", "jpeg")
private const val ZS_SAVED_DIR = "0_guardados"
private const val ZS_MAX_BYTES = 1_000_000

private val zsAnimLoader: ImageLoader = ImageLoader.Builder(androidAppContext)
  .networkObserverEnabled(false)
  .components {
    if (SDK_INT >= 28) {
      add(ImageDecoderDecoder.Factory())
    } else {
      add(GifDecoder.Factory())
    }
  }
  .build()

private val zsStaticLoader: ImageLoader = ImageLoader(androidAppContext)

private fun zsRoot(): File = File(androidAppContext.filesDir, "zetaria_stickers").apply { mkdirs() }

private fun zsExt(name: String): String = name.substringAfterLast('.', "").lowercase()

class ZsPack(val dir: File, val name: String, val files: List<File>)

private fun zsListPacks(): List<ZsPack> {
  val dirs = zsRoot().listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
  return dirs.map { d ->
    val meta = File(d, "meta.txt")
    val first = if (meta.exists()) meta.readLines().firstOrNull()?.trim() else null
    val name = if (first.isNullOrEmpty()) d.name else first
    val files = (d.listFiles { f -> f.isFile && zsExt(f.name) in ZS_EXT } ?: emptyArray())
      .sortedWith(compareBy({ it.lastModified() }, { it.name }))
    ZsPack(d, name, files)
  }
}

private fun zsMd5(b: ByteArray): String =
  MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

private fun zsIsImage(b: ByteArray): Boolean {
  val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(b, 0, b.size, o)
  return o.outWidth > 0 && o.outHeight > 0
}

/** Añade imágenes a un paquete; devuelve cuántas se añadieron (se omiten duplicados y formatos no válidos) */
private fun zsAddToPack(dir: File, title: String, items: List<Pair<String, ByteArray>>): Int {
  dir.mkdirs()
  val meta = File(dir, "meta.txt")
  if (!meta.exists()) meta.writeText(title)
  var added = 0
  val base = System.currentTimeMillis()
  for ((i, item) in items.withIndex()) {
    val ext0 = zsExt(item.first)
    val ext = if (ext0 == "jpeg") "jpg" else ext0
    val bytes = item.second
    if (ext !in ZS_EXT || bytes.size > ZS_MAX_BYTES || !zsIsImage(bytes)) continue
    val f = File(dir, zsMd5(bytes) + "." + ext)
    if (f.exists()) continue
    f.writeBytes(bytes)
    f.setLastModified(base + i)
    added++
  }
  return added
}

private class ZsZip(val title: String?, val stickers: List<Pair<String, ByteArray>>, val skipped: Int)

/** Lee un .wastickers (WhatsApp) o un .zip: imágenes + title.txt opcional; ignora tray y formatos no compatibles */
private fun zsReadZip(bytes: ByteArray): ZsZip {
  var title: String? = null
  val stickers = ArrayList<Pair<String, ByteArray>>()
  var skipped = 0
  ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
    var e = zin.nextEntry
    while (e != null) {
      val ename = e.name
      if (!e.isDirectory && !ename.contains("__MACOSX")) {
        val base = ename.substringAfterLast('/')
        val lower = base.lowercase()
        val data = zin.readBytes()
        val ext = zsExt(lower)
        if (lower == "title.txt") {
          title = String(data, Charsets.UTF_8).trim().lines().firstOrNull()?.trim()
        } else if (lower.endsWith(".txt") || lower.endsWith(".json")) {
          // metadatos: se ignoran
        } else if (lower.startsWith("tray")) {
          // icono del paquete: se ignora
        } else if (ext in ZS_EXT && stickers.size < 300) {
          stickers.add(base to data)
        } else {
          skipped++
        }
      }
      e = zin.nextEntry
    }
  }
  val sorted = stickers.sortedWith(compareBy<Pair<String, ByteArray>>({ it.first.length }, { it.first }))
  return ZsZip(title, sorted, skipped)
}

private fun zsDisplayName(uri: Uri): String {
  try {
    androidAppContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        val n = c.getString(0)
        if (!n.isNullOrEmpty()) return n
      }
    }
  } catch (e: Exception) {
  }
  return uri.lastPathSegment ?: "sticker"
}

/** Importa imágenes sueltas, .wastickers y .zip; devuelve un resumen para mostrar */
private fun zsImport(uris: List<Uri>): String {
  var added = 0
  var skipped = 0
  var packs = 0
  val loose = ArrayList<Pair<String, ByteArray>>()
  var counter = 0
  for (u in uris) {
    val name = zsDisplayName(u)
    val bytes = try {
      androidAppContext.contentResolver.openInputStream(u)?.use { it.readBytes() }
    } catch (e: Exception) {
      null
    }
    if (bytes == null) {
      skipped++
      continue
    }
    val lower = name.lowercase()
    val isZip = lower.endsWith(".wastickers") || lower.endsWith(".zip") ||
      (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
    if (isZip) {
      val z = try {
        zsReadZip(bytes)
      } catch (e: Exception) {
        null
      }
      if (z == null) {
        skipped++
        continue
      }
      val title = z.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
      val dir = File(zsRoot(), "${System.currentTimeMillis()}_${counter++}")
      val n = zsAddToPack(dir, title, z.stickers)
      if (n > 0) {
        packs++
        added += n
      } else {
        dir.deleteRecursively()
      }
      skipped += z.skipped + (z.stickers.size - n)
    } else if (zsExt(lower) in ZS_EXT) {
      loose.add(name to bytes)
    } else {
      skipped++
    }
  }
  if (loose.isNotEmpty()) {
    val n = zsAddToPack(File(zsRoot(), ZS_SAVED_DIR), "Guardados", loose)
    added += n
    skipped += loose.size - n
  }
  return if (added == 0) {
    "No se importó nada: formato no compatible o ya los tenías" + (if (skipped > 0) " ($skipped omitidos)" else "")
  } else {
    "Importados $added sticker" + (if (added == 1) "" else "s") +
      (if (packs > 0) " en $packs paquete" + (if (packs == 1) "" else "s") else "") +
      (if (skipped > 0) " · $skipped omitidos (duplicados o formato no compatible, como .tgs o .webm)" else "")
  }
}

private fun zsRecentFile() = File(zsRoot(), "recent.txt")

private fun zsRecents(): List<File> = try {
  zsRecentFile().readLines().map { File(it) }.filter { it.exists() }
} catch (e: Exception) {
  emptyList()
}

private fun zsAddRecent(f: File) {
  try {
    val l = (listOf(f.absolutePath) + zsRecents().map { it.absolutePath }.filter { it != f.absolutePath }).take(24)
    zsRecentFile().writeText(l.joinToString("\n"))
  } catch (e: Exception) {
  }
}

private fun zsRemoveRecent(f: File) {
  try {
    zsRecentFile().writeText(zsRecents().map { it.absolutePath }.filter { it != f.absolutePath }.joinToString("\n"))
  } catch (e: Exception) {
  }
}

private fun zsDeleteSticker(f: File) {
  val dir = f.parentFile
  f.delete()
  zsRemoveRecent(f)
  if (dir != null && dir.listFiles { x -> x.isFile && zsExt(x.name) in ZS_EXT }.isNullOrEmpty()) dir.deleteRecursively()
}

// ---------------------------------------------------------------------------
// Envío: igual que una imagen del compositor (mismo API), con nombre "sticker_..."
// ---------------------------------------------------------------------------
private suspend fun zsSend(chat: Chat, chatsCtx: ChatModel.ChatsContext, bytes: ByteArray, ext: String): Boolean {
  val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
  val preview = resizeImageToStrSize(bmp.asImageBitmap(), 14000)
  val destName = generateNewFileName("sticker", ext, File(getAppFilePath("")))
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

private fun zsToast(msg: String) {
  Toast.makeText(androidAppContext, msg, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------------------
// Sticker en el chat (sin burbuja)
// ---------------------------------------------------------------------------
actual fun zetariaStickerAvailable(ci: ChatItem): Boolean =
  ci.content.msgContent is MsgContent.MCImage &&
    ci.meta.itemDeleted == null && ci.quotedItem == null && ci.meta.itemForwarded == null && !ci.meta.isLive &&
    zetariaIsStickerName(ci.file?.fileName) && getLoadedFilePath(ci.file) != null

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun ZetariaStickerItem(chat: Chat, ci: ChatItem, onLongClick: () -> Unit) {
  val file = ci.file
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var loaded by remember(ci.id) { mutableStateOf<Pair<ImageBitmap, ByteArray>?>(null) }
  var askSave by remember { mutableStateOf(false) }
  LaunchedEffect(ci.id) {
    loaded = withContext(Dispatchers.IO) { getLoadedImage(file) }
  }
  Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
    val l = loaded
    if (l != null) {
      val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(ctx).data(data = l.second).size(Size.ORIGINAL).build(),
        placeholder = BitmapPainter(l.first),
        imageLoader = zsAnimLoader
      )
      Image(
        painter,
        contentDescription = null,
        modifier = Modifier
          .size(170.dp)
          .combinedClickable(onClick = { askSave = true }, onLongClick = onLongClick),
        contentScale = ContentScale.Fit
      )
    } else {
      Box(Modifier.size(170.dp))
    }
    Text(
      ci.timestampText,
      fontSize = 10.sp,
      color = MaterialTheme.colors.secondary,
      modifier = Modifier.padding(start = 6.dp)
    )
  }
  if (askSave) {
    AlertDialog(
      onDismissRequest = { askSave = false },
      title = { Text("Guardar sticker") },
      text = { Text("¿Añadirlo a tu colección de stickers?") },
      confirmButton = {
        TextButton(onClick = {
          askSave = false
          val data = loaded?.second
          if (data != null) {
            val ext = zsExt(file?.fileName ?: "").ifEmpty { "webp" }
            scope.launch(Dispatchers.IO) {
              val n = zsAddToPack(File(zsRoot(), ZS_SAVED_DIR), "Guardados", listOf("s.$ext" to data))
              withContext(Dispatchers.Main) { zsToast(if (n > 0) "Sticker guardado" else "Ya lo tenías guardado") }
            }
          }
        }) { Text("Guardar") }
      },
      dismissButton = { TextButton(onClick = { askSave = false }) { Text("Cancelar") } }
    )
  }
}

// ---------------------------------------------------------------------------
// Panel de stickers
// ---------------------------------------------------------------------------
@Composable
actual fun ZetariaStickerHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {
  var show by remember { mutableStateOf(false) }
  LaunchedEffect(attachmentOption.value) {
    if (attachmentOption.value == AttachmentOption.Stickers) {
      attachmentOption.value = null
      show = true
    }
  }
  if (show) ZetariaStickerPanel(chatsCtx, chat) { show = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZsTab(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
  Box(
    Modifier
      .padding(end = 6.dp)
      .size(46.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(if (selected) MaterialTheme.colors.primary.copy(alpha = 0.22f) else MaterialTheme.colors.onBackground.copy(alpha = 0.06f))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZetariaStickerPanel(chatsCtx: ChatModel.ChatsContext, chat: Chat, onDismiss: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var version by remember { mutableStateOf(0) }
  var status by remember { mutableStateOf<String?>(null) }
  var sending by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf("recent") }
  var menuFile by remember { mutableStateOf<File?>(null) }
  var confirmPack by remember { mutableStateOf<ZsPack?>(null) }
  val packs = remember(version) { zsListPacks() }
  val recents = remember(version) { zsRecents() }
  val currentPack = packs.firstOrNull { it.dir.name == selected }
  val current: List<File> = if (selected == "recent") recents else (currentPack?.files ?: emptyList())
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary

  LaunchedEffect(Unit) {
    if (zsRecents().isEmpty() && packs.isNotEmpty()) selected = packs.first().dir.name
  }

  val importLauncher = rememberGetMultipleContentsLauncher { uris ->
    if (uris.isNotEmpty()) {
      scope.launch(Dispatchers.IO) {
        val r = try {
          zsImport(uris)
        } catch (e: Exception) {
          "No se pudo importar: ${e.message}"
        }
        withContext(Dispatchers.Main) {
          status = r
          version++
        }
      }
    }
  }

  fun sendSticker(f: File) {
    if (sending) return
    sending = true
    scope.launch(Dispatchers.IO) {
      val ok = try {
        zsSend(chat, chatsCtx, f.readBytes(), zsExt(f.name).ifEmpty { "webp" })
      } catch (e: Exception) {
        false
      }
      if (ok) zsAddRecent(f)
      withContext(Dispatchers.Main) {
        sending = false
        if (ok) onDismiss() else status = "No se pudo enviar el sticker"
      }
    }
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Stickers", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
          TextButton(onClick = { importLauncher.launch("*/*") }) { Text("+ Importar") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
          ZsTab(selected == "recent", { selected = "recent" }) { Text("🕘", fontSize = 20.sp) }
          for (p in packs) {
            ZsTab(selected == p.dir.name, { selected = p.dir.name }) {
              val first = p.files.firstOrNull()
              if (first != null) {
                Image(
                  rememberAsyncImagePainter(
                    ImageRequest.Builder(ctx).data(first).size(96).build(),
                    imageLoader = zsStaticLoader
                  ),
                  contentDescription = null,
                  modifier = Modifier.size(34.dp),
                  contentScale = ContentScale.Fit
                )
              } else {
                Text(p.name.take(1), fontSize = 18.sp)
              }
            }
          }
        }
        if (currentPack != null) {
          Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
              currentPack.name + " · " + currentPack.files.size,
              Modifier.weight(1f),
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1
            )
            Text(
              "Eliminar paquete",
              fontSize = 12.sp,
              color = MaterialTheme.colors.error,
              modifier = Modifier.clickable { confirmPack = currentPack }.padding(4.dp)
            )
          }
        }
        status?.let {
          Text(it, fontSize = 12.sp, color = secondary, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (current.isEmpty()) {
          Text(
            if (packs.isEmpty() && recents.isEmpty()) {
              "Aún no tienes stickers.\n\nPulsa «Importar» y elige imágenes (.webp, .png), un paquete .wastickers de WhatsApp o un .zip con stickers (por ejemplo, descargado de Telegram con un bot de stickers). Los .tgs y .webm animados de Telegram no son compatibles.\n\nTambién puedes guardar los stickers que te envíen tocándolos en el chat."
            } else if (selected == "recent") {
              "Aún no has enviado stickers."
            } else {
              "Este paquete está vacío."
            },
            fontSize = 13.sp,
            color = secondary,
            modifier = Modifier.padding(vertical = 12.dp)
          )
        } else {
          LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(current.chunked(4)) { row ->
              Row(Modifier.fillMaxWidth()) {
                for (f in row) {
                  Box(
                    Modifier
                      .weight(1f)
                      .aspectRatio(1f)
                      .padding(3.dp)
                      .combinedClickable(onClick = { sendSticker(f) }, onLongClick = { menuFile = f })
                  ) {
                    Image(
                      rememberAsyncImagePainter(
                        ImageRequest.Builder(ctx).data(f).size(256).build(),
                        imageLoader = zsStaticLoader
                      ),
                      contentDescription = null,
                      modifier = Modifier.fillMaxSize(),
                      contentScale = ContentScale.Fit
                    )
                    DropdownMenu(expanded = menuFile == f, onDismissRequest = { menuFile = null }) {
                      if (selected == "recent") {
                        DropdownMenuItem(onClick = {
                          menuFile = null
                          zsRemoveRecent(f)
                          version++
                        }) { Text("Quitar de recientes") }
                      }
                      DropdownMenuItem(onClick = {
                        menuFile = null
                        zsDeleteSticker(f)
                        version++
                      }) { Text("Eliminar sticker", color = MaterialTheme.colors.error) }
                    }
                  }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
              }
            }
          }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
      }
    }
  }

  val delPack = confirmPack
  if (delPack != null) {
    AlertDialog(
      onDismissRequest = { confirmPack = null },
      title = { Text("¿Eliminar el paquete?") },
      text = { Text("Se borrará «${delPack.name}» con sus ${delPack.files.size} stickers de este dispositivo.") },
      confirmButton = {
        TextButton(onClick = {
          confirmPack = null
          delPack.files.forEach { zsRemoveRecent(it) }
          delPack.dir.deleteRecursively()
          selected = "recent"
          version++
        }) { Text("Eliminar", color = MaterialTheme.colors.error) }
      },
      dismissButton = { TextButton(onClick = { confirmPack = null }) { Text("Cancelar") } }
    )
  }
}
