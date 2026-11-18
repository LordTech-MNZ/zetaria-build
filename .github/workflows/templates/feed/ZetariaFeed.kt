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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.simplex.common.model.*
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.VideoPlayerHolder
import chat.simplex.common.platform.base64ToBitmap
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.getLoadedFileSource
import chat.simplex.common.platform.openFile
import chat.simplex.common.platform.rememberFileChooserLauncher
import chat.simplex.common.platform.resizeImageToStrSize
import chat.simplex.common.platform.settings
import chat.simplex.common.views.chat.topPaddingToContent
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
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
//
// Después de la primera línea pueden venir líneas de datos opcionales:
//   "↪ <cid>"       responde a ese comentario
//   "🪪 <imagen>"   foto de perfil en miniatura de quien comenta o reacciona
//   "🕒 <segundos>" fecha original (al reenviar publicaciones antiguas a un contacto nuevo)
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
private const val ZF_META_REPLY = "↪ "
private const val ZF_META_AVATAR = "🪪 "
private const val ZF_META_TIME = "🕒 "
private const val ZF_MAX_TEXT = 3000
private const val ZF_MAX_COMMENT = 1000
private const val ZF_MAX_VIDEO_BYTES = 60L * 1024L * 1024L
private const val ZF_BACKFILL_POSTS = 20
private const val ZF_BACKFILL_ITEMS = 40
private const val ZF_AUTOSAVE_KEY = "zetaria_auto_save_images"
private val ZF_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** Guarda una imagen o un video recibido en la galería, dentro de la carpeta «Zetaria». */
expect suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean, quiet: Boolean)

private enum class ZfKind { POST, COMMENT, CREL, REACT, RREL }

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
  val origSec: Long
)

private val zfPidRe = Regex("^[0-9a-f]{10}$")
private val zfCidRe = Regex("^[0-9a-f]{8}$")
private val zfKeyRe = Regex("^[A-Za-z0-9]{1,24}$")

private fun zfValidEmoji(s: String): Boolean = s == "-" || ZF_EMOJIS.contains(s)

