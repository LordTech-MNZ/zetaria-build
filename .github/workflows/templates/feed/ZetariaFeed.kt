package chat.simplex.common.views.chat.item

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.simplex.common.model.*
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.AudioPlayer
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.VideoPlayer
import chat.simplex.common.platform.VideoPlayerHolder
import chat.simplex.common.platform.base64ToBitmap
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.getAppFilePath
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.getLoadedFileSource
import chat.simplex.common.platform.openFile
import chat.simplex.common.platform.rememberFileChooserLauncher
import chat.simplex.common.platform.resizeImageToStrSize
import chat.simplex.common.platform.settings
import chat.simplex.common.platform.shareText
import chat.simplex.common.platform.showToast
import chat.simplex.common.views.chat.topPaddingToContent
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Feed de publicaciones de Zetaria (texto, imágenes y videos).
// Sin cambios de protocolo: todo viaja como mensajes normales entre contactos.
//
//   Publicación          "📰 ζ·<pid>"                                  autor -> todos sus contactos
//   Comentario           "💬 ζ·<pid> <cid>"                            lector -> autor
//   Comentario reenviado "🗨 ζ·<pid> <cid> <clave> <nombre>"           autor -> demás contactos
//   Reacción             "💟 ζ·<pid> <emoji>"  ("-" = quitar)          lector -> autor
//   Reacción reenviada   "💠 ζ·<pid> <clave> <emoji> <nombre>"         autor -> demás contactos
//   Eliminar publicación "🗑 ζ·<pid>"                                  autor -> todos sus contactos
//   Eliminar comentario  "🗑 ζ·<pid>" + "↪ <cid>"                       quien comentó -> autor -> demás contactos
//   Editar publicación   "✏ ζ·<pid>" + texto nuevo (+ imagen/video/audio nuevo)   autor -> todos sus contactos
//   Editar comentario    "✏ ζ·<pid>" + "↪ <cid>" + texto nuevo (+ imagen/video)    quien comentó -> autor -> demás contactos
//   (en una edición, "🚫 1" quita el archivo adjunto; un archivo adjunto reemplaza al anterior)
//
// Después de la primera línea pueden venir líneas de datos opcionales:
//   "↪ <cid>"       responde a ese comentario (o, en una reacción, reacciona a ese comentario)
//   "🪪 <imagen>"   foto de perfil en miniatura de quien comenta o reacciona
//   "🕒 <segundos>" fecha original (al reenviar publicaciones antiguas a un contacto nuevo)
//   "🔘 <opción>"   opción de una encuesta (en la publicación); un voto es una reacción con emoji "#1".."#6" ("#0" = retirar)
// y luego el texto. Publicaciones y comentarios pueden llevar una imagen o un video adjunto.
//
// El autor hace de "centro": reenvía los comentarios y reacciones que recibe al resto de sus contactos, y
// cuando alguien nuevo se conecta le reenvía sus publicaciones anteriores (con comentarios y reacciones).
// Estos mensajes se ocultan del chat, de la lista y de las notificaciones y se muestran juntos en la pestaña
// "Feed". Un SimpleX normal los vería como mensajes de texto con una cabecera.
// ---------------------------------------------------------------------------
private const val ZF_TOK = "ζ·"
private const val ZF_POST = "📰 ζ·"
private const val ZF_COMMENT = "💬 ζ·"
private const val ZF_CREL = "🗨 ζ·"
private const val ZF_REACT = "💟 ζ·"
private const val ZF_RREL = "💠 ζ·"
private const val ZF_DEL = "🗑 ζ·"
private const val ZF_EDIT = "✏ ζ·"
private const val ZF_META_REPLY = "↪ "
private const val ZF_META_AVATAR = "🪪 "
private const val ZF_META_TIME = "🕒 "
private const val ZF_META_OPT = "🔘 "
private const val ZF_META_DROP = "🚫 "
private const val ZF_MAX_TEXT = 3000
private const val ZF_MAX_COMMENT = 1000
private const val ZF_MAX_VIDEO_BYTES = 60L * 1024L * 1024L
private const val ZF_MAX_AUDIO_BYTES = 25L * 1024L * 1024L
private const val ZF_MAX_FILE_BYTES = 50L * 1024L * 1024L
private const val ZF_MAX_OPTIONS = 6
private const val ZF_MAX_EMOJI_LEN = 16
private const val ZF_BACKFILL_POSTS = 20
private const val ZF_BACKFILL_ITEMS = 40
private const val ZF_AUTOSAVE_KEY = "zetaria_auto_save_images"
private val ZF_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "😡")

/** Guarda una imagen o un video recibido en la galería, dentro de la carpeta «Zetaria». */
expect suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean, feed: Boolean, quiet: Boolean)

/** Borra de «Pictures» las copias que esta app guardó antes y que ya están en «Pictures/Zetaria Chat Images». */
expect suspend fun zetariaCleanGalleryDuplicates()

/** El botón «Guardar» de un chat: usa la misma carpeta que el guardado automático (sin copia en Pictures). */
fun zetariaSaveChatImageManual(ciFile: CIFile?) {
  withLongRunningApi {
    try {
      zetariaSaveToGallery(ciFile, false, false, false)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed manual save: " + e.stackTraceToString())
    }
  }
}

/**
 * Guarda un audio (kind 3 → Música/Zetaria Feed) o un archivo cualquiera (kind 4 → Descargas/Zetaria Feed)
 * con su nombre original. quiet = sin avisos.
 */
expect suspend fun zetariaSaveFile(file: CIFile?, kind: Int, quiet: Boolean)

/** Comparte un archivo (imagen, video o audio) con otras apps, con el texto como pie. kind: 1 imagen, 2 video, 3 audio. */
expect fun zetariaShareFile(text: String, file: CryptoFile, kind: Int)

/** Duración de un audio en milisegundos, también si está cifrado (null si no se pudo leer). */
expect suspend fun zetariaAudioDuration(file: CryptoFile): Int?

/** Mueve la reproducción de un video a una posición (milisegundos). */
expect fun zetariaVideoSeek(player: VideoPlayer, positionMs: Long)

private enum class ZfKind { POST, COMMENT, CREL, REACT, RREL, DEL, EDIT }

private class ZfHead(
  val kind: ZfKind,
  val pid: String,
  val cid: String,
  val key: String,
  val name: String,
  val emoji: String,
  val body: String,
  val reply: String,
  val avatar: String,
  val origSec: Long,
  val options: List<String>,
  val dropMedia: Boolean = false
)

private val zfPidRe = Regex("^[0-9a-f]{10}$")
private val zfCidRe = Regex("^[0-9a-f]{8}$")
private val zfKeyRe = Regex("^[A-Za-z0-9]{1,24}$")
private val zfVoteRe = Regex("^#[0-6]$")

/**
 * Un emoji personalizado: sin espacios, de pocos caracteres, con algún símbolo no ASCII y sin letras ni
 * números ASCII (así no se cuelan textos normales ni "#1".."#6", que son votos de encuestas).
 */
private fun zfIsCustomEmoji(s: String): Boolean {
  if (s.isEmpty() || s.length > ZF_MAX_EMOJI_LEN) return false
  if (s.any { it.isWhitespace() || it.isISOControl() }) return false
  if (s.any { it.code < 128 }) return false
  return true
}

private fun zfValidEmoji(s: String): Boolean = s == "-" || zfVoteRe.matches(s) || ZF_EMOJIS.contains(s) || zfIsCustomEmoji(s)

private fun zfParseText(text: String): ZfHead? {
  if (!text.contains(ZF_TOK)) return null
  val lines = text.split("\n")
  val head = lines[0]
  var reply = ""
  var avatar = ""
  var origSec = 0L
  var drop = false
  val options = ArrayList<String>()
  var i = 1
  while (i < lines.size) {
    val ln = lines[i]
    if (ln.startsWith(ZF_META_OPT)) {
      val v = ln.substring(ZF_META_OPT.length).trim()
      if (v.isNotEmpty() && options.size < ZF_MAX_OPTIONS) options.add(v.take(80))
    } else if (ln.startsWith(ZF_META_REPLY)) {
      val v = ln.substring(ZF_META_REPLY.length).trim()
      if (zfCidRe.matches(v)) reply = v
    } else if (ln.startsWith(ZF_META_AVATAR)) {
      val v = ln.substring(ZF_META_AVATAR.length).trim()
      if (v.startsWith("data:image/") && v.length <= 12000) avatar = v
    } else if (ln.startsWith(ZF_META_DROP)) {
      drop = true
    } else if (ln.startsWith(ZF_META_TIME)) {
      val v = ln.substring(ZF_META_TIME.length).trim().toLongOrNull()
      if (v != null && v > 1_500_000_000L && v < 4_000_000_000L) origSec = v
    } else {
      break
    }
    i++
  }
  val body = if (i < lines.size) lines.subList(i, lines.size).joinToString("\n").trim() else ""
  if (head.startsWith(ZF_POST)) {
    val pid = head.substring(ZF_POST.length).trim()
    if (!zfPidRe.matches(pid)) return null
    return ZfHead(ZfKind.POST, pid, "", "", "", "", body, "", "", origSec, options)
  }
  if (head.startsWith(ZF_COMMENT)) {
    val parts = head.substring(ZF_COMMENT.length).trim().split(" ")
    if (parts.size != 2 || !zfPidRe.matches(parts[0]) || !zfCidRe.matches(parts[1])) return null
    return ZfHead(ZfKind.COMMENT, parts[0], parts[1], "", "", "", body, reply, "", 0L, emptyList())
  }
  if (head.startsWith(ZF_CREL)) {
    val parts = head.substring(ZF_CREL.length).trim().split(" ", limit = 4)
    if (parts.size < 3 || !zfPidRe.matches(parts[0]) || !zfCidRe.matches(parts[1]) || !zfKeyRe.matches(parts[2])) return null
    val name = if (parts.size > 3) parts[3].trim() else ""
    return ZfHead(ZfKind.CREL, parts[0], parts[1], parts[2], name, "", body, reply, avatar, origSec, emptyList())
  }
  if (head.startsWith(ZF_REACT)) {
    val parts = head.substring(ZF_REACT.length).trim().split(" ")
    if (parts.size != 2 || !zfPidRe.matches(parts[0]) || !zfValidEmoji(parts[1])) return null
    return ZfHead(ZfKind.REACT, parts[0], "", "", "", parts[1], "", reply, "", 0L, emptyList())
  }
  if (head.startsWith(ZF_RREL)) {
    val parts = head.substring(ZF_RREL.length).trim().split(" ", limit = 4)
    if (parts.size < 3 || !zfPidRe.matches(parts[0]) || !zfKeyRe.matches(parts[1]) || !zfValidEmoji(parts[2])) return null
    val name = if (parts.size > 3) parts[3].trim() else ""
    return ZfHead(ZfKind.RREL, parts[0], "", parts[1], name, parts[2], "", reply, avatar, origSec, emptyList())
  }
  if (head.startsWith(ZF_DEL)) {
    val pid = head.substring(ZF_DEL.length).trim()
    if (!zfPidRe.matches(pid)) return null
    return ZfHead(ZfKind.DEL, pid, "", "", "", "", "", reply, "", 0L, emptyList())
  }
  if (head.startsWith(ZF_EDIT)) {
    val pid = head.substring(ZF_EDIT.length).trim()
    if (!zfPidRe.matches(pid)) return null
    return ZfHead(ZfKind.EDIT, pid, "", "", "", "", body, reply, "", 0L, emptyList(), drop)
  }
  return null
}

private fun zfIsMediaItem(ci: ChatItem): Boolean {
  val mc = ci.content.msgContent
  return mc is MsgContent.MCImage || mc is MsgContent.MCVideo
}

private val zfAudioExts = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "wma", "mka", "weba")

/** ¿El nombre de archivo es de un audio? (el resto de archivos adjuntos se tratan como «archivo») */
private fun zfIsAudioName(name: String): Boolean = zfAudioExts.contains(name.substringAfterLast('.', "").lowercase())

private fun zfFileIcon(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
  "pdf" -> "📕"
  "doc", "docx", "odt", "txt", "rtf", "md" -> "📝"
  "xls", "xlsx", "csv", "ods" -> "📊"
  "ppt", "pptx", "odp" -> "📽"
  "zip", "rar", "7z", "tar", "gz" -> "🗜"
  "apk" -> "🤖"
  "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "wma", "mka", "weba" -> "🎵"
  else -> "📎"
}

/** 1 imagen, 2 video, 3 audio, 4 archivo cualquiera, 0 nada. */
private fun zfKindOf(ci: ChatItem?): Int {
  val mc = ci?.content?.msgContent
  return when (mc) {
    is MsgContent.MCImage -> 1
    is MsgContent.MCVideo -> 2
    is MsgContent.MCFile -> if (zfIsAudioName(ci?.file?.fileName ?: "")) 3 else 4
    else -> 0
  }
}

/** ¿Este mensaje lleva una imagen, un video o un audio adjunto? */
private fun zfHasMedia(ci: ChatItem?): Boolean {
  val mc = ci?.content?.msgContent
  return mc is MsgContent.MCImage || mc is MsgContent.MCVideo || mc is MsgContent.MCFile
}

private fun zfHeadOf(ci: ChatItem): ZfHead? {
  if (ci.meta.itemDeleted != null) return null
  val mc = ci.content.msgContent ?: return null
  val media = mc is MsgContent.MCImage || mc is MsgContent.MCVideo
  val audio = mc is MsgContent.MCFile
  if (!media && !audio && mc !is MsgContent.MCText) return null
  val h = zfParseText(mc.text) ?: return null
  // solo publicaciones y comentarios llevan imagen o video; solo las publicaciones llevan audio
  if (media && h.kind != ZfKind.POST && h.kind != ZfKind.COMMENT && h.kind != ZfKind.CREL && h.kind != ZfKind.EDIT) return null
  // el audio solo va en publicaciones (y en sus ediciones, no en las de comentarios)
  if (audio && h.kind != ZfKind.POST && !(h.kind == ZfKind.EDIT && h.reply.isEmpty())) return null
  return h
}

/** ¿Este mensaje pertenece al feed (publicación, comentario o reacción)? Se oculta del chat, de la lista y de las notificaciones. */
fun zetariaIsFeedItem(ci: ChatItem): Boolean = zfHeadOf(ci) != null

// ---------------------------------------------------------------------------
// Almacén en memoria. La verdad está en la base de datos de los chats: al abrir el feed se vuelven a
// leer los mensajes marcados de cada contacto, y los nuevos se añaden en directo.
// Todo acceso ocurre en el hilo principal.
// ---------------------------------------------------------------------------
private class ZfEvent(
  val kind: ZfKind,
  val pid: String,
  val cid: String,
  val key: String,
  val name: String,
  val emoji: String,
  val body: String,
  val reply: String,
  val avatar: String,
  val options: List<String>,
  val rh: Long?,
  val chatId: String,
  val contactId: Long,
  val chatName: String,
  val chatImage: String?,
  val sent: Boolean,
  val tsMs: Long,
  val createdMs: Long,
  val itemId: Long,
  val ci: ChatItem,
  val dropMedia: Boolean = false
)

private fun zfEventOf(rh: Long?, cInfo: ChatInfo, ci: ChatItem): ZfEvent? {
  if (cInfo !is ChatInfo.Direct) return null
  val h = zfHeadOf(ci) ?: return null
  val ts = if (h.origSec > 0L) h.origSec * 1000L else ci.meta.itemTs.toEpochMilliseconds()
  return ZfEvent(
    h.kind, h.pid, h.cid, h.key, h.name, h.emoji, h.body, h.reply, h.avatar, h.options,
    rh, cInfo.id, cInfo.contact.contactId, cInfo.displayName, cInfo.image,
    ci.chatDir.sent, ts, ci.meta.createdAt.toEpochMilliseconds(), ci.id, ci, h.dropMedia
  )
}

private val zfEvents = HashMap<String, HashMap<Long, ZfEvent>>()
private var zfUserId: Long = -1L
private val zfVersion = mutableStateOf(0)
private val zfLoading = mutableStateOf(false)
private val zfSending = mutableStateOf(false)
private val zfRefreshing = AtomicBoolean(false)
private val zfInitialDone = AtomicBoolean(false)
private val zfSeen = mutableStateOf(0L)
private val zfAutoSave = mutableStateOf(true)
private var zfAutoSaveLoaded = false
private val zfAutoSaved: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
private val zfAvatarCache = ConcurrentHashMap<String, String>()

private fun zfSeenKey(uid: Long): String = "zetaria_feed_seen_" + uid

private fun zfSetSeen(ms: Long) {
  zfCheckUser()
  if (ms <= zfSeen.value) return
  zfSeen.value = ms
  try {
    settings.putLong(zfSeenKey(zfUserId), ms)
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed seen: " + e.stackTraceToString())
  }
}

/** Fecha (hora local de llegada) de la publicación ajena más reciente. */
private fun zfNewestOthersMs(): Long {
  var mx = 0L
  for (m in zfEvents.values) {
    for (e in m.values) {
      if (e.kind == ZfKind.POST && !e.sent && e.createdMs > mx) mx = e.createdMs
    }
  }
  return mx
}

private fun zfUnseenCount(): Int {
  val seen = zfSeen.value
  var n = 0
  for (m in zfEvents.values) {
    for (e in m.values) {
      if (e.kind == ZfKind.POST && !e.sent && e.createdMs > seen) n++
    }
  }
  return n
}