private fun zfParseText(text: String): ZfHead? {
  if (!text.contains(ZF_TOK)) return null
  val lines = text.split("\n")
  val head = lines[0]
  var reply = ""
  var avatar = ""
  var origSec = 0L
  var i = 1
  while (i < lines.size) {
    val ln = lines[i]
    if (ln.startsWith(ZF_META_REPLY)) {
      val v = ln.substring(ZF_META_REPLY.length).trim()
      if (zfCidRe.matches(v)) reply = v
    } else if (ln.startsWith(ZF_META_AVATAR)) {
      val v = ln.substring(ZF_META_AVATAR.length).trim()
      if (v.startsWith("data:image/") && v.length <= 12000) avatar = v
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
    return ZfHead(ZfKind.POST, pid, "", "", "", "", body, "", "", origSec)
  }
  if (head.startsWith(ZF_COMMENT)) {
    val parts = head.substring(ZF_COMMENT.length).trim().split(" ")
    if (parts.size != 2 || !zfPidRe.matches(parts[0]) || !zfCidRe.matches(parts[1])) return null
    return ZfHead(ZfKind.COMMENT, parts[0], parts[1], "", "", "", body, reply, "", 0L)
  }
  if (head.startsWith(ZF_CREL)) {
    val parts = head.substring(ZF_CREL.length).trim().split(" ", limit = 4)
    if (parts.size < 3 || !zfPidRe.matches(parts[0]) || !zfCidRe.matches(parts[1]) || !zfKeyRe.matches(parts[2])) return null
    val name = if (parts.size > 3) parts[3].trim() else ""
    return ZfHead(ZfKind.CREL, parts[0], parts[1], parts[2], name, "", body, reply, avatar, origSec)
  }
  if (head.startsWith(ZF_REACT)) {
    val parts = head.substring(ZF_REACT.length).trim().split(" ")
    if (parts.size != 2 || !zfPidRe.matches(parts[0]) || !zfValidEmoji(parts[1])) return null
    return ZfHead(ZfKind.REACT, parts[0], "", "", "", parts[1], "", "", "", 0L)
  }
  if (head.startsWith(ZF_RREL)) {
    val parts = head.substring(ZF_RREL.length).trim().split(" ", limit = 4)
    if (parts.size < 3 || !zfPidRe.matches(parts[0]) || !zfKeyRe.matches(parts[1]) || !zfValidEmoji(parts[2])) return null
    val name = if (parts.size > 3) parts[3].trim() else ""
    return ZfHead(ZfKind.RREL, parts[0], "", parts[1], name, parts[2], "", "", avatar, origSec)
  }
  return null
}

private fun zfIsMediaItem(ci: ChatItem): Boolean {
  val mc = ci.content.msgContent
  return mc is MsgContent.MCImage || mc is MsgContent.MCVideo
}

private fun zfHeadOf(ci: ChatItem): ZfHead? {
  if (ci.meta.itemDeleted != null) return null
  val mc = ci.content.msgContent ?: return null
  val media = mc is MsgContent.MCImage || mc is MsgContent.MCVideo
  if (!media && mc !is MsgContent.MCText) return null
  val h = zfParseText(mc.text) ?: return null
  // solo publicaciones y comentarios llevan imagen o video
  if (media && h.kind != ZfKind.POST && h.kind != ZfKind.COMMENT && h.kind != ZfKind.CREL) return null
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
  val rh: Long?,
  val chatId: String,
  val contactId: Long,
  val chatName: String,
  val chatImage: String?,
  val sent: Boolean,
  val tsMs: Long,
  val createdMs: Long,
  val itemId: Long,
  val ci: ChatItem
)

private fun zfEventOf(rh: Long?, cInfo: ChatInfo, ci: ChatItem): ZfEvent? {
  if (cInfo !is ChatInfo.Direct) return null
  val h = zfHeadOf(ci) ?: return null
  val ts = if (h.origSec > 0L) h.origSec * 1000L else ci.meta.itemTs.toEpochMilliseconds()
  return ZfEvent(
    h.kind, h.pid, h.cid, h.key, h.name, h.emoji, h.body, h.reply, h.avatar,
    rh, cInfo.id, cInfo.contact.contactId, cInfo.displayName, cInfo.image,
    ci.chatDir.sent, ts, ci.meta.createdAt.toEpochMilliseconds(), ci.id, ci
  )
}

private val zfEvents = HashMap<String, HashMap<Long, ZfEvent>>()
private var zfUserId: Long = -1L
private val zfVersion = mutableStateOf(0)
private val zfLoading = mutableStateOf(false)
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

private fun zfSetAutoSave(v: Boolean) {
  zfAutoSaveLoaded = true
  zfAutoSave.value = v
  try {
    settings.putBoolean(ZF_AUTOSAVE_KEY, v)
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed autosave pref: " + e.stackTraceToString())
  }
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
  val text: String,
  val reply: String,
  val tsMs: Long,
  val tsText: String,
  val mine: Boolean,
  val ci: ChatItem,
  val rh: Long?
)

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
  val tsMs: Long,
  val comments: List<ZfComment>,
  val counts: Map<String, Int>,
  val myReaction: String?,
  val reactors: List<ZfReactor>
)

private class ZfReact(val tsMs: Long, val emoji: String, val name: String, val avatar: String?)

private class ZfAcc(val key: String, var ev: ZfEvent, val mine: Boolean) {
  val comments = HashMap<String, ZfComment>()
  val reacts = HashMap<String, ZfReact>()
  val chats = HashSet<String>()
}

private fun zfAddComment(a: ZfAcc, e: ZfEvent, key: String, name: String, avatar: String?, mine: Boolean) {
  if ((e.body.isEmpty() && !zfIsMediaItem(e.ci)) || a.comments.containsKey(e.cid)) return
  a.comments[e.cid] = ZfComment(
    e.cid, key, name, avatar, e.body, e.reply, e.tsMs, getTimestampText(e.ci.meta.itemTs, true), mine, e.ci, e.rh
  )
}

private fun zfAddReact(a: ZfAcc, e: ZfEvent, reactor: String, name: String, avatar: String?) {
  val prev = a.reacts[reactor]
  if (prev == null || e.tsMs >= prev.tsMs) a.reacts[reactor] = ZfReact(e.tsMs, e.emoji, name, avatar)
}

private fun zfRelayedAvatar(e: ZfEvent): String? =
  if (e.key == "a") e.chatImage else (if (e.avatar.isEmpty()) null else e.avatar)

private fun zfBuildPosts(): List<ZfPost> {
  val all = ArrayList<ZfEvent>()
  for (m in zfEvents.values) all.addAll(m.values)
  val me = chatModel.currentUser.value
  val myImage = me?.image
  val mineAcc = HashMap<String, ZfAcc>()   // pid -> publicación mía
  val theirAcc = HashMap<String, ZfAcc>()  // "pid|chat" -> publicación de un contacto
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
  val res = ArrayList<ZfPost>()
  for (a in mineAcc.values + theirAcc.values) {
    val counts = HashMap<String, Int>()
    val reactors = ArrayList<ZfReactor>()
    for (en in a.reacts.entries) {
      val r = en.value
      if (r.emoji == "-") continue
      counts[r.emoji] = (counts[r.emoji] ?: 0) + 1
      reactors.add(ZfReactor(en.key, r.name, r.emoji, r.avatar))
    }
    val myKey = if (a.mine) "a" else "me"
    val mr = a.reacts[myKey]?.emoji
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
        text = a.ev.body,
        tsMs = a.ev.tsMs,
        comments = a.comments.values.sortedBy { it.tsMs },
        counts = counts,
        myReaction = if (mr == null || mr == "-") null else mr,
        reactors = reactors
      )
    )
  }
  res.sortByDescending { it.tsMs }
  return res
}

// ---------------------------------------------------------------------------
// Lectura: se vuelven a cargar los mensajes del feed de cada contacto
// ---------------------------------------------------------------------------
private suspend fun zfRefresh() {
  if (!zfRefreshing.compareAndSet(false, true)) return
  withContext(Dispatchers.Main) { zfLoading.value = true }
  try {
    val chats = withContext(Dispatchers.Main) { zfReadable() }
    for (c in chats) {
      val res = chatModel.controller.apiGetChat(
        rh = c.remoteHostId,
        type = ChatType.Direct,
        id = c.chatInfo.apiId,
        scope = null,
        pagination = ChatPagination.Last(100),
        search = ZF_TOK
      ) ?: continue
      val chat = res.first
      val list = ArrayList<ZfEvent>()
      for (ci in chat.chatItems) {
        val e = zfEventOf(c.remoteHostId, chat.chatInfo, ci)
        if (e != null) list.add(e)
      }
      withContext(Dispatchers.Main) { zfSetChat(chat.chatInfo.id, list) }
    }
    withContext(Dispatchers.Main) { zfInitSeen() }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed refresh: " + e.stackTraceToString())
  } finally {
    zfRefreshing.set(false)
    withContext(NonCancellable + Dispatchers.Main) { zfLoading.value = false }
  }
}

/** Espera a que no haya una lectura en curso y lee de nuevo (para tener el almacén completo). */
private suspend fun zfRefreshNow() {
  var n = 0
  while (zfRefreshing.get() && n < 40) {
    delay(250)
    n++
  }
  zfRefresh()
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
  else -> MsgContent.MCText(text)
}

private class ZfOut(val mc: MsgContent?, val file: CryptoFile?, val error: String?)