/** La primera vez, lo que ya existe cuenta como visto: el aviso solo marca lo nuevo. */
private fun zfInitSeen() {
  zfCheckUser()
  if (zfSeen.value == 0L) {
    val mx = zfNewestOthersMs()
    zfSetSeen(if (mx > 0L) mx else System.currentTimeMillis())
  }
}

private fun zfAutoSaveOn(): Boolean {
  if (!zfAutoSaveLoaded) {
    zfAutoSaveLoaded = true
    zfAutoSave.value = try {
      settings.getBoolean(ZF_AUTOSAVE_KEY, true)
    } catch (e: Exception) {
      true
    }
  }
  return zfAutoSave.value
}

private fun zfBump() {
  zfVersion.value = zfVersion.value + 1
}

private fun zfCheckUser() {
  val uid = chatModel.currentUser.value?.userId ?: -1L
  if (uid != zfUserId) {
    zfEvents.clear()
    zfUserId = uid
    zfSeen.value = try {
      settings.getLong(zfSeenKey(uid), 0L)
    } catch (e: Exception) {
      0L
    }
  }
}

private fun zfPut(e: ZfEvent) {
  zfCheckUser()
  zfEvents.getOrPut(e.chatId) { HashMap() }[e.itemId] = e
}

private fun zfSetChat(chatId: String, list: List<ZfEvent>) {
  zfCheckUser()
  val m = HashMap<Long, ZfEvent>()
  for (e in list) m[e.itemId] = e
  zfEvents[chatId] = m
  zfBump()
}

private fun zfNewId(n: Int): String = UUID.randomUUID().toString().replace("-", "").take(n)

private fun zfClean(s: String): String {
  val t = s.replace("\n", " ").replace("\r", " ").trim().take(40).trim()
  return if (t.isEmpty()) "?" else t
}

private fun zfMyName(): String = zfClean(chatModel.currentUser.value?.displayName ?: "Zetaria")

/** Foto de perfil en miniatura (≈5 KB) para viajar dentro de un mensaje. */
private fun zfSmallAvatar(image: String?): String? {
  if (image == null) return null
  if (image.length <= 5000) return image
  val k = image.hashCode().toString() + ":" + image.length
  val cached = zfAvatarCache[k]
  if (cached != null) return cached
  return try {
    val s = resizeImageToStrSize(base64ToBitmap(image), maxDataSize = 5000)
    zfAvatarCache[k] = s
    s
  } catch (e: Exception) {
    null
  }
}

/** Contactos a quienes se puede escribir ahora (conectados y activos). Llamar desde el hilo principal. */
private fun zfTargets(): List<Chat> = chatModel.chats.value.filter { c ->
  val ci = c.chatInfo
  c.remoteHostId == null && ci is ChatInfo.Direct &&
    ci.contact.ready && ci.contact.active && !ci.contact.chatDeleted && !ci.contact.sendMsgToConnect
}

/** Chats directos de los que se leen publicaciones. Llamar desde el hilo principal. */
private fun zfReadable(): List<Chat> = chatModel.chats.value.filter { c ->
  val ci = c.chatInfo
  ci is ChatInfo.Direct && !ci.contact.chatDeleted
}

private fun zfChatById(id: String): Chat? = chatModel.chats.value.firstOrNull { it.id == id }

// ---------------------------------------------------------------------------
// Construcción de las publicaciones a partir de los mensajes
// ---------------------------------------------------------------------------
private class ZfComment(
  val cid: String,
  val key: String,
  val name: String,
  val avatar: String?,
  var text: String,
  val reply: String,
  val tsMs: Long,
  val tsText: String,
  val mine: Boolean,
  val ci: ChatItem,
  val rh: Long?
) {
  val origText: String = text
  val origCi: ChatItem = ci
  var mediaCi: ChatItem? = ci   // archivo que se muestra ahora (cambia si se edita; null = se quitó)
  var edited: Boolean = false
  var counts: Map<String, Int> = emptyMap()
  var myReaction: String? = null
  var reactors: List<ZfReactor> = emptyList()
}

private class ZfReactor(val key: String, val name: String, val emoji: String, val avatar: String?)

private class ZfPost(
  val key: String,
  val pid: String,
  val mine: Boolean,
  val authorName: String,
  val authorImage: String?,
  val ci: ChatItem,
  val rh: Long?,
  val chatId: String,
  val text: String,
  val origText: String,
  val origCi: ChatItem,
  val mediaCi: ChatItem?,
  val edited: Boolean,
  val tsMs: Long,
  val comments: List<ZfComment>,
  val counts: Map<String, Int>,
  val myReaction: String?,
  val reactors: List<ZfReactor>,
  val options: List<String>,
  val voteCounts: List<Int>,
  val myVote: Int,
  val voters: List<ZfVoter>
)

private class ZfVoter(val key: String, val name: String, val avatar: String?, val option: Int)

private class ZfReact(val tsMs: Long, val emoji: String, val name: String, val avatar: String?)

private class ZfAcc(val key: String, var ev: ZfEvent, val mine: Boolean) {
  val comments = HashMap<String, ZfComment>()
  val reacts = HashMap<String, ZfReact>()
  val creacts = HashMap<String, HashMap<String, ZfReact>>()   // comentario -> quien -> reacción
  val votes = HashMap<String, ZfReact>()                      // quien -> voto de la encuesta ("#1".."#6", "#0" = retirado)
  val deletedComments = HashSet<String>()
  val chats = HashSet<String>()
  var editText: String? = null                                // texto vigente tras editar la publicación
  var editMedia: ChatItem? = null                             // archivo nuevo tras editar
  var editDrop: Boolean = false                               // se quitó el archivo
}

private fun zfAddComment(a: ZfAcc, e: ZfEvent, key: String, name: String, avatar: String?, mine: Boolean) {
  if ((e.body.isEmpty() && !zfIsMediaItem(e.ci)) || a.comments.containsKey(e.cid)) return
  a.comments[e.cid] = ZfComment(
    e.cid, key, name, avatar, e.body, e.reply, e.tsMs, getTimestampText(e.ci.meta.itemTs, true), mine, e.ci, e.rh
  )
}

private fun zfAddReact(a: ZfAcc, e: ZfEvent, reactor: String, name: String, avatar: String?) {
  if (e.emoji.startsWith("#")) {
    // voto de una encuesta (solo en la publicación)
    if (e.reply.isNotEmpty()) return
    val pv = a.votes[reactor]
    if (pv == null || e.tsMs >= pv.tsMs) a.votes[reactor] = ZfReact(e.tsMs, e.emoji, name, avatar)
    return
  }
  val map = if (e.reply.isEmpty()) a.reacts else a.creacts.getOrPut(e.reply) { HashMap() }
  val prev = map[reactor]
  if (prev == null || e.tsMs >= prev.tsMs) map[reactor] = ZfReact(e.tsMs, e.emoji, name, avatar)
}

/** ¿Eliminé yo esta publicación? (se recuerda en el teléfono por si no hay a quién avisar) */
private fun zfDeletedLocalKey(pid: String): String = "zetaria_feed_del_" + zfUserId + "_" + pid

private fun zfIsDeletedLocal(pid: String): Boolean = try {
  settings.getBoolean(zfDeletedLocalKey(pid), false)
} catch (e: Exception) {
  false
}

private fun zfMarkDeletedLocal(pid: String) {
  try {
    settings.putBoolean(zfDeletedLocalKey(pid), true)
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed delete flag: " + e.stackTraceToString())
  }
}

private fun zfRelayedAvatar(e: ZfEvent): String? =
  if (e.key == "a") e.chatImage else (if (e.avatar.isEmpty()) null else e.avatar)

private fun zfApplyCommentEdit(cm: ZfComment, e: ZfEvent) {
  cm.text = e.body
  cm.edited = true
  if (e.dropMedia) {
    cm.mediaCi = null
  } else if (zfHasMedia(e.ci)) {
    cm.mediaCi = e.ci
  }
}

private fun zfBuildPosts(): List<ZfPost> {
  val all = ArrayList<ZfEvent>()
  for (m in zfEvents.values) all.addAll(m.values)
  val me = chatModel.currentUser.value
  val myImage = me?.image
  val mineAcc = HashMap<String, ZfAcc>()   // pid -> publicación mía
  val theirAcc = HashMap<String, ZfAcc>()  // "pid|chat" -> publicación de un contacto
  val deletedMine = HashSet<String>()      // pid de publicaciones mías eliminadas
  val deletedTheirs = HashSet<String>()    // "pid|chat" de publicaciones eliminadas por su autor
  val delComments = ArrayList<ZfEvent>()   // eliminaciones de comentarios
  val edits = ArrayList<ZfEvent>()         // ediciones de publicaciones y comentarios
  for (e in all) {
    if (e.kind == ZfKind.DEL) {
      if (e.reply.isNotEmpty()) {
        delComments.add(e)
      } else if (e.sent) {
        deletedMine.add(e.pid)
      } else {
        deletedTheirs.add(e.pid + "|" + e.chatId)
      }
    } else if (e.kind == ZfKind.EDIT) {
      edits.add(e)
    }
  }
  for (e in all) {
    if (e.kind != ZfKind.POST) continue
    if (e.sent) {
      val a = mineAcc.getOrPut(e.pid) { ZfAcc(e.pid + "|me", e, true) }
      a.chats.add(e.chatId)
      if (e.tsMs < a.ev.tsMs) a.ev = e
    } else {
      val k = e.pid + "|" + e.chatId
      theirAcc.getOrPut(k) { ZfAcc(k, e, false) }
    }
  }
  for (e in all) {
    when (e.kind) {
      ZfKind.POST -> {}
      ZfKind.DEL -> {}
      ZfKind.EDIT -> {}
      ZfKind.COMMENT -> {
        if (!e.sent) {
          // un contacto comenta una publicación mía
          val a = mineAcc[e.pid]
          if (a != null && a.chats.contains(e.chatId)) zfAddComment(a, e, "c" + e.contactId, e.chatName, e.chatImage, false)
        } else {
          // yo comenté la publicación de un contacto
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddComment(a, e, "me", "Tú", myImage, true)
        }
      }
      ZfKind.CREL -> {
        if (e.sent) {
          // mi propio comentario en mi publicación
          val a = mineAcc[e.pid]
          if (a != null && e.key == "a") zfAddComment(a, e, "a", "Tú", myImage, true)
        } else {
          // comentario de otra persona, reenviado por el autor
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddComment(a, e, e.key, e.name, zfRelayedAvatar(e), false)
        }
      }
      ZfKind.REACT -> {
        if (!e.sent) {
          val a = mineAcc[e.pid]
          if (a != null && a.chats.contains(e.chatId)) zfAddReact(a, e, "c" + e.contactId, e.chatName, e.chatImage)
        } else {
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddReact(a, e, "me", "Tú", myImage)
        }
      }
      ZfKind.RREL -> {
        if (e.sent) {
          val a = mineAcc[e.pid]
          if (a != null && e.key == "a") zfAddReact(a, e, "a", "Tú", myImage)
        } else {
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddReact(a, e, e.key, e.name, zfRelayedAvatar(e))
        }
      }
    }
  }
  for (e in delComments) {
    if (e.sent) {
      // yo eliminé un comentario (mío, o lo reenvié como autor tras validarlo)
      val a = mineAcc[e.pid] ?: theirAcc[e.pid + "|" + e.chatId]
      if (a != null) a.deletedComments.add(e.reply)
    } else {
      val t = theirAcc[e.pid + "|" + e.chatId]
      if (t != null) {
        // el autor avisa que se eliminó un comentario
        t.deletedComments.add(e.reply)
      } else {
        // un contacto elimina su propio comentario en mi publicación
        val m = mineAcc[e.pid]
        if (m != null && m.chats.contains(e.chatId)) {
          val cm = m.comments[e.reply]
          if (cm != null && cm.key == "c" + e.contactId) m.deletedComments.add(e.reply)
        }
      }
    }
  }
  // ediciones: de más vieja a más nueva, así gana la última
  edits.sortBy { it.tsMs }
  for (e in edits) {
    if (e.reply.isEmpty()) {
      // editar una publicación: solo la hace su autor
      val a = if (e.sent) mineAcc[e.pid] else theirAcc[e.pid + "|" + e.chatId]
      if (a != null && a.ev.options.isEmpty()) {
        a.editText = e.body
        if (e.dropMedia) {
          a.editDrop = true
          a.editMedia = null
        } else if (zfHasMedia(e.ci)) {
          a.editDrop = false
          a.editMedia = e.ci
        }
      }
    } else if (e.sent) {
      // yo edité mi comentario (o, como autor, reenvié la edición de un contacto tras validarla)
      val m = mineAcc[e.pid]
      if (m != null) {
        val cm = m.comments[e.reply]
        if (cm != null) zfApplyCommentEdit(cm, e)
      } else {
        val th = theirAcc[e.pid + "|" + e.chatId]
        val cm = th?.comments?.get(e.reply)
        if (cm != null && cm.mine) zfApplyCommentEdit(cm, e)
      }
    } else {
      val th = theirAcc[e.pid + "|" + e.chatId]
      if (th != null) {
        // el autor avisa que se editó un comentario
        val cm = th.comments[e.reply]
        if (cm != null) zfApplyCommentEdit(cm, e)
      } else {
        // un contacto edita su propio comentario en mi publicación
        val m = mineAcc[e.pid]
        if (m != null && m.chats.contains(e.chatId)) {
          val cm = m.comments[e.reply]
          if (cm != null && cm.key == "c" + e.contactId) zfApplyCommentEdit(cm, e)
        }
      }
    }
  }
  val res = ArrayList<ZfPost>()
  for (a in mineAcc.values + theirAcc.values) {
    if (a.mine && (deletedMine.contains(a.ev.pid) || zfIsDeletedLocal(a.ev.pid))) continue
    if (!a.mine && deletedTheirs.contains(a.key)) continue
    val counts = HashMap<String, Int>()
    val reactors = ArrayList<ZfReactor>()
    for (en in a.reacts.entries) {
      val r = en.value
      if (r.emoji == "-") continue
      counts[r.emoji] = (counts[r.emoji] ?: 0) + 1
      reactors.add(ZfReactor(en.key, r.name, r.emoji, r.avatar))
    }
    val myKey = if (a.mine) "a" else "me"
    val liveComments = a.comments.values.filter { !a.deletedComments.contains(it.cid) }
    for (cm in liveComments) {
      val cr = a.creacts[cm.cid] ?: continue
      val cc = HashMap<String, Int>()
      val rs = ArrayList<ZfReactor>()
      for (en in cr.entries) {
        val r = en.value
        if (r.emoji == "-") continue
        cc[r.emoji] = (cc[r.emoji] ?: 0) + 1
        rs.add(ZfReactor(en.key, r.name, r.emoji, r.avatar))
      }
      cm.counts = cc
      cm.reactors = rs
      val my = cr[myKey]?.emoji
      cm.myReaction = if (my == null || my == "-") null else my
    }
    val mr = a.reacts[myKey]?.emoji
    val opts = a.ev.options
    val vc = ArrayList<Int>()
    for (i in opts.indices) vc.add(0)
    val voters = ArrayList<ZfVoter>()
    for (en in a.votes.entries) {
      val idx = en.value.emoji.removePrefix("#").toIntOrNull() ?: continue
      if (idx < 1 || idx > opts.size) continue
      vc[idx - 1] = vc[idx - 1] + 1
      voters.add(ZfVoter(en.key, en.value.name, en.value.avatar, idx))
    }
    val mv = a.votes[myKey]?.emoji?.removePrefix("#")?.toIntOrNull() ?: 0
    res.add(
      ZfPost(
        key = a.key,
        pid = a.ev.pid,
        mine = a.mine,
        authorName = if (a.mine) "Tú" else a.ev.chatName,
        authorImage = if (a.mine) myImage else a.ev.chatImage,
        ci = a.ev.ci,
        rh = a.ev.rh,
        chatId = a.ev.chatId,
        text = a.editText ?: a.ev.body,
        origText = a.ev.body,
        origCi = a.ev.ci,
        mediaCi = if (a.editDrop) null else (a.editMedia ?: a.ev.ci),
        edited = a.editText != null,
        tsMs = a.ev.tsMs,
        comments = liveComments.sortedBy { it.tsMs },
        counts = counts,
        myReaction = if (mr == null || mr == "-") null else mr,
        reactors = reactors,
        options = opts,
        voteCounts = vc,
        myVote = if (mv >= 1 && mv <= opts.size) mv else 0,
        voters = voters
      )
    )
  }
  res.sortByDescending { it.tsMs }
  return res
}

// ---------------------------------------------------------------------------
// Lectura: se vuelven a cargar los mensajes del feed de cada contacto
// ---------------------------------------------------------------------------
/**
 * Lee mensajes de un chat directo sin avisos de error y solo si el chat está en marcha
 * (al iniciar o detener la app el núcleo no responde y apiGetChat mostraba «Fallo en la carga del chat»).
 */