/** Prepara el contenido (texto, imagen o video) de una publicación o comentario. */
private suspend fun zfPrepare(media: URI?, video: Boolean, caption: String): ZfOut {
  if (media == null) return ZfOut(MsgContent.MCText(caption), null, null)
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
private suspend fun zfPublish(text: String, media: URI?, video: Boolean): String? {
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  if (targets.isEmpty()) return "Aún no tienes contactos conectados a quienes publicar."
  val caption = ZF_POST + zfNewId(10) + (if (text.isEmpty()) "" else "\n" + text)
  val out = zfPrepare(media, video, caption)
  val err = out.error
  if (err != null) return err
  val mc = out.mc ?: return "No se pudo preparar la publicación."
  var ok = 0
  for (c in targets) {
    if (zfSendOne(c, mc, out.file)) ok++
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

/** emoji = una reacción, o "-" para quitar la mía. */
private suspend fun zfReact(p: ZfPost, emoji: String): Boolean {
  if (!p.mine) {
    val c = withContext(Dispatchers.Main) { zfChatById(p.chatId) } ?: return false
    return zfSendOne(c, MsgContent.MCText(ZF_REACT + p.pid + " " + emoji), null)
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  val msg = ZF_RREL + p.pid + " a " + emoji + " " + zfMyName()
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, MsgContent.MCText(msg), null)) ok = true
  }
  return ok
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
  if (ev.kind != ZfKind.COMMENT && ev.kind != ZfKind.REACT) return
  if (ev.kind == ZfKind.COMMENT) {
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
  val r = chatModel.controller.apiGetChat(
    rh = rhId,
    type = ChatType.Direct,
    id = cInfo.apiId,
    scope = null,
    pagination = ChatPagination.Last(300),
    search = ev.pid
  ) ?: return
  var mineHere = false
  for (x in r.first.chatItems) {
    if (!x.chatDir.sent) continue
    val xe = zfEventOf(rhId, r.first.chatInfo, x) ?: continue
    if (xe.kind == ZfKind.POST && xe.pid == ev.pid) {
      mineHere = true
      break
    }
  }
  if (!mineHere) return
  val targets = withContext(Dispatchers.Main) { zfTargets() }.filter { it.chatInfo.id != cInfo.id }
  if (targets.isEmpty()) return
  val key = "c" + ev.contactId
  val name = zfClean(ev.chatName)
  val avatar = zfSmallAvatar(ev.chatImage)
  val lines = ArrayList<String>()
  var src: CryptoFile? = null
  if (ev.kind == ZfKind.COMMENT) {
    lines.add(ZF_CREL + ev.pid + " " + ev.cid + " " + key + " " + name)
    if (ev.reply.isNotEmpty()) lines.add(ZF_META_REPLY + ev.reply)
    if (avatar != null) lines.add(ZF_META_AVATAR + avatar)
    if (ev.body.isNotEmpty()) lines.add(ev.body)
    src = zfFileOf(ev.ci)
  } else {
    lines.add(ZF_RREL + ev.pid + " " + key + " " + ev.emoji + " " + name)
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
  if (ci.content.msgContent is MsgContent.MCImage && zfAutoSaveOn()) {
    val k = user.userId.toString() + ":" + file.fileId
    if (zfAutoSaved.add(k)) zetariaSaveToGallery(file, false, true)
  }
  val ev = zfEventOf(rhId, cInfo, ci) ?: return
  if (ev.sent || ev.kind != ZfKind.COMMENT) return
  if (!zfIsActiveUser(user)) return
  zfRelayFromContact(rhId, cInfo, ev)
}

// ---------------------------------------------------------------------------
// Contacto nuevo: se le reenvían mis publicaciones anteriores (con sus comentarios y reacciones),
// como cuando te unes a un canal.
// ---------------------------------------------------------------------------
fun zetariaFeedOnContactReady(rhId: Long?, user: UserLike, contact: Contact) {
  if (rhId != null || !contact.directOrUsed) return
  val contactId = contact.contactId
  withLongRunningApi {
    try {
      zfBackfill(user, contactId)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed backfill: " + e.stackTraceToString())
    }
  }
}

private fun zfChatOfContact(contactId: Long): Chat? = zfTargets().firstOrNull { c ->
  val ci = c.chatInfo
  ci is ChatInfo.Direct && ci.contact.contactId == contactId
}

private suspend fun zfBackfill(user: UserLike, contactId: Long) {
  if (!zfIsActiveUser(user)) return
  val flag = "zetaria_feed_bf_" + user.userId + "_" + contactId
  val done = try {
    settings.getBoolean(flag, false)
  } catch (e: Exception) {
    false
  }
  if (done) return
  try {
    settings.putBoolean(flag, true)
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed backfill flag: " + e.stackTraceToString())
  }
  var chat: Chat? = null
  var tries = 0
  while (chat == null && tries < 15) {
    chat = withContext(Dispatchers.Main) { zfChatOfContact(contactId) }
    if (chat == null) {
      delay(1000)
      tries++
    }
  }
  if (chat == null) {
    // todavía no está lista: se intentará de nuevo con el siguiente aviso de conexión
    try {
      settings.putBoolean(flag, false)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria feed backfill flag: " + e.stackTraceToString())
    }
    return
  }
  zfRefreshNow()
  val posts = withContext(Dispatchers.Main) { zfBuildPosts() }
    .filter { it.mine }
    .sortedByDescending { it.tsMs }
    .take(ZF_BACKFILL_POSTS)
    .reversed()
  for (p in posts) {
    zfSendPostCopy(chat, p)
    delay(150)
    for (cm in p.comments.take(ZF_BACKFILL_ITEMS)) {
      zfSendCommentCopy(chat, p, cm)
      delay(150)
    }
    for (rc in p.reactors.take(ZF_BACKFILL_ITEMS)) {
      zfSendReactionCopy(chat, p, rc)
      delay(100)
    }
  }
}

private suspend fun zfSendPostCopy(c: Chat, p: ZfPost): Boolean {
  val lines = ArrayList<String>()
  lines.add(ZF_POST + p.pid)
  lines.add(ZF_META_TIME + (p.tsMs / 1000L))
  if (p.text.isNotEmpty()) lines.add(p.text)
  val src = zfFileOf(p.ci)
  val mc = zfMakeContent(if (src != null) p.ci.content.msgContent else null, lines.joinToString("\n"))
  return zfSendOne(c, mc, src)
}

private suspend fun zfSendCommentCopy(c: Chat, p: ZfPost, cm: ZfComment): Boolean {
  val own = cm.key == "a"
  val lines = ArrayList<String>()
  lines.add(ZF_CREL + p.pid + " " + cm.cid + " " + cm.key + " " + (if (own) zfMyName() else zfClean(cm.name)))
  if (cm.reply.isNotEmpty()) lines.add(ZF_META_REPLY + cm.reply)
  val av = if (own) null else zfSmallAvatar(cm.avatar)
  if (av != null) lines.add(ZF_META_AVATAR + av)
  lines.add(ZF_META_TIME + (cm.tsMs / 1000L))
  if (cm.text.isNotEmpty()) lines.add(cm.text)
  val src = zfFileOf(cm.ci)
  val mc = zfMakeContent(if (src != null) cm.ci.content.msgContent else null, lines.joinToString("\n"))
  return zfSendOne(c, mc, src)
}

private suspend fun zfSendReactionCopy(c: Chat, p: ZfPost, rc: ZfReactor): Boolean {
  val own = rc.key == "a"
  val lines = ArrayList<String>()
  lines.add(ZF_RREL + p.pid + " " + rc.key + " " + rc.emoji + " " + (if (own) zfMyName() else zfClean(rc.name)))
  val av = if (own) null else zfSmallAvatar(rc.avatar)
  if (av != null) lines.add(ZF_META_AVATAR + av)
  return zfSendOne(c, MsgContent.MCText(lines.joinToString("\n")), null)
}

// ---------------------------------------------------------------------------
// Lista de chats: la fila de un contacto no muestra la publicación enviada; se muestra su último mensaje real.
// (Dentro de la sesión los mensajes del feed ni siquiera entran al modelo; esto cubre lo que la base de datos
// devuelve como «último mensaje» al reiniciar la app.)
// ---------------------------------------------------------------------------
private val zfPreviewCache = HashMap<String, ChatItem?>()

private suspend fun zfLastRealItem(chat: Chat): ChatItem? {
  val res = chatModel.controller.apiGetChat(
    rh = chat.remoteHostId,
    type = ChatType.Direct,
    id = chat.chatInfo.apiId,
    scope = null,
    pagination = ChatPagination.Last(40)
  ) ?: return null
  return res.first.chatItems.lastOrNull { !zetariaIsFeedItem(it) }
}

@Composable
fun zetariaPreviewChat(chat: Chat): Chat {
  val last = chat.chatItems.lastOrNull()
  if (last == null || chat.chatInfo !is ChatInfo.Direct || !zetariaIsFeedItem(last)) return chat
  val key = chat.id + ":" + last.id
  var repl by remember(key) { mutableStateOf(zfPreviewCache[key]) }
  var done by remember(key) { mutableStateOf(zfPreviewCache.containsKey(key)) }
  LaunchedEffect(key) {
    if (!done) {
      val found = withContext(Dispatchers.IO) {
        try {
          zfLastRealItem(chat)
        } catch (e: Exception) {
          null
        }
      }
      withContext(Dispatchers.Main) {
        zfPreviewCache[key] = found
        repl = found
        done = true
      }
    }
  }
  val r = repl
  return chat.copy(chatItems = if (r != null) arrayListOf(r) else arrayListOf<ChatItem>())
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
  val ready = chatModel.chats.value.isNotEmpty()
  val unseen = remember(zfVersion.value, zfSeen.value) { zfUnseenCount() }
  // una sola lectura al arrancar, para saber si hay publicaciones nuevas aunque no abras el feed
  LaunchedEffect(ready) {
    if (ready && zfInitialDone.compareAndSet(false, true)) {
      withContext(Dispatchers.IO) { zfRefresh() }
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
  val autoSave = zfAutoSave.value
  val secondary = MaterialTheme.colors.secondary
  val primary = MaterialTheme.colors.primary
  var showPublish by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    zfAutoSaveOn()
    withContext(Dispatchers.IO) { zfRefresh() }
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
          if (loading) "Actualizando…" else (posts.size.toString() + (if (posts.size == 1) " publicación" else " publicaciones")),
          Modifier.weight(1f),
          fontSize = 12.sp,
          color = secondary
        )
        Text(
          "🔄 Actualizar",
          fontSize = 13.sp,
          color = primary,
          modifier = Modifier
            .clickable(enabled = !loading) { scope.launch(Dispatchers.IO) { zfRefresh() } }
            .padding(8.dp)
        )
      }
    }
    item(key = "autosave") {
      Text(
        "🖼 Guardar en la galería las fotos que recibes: " + (if (autoSave) "Sí" else "No"),
        Modifier
          .fillMaxWidth()
          .clickable { zfSetAutoSave(!autoSave) }
          .padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = secondary
      )
    }
    if (posts.isEmpty() && !loading) {
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
@Composable
private fun ZfVideoPlayer(file: CIFile, preview: ImageBitmap, durationMs: Long, maxWidth: Dp) {
  var uri by remember(file.fileId) { mutableStateOf<URI?>(null) }
  var failed by remember(file.fileId) { mutableStateOf(false) }
  LaunchedEffect(file.fileId) {
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
    Box(Modifier.width(width)) {
      Image(preview, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillWidth)
      Text(
        if (failed) "No se pudo abrir el video" else "Preparando video…",
        Modifier.align(Alignment.Center),
        color = Color.White,
        fontSize = 13.sp
      )
    }
    return
  }
  val player = remember(u) { VideoPlayerHolder.getOrCreate(u, false, preview, durationMs, true) }
  val playing = remember(u.path) { player.videoPlaying }
  val progress = remember(u.path) { player.progress }
  val duration = remember(u.path) { player.duration }
  val pv by remember(u) { player.preview }
  val play = {
    player.enableSound(true)
    player.play(true)
  }
  val stop = {
    player.enableSound(false)
    player.stop()
  }
  DisposableEffect(u) {
    onDispose { stop() }
  }
  val showPreview = !playing.value || progress.value == 0L
  Box(Modifier.width(width).clip(RoundedCornerShape(10.dp))) {
    PlayerView(player, width, onClick = play, onLongClick = {}, stop = stop)
    if (showPreview) {
      Image(pv, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
      Box(
        Modifier
          .align(Alignment.Center)
          .size(56.dp)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = 0.4f))
          .clickable { play() },
        contentAlignment = Alignment.Center
      ) {
        Text("▶", fontSize = 26.sp, color = Color.White)
      }
    }
    val d = duration.value
    if (d > 0L && !showPreview) {
      LinearProgressIndicator(
        progress = (progress.value.toFloat() / d.toFloat()).coerceIn(0f, 1f),
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
      )
    }
  }
}

@Composable
private fun ZfMedia(ci: ChatItem, rh: Long?, compact: Boolean) {
  val mc = ci.content.msgContent
  val isVideo = mc is MsgContent.MCVideo
  val previewStr = when (mc) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> return
  }
  val durationSec = (mc as? MsgContent.MCVideo)?.duration ?: 0
  val file = ci.file
  var full by remember(ci.id) { mutableStateOf<ImageBitmap?>(null) }
  var loadedFile by remember(ci.id) { mutableStateOf<CIFile?>(null) }
  var attempt by remember(ci.id) { mutableStateOf(0) }
  val previewBmp = remember(previewStr) {
    try {
      base64ToBitmap(previewStr)
    } catch (e: Exception) {
      null
    }
  }

  // Se vuelve a comprobar el disco cada pocos segundos: el archivo puede terminar de descargarse con el feed abierto
  LaunchedEffect(ci.id, attempt) {
    if (file == null || loadedFile != null) return@LaunchedEffect
    var tries = 0
    while (tries < 40) {
      val probe = if (file.fileStatus.sent) file else file.copy(fileStatus = CIFileStatus.RcvComplete)
      var bmp: ImageBitmap? = null
      var ready = false
      withContext(Dispatchers.IO) {
        try {
          if (isVideo) {
            ready = getLoadedFilePath(probe) != null
          } else {
            bmp = getLoadedImage(probe)?.first
            ready = bmp != null
          }
        } catch (e: Exception) {
          ready = false
        }
      }
      if (ready) {
        full = bmp
        loadedFile = probe
        break
      }
      tries++
      delay(2000)
    }
  }

  val shown = full ?: previewBmp
  if (shown != null) {
    val lf = loadedFile
    val waiting = lf == null && file != null && file.fileStatus is CIFileStatus.RcvInvitation
    Spacer(Modifier.height(8.dp))
    if (isVideo && lf != null && previewBmp != null) {
      ZfVideoPlayer(lf, previewBmp, durationSec * 1000L, if (compact) 220.dp else 360.dp)
    } else {
      Box(Modifier.fillMaxWidth()) {
        Image(
          shown,
          null,
          Modifier
            .fillMaxWidth()
            .heightIn(max = if (compact) 240.dp else 420.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = waiting) {
              val u = chatModel.currentUser.value
              if (u != null && file != null) {
                withLongRunningApi {
                  chatModel.controller.receiveFiles(rh, u, listOf(file.fileId), userApprovedRelays = false, auto = false)
                  withContext(Dispatchers.Main) { attempt = attempt + 1 }
                }
              }
            },
          contentScale = ContentScale.Fit
        )
        if (waiting) {
          Text(
            if (isVideo) "⬇ Toca para descargar el video" else "Toca para descargar",
            Modifier.align(Alignment.Center),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.primary
          )
        } else if (lf == null && file != null && !file.fileStatus.sent) {
          Text("Descargando…", Modifier.align(Alignment.Center), fontSize = 13.sp, color = MaterialTheme.colors.primary)
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
          .clickable { withLongRunningApi { zetariaSaveToGallery(lf, isVideo, false) } }
          .padding(vertical = 8.dp)
      )
    }
  }
}

// ---------------------------------------------------------------------------
// Elegir una imagen o un video (publicaciones y comentarios)
// ---------------------------------------------------------------------------
private class ZfPicked {
  var uri by mutableStateOf<URI?>(null)
  var video by mutableStateOf(false)
  var preview by mutableStateOf<ImageBitmap?>(null)
  var error by mutableStateOf<String?>(null)

  fun clear() {
    uri = null
    preview = null
  }
}

@Composable
private fun rememberZfPickers(picked: ZfPicked): Pair<() -> Unit, () -> Unit> {
  val scope = rememberCoroutineScope()
  val pickImage = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = getBitmapFromUri(uri, withAlertOnException = false)
        withContext(Dispatchers.Main) {
          if (bmp != null) {
            picked.uri = uri
            picked.video = false
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
            picked.preview = frame
            picked.error = null
          } else {
            picked.error = "No se pudo leer el video."
          }
        }
      }
    }
  }
  val launchImage: () -> Unit = { scope.launch { pickImage.launch("image/*") } }
  val launchVideo: () -> Unit = { scope.launch { pickVideo.launch("video/*") } }
  return Pair(launchImage, launchVideo)
}