private suspend fun zfGetChatQuiet(rh: Long?, id: Long, pagination: ChatPagination, search: String): Pair<Chat, NavigationInfo>? {
  if (chatModel.chatRunning.value != true) return null
  return try {
    val r = chatModel.controller.sendCmd(rh, CC.ApiGetChat(ChatType.Direct, id, null, null, pagination, search), log = false)
    if (r is API.Result && r.res is CR.ApiChat) {
      if (rh == null) r.res.chat to r.res.navInfo else r.res.chat.copy(remoteHostId = rh) to r.res.navInfo
    } else {
      Log.e(TAG, "zetaria feed get chat: " + r.responseType)
      null
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed get chat: " + e.stackTraceToString())
    null
  }
}

/** Hasta cuántos chats se leen a la vez al construir el feed completo (no afecta la lista de Chats). */
private val zfScanGate = Semaphore(4)

/** Devuelve true si se leyeron todos los chats sin errores. Los lee en paralelo (hasta 4 a la vez) para que abrir el Feed sea más rápido. */
private suspend fun zfRefresh(): Boolean {
  if (chatModel.chatRunning.value != true) return false
  if (!zfRefreshing.compareAndSet(false, true)) return false
  withContext(Dispatchers.Main) { zfLoading.value = true }
  var complete = false
  try {
    val chats = withContext(Dispatchers.Main) { zfReadable() }
    val allRead = coroutineScope {
      chats.map { c ->
        async {
          zfScanGate.acquire()
          try {
            val res = zfGetChatQuiet(c.remoteHostId, c.chatInfo.apiId, ChatPagination.Last(100), ZF_TOK)
              ?: return@async false
            val chat = res.first
            val list = ArrayList<ZfEvent>()
            for (ci in chat.chatItems) {
              val e = zfEventOf(c.remoteHostId, chat.chatInfo, ci)
              if (e != null) list.add(e)
            }
            withContext(Dispatchers.Main) { zfSetChat(chat.chatInfo.id, list) }
            true
          } finally {
            zfScanGate.release()
          }
        }
      }.awaitAll().all { it }
    }
    withContext(Dispatchers.Main) { zfInitSeen() }
    complete = allRead
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed refresh: " + e.stackTraceToString())
  } finally {
    zfRefreshing.set(false)
    withContext(NonCancellable + Dispatchers.Main) { zfLoading.value = false }
  }
  return complete
}

/** Espera a que no haya una lectura en curso y lee de nuevo (para tener el almacén completo). */
private suspend fun zfRefreshNow(): Boolean {
  var n = 0
  while (zfRefreshing.get() && n < 40) {
    delay(250)
    n++
  }
  return zfRefresh()
}

/**
 * Lee el feed y, si la lectura fue completa, reenvía publicaciones anteriores a contactos que no las tienen.
 * force = botón «Actualizar»: se revisa a todos los contactos y se les envían TODAS las publicaciones que les falten,
 * sin el tope de ZF_BACKFILL_POSTS que se usa en el envío automático.
 */
private suspend fun zfRefreshAll(force: Boolean = false) {
  if (zfRefresh()) {
    withContext(Dispatchers.Main) { zfSending.value = true }
    try {
      zfReconcile(force)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed reconcile: " + e.stackTraceToString())
    } finally {
      withContext(NonCancellable + Dispatchers.Main) { zfSending.value = false }
    }
  }
}

// ---------------------------------------------------------------------------
// Envío (mismo API que el compositor)
// ---------------------------------------------------------------------------
private suspend fun zfSendOne(c: Chat, mc: MsgContent, file: CryptoFile?): Boolean {
  val sent = chatModel.controller.apiSendMessages(
    rh = c.remoteHostId,
    type = c.chatInfo.chatType,
    id = c.chatInfo.apiId,
    scope = c.chatInfo.groupChatScope(),
    sendAsGroup = c.chatInfo.sendAsGroup,
    live = false,
    ttl = null,
    sign = false,
    composedMessages = listOf(ComposedMessage(file, null, mc, emptyMap()))
  )
  if (sent.isNullOrEmpty()) return false
  withContext(Dispatchers.Main) {
    for (a in sent) {
      val e = zfEventOf(c.remoteHostId, a.chatInfo, a.chatItem)
      if (e != null) zfPut(e)
    }
    zfBump()
  }
  return true
}

/** Archivo de un mensaje si ya está completo en el teléfono. */
private fun zfFileOf(ci: ChatItem): CryptoFile? {
  val f = ci.file ?: return null
  val probe = if (f.fileStatus.sent) f else f.copy(fileStatus = CIFileStatus.RcvComplete)
  return getLoadedFileSource(probe)
}

/** Mismo tipo de contenido que el original (imagen o video) pero con otro texto. */
private fun zfMakeContent(orig: MsgContent?, text: String): MsgContent = when (orig) {
  is MsgContent.MCImage -> MsgContent.MCImage(text, orig.image)
  is MsgContent.MCVideo -> MsgContent.MCVideo(text, orig.image, orig.duration)
  is MsgContent.MCFile -> MsgContent.MCFile(text)
  else -> MsgContent.MCText(text)
}

private class ZfOut(val mc: MsgContent?, val file: CryptoFile?, val error: String?)

/** Prepara el contenido (texto, imagen o video) de una publicación o comentario. */
private suspend fun zfPrepare(media: URI?, video: Boolean, caption: String, audio: Boolean = false): ZfOut {
  if (media == null) return ZfOut(MsgContent.MCText(caption), null, null)
  if (audio) {
    // «audio» = archivo adjunto: los audios conservan el trato de siempre y cualquier otro archivo se envía con su nombre
    val nm = try {
      getFileName(media) ?: ""
    } catch (e: Exception) {
      ""
    }
    val isAudio = zfIsAudioName(nm)
    val file = (if (isAudio) saveFileFromUri(media, ZF_MAX_AUDIO_BYTES, hiddenFileNamePrefix = "audio") else saveFileFromUri(media, ZF_MAX_FILE_BYTES))
      ?: return ZfOut(null, null, if (isAudio) "No se pudo preparar el audio (máximo 25 MB)." else "No se pudo preparar el archivo (máximo 50 MB).")
    return ZfOut(MsgContent.MCFile(caption), file, null)
  }
  if (!video) {
    val bmp = getBitmapFromUri(media) ?: return ZfOut(null, null, "No se pudo leer la imagen.")
    val preview = resizeImageToStrSize(bmp, maxDataSize = 14000)
    val file = saveImage(bmp) ?: return ZfOut(null, null, "No se pudo preparar la imagen.")
    return ZfOut(MsgContent.MCImage(caption, preview), file, null)
  }
  val pd = getBitmapFromVideo(media, withAlertOnException = false)
  val frame = pd.preview ?: return ZfOut(null, null, "No se pudo leer el video.")
  val secs = ((pd.duration ?: 0L) / 1000L).toInt()
  val preview = resizeImageToStrSize(frame, maxDataSize = 14000)
  val file = saveFileFromUri(media, ZF_MAX_VIDEO_BYTES, hiddenFileNamePrefix = "video")
    ?: return ZfOut(null, null, "No se pudo preparar el video (máximo 60 MB).")
  return ZfOut(MsgContent.MCVideo(caption, preview, secs), file, null)
}

/** Publica en el feed. Devuelve null si salió bien, o el motivo del error. */
private suspend fun zfPublish(text: String, media: URI?, video: Boolean, audio: Boolean, options: List<String>): String? {
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  if (targets.isEmpty()) return "Aún no tienes contactos conectados a quienes publicar."
  val lines = ArrayList<String>()
  val pidNew = zfNewId(10)
  lines.add(ZF_POST + pidNew)
  for (o in options) lines.add(ZF_META_OPT + o.replace("\n", " ").trim().take(80))
  if (text.isNotEmpty()) lines.add(text)
  val out = zfPrepare(if (options.isEmpty()) media else null, video, lines.joinToString("\n"), audio)
  val err = out.error
  if (err != null) return err
  val mc = out.mc ?: return "No se pudo preparar la publicación."
  var ok = 0
  for (c in targets) {
    if (zfSendOne(c, mc, out.file)) {
      ok++
      withContext(Dispatchers.Main) { zfMarkSent(c, pidNew) }
    }
  }
  return if (ok == 0) "No se pudo enviar la publicación." else null
}

/** Comenta (o responde a un comentario, con replyTo). Devuelve null si salió bien, o el motivo del error. */
private suspend fun zfComment(p: ZfPost, text: String, media: URI?, video: Boolean, replyTo: String?): String? {
  val t = text.trim().replace("\r", "").take(ZF_MAX_COMMENT)
  if (t.isEmpty() && media == null) return "Escribe algo o adjunta una imagen o un video."
  val cid = zfNewId(8)
  val lines = ArrayList<String>()
  if (p.mine) {
    lines.add(ZF_CREL + p.pid + " " + cid + " a " + zfMyName())
  } else {
    lines.add(ZF_COMMENT + p.pid + " " + cid)
  }
  if (replyTo != null) lines.add(ZF_META_REPLY + replyTo)
  if (t.isNotEmpty()) lines.add(t)
  val out = zfPrepare(media, video, lines.joinToString("\n"))
  val err = out.error
  if (err != null) return err
  val mc = out.mc ?: return "No se pudo preparar el comentario."
  if (!p.mine) {
    val c = withContext(Dispatchers.Main) { zfChatById(p.chatId) } ?: return "No se encontró el chat del autor."
    return if (zfSendOne(c, mc, out.file)) null else "No se pudo enviar el comentario."
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, mc, out.file)) ok = true
  }
  return if (ok) null else "No se pudo enviar el comentario."
}

/** emoji = una reacción, o "-" para quitar la mía. target = id del comentario al que se reacciona (null = la publicación). */
private suspend fun zfReact(p: ZfPost, emoji: String, target: String?): Boolean {
  val metaLine = if (target != null) "\n" + ZF_META_REPLY + target else ""
  if (!p.mine) {
    val c = withContext(Dispatchers.Main) { zfChatById(p.chatId) } ?: return false
    return zfSendOne(c, MsgContent.MCText(ZF_REACT + p.pid + " " + emoji + metaLine), null)
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  val msg = ZF_RREL + p.pid + " a " + emoji + " " + zfMyName() + metaLine
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, MsgContent.MCText(msg), null)) ok = true
  }
  return ok
}

/** Elimina una publicación mía: desaparece de mi feed y del de mis contactos. */
private suspend fun zfDeletePost(p: ZfPost): Boolean {
  if (!p.mine) return false
  withContext(Dispatchers.Main) {
    zfMarkDeletedLocal(p.pid)
    zfBump()
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  for (c in targets) {
    zfSendOne(c, MsgContent.MCText(ZF_DEL + p.pid), null)
  }
  return true
}

/** Elimina un comentario mío: desaparece del feed de todos. */
private suspend fun zfDeleteComment(p: ZfPost, cm: ZfComment): Boolean {
  val msg = ZF_DEL + p.pid + "\n" + ZF_META_REPLY + cm.cid
  if (!p.mine) {
    val c = withContext(Dispatchers.Main) { zfChatById(p.chatId) } ?: return false
    return zfSendOne(c, MsgContent.MCText(msg), null)
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, MsgContent.MCText(msg), null)) ok = true
  }
  return ok
}

/** Lo que se envía al editar: texto nuevo y, opcionalmente, un archivo nuevo (uri) o quitar el actual (remove). */
private class ZfEditInput(val text: String, val uri: URI?, val video: Boolean, val audio: Boolean, val remove: Boolean)

/** Edita una publicación mía (texto, imagen, video o audio; las encuestas no se editan). */
private suspend fun zfEditPost(p: ZfPost, inp: ZfEditInput): String? {
  if (!p.mine) return "Solo puedes editar tus publicaciones."
  if (p.options.isNotEmpty()) return "Las encuestas no se pueden editar."
  val t = inp.text.trim().replace("\r", "").take(ZF_MAX_TEXT)
  val hadMedia = zfHasMedia(p.mediaCi)
  val newHasMedia = inp.uri != null || (!inp.remove && hadMedia)
  if (t.isEmpty() && !newHasMedia) return "La publicación no puede quedar vacía."
  if (t == p.text && inp.uri == null && !(inp.remove && hadMedia)) return null
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  if (targets.isEmpty()) return "Aún no tienes contactos conectados."
  val lines = ArrayList<String>()
  lines.add(ZF_EDIT + p.pid)
  if (inp.uri == null && inp.remove && hadMedia) lines.add(ZF_META_DROP + "1")
  if (t.isNotEmpty()) lines.add(t)
  val out = zfPrepare(inp.uri, inp.video, lines.joinToString("\n"), inp.audio)
  val err = out.error
  if (err != null) return err
  val mc = out.mc ?: return "No se pudo preparar la edición."
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, mc, out.file)) ok = true
  }
  return if (ok) null else "No se pudo enviar la edición."
}

/** Edita un comentario mío (texto, imagen o video): se actualiza para todos. */
private suspend fun zfEditComment(p: ZfPost, cm: ZfComment, inp: ZfEditInput): String? {
  val t = inp.text.trim().replace("\r", "").take(ZF_MAX_COMMENT)
  val hadMedia = zfHasMedia(cm.mediaCi)
  val newHasMedia = inp.uri != null || (!inp.remove && hadMedia)
  if (t.isEmpty() && !newHasMedia) return "El comentario no puede quedar vacío."
  if (t == cm.text && inp.uri == null && !(inp.remove && hadMedia)) return null
  val lines = ArrayList<String>()
  lines.add(ZF_EDIT + p.pid)
  lines.add(ZF_META_REPLY + cm.cid)
  if (inp.uri == null && inp.remove && hadMedia) lines.add(ZF_META_DROP + "1")
  if (t.isNotEmpty()) lines.add(t)
  val out = zfPrepare(inp.uri, inp.video, lines.joinToString("\n"))
  val err = out.error
  if (err != null) return err
  val mc = out.mc ?: return "No se pudo preparar la edición."
  if (!p.mine) {
    val c = withContext(Dispatchers.Main) { zfChatById(p.chatId) } ?: return "No se encontró el chat del autor."
    return if (zfSendOne(c, mc, out.file)) null else "No se pudo enviar la edición."
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, mc, out.file)) ok = true
  }
  return if (ok) null else "No se pudo enviar la edición."
}

/** Vuelve a publicar en MI feed una publicación (mía o ajena), con una nota de quién es el autor original. */
private suspend fun zfShareToFeed(p: ZfPost, note: String): String? {
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  if (targets.isEmpty()) return "Aún no tienes contactos conectados a quienes compartir."
  val lines = ArrayList<String>()
  val pidNew = zfNewId(10)
  lines.add(ZF_POST + pidNew)
  for (o in p.options) lines.add(ZF_META_OPT + o)
  val body = StringBuilder()
  body.append(if (p.mine) "🔁 Compartiendo mi publicación" else "🔁 Compartido de " + zfClean(p.authorName))
  val extra = note.trim()
  if (extra.isNotEmpty()) body.append("\n").append(extra)
  if (p.text.isNotEmpty()) body.append("\n\n").append(p.text)
  lines.add(body.toString().take(ZF_MAX_TEXT))
  val mci = p.mediaCi
  val src = if (mci != null && zfHasMedia(mci)) zfFileOf(mci) else null
  val mc = zfMakeContent(if (src != null) mci?.content?.msgContent else null, lines.joinToString("\n"))
  var ok = 0
  for (c in targets) {
    if (zfSendOne(c, mc, src)) {
      ok++
      withContext(Dispatchers.Main) { zfMarkSent(c, pidNew) }
    }
  }
  return if (ok == 0) "No se pudo compartir la publicación." else null
}

// ---------------------------------------------------------------------------
// Mensajes que llegan: se marcan como leídos (no cuentan como mensaje sin leer), entran al feed y,
// si soy el autor, reenvío los comentarios y reacciones al resto de mis contactos.
// ---------------------------------------------------------------------------
fun zetariaFeedOnNewItem(rhId: Long?, user: UserLike, cInfo: ChatInfo, cItem: ChatItem) {
  if (!zetariaIsFeedItem(cItem)) return
  withLongRunningApi {
    try {
      zfHandleIncoming(rhId, user, cInfo, cItem)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed: " + e.stackTraceToString())
    }
  }
}

private suspend fun zfIsActiveUser(user: UserLike): Boolean =
  withContext(Dispatchers.Main) { chatModel.currentUser.value?.userId == user.userId }

private suspend fun zfHandleIncoming(rhId: Long?, user: UserLike, cInfo: ChatInfo, ci: ChatItem) {
  if (!zfIsActiveUser(user)) return
  if (ci.isRcvNew) {
    val updated = chatModel.controller.apiChatItemsRead(rhId, cInfo.chatType, cInfo.apiId, cInfo.groupChatScope(), listOf(ci.id))
    if (updated != null) {
      withContext(Dispatchers.Main) { chatModel.chatsContext.updateChatInfo(rhId, updated) }
    }
  }
  val ev = zfEventOf(rhId, cInfo, ci) ?: return
  withContext(Dispatchers.Main) {
    zfPut(ev)
    zfBump()
  }
  if (ev.sent) return
  if (ev.kind != ZfKind.COMMENT && ev.kind != ZfKind.REACT && !((ev.kind == ZfKind.DEL || ev.kind == ZfKind.EDIT) && ev.reply.isNotEmpty())) return
  if (ev.kind == ZfKind.COMMENT || ev.kind == ZfKind.EDIT) {
    val f = ci.file
    if (f != null && zfFileOf(ci) == null) {
      // comentario con imagen o video: se reenvía cuando el archivo termine de descargarse
      chatModel.controller.receiveFiles(rhId, user, listOf(f.fileId), userApprovedRelays = false, auto = true)
      return
    }
  }
  zfRelayFromContact(rhId, cInfo, ev)
}

/** Reenvía al resto de mis contactos un comentario o reacción que me llegó, si la publicación es mía. */
private suspend fun zfRelayFromContact(rhId: Long?, cInfo: ChatInfo, ev: ZfEvent) {
  val r = zfGetChatQuiet(rhId, cInfo.apiId, ChatPagination.Last(300), ev.pid) ?: return
  var mineHere = false
  var commentFromHim = false
  for (x in r.first.chatItems) {
    val xe = zfEventOf(rhId, r.first.chatInfo, x) ?: continue
    if (x.chatDir.sent) {
      if (xe.kind == ZfKind.POST && xe.pid == ev.pid) mineHere = true
    } else if (xe.kind == ZfKind.COMMENT && xe.pid == ev.pid && xe.cid == ev.reply) {
      commentFromHim = true
    }
  }
  if (!mineHere) return
  // solo quien escribió un comentario puede eliminarlo o editarlo
  if ((ev.kind == ZfKind.DEL || ev.kind == ZfKind.EDIT) && !commentFromHim) return
  val targets = withContext(Dispatchers.Main) { zfTargets() }.filter { it.chatInfo.id != cInfo.id }
  if (targets.isEmpty()) return
  val key = "c" + ev.contactId
  val name = zfClean(ev.chatName)
  val avatar = zfSmallAvatar(ev.chatImage)
  val lines = ArrayList<String>()
  var src: CryptoFile? = null
  if (ev.kind == ZfKind.DEL) {
    lines.add(ZF_DEL + ev.pid)
    lines.add(ZF_META_REPLY + ev.reply)
  } else if (ev.kind == ZfKind.EDIT) {
    lines.add(ZF_EDIT + ev.pid)
    lines.add(ZF_META_REPLY + ev.reply)
    if (ev.dropMedia) lines.add(ZF_META_DROP + "1")
    if (ev.body.isNotEmpty()) lines.add(ev.body)
    src = zfFileOf(ev.ci)
  } else if (ev.kind == ZfKind.COMMENT) {
    lines.add(ZF_CREL + ev.pid + " " + ev.cid + " " + key + " " + name)
    if (ev.reply.isNotEmpty()) lines.add(ZF_META_REPLY + ev.reply)
    if (avatar != null) lines.add(ZF_META_AVATAR + avatar)
    if (ev.body.isNotEmpty()) lines.add(ev.body)
    src = zfFileOf(ev.ci)
  } else {
    lines.add(ZF_RREL + ev.pid + " " + key + " " + ev.emoji + " " + name)
    if (ev.reply.isNotEmpty()) lines.add(ZF_META_REPLY + ev.reply)
    if (avatar != null && ev.emoji != "-") lines.add(ZF_META_AVATAR + avatar)
  }
  val mc = zfMakeContent(if (src != null) ev.ci.content.msgContent else null, lines.joinToString("\n"))
  for (c in targets) {
    zfSendOne(c, mc, src)
  }
}

// ---------------------------------------------------------------------------
// Archivo recibido completo: guarda las imágenes en la galería (Pictures/Zetaria) y, si es un comentario
// con imagen o video sobre una publicación mía, lo reenvía al resto.
// ---------------------------------------------------------------------------
fun zetariaFeedOnFileComplete(rhId: Long?, user: UserLike, cInfo: ChatInfo, ci: ChatItem) {
  withLongRunningApi {
    try {
      zfHandleFileComplete(rhId, user, cInfo, ci)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed file: " + e.stackTraceToString())
    }
  }
}

private suspend fun zfHandleFileComplete(rhId: Long?, user: UserLike, cInfo: ChatInfo, ci: ChatItem) {
  val file = ci.file ?: return
  if (ci.content.msgContent is MsgContent.MCImage && !zetariaIsFeedItem(ci) && zfAutoSaveOn()) {
    val k = user.userId.toString() + ":" + file.fileId
    if (zfAutoSaved.add(k)) zetariaSaveToGallery(file, false, false, true)
  }
  val ev = zfEventOf(rhId, cInfo, ci) ?: return
  if (ev.sent || (ev.kind != ZfKind.COMMENT && !(ev.kind == ZfKind.EDIT && ev.reply.isNotEmpty()))) return
  if (!zfIsActiveUser(user)) return
  zfRelayFromContact(rhId, cInfo, ev)
}

// ---------------------------------------------------------------------------
// Contacto nuevo: se le reenvían mis publicaciones anteriores (con sus comentarios y reacciones),
// como cuando te unes a un canal. Se dispara al conectarse el contacto y, por si ese aviso falla,
// también al leer el feed (se reenvía a cada contacto lo que le falta).
// ---------------------------------------------------------------------------
private val zfBackfilling: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()

private fun zfBfKey(userId: Long, contactId: Long): String = "zetaria_feed_bf2_" + userId + "_" + contactId

private fun zfFlag(key: String): Boolean = try {
  settings.getBoolean(key, false)
} catch (e: Exception) {
  false
}

private fun zfSetFlag(key: String) {
  try {
    settings.putBoolean(key, true)
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed flag: " + e.stackTraceToString())
  }
}

fun zetariaFeedOnContactReady(rhId: Long?, user: UserLike, contact: Contact) {
  if (rhId != null || !contact.directOrUsed) return
  val contactId = contact.contactId
  withLongRunningApi {
    try {
      zfBackfillContact(user, contactId, true, false)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed backfill: " + e.stackTraceToString())
    }
  }
}

private fun zfChatOfContact(contactId: Long): Chat? = zfTargets().firstOrNull { c ->
  val ci = c.chatInfo
  ci is ChatInfo.Direct && ci.contact.contactId == contactId
}

private fun zfSentKey(userId: Long, contactId: Long, pid: String): String = "zetaria_feed_sent_" + userId + "_" + contactId + "_" + pid

/** Recuerda en el teléfono que ya se le envió esa publicación a ese contacto (el almacén solo lee los últimos mensajes). */
private fun zfMarkSent(chat: Chat, pid: String) {
  val ci = chat.chatInfo
  if (ci !is ChatInfo.Direct) return
  zfSetFlag(zfSentKey(zfUserId, ci.contact.contactId, pid))
}

/** ¿Ya le envié a ese chat esta publicación? Llamar desde el hilo principal con el almacén cargado. */
private fun zfChatHasMyPost(chatId: String, pid: String): Boolean {
  val m = zfEvents[chatId]
  if (m != null) {
    for (e in m.values) {
      if (e.kind == ZfKind.POST && e.sent && e.pid == pid) return true
    }
  }
  val ci = zfChatById(chatId)?.chatInfo
  if (ci is ChatInfo.Direct && zfFlag(zfSentKey(zfUserId, ci.contact.contactId, pid))) return true
  return false
}

/**
 * Envía a un contacto las publicaciones mías que no tiene. wait = esperar a que el chat esté listo
 * (aviso de conexión). refreshed = el almacén ya se acaba de leer completo.
 * force = ignora la marca de «ya enviado» y el tope de publicaciones (botón «Actualizar»).
 */
private suspend fun zfBackfillContact(user: UserLike, contactId: Long, wait: Boolean, refreshed: Boolean, force: Boolean = false): Boolean {
  if (!zfIsActiveUser(user)) return false
  val flag = zfBfKey(user.userId, contactId)
  if (!force && zfFlag(flag)) return true
  if (!zfBackfilling.add(contactId)) return false
  try {
    var found: Chat? = null
    var tries = 0
    while (found == null) {
      found = withContext(Dispatchers.Main) { zfChatOfContact(contactId) }
      if (found != null || !wait || tries >= 20) break
      delay(1000)
      tries++
    }
    val chat = found ?: return false
    if (!refreshed && !zfRefreshNow()) return false
    val missing = withContext(Dispatchers.Main) {
      val list = zfBuildPosts()
        .filter { it.mine && !zfChatHasMyPost(chat.id, it.pid) }
        .sortedByDescending { it.tsMs }
      (if (force) list else list.take(ZF_BACKFILL_POSTS)).reversed()
    }
    if (missing.isEmpty()) return true
    val maxItems = if (force) Int.MAX_VALUE else ZF_BACKFILL_ITEMS
    var allOk = true
    for (p in missing) {
      if (zfSendPostCopy(chat, p)) {
        withContext(Dispatchers.Main) { zfMarkSent(chat, p.pid) }
      } else {
        allOk = false
      }
      delay(150)
      for (cm in p.comments.take(maxItems)) {
        zfSendCommentCopy(chat, p, cm)
        delay(150)
        for (rc in cm.reactors.take(maxItems)) {
          zfSendReactionCopy(chat, p, rc, cm.cid)
          delay(100)
        }
      }
      for (rc in p.reactors.take(maxItems)) {
        zfSendReactionCopy(chat, p, rc, null)
        delay(100)
      }
      for (v in p.voters.take(maxItems)) {
        zfSendReactionCopy(chat, p, ZfReactor(v.key, v.name, "#" + v.option, v.avatar), null)
        delay(100)
      }
    }
    if (allOk) zfSetFlag(flag)
    return allOk
  } finally {
    zfBackfilling.remove(contactId)
  }
}

/** Revisa todos los contactos y reenvía lo que les falte. Solo llamar justo después de una lectura completa. */
private suspend fun zfReconcile(force: Boolean = false) {
  val user = chatModel.currentUser.value ?: return
  val ids = withContext(Dispatchers.Main) {
    val res = ArrayList<Long>()
    for (c in zfTargets()) {
      val ci = c.chatInfo
      if (ci is ChatInfo.Direct) res.add(ci.contact.contactId)
    }
    res
  }
  for (id in ids) {
    zfBackfillContact(user, id, false, true, force)
  }
}

/**
 * Para un contacto nuevo que ya tiene el original: manda la edición vigente (texto y, si cambió, el archivo nuevo
 * o la orden de quitarlo). replyCid = comentario editado (null = la publicación).
 */
private suspend fun zfSendEditCopy(c: Chat, pid: String, replyCid: String?, text: String, origCi: ChatItem, curCi: ChatItem?) {
  val lines = ArrayList<String>()
  lines.add(ZF_EDIT + pid)
  if (replyCid != null) lines.add(ZF_META_REPLY + replyCid)
  var src: CryptoFile? = null
  var base: ChatItem? = null
  if (curCi == null || !zfHasMedia(curCi)) {
    if (zfHasMedia(origCi)) lines.add(ZF_META_DROP + "1")
  } else if (curCi.id != origCi.id) {
    src = zfFileOf(curCi)
    base = curCi
  }
  if (text.isNotEmpty()) lines.add(text)
  val mc = zfMakeContent(if (src != null) base?.content?.msgContent else null, lines.joinToString("\n"))
  zfSendOne(c, mc, src)
}

private suspend fun zfSendPostCopy(c: Chat, p: ZfPost): Boolean {
  val lines = ArrayList<String>()
  lines.add(ZF_POST + p.pid)
  for (o in p.options) lines.add(ZF_META_OPT + o)
  lines.add(ZF_META_TIME + (p.tsMs / 1000L))
  if (p.origText.isNotEmpty()) lines.add(p.origText)
  val src = zfFileOf(p.origCi)
  val mc = zfMakeContent(if (src != null) p.origCi.content.msgContent else null, lines.joinToString("\n"))
  val ok = zfSendOne(c, mc, src)
  // si se editó, el contacto nuevo recibe primero el original y luego la edición (así también ve el original)
  if (ok && p.edited) {
    delay(150)
    zfSendEditCopy(c, p.pid, null, p.text, p.origCi, p.mediaCi)
  }
  return ok
}

private suspend fun zfSendCommentCopy(c: Chat, p: ZfPost, cm: ZfComment): Boolean {
  val own = cm.key == "a"
  val lines = ArrayList<String>()
  lines.add(ZF_CREL + p.pid + " " + cm.cid + " " + cm.key + " " + (if (own) zfMyName() else zfClean(cm.name)))
  if (cm.reply.isNotEmpty()) lines.add(ZF_META_REPLY + cm.reply)
  val av = if (own) null else zfSmallAvatar(cm.avatar)
  if (av != null) lines.add(ZF_META_AVATAR + av)
  lines.add(ZF_META_TIME + (cm.tsMs / 1000L))
  if (cm.origText.isNotEmpty()) lines.add(cm.origText)
  val src = zfFileOf(cm.origCi)
  val mc = zfMakeContent(if (src != null) cm.origCi.content.msgContent else null, lines.joinToString("\n"))
  val ok = zfSendOne(c, mc, src)
  if (ok && cm.edited) {
    delay(150)
    zfSendEditCopy(c, p.pid, cm.cid, cm.text, cm.origCi, cm.mediaCi)
  }
  return ok
}

private suspend fun zfSendReactionCopy(c: Chat, p: ZfPost, rc: ZfReactor, target: String?): Boolean {
  val own = rc.key == "a"
  val lines = ArrayList<String>()
  lines.add(ZF_RREL + p.pid + " " + rc.key + " " + rc.emoji + " " + (if (own) zfMyName() else zfClean(rc.name)))
  if (target != null) lines.add(ZF_META_REPLY + target)
  val av = if (own) null else zfSmallAvatar(rc.avatar)
  if (av != null) lines.add(ZF_META_AVATAR + av)
  return zfSendOne(c, MsgContent.MCText(lines.joinToString("\n")), null)
}

// ---------------------------------------------------------------------------
// Lista de chats: los mensajes del feed no deben mover ni cambiar la fila de un contacto.
// La base de datos sí los cuenta como actividad, así que al recargar la lista el chat del contacto
// llegaría arriba y con la publicación como «último mensaje». Aquí se ordena y se muestra según el
// último mensaje real de la conversación.
// ---------------------------------------------------------------------------
private class ZfReal(val item: ChatItem?, val tsMs: Long, val resolved: Boolean)

private val zfRealCache = ConcurrentHashMap<String, ZfReal>()
private val zfResolving: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

/** Último mensaje de la fila si es del feed (solo chats directos), si no null. */
private fun zfFeedLast(chat: Chat): ChatItem? {
  if (chat.chatInfo !is ChatInfo.Direct) return null
  val last = chat.chatItems.lastOrNull() ?: return null
  return if (zetariaIsFeedItem(last)) last else null
}

/** Último mensaje real del chat. Lanza una excepción si no se pudo leer (para no guardar un resultado falso). */
private suspend fun zfLastRealItem(chat: Chat): ChatItem? {
  val res = zfGetChatQuiet(chat.remoteHostId, chat.chatInfo.apiId, ChatPagination.Last(100), "")
    ?: throw IllegalStateException("chat not readable")
  return res.first.chatItems.lastOrNull { !zetariaIsFeedItem(it) }
}

/**
 * Último mensaje real de un chat cuya fila muestra un mensaje del feed. Solo se guarda en memoria
 * cuando la lectura salió bien; si falló devuelve resolved = false y se vuelve a intentar después.
 */
private suspend fun zfEnsureReal(chat: Chat): ZfReal {
  val fl = zfFeedLast(chat)
    ?: return ZfReal(chat.chatItems.lastOrNull(), chat.chatInfo.chatTs.toEpochMilliseconds(), true)
  val key = chat.id + ":" + fl.id
  val cached = zfRealCache[key]
  if (cached != null) return cached
  val item: ChatItem? = try {
    zfLastRealItem(chat)
  } catch (e: Exception) {
    return ZfReal(null, chat.chatInfo.chatTs.toEpochMilliseconds(), false)
  }
  val r = ZfReal(item, (item?.meta?.itemTs ?: chat.chatInfo.chatTs).toEpochMilliseconds(), true)
  zfRealCache[key] = r
  return r
}

private fun zfChatSortMs(chat: Chat): Long {
  val fl = zfFeedLast(chat)
  if (fl != null) {
    val r = zfRealCache[chat.id + ":" + fl.id]
    return r?.tsMs ?: chat.chatInfo.chatTs.toEpochMilliseconds()
  }
  return (chat.chatItems.lastOrNull()?.meta?.itemTs ?: chat.chatInfo.chatTs).toEpochMilliseconds()
}

/** Espera a que el chat esté en marcha, lee el último mensaje real de cada fila y reordena la lista. */
private suspend fun zfResolveReal(chats: List<Chat>) {
  var waited = 0
  while (chatModel.chatRunning.value != true && waited < 240) {
    delay(500)
    waited++
  }
  if (chatModel.chatRunning.value != true) return
  var pending = chats
  var round = 0
  while (pending.isNotEmpty() && round < 6) {
    val next = ArrayList<Chat>()
    var changed = false
    for (c in pending) {
      val fl = zfFeedLast(c) ?: continue
      if (zfRealCache.containsKey(c.id + ":" + fl.id)) continue
      val r = zfEnsureReal(c)
      if (r.resolved) changed = true else next.add(c)
    }
    if (changed) {
      withContext(Dispatchers.Main) {
        val cur = chatModel.chatsContext.chats.value
        chatModel.chatsContext.chats.replaceAll(cur.sortedByDescending { zfChatSortMs(it) })
      }
    }
    pending = next
    if (pending.isNotEmpty()) delay(2000)
    round++
  }
}

/**
 * Se aplica cada vez que la app carga la lista de chats desde la base de datos. Si ningún chat tiene
 * un mensaje del feed como último, devuelve la lista tal cual.
 */
fun zetariaOrderChats(list: List<Chat>): List<Chat> {
  var any = false
  val pending = ArrayList<Chat>()
  for (c in list) {
    val fl = zfFeedLast(c) ?: continue
    any = true
    val key = c.id + ":" + fl.id
    if (!zfRealCache.containsKey(key) && zfResolving.add(key)) pending.add(c)
  }
  if (!any) return list
  if (pending.isNotEmpty()) {
    withLongRunningApi {
      try {
        zfResolveReal(pending)
      } catch (e: Exception) {
        Log.e(TAG, "zetaria feed order: " + e.stackTraceToString())
      } finally {
        for (c in pending) {
          val fl = zfFeedLast(c)
          if (fl != null) zfResolving.remove(c.id + ":" + fl.id)
        }
      }
    }
  }
  return list.sortedByDescending { zfChatSortMs(it) }
}