@Composable
private fun ZfPickedView(picked: ZfPicked, maxHeight: Dp, enabled: Boolean, launchImage: () -> Unit, launchVideo: () -> Unit) {
  val pv = picked.preview
  if (pv != null) {
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
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Text(
        "📷 Imagen",
        color = MaterialTheme.colors.primary,
        fontSize = 14.sp,
        modifier = Modifier.clickable(enabled = enabled) { launchImage() }.padding(vertical = 8.dp)
      )
      Text(
        "🎬 Video",
        color = MaterialTheme.colors.primary,
        fontSize = 14.sp,
        modifier = Modifier.clickable(enabled = enabled) { launchVideo() }.padding(vertical = 8.dp)
      )
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
private fun ZfCommentRow(c: ZfComment, depth: Int, parentName: String?, onReply: () -> Unit) {
  val secondary = MaterialTheme.colors.secondary
  val onBg = MaterialTheme.colors.onBackground
  Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = if (depth > 0) 30.dp else 0.dp)) {
    ProfileImage(size = 28.dp, image = c.avatar)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onBg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(c.tsText, fontSize = 11.sp, color = secondary)
      }
      if (parentName != null) {
        Text("↪ en respuesta a " + parentName, fontSize = 11.sp, color = secondary)
      }
      if (c.text.isNotEmpty()) {
        Text(c.text, fontSize = 14.sp, color = onBg)
      }
      ZfMedia(c.ci, c.rh, true)
      Text(
        "Responder",
        fontSize = 12.sp,
        color = MaterialTheme.colors.primary,
        modifier = Modifier.clickable { onReply() }.padding(vertical = 6.dp)
      )
    }
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
    ZfPickedView(picked, 200.dp, !busy, pickers.first, pickers.second)
  }
}