@Composable
fun zetariaPreviewChat(chat: Chat): Chat {
  val fl = zfFeedLast(chat) ?: return chat
  val key = chat.id + ":" + fl.id
  var real by remember(key) { mutableStateOf<ZfReal?>(zfRealCache[key]) }
  val running = chatModel.chatRunning.value == true
  LaunchedEffect(key, running) {
    if (running) {
      var tries = 0
      while (real?.resolved != true && tries < 20) {
        val r = withContext(Dispatchers.IO) { zfEnsureReal(chat) }
        if (r.resolved) {
          real = r
          break
        }
        delay(3000)
        tries++
      }
    }
  }
  // Mientras se resuelve (o si de verdad no hay mensaje real, p. ej. solo hubo publicaciones), se muestra
  // el chat tal cual en vez de dejar la fila sin mensaje: una fila vacía es peor que ver brevemente la publicación.
  val item = real?.item ?: return chat
  return chat.copy(chatItems = arrayListOf(item))
}

// ---------------------------------------------------------------------------
// Interfaz: pestañas "Chats | Feed" en la barra superior
// ---------------------------------------------------------------------------
val zetariaFeedOpen: MutableState<Boolean> = mutableStateOf(false)

@Composable
fun ZetariaFeedTabs(chatsTitle: String) {
  val open = zetariaFeedOpen.value
  val onBg = MaterialTheme.colors.onBackground
  val secondary = MaterialTheme.colors.secondary
  val ready = chatModel.chats.value.isNotEmpty() && chatModel.chatRunning.value == true
  val unseen = remember(zfVersion.value, zfSeen.value) { zfUnseenCount() }
  // una sola lectura al arrancar, para saber si hay publicaciones nuevas aunque no abras el feed
  LaunchedEffect(ready) {
    if (ready && zfInitialDone.compareAndSet(false, true)) {
      withContext(Dispatchers.IO) {
        try {
          zetariaCleanGalleryDuplicates()
        } catch (e: Exception) {
          Log.e(TAG, "zetaria feed clean duplicates: " + e.stackTraceToString())
        }
        zfRefreshAll()
      }
    }
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(
      chatsTitle,
      color = if (!open) onBg else secondary,
      fontWeight = if (!open) FontWeight.SemiBold else FontWeight.Normal,
      modifier = Modifier.clickable { zetariaFeedOpen.value = false }
    )
    Text(
      "Feed",
      color = if (open) onBg else secondary,
      fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
      modifier = Modifier.clickable { zetariaFeedOpen.value = true }
    )
    if (unseen > 0 && !open) {
      Box(
        Modifier
          .size(18.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colors.primary)
          .clickable { zetariaFeedOpen.value = true },
        contentAlignment = Alignment.Center
      ) {
        Text(if (unseen > 9) "9+" else unseen.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
      }
    }
  }
}

@Composable
fun ZetariaFeedScreen() {
  val oneHandUI = remember { appPrefs.oneHandUI.state }
  val topPad = topPaddingToContent(false)
  val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val bottomPad = if (oneHandUI.value) navBottom + AppBarHeight * fontSizeSqrtMultiplier else navBottom
  val version = zfVersion.value
  val posts = remember(version) { zfBuildPosts() }
  val loading = zfLoading.value
  val sending = zfSending.value
  val secondary = MaterialTheme.colors.secondary
  val primary = MaterialTheme.colors.primary
  var showPublish by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    zfAutoDlMode()
    withContext(Dispatchers.IO) { zfRefreshAll() }
  }
  // mientras el feed está abierto, todo lo que llega cuenta como visto
  LaunchedEffect(version) { zfSetSeen(zfNewestOthersMs()) }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topPad, bottom = bottomPad + 16.dp)) {
    item(key = "composer") {
      Row(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(secondary.copy(alpha = 0.10f))
          .clickable { showPublish = true }
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        ProfileImage(size = 40.dp, image = chatModel.currentUser.value?.image)
        Spacer(Modifier.width(10.dp))
        Text("¿Qué quieres compartir?", Modifier.weight(1f), color = secondary)
        Text("📷", fontSize = 20.sp)
      }
    }
    item(key = "status") {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (loading || sending) "Actualizando…" else (posts.size.toString() + (if (posts.size == 1) " publicación" else " publicaciones")),
          Modifier.weight(1f),
          fontSize = 12.sp,
          color = secondary
        )
        Text(
          "🔄 Actualizar",
          fontSize = 13.sp,
          color = primary,
          modifier = Modifier
            .clickable(enabled = !loading && !sending) { scope.launch(Dispatchers.IO) { zfRefreshAll(true) } }
            .padding(8.dp)
        )
      }
    }
    item(key = "autodl") {
      val mode = zfAutoDl.value
      Text(
        "⬇ Descarga automática: " + (if (mode == 1) "audios (hasta 10 MB)" else if (mode == 2) "audios y videos (hasta 25 MB)" else "apagada") + "  ·  toca para cambiar",
        Modifier.fillMaxWidth().clickable { zfCycleAutoDl() }.padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = secondary
      )
    }
    if (posts.isEmpty() && !loading && !sending) {
      item(key = "empty") {
        Text(
          "Aún no hay publicaciones. Las de tus contactos aparecerán aquí, de la más nueva a la más antigua.",
          Modifier.fillMaxWidth().padding(24.dp),
          color = secondary
        )
      }
    }
    items(posts, key = { it.key }) { p ->
      ZfPostCard(p)
    }
  }

  if (showPublish) {
    ZfPublishDialog(onDismiss = { showPublish = false })
  }
}

// ---------------------------------------------------------------------------
// Imágenes y videos
// ---------------------------------------------------------------------------
private fun zfClock(ms: Long): String {
  val s = (if (ms < 0L) 0L else ms / 1000L).toInt()
  return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
}

@Composable
private fun ZfRoundButton(label: String, size: Dp, fontSize: Int, onClick: () -> Unit) {
  Box(
    Modifier
      .size(size)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = 0.5f))
      .clickable { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = Color.White)
  }
}

@Composable
private fun ZfFullscreenVideo(
  player: VideoPlayer,
  preview: ImageBitmap,
  durationMs: Long,
  play: () -> Unit,
  stop: () -> Unit,
  skip: (Long) -> Unit,
  onClose: () -> Unit
) {
  val playing = remember(player) { player.videoPlaying }
  val progress = remember(player) { player.progress }
  val duration = remember(player) { player.duration }
  var controlsVisible by remember { mutableStateOf(true) }
  var dragValue by remember { mutableStateOf<Float?>(null) }
  val total = if (duration.value > 0L) duration.value else durationMs
  LaunchedEffect(controlsVisible, playing.value, dragValue) {
    if (controlsVisible && playing.value && dragValue == null) {
      delay(3500)
      controlsVisible = false
    }
  }
  Dialog(
    onDismissRequest = onClose,
    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
  ) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
      val aspect = if (preview.height > 0) preview.width.toFloat() / preview.height.toFloat() else 1.7778f
      val w = minOf(maxWidth, maxHeight * aspect)
      Box(Modifier.align(Alignment.Center).width(w)) {
        PlayerView(
          player,
          w,
          onClick = {
            play()
            controlsVisible = true
          },
          onLongClick = {},
          stop = { controlsVisible = !controlsVisible }
        )
        if (!playing.value || progress.value == 0L) {
          Image(preview, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        }
      }
      if (!playing.value || controlsVisible) {
        Row(
          Modifier.align(Alignment.Center),
          horizontalArrangement = Arrangement.spacedBy(18.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          ZfRoundButton("↺10", 46.dp, 13) { skip(-10_000L) }
          ZfRoundButton(if (playing.value) "⏸" else "▶", 60.dp, 26) {
            if (playing.value) stop() else play()
            controlsVisible = true
          }
          ZfRoundButton("10↻", 46.dp, 13) { skip(10_000L) }
        }
        Box(
          Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onClose() },
          contentAlignment = Alignment.Center
        ) {
          Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
          Row(Modifier.fillMaxWidth()) {
            Text(zfClock(progress.value), fontSize = 12.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(zfClock(total), fontSize = 12.sp, color = Color.White)
          }
          val frac = dragValue ?: (if (total > 0L) (progress.value.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f)
          Slider(
            value = frac,
            onValueChange = {
              dragValue = it
              controlsVisible = true
            },
            onValueChangeFinished = {
              val dv = dragValue
              if (dv != null) zetariaVideoSeek(player, (dv * total.toFloat()).toLong())
              dragValue = null
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
              thumbColor = Color.White,
              activeTrackColor = Color.White,
              inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
          )
        }
      }
    }
  }
}

@Composable
private fun ZfVideoPlayer(file: CIFile, preview: ImageBitmap, durationMs: Long, maxWidth: Dp, autoPlay: Boolean) {
  var uri by remember(file.fileId) { mutableStateOf<URI?>(null) }
  var failed by remember(file.fileId) { mutableStateOf(false) }
  var tryN by remember(file.fileId) { mutableStateOf(0) }
  LaunchedEffect(file.fileId, tryN) {
    val src = getLoadedFileSource(file)
    val res = withContext(Dispatchers.IO) {
      try {
        if (src == null) {
          null
        } else if (src.cryptoArgs == null) {
          getAppFileUri(src.filePath.substringAfterLast(File.separator))
        } else {
          src.decryptedGetOrCreate()
        }
      } catch (e: Exception) {
        null
      }
    }
    uri = res
    failed = res == null
  }
  val width = minOf(LocalWindowWidth() - 48.dp, maxWidth)
  val u = uri
  if (u == null) {
    Box(Modifier.width(width).clip(RoundedCornerShape(10.dp)).clickable(enabled = failed) {
      failed = false
      tryN = tryN + 1
    }) {
      Image(preview, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
      Row(
        Modifier
          .align(Alignment.Center)
          .clip(RoundedCornerShape(20.dp))
          .background(Color.Black.copy(alpha = 0.6f))
          .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (!failed) {
          CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
          Spacer(Modifier.width(8.dp))
        }
        Text(
          if (failed) "No se pudo abrir el video · toca para reintentar" else "Preparando video…",
          color = Color.White,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }
    return
  }
  val player = remember(u) { VideoPlayerHolder.getOrCreate(u, false, preview, durationMs, true) }
  val playing = remember(u.path) { player.videoPlaying }
  val progress = remember(u.path) { player.progress }
  val duration = remember(u.path) { player.duration }
  val pv by remember(u) { player.preview }
  var controlsVisible by remember(u) { mutableStateOf(true) }
  var dragValue by remember(u) { mutableStateOf<Float?>(null) }
  var full by remember(u) { mutableStateOf(false) }
  val play = {
    player.enableSound(true)
    player.play(true)
  }
  val stop = {
    player.enableSound(false)
    player.stop()
  }
  val toggle: () -> Unit = { if (playing.value) stop() else play() }
  // el video que acaba de descargarse por un toque empieza solo
  LaunchedEffect(u) {
    if (autoPlay) {
      delay(300)
      play()
      controlsVisible = true
    }
  }
  val skip: (Long) -> Unit = { delta ->
    val total = if (duration.value > 0L) duration.value else durationMs
    val target = (progress.value + delta).coerceIn(0L, if (total > 0L) total else Long.MAX_VALUE)
    zetariaVideoSeek(player, target)
  }
  DisposableEffect(u) {
    onDispose { stop() }
  }
  if (full) {
    ZfFullscreenVideo(player, preview, durationMs, play, stop, skip) { full = false }
  }
  // mientras se reproduce, los controles se esconden solos
  LaunchedEffect(controlsVisible, playing.value, dragValue) {
    if (controlsVisible && playing.value && dragValue == null) {
      delay(3500)
      controlsVisible = false
    }
  }
  val showPreview = !playing.value || progress.value == 0L
  val showControls = !playing.value || controlsVisible
  val total = if (duration.value > 0L) duration.value else durationMs
  Box(Modifier.width(width).clip(RoundedCornerShape(10.dp))) {
    if (!full) {
      PlayerView(
        player,
        width,
        onClick = {
          play()
          controlsVisible = true
        },
        onLongClick = {},
        stop = { controlsVisible = !controlsVisible }
      )
    } else {
      Image(preview, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
    if (showPreview && !full) {
      Image(pv, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
    if (showControls && !full) {
      Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)))
      // botón del cuadro: pantalla completa
      Box(
        Modifier
          .align(Alignment.TopEnd)
          .padding(8.dp)
          .size(34.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color.Black.copy(alpha = 0.55f))
          .clickable { full = true },
        contentAlignment = Alignment.Center
      ) {
        Box(Modifier.size(16.dp).border(2.dp, Color.White, RoundedCornerShape(3.dp)))
      }
      Row(
        Modifier.align(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        ZfRoundButton("↺10", 46.dp, 13) { skip(-10_000L) }
        ZfRoundButton(if (playing.value) "⏸" else "▶", 60.dp, 26) {
          toggle()
          controlsVisible = true
        }
        ZfRoundButton("10↻", 46.dp, 13) { skip(10_000L) }
      }
      Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
          Text(zfClock(progress.value), fontSize = 11.sp, color = Color.White)
          Spacer(Modifier.weight(1f))
          Text(zfClock(total), fontSize = 11.sp, color = Color.White)
        }
        val frac = dragValue ?: (if (total > 0L) (progress.value.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f)
        Slider(
          value = frac,
          onValueChange = {
            dragValue = it
            controlsVisible = true
          },
          onValueChangeFinished = {
            val dv = dragValue
            if (dv != null) zetariaVideoSeek(player, (dv * total.toFloat()).toLong())
            dragValue = null
          },
          modifier = Modifier.fillMaxWidth(),
          colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
          )
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Descarga de archivos del feed (imagen, video y audio).
// Al tocar se pide el archivo, se muestra «Descargando…» con rueda y, en cuanto está completo en el teléfono,
// aparece el reproductor (y empieza solo si la descarga la pidió ese toque). No hay límite corto de espera:
// se sigue comprobando mientras la publicación esté en pantalla, y si tarda se puede reintentar con otro toque.
// ---------------------------------------------------------------------------
private val zfDlStarted = ConcurrentHashMap<String, Long>()
private val zfReady = ConcurrentHashMap<String, Boolean>()     // archivos que ya se vio completos: sin parpadeo al volver a verlos
private const val ZF_DL_SLOW_MS = 45_000L
private const val ZF_DL_GIVEUP_MS = 20L * 60L * 1000L

// Descarga automática (opcional): al ver una publicación se bajan solos los audios y, si se elige, los videos pequeños.
// Máximo 2 a la vez, para no saturar la conexión ni la batería.
private const val ZF_AUTODL_KEY = "zetaria_feed_autodl"
private const val ZF_AUTODL_AUDIO_MAX = 10L * 1024L * 1024L
private const val ZF_AUTODL_VIDEO_MAX = 25L * 1024L * 1024L
private val zfAutoDl = mutableStateOf(0)       // 0 apagada, 1 audios, 2 audios y videos
private var zfAutoDlLoaded = false
private val zfDlGate = Semaphore(2)

/** Modo de descarga automática (se lee una vez de los ajustes; llamar fuera de la composición). */
private fun zfAutoDlMode(): Int {
  if (!zfAutoDlLoaded) {
    zfAutoDlLoaded = true
    zfAutoDl.value = try {
      settings.getLong(ZF_AUTODL_KEY, 0L).toInt().coerceIn(0, 2)
    } catch (e: Exception) {
      0
    }
  }
  return zfAutoDl.value
}

private fun zfCycleAutoDl() {
  val n = (zfAutoDlMode() + 1) % 3
  zfAutoDl.value = n
  try {
    settings.putLong(ZF_AUTODL_KEY, n.toLong())
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed autodl: " + e.stackTraceToString())
  }
}

private fun zfDlKey(rh: Long?, file: CIFile): String = (rh ?: -1L).toString() + ":" + file.fileId

private fun zfProbeOf(file: CIFile): CIFile = if (file.fileStatus.sent) file else file.copy(fileStatus = CIFileStatus.RcvComplete)

/** Archivo ya visto como completo (para mostrar el reproductor sin esperar a la primera comprobación). */
private fun zfKnownReady(rh: Long?, file: CIFile?): CIFile? =
  if (file != null && zfReady.containsKey(zfDlKey(rh, file))) zfProbeOf(file) else null

private fun zfSizeText(bytes: Long): String {
  if (bytes <= 0L) return ""
  val mb = bytes.toDouble() / 1048576.0
  return if (mb >= 1.0) (Math.round(mb * 10.0) / 10.0).toString() + " MB" else Math.max(1L, bytes / 1024L).toString() + " KB"
}

private suspend fun zfReceive(rh: Long?, file: CIFile, auto: Boolean) {
  try {
    val u = chatModel.currentUser.value
    if (u != null) {
      chatModel.controller.receiveFiles(rh, u, listOf(file.fileId), userApprovedRelays = false, auto = auto)
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed download: " + e.stackTraceToString())
  }
}

/**
 * Causa del «tarda mucho» de antes: el mensaje que tiene el Feed es una «foto» del momento en que llegó, y ahí el archivo
 * todavía no tiene ruta en el teléfono (fileSource = null). Aunque la descarga terminara, se seguía mirando esa foto vieja
 * y el archivo no «aparecía» hasta reiniciar la app (que vuelve a leer los mensajes). Ahora, mientras se descarga, se
 * vuelve a pedir el mensaje al núcleo: de ahí salen la ruta real, el porcentaje y los errores de verdad.
 */
private fun zfChatOfItem(itemId: Long): Chat? {
  for (en in zfEvents.entries) {
    if (en.value.containsKey(itemId)) return zfChatById(en.key)
  }
  return null
}

private suspend fun zfFetchItem(itemId: Long): Pair<Chat, ChatItem>? {
  val c = withContext(Dispatchers.Main) { zfChatOfItem(itemId) } ?: return null
  val res = zfGetChatQuiet(c.remoteHostId, c.chatInfo.apiId, ChatPagination.Around(itemId, 3), "") ?: return null
  val item = res.first.chatItems.firstOrNull { it.id == itemId } ?: return null
  return Pair(res.first, item)
}

/** Deja al día en el Feed el mensaje de un archivo ya descargado (para guardarlo o compartirlo sin reabrir la app). */
private suspend fun zfStoreFresh(rh: Long?, itemId: Long) {
  val r = zfFetchItem(itemId) ?: return
  val e = zfEventOf(rh, r.first.chatInfo, r.second) ?: return
  withContext(Dispatchers.Main) {
    zfPut(e)
    zfBump()
  }
}

private fun zfIsTerminalError(s: CIFileStatus): Boolean =
  s is CIFileStatus.RcvError || s is CIFileStatus.RcvAborted || s is CIFileStatus.RcvCancelled

private class ZfDlState(val rh: Long?, val file: CIFile?, val itemId: Long) {
  var downloading by mutableStateOf(false)
  var slow by mutableStateOf(false)
  var failed by mutableStateOf(false)
  var percent by mutableStateOf(-1)
  var byUser by mutableStateOf(false)
  var cur: CIFile? = file          // última versión conocida del archivo (con su ruta local cuando existe)
  var requestedAt = 0L
  var retried = false
  var autoTried = false

  /** Pide el archivo con un toque (también sirve para reintentar). */
  fun request() {
    val f = cur ?: file ?: return
    byUser = true
    downloading = true
    slow = false
    failed = false
    retried = false
    requestedAt = System.currentTimeMillis()
    zfDlStarted[zfDlKey(rh, f)] = requestedAt
    withLongRunningApi { zfReceive(rh, f, false) }
  }

  /** Segundo intento silencioso si la descarga pedida con un toque se queda parada. */
  fun resend() {
    val f = cur ?: file ?: return
    if (retried) return
    retried = true
    withLongRunningApi { zfReceive(rh, f, true) }
  }

  /** Descarga automática de audios y videos pequeños (kind: 2 video, 3 audio), si el usuario la activó. */
  fun maybeAuto(kind: Int) {
    val f = file ?: return
    if (autoTried) return
    autoTried = true
    if (f.fileStatus.sent || f.fileStatus !is CIFileStatus.RcvInvitation) return
    val mode = zfAutoDlMode()
    val allowed = (kind == 3 && mode >= 1 && f.fileSize <= ZF_AUTODL_AUDIO_MAX) ||
      (kind == 2 && mode >= 2 && f.fileSize <= ZF_AUTODL_VIDEO_MAX)
    if (!allowed) return
    val key = zfDlKey(rh, f)
    if (zfReady.containsKey(key) || zfDlStarted.containsKey(key)) return
    zfDlStarted[key] = System.currentTimeMillis()
    downloading = true
    withLongRunningApi {
      zfDlGate.acquire()
      try {
        zfReceive(rh, f, true)
        // el turno se libera cuando el núcleo dice que terminó (o falló), o a los 3 minutos
        var n = 0
        while (n < 120) {
          delay(1500)
          val s = try {
            zfFetchItem(itemId)?.second?.file?.fileStatus
          } catch (e: Exception) {
            null
          }
          if (s is CIFileStatus.RcvComplete || (s != null && zfIsTerminalError(s))) break
          n++
        }
      } finally {
        zfDlGate.release()
      }
    }
  }
}

/**
 * Espera a que el archivo esté completo en el teléfono. Se cancela solo al salir de la pantalla.
 * autoKind: 2 / 3 para permitir la descarga automática de ese tipo (0 = no).
 */
private suspend fun zfWaitFile(st: ZfDlState, autoKind: Int, check: suspend (CIFile) -> Boolean): CIFile? {
  val file0 = st.file ?: return null
  val key = zfDlKey(st.rh, file0)
  var waited = 0L
  var tick = 0
  var lastSig = ""
  var lastChange = System.currentTimeMillis()
  while (true) {
    val probe = zfProbeOf(st.cur ?: file0)
    val ok = withContext(Dispatchers.IO) {
      try {
        check(probe)
      } catch (e: Exception) {
        false
      }
    }
    if (ok) {
      zfDlStarted.remove(key)
      zfReady[key] = true
      st.downloading = false
      st.slow = false
      st.failed = false
      st.percent = -1
      if (file0.fileSource == null) {
        try {
          zfStoreFresh(st.rh, st.itemId)
        } catch (e: Exception) {
          Log.e(TAG, "zetaria feed store fresh: " + e.stackTraceToString())
        }
      }
      return probe
    }
    if (autoKind != 0) st.maybeAuto(autoKind)
    val now = System.currentTimeMillis()
    val startedAt = zfDlStarted[key]
    if (startedAt != null && now - startedAt > ZF_DL_GIVEUP_MS) zfDlStarted.remove(key)
    // estado real según el núcleo: ruta local, progreso y errores
    val known = (st.cur ?: file0).fileStatus
    if (!known.sent && (zfDlStarted.containsKey(key) || known is CIFileStatus.RcvAccepted || known is CIFileStatus.RcvTransfer || tick % 4 == 0)) {
      val fresh = try {
        withContext(Dispatchers.IO) { zfFetchItem(st.itemId)?.second?.file }
      } catch (e: Exception) {
        null
      }
      if (fresh != null) st.cur = fresh
    }
    val s = (st.cur ?: file0).fileStatus
    val pct = if (s is CIFileStatus.RcvTransfer && s.rcvTotal > 0L) ((s.rcvProgress * 100L) / s.rcvTotal).toInt().coerceIn(0, 100) else -1
    val inProgress = s is CIFileStatus.RcvAccepted || s is CIFileStatus.RcvTransfer
    val terminal = zfIsTerminalError(s)
    val justTapped = now - st.requestedAt < 6000L
    val failedNow = terminal && !justTapped
    if (failedNow) zfDlStarted.remove(key)
    st.failed = failedNow
    st.percent = pct
    st.downloading = !failedNow && (zfDlStarted.containsKey(key) || inProgress || (terminal && justTapped))
    val sig = s.javaClass.simpleName + ":" + pct
    if (sig != lastSig) {
      lastSig = sig
      lastChange = now
    }
    st.slow = st.downloading && now - lastChange > ZF_DL_SLOW_MS
    if (st.slow && st.byUser) st.resend()
    tick++
    val step = if (!st.downloading) 3000L else if (waited < 20_000L) 800L else if (waited < 120_000L) 1500L else 3000L
    delay(step)
    waited += step
  }
}

private fun zfDlText(st: ZfDlState, what: String): String {
  val size = st.file?.fileSize ?: 0L
  return if (st.failed) "No se pudo descargar · toca para reintentar"
  else if (st.slow) "Tarda mucho · toca para reintentar"
  else if (st.downloading) "Descargando " + what + "…" + (if (st.percent >= 0) " " + st.percent + "%" else "")
  else "⬇ Descargar " + what + (if (size > 0L) " (" + zfSizeText(size) + ")" else "")
}

@Composable
private fun ZfDlBadge(st: ZfDlState, what: String, modifier: Modifier) {
  Row(
    modifier
      .clip(RoundedCornerShape(20.dp))
      .background(Color.Black.copy(alpha = 0.6f))
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (st.downloading && !st.slow) {
      CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
      Spacer(Modifier.width(8.dp))
    }
    Text(zfDlText(st, what), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
  }
}

@Composable
private fun ZfMedia(ci: ChatItem, rh: Long?, compact: Boolean) {
  val mc = ci.content.msgContent
  if (mc is MsgContent.MCFile) {
    if (zfIsAudioName(ci.file?.fileName ?: "")) ZfAudio(ci, rh) else ZfFile(ci, rh)
    return
  }
  val isVideo = mc is MsgContent.MCVideo
  val previewStr = when (mc) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> return
  }
  val durationSec = (mc as? MsgContent.MCVideo)?.duration ?: 0
  val file = ci.file
  val st = remember(ci.id) { ZfDlState(rh, file, ci.id) }
  var full by remember(ci.id) { mutableStateOf<ImageBitmap?>(null) }
  var loadedFile by remember(ci.id) { mutableStateOf<CIFile?>(if (isVideo) zfKnownReady(rh, file) else null) }
  val previewBmp = remember(previewStr) {
    try {
      base64ToBitmap(previewStr)
    } catch (e: Exception) {
      null
    }
  }

  // El archivo puede terminar de descargarse con el feed abierto: se comprueba el disco hasta que esté
  LaunchedEffect(ci.id) {
    if (file == null) return@LaunchedEffect
    var bmp: ImageBitmap? = null
    val lf = zfWaitFile(st, if (isVideo) 2 else 0) { probe ->
      if (isVideo) {
        getLoadedFilePath(probe) != null
      } else {
        bmp = getLoadedImage(probe)?.first
        bmp != null
      }
    }
    if (lf != null) {
      full = bmp
      loadedFile = lf
    }
  }

  val shown = full ?: previewBmp
  if (shown != null) {
    val lf = loadedFile
    Spacer(Modifier.height(8.dp))
    if (isVideo && lf != null && previewBmp != null) {
      ZfVideoPlayer(lf, previewBmp, durationSec * 1000L, if (compact) 220.dp else 360.dp, st.byUser)
    } else {
      val canTap = lf == null && file != null && (!st.downloading || st.slow)
      Box(Modifier.fillMaxWidth()) {
        Image(
          shown,
          null,
          Modifier
            .fillMaxWidth()
            .heightIn(max = if (compact) 240.dp else 420.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = canTap) { st.request() },
          contentScale = ContentScale.Fit
        )
        if (lf == null && file != null) {
          ZfDlBadge(st, if (isVideo) "video" else "imagen", Modifier.align(Alignment.Center))
        }
        if (isVideo && durationSec > 0) {
          Text(
            (durationSec / 60).toString() + ":" + (durationSec % 60).toString().padStart(2, '0'),
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
        }
      }
    }
    if (lf != null) {
      Text(
        "⬇ Guardar en la galería (Zetaria)",
        fontSize = 13.sp,
        color = MaterialTheme.colors.primary,
        modifier = Modifier
          .clickable { withLongRunningApi { zetariaSaveToGallery(lf, isVideo, true, false) } }
          .padding(vertical = 8.dp)
      )
    }
  }
}

// ---------------------------------------------------------------------------
// Archivos de cualquier formato (documentos, PDF, ZIP…): se descargan, se abren y se guardan
// ---------------------------------------------------------------------------
@Composable
private fun ZfFile(ci: ChatItem, rh: Long?) {
  val file = ci.file ?: return
  val st = remember(ci.id) { ZfDlState(rh, file, ci.id) }
  var loadedFile by remember(ci.id) { mutableStateOf<CIFile?>(zfKnownReady(rh, file)) }
  LaunchedEffect(ci.id) {
    loadedFile = zfWaitFile(st, 0) { probe -> getLoadedFilePath(probe) != null }
  }
  val lf = loadedFile
  val secondary = MaterialTheme.colors.secondary
  val primary = MaterialTheme.colors.primary
  Spacer(Modifier.height(8.dp))
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(secondary.copy(alpha = 0.12f)).padding(12.dp)) {
    Row(
      Modifier.fillMaxWidth().clickable(enabled = lf == null && (!st.downloading || st.slow)) { st.request() },
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (lf == null && st.downloading && !st.slow) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
      } else {
        Text(zfFileIcon(file.fileName), fontSize = 28.sp)
      }
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(file.fileName, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colors.onBackground)
        val size = zfSizeText(file.fileSize)
        Text(
          if (lf != null) (if (size.isNotEmpty()) size + " · " else "") + "Descargado" else zfDlText(st, "archivo"),
          fontSize = 12.sp,
          color = if (lf != null) secondary else primary
        )
      }
    }
    if (lf != null) {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Text(
          "📂 Abrir",
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          color = primary,
          modifier = Modifier
            .clickable {
              val src = getLoadedFileSource(lf)
              if (src != null) openFile(src)
            }
            .padding(vertical = 4.dp)
        )
        Text(
          "⬇ Guardar (Descargas)",
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          color = primary,
          modifier = Modifier
            .clickable { withLongRunningApi { zetariaSaveFile(lf, 4, false) } }
            .padding(vertical = 4.dp)
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Audio (MP3 y otros) con reproductor propio
// ---------------------------------------------------------------------------
@Composable
private fun ZfAudioPlayer(file: CIFile, title: String, autoPlay: Boolean) {
  val src = remember(file.fileId) { getLoadedFileSource(file) }
  val playing = remember(file.fileId) { mutableStateOf(false) }
  val progress = remember(file.fileId) { mutableStateOf(0) }
  val duration = remember(file.fileId) { mutableStateOf(0) }
  var broken by remember(file.fileId) { mutableStateOf(false) }
  var dragValue by remember(file.fileId) { mutableStateOf<Float?>(null) }
  val secondary = MaterialTheme.colors.secondary
  val onBg = MaterialTheme.colors.onBackground
  // la duración se lee aparte (también de archivos cifrados), así la barra funciona antes de darle a reproducir
  LaunchedEffect(file.fileId) {
    if (src != null) {
      val d = zetariaAudioDuration(src)
      if (d != null && duration.value == 0) duration.value = d
    }
  }
  DisposableEffect(file.fileId) {
    onDispose {
      if (playing.value) AudioPlayer.pause(playing, progress)
    }
  }
  val play: () -> Unit = {
    if (src != null) {
      try {
        AudioPlayer.play(src, playing, progress, duration, true, false)
        broken = !playing.value
      } catch (e: Exception) {
        Log.e(TAG, "zetaria feed audio play: " + e.stackTraceToString())
        broken = true
      }
    }
  }
  val pause: () -> Unit = { AudioPlayer.pause(playing, progress) }
  // el audio que acaba de descargarse por un toque suena solo
  LaunchedEffect(file.fileId) {
    if (autoPlay && src != null) {
      delay(300)
      if (!playing.value) play()
    }
  }
  val total = duration.value
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(secondary.copy(alpha = 0.12f)).padding(10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ZfRoundButton(if (playing.value) "⏸" else "▶", 44.dp, 20) {
        if (playing.value) pause() else play()
      }
      Spacer(Modifier.width(10.dp))
      Text("🎵 " + title, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = onBg)
    }
    val frac = dragValue ?: (if (total > 0) (progress.value.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f)
    Slider(
      value = frac,
      onValueChange = { dragValue = it },
      onValueChangeFinished = {
        val dv = dragValue
        if (dv != null && total > 0) AudioPlayer.seekTo((dv * total.toFloat()).toInt(), progress, src?.filePath)
        dragValue = null
      },
      enabled = total > 0,
      modifier = Modifier.fillMaxWidth()
    )
    Row(Modifier.fillMaxWidth()) {
      Text(zfClock(progress.value.toLong()), fontSize = 11.sp, color = secondary)
      Spacer(Modifier.weight(1f))
      Text(zfClock(total.toLong()), fontSize = 11.sp, color = secondary)
    }
    if (broken) {
      Text("No se pudo reproducir el audio · toca ▶ para reintentar", fontSize = 12.sp, color = MaterialTheme.colors.error)
    }
  }
}

@Composable
private fun ZfAudio(ci: ChatItem, rh: Long?) {
  val file = ci.file ?: return
  val st = remember(ci.id) { ZfDlState(rh, file, ci.id) }
  var loadedFile by remember(ci.id) { mutableStateOf<CIFile?>(zfKnownReady(rh, file)) }
  LaunchedEffect(ci.id) {
    loadedFile = zfWaitFile(st, 3) { probe -> getLoadedFilePath(probe) != null }
  }
  val lf = loadedFile
  Spacer(Modifier.height(8.dp))
  if (lf != null) {
    ZfAudioPlayer(lf, file.fileName, st.byUser)
    Text(
      "⬇ Guardar audio (Música/Zetaria Feed)",
      fontSize = 13.sp,
      color = MaterialTheme.colors.primary,
      modifier = Modifier
        .clickable { withLongRunningApi { zetariaSaveFile(lf, 3, false) } }
        .padding(vertical = 8.dp)
    )
  } else {
    val canTap = !st.downloading || st.slow
    Row(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colors.secondary.copy(alpha = 0.12f))
        .clickable(enabled = canTap) { st.request() }
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (st.downloading && !st.slow) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
      } else {
        Text("🎵", fontSize = 24.sp)
      }
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(file.fileName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colors.onBackground)
        Text(zfDlText(st, "audio"), fontSize = 12.sp, color = MaterialTheme.colors.primary)
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Elegir una imagen o un video (publicaciones y comentarios)
// ---------------------------------------------------------------------------
private class ZfPicked {
  var uri by mutableStateOf<URI?>(null)
  var video by mutableStateOf(false)
  var audio by mutableStateOf(false)
  var audioName by mutableStateOf("")
  var preview by mutableStateOf<ImageBitmap?>(null)
  var error by mutableStateOf<String?>(null)

  fun clear() {
    uri = null
    preview = null
    audio = false
    audioName = ""
  }
}

private class ZfPickers(val image: () -> Unit, val video: () -> Unit, val audio: () -> Unit, val file: () -> Unit)

@Composable
private fun rememberZfPickers(picked: ZfPicked): ZfPickers {
  val scope = rememberCoroutineScope()
  val pickImage = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = getBitmapFromUri(uri, withAlertOnException = false)
        withContext(Dispatchers.Main) {
          if (bmp != null) {
            picked.uri = uri
            picked.video = false
            picked.audio = false
            picked.preview = bmp
            picked.error = null
          } else {
            picked.error = "No se pudo leer la imagen."
          }
        }
      }
    }
  }
  val pickVideo = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val frame = try {
          getBitmapFromVideo(uri, withAlertOnException = false).preview
        } catch (e: Exception) {
          null
        }
        withContext(Dispatchers.Main) {
          if (frame != null) {
            picked.uri = uri
            picked.video = true
            picked.audio = false
            picked.preview = frame
            picked.error = null
          } else {
            picked.error = "No se pudo leer el video."
          }
        }
      }
    }
  }
  val pickAudio = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val name = try {
          getFileName(uri)
        } catch (e: Exception) {
          null
        }
        withContext(Dispatchers.Main) {
          picked.uri = uri
          picked.video = false
          picked.audio = true
          picked.audioName = name ?: "audio"
          picked.preview = null
          picked.error = null
        }
      }
    }
  }
  val pickFile = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val name = try {
          getFileName(uri)
        } catch (e: Exception) {
          null
        }
        withContext(Dispatchers.Main) {
          picked.uri = uri
          picked.video = false
          picked.audio = true
          picked.audioName = name ?: "archivo"
          picked.preview = null
          picked.error = null
        }
      }
    }
  }
  val launchImage: () -> Unit = { scope.launch { pickImage.launch("image/*") } }
  val launchVideo: () -> Unit = { scope.launch { pickVideo.launch("video/*") } }
  val launchAudio: () -> Unit = { scope.launch { pickAudio.launch("audio/*") } }
  val launchFile: () -> Unit = { scope.launch { pickFile.launch("*/*") } }
  return ZfPickers(launchImage, launchVideo, launchAudio, launchFile)
}

@Composable
private fun ZfPickedView(picked: ZfPicked, maxHeight: Dp, enabled: Boolean, pickers: ZfPickers, allowAudio: Boolean) {
  val pv = picked.preview
  if (picked.audio) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(zfFileIcon(picked.audioName) + " " + picked.audioName, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        "✕ Quitar",
        color = MaterialTheme.colors.error,
        fontSize = 13.sp,
        modifier = Modifier.clickable(enabled = enabled) { picked.clear() }.padding(8.dp)
      )
    }
  } else if (pv != null) {
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth()) {
      Image(pv, null, Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
      if (picked.video) {
        Text("▶", Modifier.align(Alignment.Center), fontSize = 36.sp, color = Color.White)
      }
    }
    Text(
      if (picked.video) "✕ Quitar video" else "✕ Quitar imagen",
      color = MaterialTheme.colors.error,
      fontSize = 13.sp,
      modifier = Modifier.clickable(enabled = enabled) { picked.clear() }.padding(vertical = 8.dp)
    )
  } else {
    Row(Modifier.padding(vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      Text(
        "📷 Imagen",
        color = MaterialTheme.colors.primary,
        fontSize = 14.sp,
        modifier = Modifier.clickable(enabled = enabled) { pickers.image() }.padding(vertical = 8.dp)
      )
      Text(
        "🎬 Video",
        color = MaterialTheme.colors.primary,
        fontSize = 14.sp,
        modifier = Modifier.clickable(enabled = enabled) { pickers.video() }.padding(vertical = 8.dp)
      )
      if (allowAudio) {
        Text(
          "🎵 Audio",
          color = MaterialTheme.colors.primary,
          fontSize = 14.sp,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.clickable(enabled = enabled) { pickers.audio() }.padding(vertical = 8.dp)
        )
        Text(
          "📎 Archivo",
          color = MaterialTheme.colors.primary,
          fontSize = 14.sp,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.clickable(enabled = enabled) { pickers.file() }.padding(vertical = 8.dp)
        )
      }
    }
  }
  val err = picked.error
  if (err != null) {
    Text(err, color = MaterialTheme.colors.error, fontSize = 13.sp)
  }
}

// ---------------------------------------------------------------------------
// Publicación, comentarios y reacciones
// ---------------------------------------------------------------------------
/** Comentarios en orden de conversación: cada respuesta queda bajo el comentario al que contesta. */
private fun zfThread(comments: List<ZfComment>): List<Pair<ZfComment, Int>> {
  val byId = HashMap<String, ZfComment>()
  for (c in comments) byId[c.cid] = c
  fun rootOf(c: ZfComment): String {
    var cur = c
    var n = 0
    while (cur.reply.isNotEmpty() && n < 30) {
      val par = byId[cur.reply] ?: break
      cur = par
      n++
    }
    return cur.cid
  }
  val out = ArrayList<Pair<ZfComment, Int>>()
  val used = HashSet<String>()
  val roots = comments.filter { rootOf(it) == it.cid }.sortedBy { it.tsMs }
  for (r in roots) {
    out.add(Pair(r, 0))
    used.add(r.cid)
    for (c in comments.filter { it.cid != r.cid && rootOf(it) == r.cid }.sortedBy { it.tsMs }) {
      out.add(Pair(c, 1))
      used.add(c.cid)
    }
  }
  for (c in comments) {
    if (!used.contains(c.cid)) out.add(Pair(c, 0))
  }
  return out
}

@Composable
private fun ZfEmojiTray(mine: String?, small: Boolean, onPick: (String) -> Unit) {
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  var showCustom by remember { mutableStateOf(false) }
  // si mi reacción es un emoji personalizado, aparece en la bandeja para poder quitarlo con un toque
  val emojis = if (mine != null && mine != "-" && !ZF_EMOJIS.contains(mine)) ZF_EMOJIS + mine else ZF_EMOJIS
  Box {
    Row(
      Modifier
        .clip(RoundedCornerShape(26.dp))
        .background(secondary.copy(alpha = 0.18f))
        .padding(horizontal = 6.dp, vertical = 4.dp)
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      for (em in emojis) {
        Box(
          Modifier
            .clip(CircleShape)
            .background(if (mine == em) primary.copy(alpha = 0.35f) else Color.Transparent)
            .clickable { onPick(em) }
            .padding(if (small) 5.dp else 7.dp)
        ) {
          Text(em, fontSize = if (small) 20.sp else 26.sp)
        }
      }
      // «+»: elegir cualquier otro emoji
      Box(
        Modifier
          .clip(CircleShape)
          .background(secondary.copy(alpha = 0.25f))
          .clickable { showCustom = true }
          .padding(horizontal = if (small) 9.dp else 11.dp, vertical = if (small) 3.dp else 4.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("+", fontSize = if (small) 20.sp else 26.sp, fontWeight = FontWeight.Bold, color = primary)
      }
    }
    if (showCustom) {
      ZfCustomEmojiDialog(
        onPick = { em ->
          showCustom = false
          onPick(em)
        },
        onDismiss = { showCustom = false }
      )
    }
  }
}

private val ZF_EXTRA_EMOJIS = listOf(
  "🔥", "🎉", "👏", "💯", "🤔", "🤡", "💀", "😍", "😎", "🥳", "🤯", "😴", "🤝", "💔", "✅", "👎",
  "😭", "🤣", "😅", "🙄", "😤", "🤬", "🥺", "💪", "👀", "🙌", "💩", "🚀"
)

/** Elegir un emoji personalizado: uno de la lista o cualquiera escrito/pegado con el teclado. */
@Composable
private fun ZfCustomEmojiDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
  var text by remember { mutableStateOf("") }
  val clean = text.trim()
  val valid = zfIsCustomEmoji(clean)
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Emoji personalizado", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
          "Toca uno de la lista o escribe (o pega) el emoji que quieras con tu teclado.",
          fontSize = 12.sp,
          color = MaterialTheme.colors.secondary
        )
        Spacer(Modifier.height(10.dp))
        val rows = ZF_EXTRA_EMOJIS.chunked(7)
        for (row in rows) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (em in row) {
              Box(Modifier.clip(CircleShape).clickable { onPick(em) }.padding(6.dp)) {
                Text(em, fontSize = 24.sp)
              }
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          text,
          { if (it.length <= ZF_MAX_EMOJI_LEN) text = it },
          label = { Text("Otro emoji") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
        if (clean.isNotEmpty() && !valid) {
          Text("Escribe solo un emoji (sin letras ni números).", fontSize = 12.sp, color = MaterialTheme.colors.error)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cancelar") }
          TextButton(onClick = { onPick(clean) }, enabled = valid) { Text("Reaccionar") }
        }
      }
    }
  }
}