@Composable
private fun ZfReactorsDialog(p: ZfPost, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.padding(16.dp)) {
        Text("Reacciones", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
          for (r in p.reactors.sortedBy { it.emoji }) {
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
private fun ZfPostCard(p: ZfPost) {
  var showComments by remember(p.key) { mutableStateOf(false) }
  var showReactors by remember(p.key) { mutableStateOf(false) }
  var replyTo by remember(p.key) { mutableStateOf<ZfComment?>(null) }
  var busy by remember(p.key) { mutableStateOf(false) }
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
        Text(getTimestampText(p.ci.meta.itemTs), fontSize = 12.sp, color = secondary)
      }
    }
    if (p.text.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      Text(p.text, fontSize = 15.sp, color = onBg)
    }
    ZfMedia(p.ci, p.rh, false)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (em in ZF_EMOJIS) {
        val n = p.counts[em] ?: 0
        val mine = p.myReaction == em
        Row(
          Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (mine) primary else secondary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable(enabled = !busy) {
              busy = true
              withLongRunningApi {
                try {
                  zfReact(p, if (mine) "-" else em)
                } finally {
                  withContext(Dispatchers.Main) { busy = false }
                }
              }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(em, fontSize = 16.sp)
          if (n > 0) {
            Spacer(Modifier.width(4.dp))
            Text(n.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (mine) primary else onBg)
          }
        }
      }
    }
    if (p.reactors.isNotEmpty()) {
      Text(
        "👥 " + p.reactors.size + (if (p.reactors.size == 1) " persona reaccionó" else " personas reaccionaron") + " · ver quiénes",
        fontSize = 12.sp,
        color = secondary,
        modifier = Modifier.clickable { showReactors = true }.padding(vertical = 6.dp)
      )
    }
    Text(
      "💬 " + p.comments.size + (if (p.comments.size == 1) " comentario" else " comentarios"),
      fontSize = 13.sp,
      color = primary,
      modifier = Modifier.clickable { showComments = !showComments }.padding(vertical = 8.dp)
    )
    if (showComments) {
      val names = HashMap<String, String>()
      for (c in p.comments) names[c.cid] = c.name
      for (en in zfThread(p.comments)) {
        val c = en.first
        ZfCommentRow(c, en.second, if (c.reply.isNotEmpty()) names[c.reply] else null) { replyTo = c }
      }
      ZfCommentComposer(p, replyTo) { replyTo = null }
    }
  }
  if (showReactors) {
    ZfReactorsDialog(p) { showReactors = false }
  }
}

@Composable
private fun ZfPublishDialog(onDismiss: () -> Unit) {
  var text by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val targetCount = remember { zfTargets().size }
  val picked = remember { ZfPicked() }
  val pickers = rememberZfPickers(picked)
  val secondary = MaterialTheme.colors.secondary
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
          label = { Text("¿Qué quieres compartir?") },
          modifier = Modifier.fillMaxWidth(),
          maxLines = 8
        )
        ZfPickedView(picked, 260.dp, !busy, pickers.first, pickers.second)
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
              busy = true
              error = null
              withLongRunningApi {
                var res: String? = "No se pudo publicar."
                try {
                  res = zfPublish(t, u, v)
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
            enabled = !busy && (text.isNotBlank() || picked.uri != null)
          ) { Text("Publicar") }
        }
      }
    }
  }
}