@Composable
private fun ZfReactionSummary(counts: Map<String, Int>, small: Boolean, onClick: () -> Unit) {
  val total = counts.values.sum()
  if (total == 0) return
  val top = counts.entries.sortedByDescending { it.value }.take(3).joinToString("") { it.key }
  Row(Modifier.clickable { onClick() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(top, fontSize = if (small) 13.sp else 16.sp)
    Spacer(Modifier.width(4.dp))
    Text(total.toString(), fontSize = if (small) 11.sp else 13.sp, color = MaterialTheme.colors.secondary)
  }
}

@Composable
private fun ZfCommentRow(p: ZfPost, c: ZfComment, depth: Int, parentName: String?, onReply: () -> Unit, onDelete: (() -> Unit)?) {
  var reacting by remember(c.cid) { mutableStateOf(false) }
  var trayOpen by remember(c.cid) { mutableStateOf(false) }
  var showReactors by remember(c.cid) { mutableStateOf(false) }
  var showEdit by remember(c.cid) { mutableStateOf(false) }
  var showOriginal by remember(c.cid) { mutableStateOf(false) }
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  val onBg = MaterialTheme.colors.onBackground
  Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = if (depth > 0) 30.dp else 0.dp)) {
    ProfileImage(size = 28.dp, image = c.avatar)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      // cabecera: nombre y hora a la izquierda; lápiz y papelera (solo en lo mío) a la derecha
      Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
          Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onBg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
          Spacer(Modifier.width(8.dp))
          Text(c.tsText, fontSize = 11.sp, color = secondary, maxLines = 1, softWrap = false)
          if (c.edited) {
            Text(
              "· editado",
              fontSize = 11.sp,
              color = primary,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.clickable { showOriginal = true }.padding(start = 6.dp)
            )
          }
        }
        if (c.mine) {
          Text("✏️", fontSize = 16.sp, maxLines = 1, softWrap = false, modifier = Modifier.clickable { showEdit = true }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (onDelete != null) {
          Text("🗑", fontSize = 16.sp, maxLines = 1, softWrap = false, modifier = Modifier.clickable { onDelete() }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
      }
      if (parentName != null) {
        Text("↪ en respuesta a " + parentName, fontSize = 11.sp, color = secondary)
      }
      if (c.text.isNotEmpty()) {
        Text(c.text, fontSize = 14.sp, color = onBg)
      }
      val mci = c.mediaCi
      if (mci != null) ZfMedia(mci, c.rh, true)
      // las reacciones van en su propia línea y las acciones no se parten aunque falte espacio
      ZfReactionSummary(c.counts, true) { showReactors = true }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val mr = c.myReaction
        Text(
          if (mr != null) mr + " Reaccionaste" else "Reaccionar",
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (mr != null) primary else secondary,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.clickable { trayOpen = !trayOpen }.padding(top = 6.dp, bottom = 6.dp, end = 28.dp)
        )
        Text(
          "Responder",
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = secondary,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.clickable { onReply() }.padding(vertical = 6.dp)
        )
      }
      if (trayOpen) {
        ZfEmojiTray(c.myReaction, true) { em ->
          trayOpen = false
          reacting = true
          withLongRunningApi {
            try {
              zfReact(p, if (c.myReaction == em) "-" else em, c.cid)
            } finally {
              withContext(Dispatchers.Main) { reacting = false }
            }
          }
        }
      }
    }
  }
  if (showReactors) {
    ZfReactorsDialog(c.reactors) { showReactors = false }
  }
  if (showEdit) {
    ZfEditDialog(
      title = "Editar comentario",
      origText = c.origText,
      origCi = c.origCi,
      curText = c.text,
      curCi = c.mediaCi,
      maxLen = ZF_MAX_COMMENT,
      allowAudio = false,
      onSave = { inp -> zfEditComment(p, c, inp) },
      onDismiss = { showEdit = false }
    )
  }
  if (showOriginal) {
    ZfOriginalDialog("Comentario editado", c.origText, c.origCi, c.text, c.mediaCi) { showOriginal = false }
  }
}

@Composable
private fun ZfCommentComposer(p: ZfPost, replyTo: ZfComment?, onClearReply: () -> Unit) {
  var draft by remember(p.key) { mutableStateOf("") }
  var busy by remember(p.key) { mutableStateOf(false) }
  val picked = remember(p.key) { ZfPicked() }
  val pickers = rememberZfPickers(picked)
  Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    if (replyTo != null) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("↪ Respondiendo a " + replyTo.name, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("✕", modifier = Modifier.clickable { onClearReply() }.padding(8.dp), color = MaterialTheme.colors.error)
      }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        draft,
        { if (it.length <= ZF_MAX_COMMENT) draft = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text(if (replyTo != null) "Escribe tu respuesta…" else "Escribe un comentario…") },
        maxLines = 4
      )
      TextButton(
        onClick = {
          val t = draft
          val u = picked.uri
          val v = picked.video
          val rid = replyTo?.cid
          busy = true
          picked.error = null
          withLongRunningApi {
            var err: String? = "No se pudo enviar."
            try {
              err = zfComment(p, t, u, v, rid)
            } catch (e: Exception) {
              Log.e(TAG, "zetaria feed comment: " + e.stackTraceToString())
            } finally {
              withContext(Dispatchers.Main) { busy = false }
            }
            withContext(Dispatchers.Main) {
              if (err == null) {
                draft = ""
                picked.clear()
                onClearReply()
              } else {
                picked.error = err
              }
            }
          }
        },
        enabled = !busy && (draft.isNotBlank() || picked.uri != null)
      ) { Text("Enviar") }
    }
    ZfPickedView(picked, 200.dp, !busy, pickers, false)
  }
}

@Composable
private fun ZfReactorsDialog(reactors: List<ZfReactor>, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.padding(16.dp)) {
        Text("Reacciones", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
          for (r in reactors.sortedBy { it.emoji }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              ProfileImage(size = 36.dp, image = r.avatar)
              Spacer(Modifier.width(10.dp))
              Text(r.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(r.emoji, fontSize = 22.sp)
            }
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
      }
    }
  }
}

@Composable
private fun ZfVotersDialog(p: ZfPost, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.padding(16.dp)) {
        Text("Votantes", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
          for (v in p.voters.sortedBy { it.option }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              ProfileImage(size = 36.dp, image = v.avatar)
              Spacer(Modifier.width(10.dp))
              Text(v.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                if (v.option >= 1 && v.option <= p.options.size) p.options[v.option - 1] else "",
                fontSize = 13.sp,
                color = MaterialTheme.colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
              )
            }
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
      }
    }
  }
}

@Composable
private fun ZfDeleteDialog(title: String, message: String, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.padding(16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 14.sp, color = MaterialTheme.colors.secondary)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
          TextButton(onClick = onConfirm, enabled = !busy) { Text("Eliminar", color = MaterialTheme.colors.error) }
        }
      }
    }
  }
}

/** Caja con un texto de solo lectura (para mostrar el original). */
@Composable
private fun ZfQuote(label: String, text: String) {
  val secondary = MaterialTheme.colors.secondary
  Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = secondary)
  Spacer(Modifier.height(4.dp))
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(secondary.copy(alpha = 0.12f))
      .padding(10.dp)
  ) {
    Text(
      if (text.isEmpty()) "(sin texto)" else text,
      fontSize = 14.sp,
      color = if (text.isEmpty()) secondary else MaterialTheme.colors.onBackground
    )
  }
}

/** Miniatura fija (sin reproductor) del archivo de un mensaje: imagen, video o audio. */
@Composable
private fun ZfStaticMedia(ci: ChatItem?) {
  val mc = ci?.content?.msgContent
  val prev: String? = when (mc) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> null
  }
  if (prev != null) {
    val bmp = remember(prev) {
      try {
        base64ToBitmap(prev)
      } catch (e: Exception) {
        null
      }
    }
    if (bmp != null) {
      Spacer(Modifier.height(6.dp))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(bmp, null, Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
        if (mc is MsgContent.MCVideo) {
          Text("▶", fontSize = 34.sp, color = Color.White)
        }
      }
    }
  } else if (mc is MsgContent.MCFile) {
    Spacer(Modifier.height(6.dp))
    Text(zfFileIcon(ci?.file?.fileName ?: "") + " " + (ci?.file?.fileName ?: "archivo"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colors.onBackground)
  }
}

/**
 * Ventana para editar: muestra arriba el original (texto y archivo, antes de editar) y debajo el campo para cambiar
 * el texto y el archivo (cambiarlo por otro o quitarlo).
 */
@Composable
private fun ZfEditDialog(
  title: String,
  origText: String,
  origCi: ChatItem,
  curText: String,
  curCi: ChatItem?,
  maxLen: Int,
  allowAudio: Boolean,
  onSave: suspend (ZfEditInput) -> String?,
  onDismiss: () -> Unit
) {
  var text by remember { mutableStateOf(curText) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var remove by remember { mutableStateOf(false) }
  val picked = remember { ZfPicked() }
  val pickers = rememberZfPickers(picked)
  val secondary = MaterialTheme.colors.secondary
  val primary = MaterialTheme.colors.primary
  val hasCur = zfHasMedia(curCi)
  val curDiffers = hasCur && curCi != null && curCi.id != origCi.id
  Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ZfQuote("Original (antes de editar)", origText)
        ZfStaticMedia(origCi)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          text,
          { if (it.length <= maxLen) text = it },
          label = { Text("Texto nuevo") },
          modifier = Modifier.fillMaxWidth(),
          maxLines = 8,
          enabled = !busy
        )
        Spacer(Modifier.height(8.dp))
        if (picked.uri != null || picked.audio) {
          Text("Archivo nuevo (reemplaza al anterior):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = secondary)
          ZfPickedView(picked, 200.dp, !busy, pickers, allowAudio)
        } else if (remove) {
          Text("Se quitará el archivo adjunto.", fontSize = 13.sp, color = MaterialTheme.colors.error)
          Text(
            "↩ Deshacer",
            color = primary,
            fontSize = 13.sp,
            modifier = Modifier.clickable(enabled = !busy) { remove = false }.padding(vertical = 8.dp)
          )
        } else {
          if (curDiffers) {
            Text("Archivo actual:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = secondary)
            ZfStaticMedia(curCi)
            Spacer(Modifier.height(6.dp))
          }
          Text(
            if (hasCur) "Cambiar el archivo por otro (si no eliges nada, se queda el actual):" else "Añadir un archivo:",
            fontSize = 12.sp,
            color = secondary
          )
          ZfPickedView(picked, 200.dp, !busy, pickers, allowAudio)
          if (hasCur) {
            Text(
              "🗑 Quitar el archivo actual",
              color = MaterialTheme.colors.error,
              fontSize = 13.sp,
              modifier = Modifier.clickable(enabled = !busy) { remove = true }.padding(vertical = 8.dp)
            )
          }
        }
        Text("Se verá «editado» y quien quiera podrá ver el original.", fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 4.dp))
        val err = error
        if (err != null) {
          Text(err, color = MaterialTheme.colors.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          if (busy) {
            Text("Guardando…", fontSize = 13.sp, color = secondary, modifier = Modifier.padding(end = 8.dp))
          }
          TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
          TextButton(
            onClick = {
              val inp = ZfEditInput(text, picked.uri, picked.video, picked.audio, remove)
              busy = true
              error = null
              withLongRunningApi {
                var res: String? = "No se pudo guardar la edición."
                try {
                  res = onSave(inp)
                } catch (e: Exception) {
                  Log.e(TAG, "zetaria feed edit: " + e.stackTraceToString())
                } finally {
                  withContext(Dispatchers.Main) { busy = false }
                }
                withContext(Dispatchers.Main) {
                  if (res == null) { onDismiss() } else { error = res }
                }
              }
            },
            enabled = !busy && (text.trim() != curText.trim() || picked.uri != null || remove)
          ) { Text("Guardar") }
        }
      }
    }
  }
}

/** Ventana que muestra la versión original (texto y archivo) de algo ya editado, junto a la versión actual. */
@Composable
private fun ZfOriginalDialog(title: String, origText: String, origCi: ChatItem, curText: String, curCi: ChatItem?, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ZfQuote("Original", origText)
        ZfStaticMedia(origCi)
        Spacer(Modifier.height(12.dp))
        ZfQuote("Versión actual (editada)", curText)
        if (zfHasMedia(curCi)) {
          ZfStaticMedia(curCi)
        } else if (zfHasMedia(origCi)) {
          Text("(se quitó el archivo adjunto)", fontSize = 12.sp, color = MaterialTheme.colors.secondary, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
      }
    }
  }
}

/** Compartir una publicación: en mi propio Feed (a mis contactos) o fuera de Zetaria con el menú de Android. */
@Composable
private fun ZfShareDialog(p: ZfPost, onDismiss: () -> Unit) {
  var note by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val clipboard = LocalClipboardManager.current
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  val targetCount = remember { zfTargets().size }
  Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Compartir publicación", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ZfQuote(p.authorName, if (p.text.isEmpty()) "(publicación con archivo adjunto)" else p.text.take(300))
        ZfStaticMedia(p.mediaCi)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          note,
          { if (it.length <= 500) note = it },
          label = { Text("Añade un comentario (opcional)") },
          modifier = Modifier.fillMaxWidth(),
          maxLines = 4,
          enabled = !busy
        )
        Spacer(Modifier.height(4.dp))
        Text(
          "🔁 Compartir en mi Feed",
          color = primary,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
              val n = note
              busy = true
              error = null
              withLongRunningApi {
                var res: String? = "No se pudo compartir."
                try {
                  res = zfShareToFeed(p, n)
                } catch (e: Exception) {
                  Log.e(TAG, "zetaria feed share: " + e.stackTraceToString())
                } finally {
                  withContext(Dispatchers.Main) { busy = false }
                }
                withContext(Dispatchers.Main) {
                  if (res == null) {
                    showToast("Compartido en tu Feed")
                    onDismiss()
                  } else {
                    error = res
                  }
                }
              }
            }
            .padding(vertical = 12.dp)
        )
        Text(
          "Se publicará con tu nombre a " + targetCount + (if (targetCount == 1) " contacto." else " contactos."),
          fontSize = 12.sp,
          color = secondary
        )
        Text(
          "📤 Enviar a otra app",
          color = primary,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
              val sb = StringBuilder()
              if (note.isNotBlank()) sb.append(note.trim()).append("\n\n")
              sb.append(p.authorName).append(":")
              if (p.text.isNotEmpty()) sb.append("\n").append(p.text)
              val mci = p.mediaCi
              val src = if (mci != null && zfHasMedia(mci)) zfFileOf(mci) else null
              if (src != null) {
                // imagen, video o audio con el texto como pie
                zetariaShareFile(sb.toString(), src, zfKindOf(mci))
              } else {
                if (zfHasMedia(mci)) showToast("Descarga primero el archivo para poder compartirlo; se envía solo el texto.")
                clipboard.shareText(sb.toString())
              }
              onDismiss()
            }
            .padding(vertical = 12.dp)
        )
        val err = error
        if (err != null) {
          Text(err, color = MaterialTheme.colors.error, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        }
      }
    }
  }
}

@Composable
private fun ZfPoll(p: ZfPost, onShowVoters: () -> Unit) {
  var busy by remember(p.key) { mutableStateOf(false) }
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  val onBg = MaterialTheme.colors.onBackground
  val total = p.voteCounts.sum()
  Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text("📊 Encuesta", fontSize = 12.sp, color = secondary)
    for (i in p.options.indices) {
      val n = if (i < p.voteCounts.size) p.voteCounts[i] else 0
      val frac = if (total > 0) n.toFloat() / total.toFloat() else 0f
      val sel = p.myVote == i + 1
      Box(
        Modifier
          .fillMaxWidth()
          .padding(top = 6.dp)
          .height(44.dp)
          .clip(RoundedCornerShape(10.dp))
          .border(1.dp, if (sel) primary else secondary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
          .clickable(enabled = !busy) {
            busy = true
            withLongRunningApi {
              try {
                zfReact(p, if (sel) "#0" else "#" + (i + 1), null)
              } finally {
                withContext(Dispatchers.Main) { busy = false }
              }
            }
          }
      ) {
        if (frac > 0f) {
          Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(primary.copy(alpha = 0.25f)))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            (if (sel) "● " else "○ ") + p.options[i],
            Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onBg
          )
          Text((frac * 100f).toInt().toString() + "%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onBg)
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(total.toString() + (if (total == 1) " voto" else " votos"), Modifier.weight(1f), fontSize = 12.sp, color = secondary)
      if (total > 0) {
        Text("Ver votantes", fontSize = 12.sp, color = primary, modifier = Modifier.clickable { onShowVoters() }.padding(4.dp))
      }
    }
  }
}

@Composable
private fun ZfPostCard(p: ZfPost) {
  var showDelete by remember(p.key) { mutableStateOf(false) }
  var deleting by remember(p.key) { mutableStateOf(false) }
  var commentToDelete by remember(p.key) { mutableStateOf<ZfComment?>(null) }
  var deletingComment by remember(p.key) { mutableStateOf(false) }
  var showComments by remember(p.key) { mutableStateOf(false) }
  var showReactors by remember(p.key) { mutableStateOf(false) }
  var showVoters by remember(p.key) { mutableStateOf(false) }
  var trayOpen by remember(p.key) { mutableStateOf(false) }
  var replyTo by remember(p.key) { mutableStateOf<ZfComment?>(null) }
  var busy by remember(p.key) { mutableStateOf(false) }
  var showEdit by remember(p.key) { mutableStateOf(false) }
  var showShare by remember(p.key) { mutableStateOf(false) }
  var showOriginal by remember(p.key) { mutableStateOf(false) }
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  val onBg = MaterialTheme.colors.onBackground
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 6.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(secondary.copy(alpha = 0.10f))
      .padding(12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ProfileImage(size = 40.dp, image = p.authorImage)
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(p.authorName, fontWeight = FontWeight.Bold, color = onBg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(getTimestampText(p.ci.meta.itemTs), fontSize = 12.sp, color = secondary)
          if (p.edited) {
            Text(
              "· editado",
              fontSize = 12.sp,
              color = primary,
              modifier = Modifier.clickable { showOriginal = true }.padding(start = 6.dp)
            )
          }
        }
      }
      Text("📤", fontSize = 18.sp, modifier = Modifier.clickable { showShare = true }.padding(8.dp))
      if (p.mine) {
        if (p.options.isEmpty()) {
          Text("✏️", fontSize = 18.sp, modifier = Modifier.clickable { showEdit = true }.padding(8.dp))
        }
        Text("🗑", fontSize = 18.sp, modifier = Modifier.clickable { showDelete = true }.padding(8.dp))
      }
    }
    if (p.text.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      Text(p.text, fontSize = 15.sp, color = onBg)
    }
    if (p.options.isNotEmpty()) {
      ZfPoll(p) { showVoters = true }
    }
    val pmedia = p.mediaCi
    if (pmedia != null) ZfMedia(pmedia, p.rh, false)
    // resumen: reacciones a la izquierda, comentarios a la derecha
    if (p.reactors.isNotEmpty() || p.comments.isNotEmpty()) {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ZfReactionSummary(p.counts, false) { showReactors = true }
        Spacer(Modifier.weight(1f))
        if (p.comments.isNotEmpty()) {
          Text(
            p.comments.size.toString() + (if (p.comments.size == 1) " comentario" else " comentarios"),
            fontSize = 13.sp,
            color = secondary,
            modifier = Modifier.clickable { showComments = !showComments }.padding(vertical = 4.dp)
          )
        }
      }
    }
    Divider(Modifier.padding(top = 6.dp), color = secondary.copy(alpha = 0.25f))
    // botones: Reaccionar | Comentar
    Row(Modifier.fillMaxWidth()) {
      val mr = p.myReaction
      Text(
        if (mr != null) mr + " Reaccionaste" else "🙂 Reaccionar",
        Modifier.weight(1f).clickable { trayOpen = !trayOpen }.padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
        color = if (mr != null) primary else secondary
      )
      Text(
        "💬 Comentar",
        Modifier.weight(1f).clickable { showComments = !showComments }.padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
        color = if (showComments) primary else secondary
      )
    }
    if (trayOpen) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ZfEmojiTray(p.myReaction, false) { em ->
          trayOpen = false
          busy = true
          withLongRunningApi {
            try {
              zfReact(p, if (p.myReaction == em) "-" else em, null)
            } finally {
              withContext(Dispatchers.Main) { busy = false }
            }
          }
        }
      }
    }
    if (showComments) {
      val names = HashMap<String, String>()
      for (c in p.comments) names[c.cid] = c.name
      for (en in zfThread(p.comments)) {
        val c = en.first
        ZfCommentRow(
          p,
          c,
          en.second,
          if (c.reply.isNotEmpty()) names[c.reply] else null,
          { replyTo = c },
          if (c.mine) ({ commentToDelete = c }) else null
        )
      }
      ZfCommentComposer(p, replyTo) { replyTo = null }
    }
  }
  if (showReactors) {
    ZfReactorsDialog(p.reactors) { showReactors = false }
  }
  if (showEdit) {
    ZfEditDialog(
      title = "Editar publicación",
      origText = p.origText,
      origCi = p.origCi,
      curText = p.text,
      curCi = p.mediaCi,
      maxLen = ZF_MAX_TEXT,
      allowAudio = true,
      onSave = { inp -> zfEditPost(p, inp) },
      onDismiss = { showEdit = false }
    )
  }
  if (showShare) {
    ZfShareDialog(p) { showShare = false }
  }
  if (showOriginal) {
    ZfOriginalDialog("Publicación editada", p.origText, p.origCi, p.text, p.mediaCi) { showOriginal = false }
  }
  if (showVoters) {
    ZfVotersDialog(p) { showVoters = false }
  }
  if (showDelete) {
    ZfDeleteDialog(
      title = "Eliminar publicación",
      message = "Dejará de verse en tu Feed y en el de tus contactos.",
      busy = deleting,
      onConfirm = {
        deleting = true
        withLongRunningApi {
          try {
            zfDeletePost(p)
          } finally {
            withContext(Dispatchers.Main) {
              deleting = false
              showDelete = false
            }
          }
        }
      },
      onDismiss = { showDelete = false }
    )
  }
  val cd = commentToDelete
  if (cd != null) {
    ZfDeleteDialog(
      title = "Eliminar comentario",
      message = "Dejará de verse para todos.",
      busy = deletingComment,
      onConfirm = {
        deletingComment = true
        withLongRunningApi {
          try {
            zfDeleteComment(p, cd)
          } finally {
            withContext(Dispatchers.Main) {
              deletingComment = false
              commentToDelete = null
            }
          }
        }
      },
      onDismiss = { commentToDelete = null }
    )
  }
}

@Composable
private fun ZfPublishDialog(onDismiss: () -> Unit) {
  var text by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var pollOn by remember { mutableStateOf(false) }
  val opts = remember { mutableStateListOf("", "") }
  val targetCount = remember { zfTargets().size }
  val picked = remember { ZfPicked() }
  val pickers = rememberZfPickers(picked)
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  val pollReady = text.isNotBlank() && opts.count { it.isNotBlank() } >= 2
  Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Nueva publicación", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
          "Se enviará a " + targetCount + (if (targetCount == 1) " contacto." else " contactos.") + " Quien agregues después también la verá.",
          fontSize = 12.sp,
          color = secondary
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          text,
          { if (it.length <= ZF_MAX_TEXT) text = it },
          label = { Text(if (pollOn) "Pregunta de la encuesta" else "¿Qué quieres compartir?") },
          modifier = Modifier.fillMaxWidth(),
          maxLines = 8
        )
        if (pollOn) {
          for (i in opts.indices) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              OutlinedTextField(
                opts[i],
                { if (it.length <= 80) opts[i] = it },
                label = { Text("Opción " + (i + 1)) },
                modifier = Modifier.weight(1f),
                singleLine = true
              )
              if (opts.size > 2) {
                Text(
                  "✕",
                  color = MaterialTheme.colors.error,
                  modifier = Modifier.clickable(enabled = !busy) { opts.removeAt(i) }.padding(10.dp)
                )
              }
            }
          }
          if (opts.size < ZF_MAX_OPTIONS) {
            Text(
              "＋ Añadir opción",
              color = primary,
              fontSize = 14.sp,
              modifier = Modifier.clickable(enabled = !busy) { opts.add("") }.padding(vertical = 10.dp)
            )
          }
        } else {
          ZfPickedView(picked, 260.dp, !busy, pickers, true)
        }
        Text(
          if (pollOn) "✕ Quitar encuesta" else "📊 Añadir encuesta",
          color = if (pollOn) MaterialTheme.colors.error else primary,
          fontSize = 14.sp,
          modifier = Modifier
            .clickable(enabled = !busy) {
              pollOn = !pollOn
              if (pollOn) picked.clear()
            }
            .padding(vertical = 8.dp)
        )
        val err = error
        if (err != null) {
          Text(err, color = MaterialTheme.colors.error, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          if (busy) {
            Text("Publicando…", fontSize = 13.sp, color = secondary, modifier = Modifier.padding(end = 8.dp))
          }
          TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
          TextButton(
            onClick = {
              val t = text.trim()
              val u = picked.uri
              val v = picked.video
              val a = picked.audio
              val options = if (pollOn) opts.map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
              busy = true
              error = null
              withLongRunningApi {
                var res: String? = "No se pudo publicar."
                try {
                  res = zfPublish(t, u, v, a, options)
                } catch (e: Exception) {
                  Log.e(TAG, "zetaria feed publish: " + e.stackTraceToString())
                } finally {
                  withContext(Dispatchers.Main) { busy = false }
                }
                withContext(Dispatchers.Main) {
                  if (res == null) { onDismiss() } else { error = res }
                }
              }
            },
            enabled = !busy && (if (pollOn) pollReady else (text.isNotBlank() || picked.uri != null))
          ) { Text("Publicar") }
        }
      }
    }
  }
}
