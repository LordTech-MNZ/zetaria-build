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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.simplex.common.model.*
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.base64ToBitmap
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.getLoadedFileSource
import chat.simplex.common.platform.openFile
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.rememberFileChooserLauncher
import chat.simplex.common.platform.resizeImageToStrSize
import chat.simplex.common.platform.settings
import chat.simplex.common.views.chat.topPaddingToContent
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Feed de publicaciones de Zetaria (texto, imágenes y videos).
// Sin cambios de protocolo: todo viaja como mensajes normales entre contactos.
//
//   Publicación          "📰 ζ·<pid>" + texto (o imagen con ese pie)   autor -> todos sus contactos
//   Comentario           "💬 ζ·<pid> <cid>" + texto                    lector -> autor
//   Comentario reenviado "🗨 ζ·<pid> <cid> <clave> <nombre>" + texto   autor -> demás contactos
//   Reacción             "💟 ζ·<pid> <emoji>"  ("-" = quitar)          lector -> autor
//   Reacción reenviada   "💠 ζ·<pid> <clave> <emoji> <nombre>"         autor -> demás contactos
//
// El autor hace de "centro": reenvía los comentarios y reacciones que recibe al resto de sus contactos,
// así todos ven lo mismo. Estos mensajes se ocultan del chat, de la vista previa y de las notificaciones,
// y se muestran juntos, de más nuevo a más antiguo, en la pestaña "Feed". Un SimpleX normal los vería como
// mensajes de texto con una cabecera.
// ---------------------------------------------------------------------------
private const val ZF_TOK = "ζ·"
private const val ZF_POST = "📰 ζ·"
private const val ZF_COMMENT = "💬 ζ·"
private const val ZF_CREL = "🗨 ζ·"
private const val ZF_REACT = "💟 ζ·"
private const val ZF_RREL = "💠 ζ·"
private const val ZF_MAX_TEXT = 3000
private const val ZF_MAX_COMMENT = 1000
private const val ZF_MAX_VIDEO_BYTES = 60L * 1024L * 1024L
private val ZF_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** Guarda una imagen o un video recibido en la galería, dentro de la carpeta «Zetaria». */
expect suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean)

private enum class ZfKind { POST, COMMENT, CREL, REACT, RREL }

private class ZfHead(
  val kind: ZfKind,
  val pid: String,
  val cid: String,
  val key: String,
  val name: String,
  val emoji: String,
  val body: String
)

private val zfPidRe = Regex("^[0-9a-f]{10}$")
private val zfCidRe = Regex("^[0-9a-f]{8}$")
private val zfKeyRe = Regex("^[A-Za-z0-9]{1,24}$")

private fun zfValidEmoji(s: String): Boolean = s == "-" || ZF_EMOJIS.contains(s)

private fun zfParseText(text: String): ZfHead? {
  if (!text.contains(ZF_TOK)) return null
  val nl = text.indexOf('\n')
  val head = if (nl >= 0) text.substring(0, nl) else text
  val body = if (nl >= 0) text.substring(nl + 1).trim() else ""
  if (head.startsWith(ZF_POST)) {
    val pid = head.substring(ZF_POST.length).trim()
    if (!zfPidRe.matches(pid)) return null
    return ZfHead(ZfKind.POST, pid, "", "", "", "", body)
  }
  if (head.startsWith(ZF_COMMENT)) {
    val parts = head.substring(ZF_COMMENT.length).trim().split(" ")
    if (parts.size != 2 || !zfPidRe.matches(parts[0]) || !zfCidRe.matches(parts[1])) return null
    return ZfHead(ZfKind.COMMENT, parts[0], parts[1], "", "", "", body)
  }
  if (head.startsWith(ZF_CREL)) {
    val parts = head.substring(ZF_CREL.length).trim().split(" ", limit = 4)
    if (parts.size < 3 || !zfPidRe.matches(parts[0]) || !zfCidRe.matches(parts[1]) || !zfKeyRe.matches(parts[2])) return null
    val name = if (parts.size > 3) parts[3].trim() else ""
    return ZfHead(ZfKind.CREL, parts[0], parts[1], parts[2], name, "", body)
  }
  if (head.startsWith(ZF_REACT)) {
    val parts = head.substring(ZF_REACT.length).trim().split(" ")
    if (parts.size != 2 || !zfPidRe.matches(parts[0]) || !zfValidEmoji(parts[1])) return null
    return ZfHead(ZfKind.REACT, parts[0], "", "", "", parts[1], "")
  }
  if (head.startsWith(ZF_RREL)) {
    val parts = head.substring(ZF_RREL.length).trim().split(" ", limit = 4)
    if (parts.size < 3 || !zfPidRe.matches(parts[0]) || !zfKeyRe.matches(parts[1]) || !zfValidEmoji(parts[2])) return null
    val name = if (parts.size > 3) parts[3].trim() else ""
    return ZfHead(ZfKind.RREL, parts[0], "", parts[1], name, parts[2], "")
  }
  return null
}

private fun zfHeadOf(ci: ChatItem): ZfHead? {
  if (ci.meta.itemDeleted != null) return null
  val mc = ci.content.msgContent ?: return null
  if (mc !is MsgContent.MCText && mc !is MsgContent.MCImage && mc !is MsgContent.MCVideo) return null
  val h = zfParseText(mc.text) ?: return null
  // solo las publicaciones llevan imagen o video
  if ((mc is MsgContent.MCImage || mc is MsgContent.MCVideo) && h.kind != ZfKind.POST) return null
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
  return ZfEvent(
    h.kind, h.pid, h.cid, h.key, h.name, h.emoji, h.body,
    rh, cInfo.id, cInfo.contact.contactId, cInfo.displayName, cInfo.image,
    ci.chatDir.sent, ci.meta.itemTs.toEpochMilliseconds(), ci.meta.createdAt.toEpochMilliseconds(), ci.id, ci
  )
}

private val zfEvents = HashMap<String, HashMap<Long, ZfEvent>>()
private var zfUserId: Long = -1L
private val zfVersion = mutableStateOf(0)
private val zfLoading = mutableStateOf(false)
private val zfRefreshing = AtomicBoolean(false)
private val zfInitialDone = AtomicBoolean(false)
private val zfSeen = mutableStateOf(0L)

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
private class ZfComment(val cid: String, val name: String, val text: String, val tsMs: Long, val tsText: String, val mine: Boolean)

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
  val reactors: List<String>
)

private class ZfReact(val tsMs: Long, val emoji: String, val name: String)

private class ZfAcc(val key: String, var ev: ZfEvent, val mine: Boolean) {
  val comments = HashMap<String, ZfComment>()
  val reacts = HashMap<String, ZfReact>()
  val chats = HashSet<String>()
}

private fun zfAddComment(a: ZfAcc, e: ZfEvent, name: String, mine: Boolean) {
  if (e.body.isEmpty() || a.comments.containsKey(e.cid)) return
  a.comments[e.cid] = ZfComment(e.cid, name, e.body, e.tsMs, getTimestampText(e.ci.meta.itemTs, true), mine)
}

private fun zfAddReact(a: ZfAcc, e: ZfEvent, reactor: String, name: String) {
  val prev = a.reacts[reactor]
  if (prev == null || e.tsMs >= prev.tsMs) a.reacts[reactor] = ZfReact(e.tsMs, e.emoji, name)
}

private fun zfBuildPosts(): List<ZfPost> {
  val all = ArrayList<ZfEvent>()
  for (m in zfEvents.values) all.addAll(m.values)
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
          if (a != null && a.chats.contains(e.chatId)) zfAddComment(a, e, e.chatName, false)
        } else {
          // yo comenté la publicación de un contacto
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddComment(a, e, "Tú", true)
        }
      }
      ZfKind.CREL -> {
        if (e.sent) {
          // mi propio comentario en mi publicación
          val a = mineAcc[e.pid]
          if (a != null && e.key == "a") zfAddComment(a, e, "Tú", true)
        } else {
          // comentario de otra persona, reenviado por el autor
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddComment(a, e, e.name, false)
        }
      }
      ZfKind.REACT -> {
        if (!e.sent) {
          val a = mineAcc[e.pid]
          if (a != null && a.chats.contains(e.chatId)) zfAddReact(a, e, "c" + e.contactId, e.chatName)
        } else {
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddReact(a, e, "me", "Tú")
        }
      }
      ZfKind.RREL -> {
        if (e.sent) {
          val a = mineAcc[e.pid]
          if (a != null && e.key == "a") zfAddReact(a, e, "a", "Tú")
        } else {
          val a = theirAcc[e.pid + "|" + e.chatId]
          if (a != null) zfAddReact(a, e, e.key, e.name)
        }
      }
    }
  }
  val me = chatModel.currentUser.value
  val res = ArrayList<ZfPost>()
  for (a in mineAcc.values + theirAcc.values) {
    val counts = HashMap<String, Int>()
    val reactors = ArrayList<String>()
    for (r in a.reacts.values) {
      if (r.emoji == "-") continue
      counts[r.emoji] = (counts[r.emoji] ?: 0) + 1
      reactors.add(r.name)
    }
    val myKey = if (a.mine) "a" else "me"
    val mr = a.reacts[myKey]?.emoji
    res.add(
      ZfPost(
        key = a.key,
        pid = a.ev.pid,
        mine = a.mine,
        authorName = if (a.mine) "Tú" else a.ev.chatName,
        authorImage = if (a.mine) me?.image else a.ev.chatImage,
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

/** Publica en el feed. Devuelve null si salió bien, o el motivo del error. */
private suspend fun zfPublish(text: String, media: URI?, video: Boolean): String? {
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  if (targets.isEmpty()) return "Aún no tienes contactos conectados a quienes publicar."
  val caption = ZF_POST + zfNewId(10) + (if (text.isEmpty()) "" else "\n" + text)
  var mc: MsgContent = MsgContent.MCText(caption)
  var file: CryptoFile? = null
  if (media != null && !video) {
    val bmp = getBitmapFromUri(media) ?: return "No se pudo leer la imagen."
    val preview = resizeImageToStrSize(bmp, maxDataSize = 14000)
    file = saveImage(bmp) ?: return "No se pudo preparar la imagen."
    mc = MsgContent.MCImage(caption, preview)
  } else if (media != null) {
    val pd = getBitmapFromVideo(media, withAlertOnException = false)
    val frame = pd.preview ?: return "No se pudo leer el video."
    val secs = ((pd.duration ?: 0L) / 1000L).toInt()
    val preview = resizeImageToStrSize(frame, maxDataSize = 14000)
    file = saveFileFromUri(media, ZF_MAX_VIDEO_BYTES, hiddenFileNamePrefix = "video") ?: return "No se pudo preparar el video (máximo 60 MB)."
    mc = MsgContent.MCVideo(caption, preview, secs)
  }
  var ok = 0
  for (c in targets) {
    if (zfSendOne(c, mc, file)) ok++
  }
  return if (ok == 0) "No se pudo enviar la publicación." else null
}

private suspend fun zfComment(p: ZfPost, text: String): Boolean {
  val t = text.trim().replace("\r", "").take(ZF_MAX_COMMENT)
  if (t.isEmpty()) return false
  val cid = zfNewId(8)
  if (!p.mine) {
    val c = withContext(Dispatchers.Main) { zfChatById(p.chatId) } ?: return false
    return zfSendOne(c, MsgContent.MCText(ZF_COMMENT + p.pid + " " + cid + "\n" + t), null)
  }
  val targets = withContext(Dispatchers.Main) { zfTargets() }
  val msg = ZF_CREL + p.pid + " " + cid + " a " + zfMyName() + "\n" + t
  var ok = false
  for (c in targets) {
    if (zfSendOne(c, MsgContent.MCText(msg), null)) ok = true
  }
  return ok
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

private suspend fun zfHandleIncoming(rhId: Long?, user: UserLike, cInfo: ChatInfo, ci: ChatItem) {
  val active = withContext(Dispatchers.Main) { chatModel.currentUser.value?.userId == user.userId }
  if (!active) return
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
  // Solo reenvío si la publicación es mía y se la envié a este contacto
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
  val key = "c" + ev.contactId
  val name = zfClean(ev.chatName)
  val msg = if (ev.kind == ZfKind.COMMENT) {
    ZF_CREL + ev.pid + " " + ev.cid + " " + key + " " + name + "\n" + ev.body
  } else {
    ZF_RREL + ev.pid + " " + key + " " + ev.emoji + " " + name
  }
  for (c in targets) {
    zfSendOne(c, MsgContent.MCText(msg), null)
  }
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
  val secondary = MaterialTheme.colors.secondary
  val primary = MaterialTheme.colors.primary
  var showPublish by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { zfRefresh() } }
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

@Composable
private fun ZfPostMedia(p: ZfPost) {
  val mc = p.ci.content.msgContent
  val isVideo = mc is MsgContent.MCVideo
  val previewStr = when (mc) {
    is MsgContent.MCImage -> mc.image
    is MsgContent.MCVideo -> mc.image
    else -> return
  }
  val durationSec = (mc as? MsgContent.MCVideo)?.duration ?: 0
  val file = p.ci.file
  var full by remember(p.ci.id) { mutableStateOf<ImageBitmap?>(null) }
  var loadedFile by remember(p.ci.id) { mutableStateOf<CIFile?>(null) }
  var attempt by remember(p.ci.id) { mutableStateOf(0) }
  val previewBmp = remember(previewStr) {
    try {
      base64ToBitmap(previewStr)
    } catch (e: Exception) {
      null
    }
  }

  // Se vuelve a comprobar el disco cada pocos segundos: el archivo puede terminar de descargarse con el feed abierto
  LaunchedEffect(p.ci.id, attempt) {
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
    Box(Modifier.fillMaxWidth()) {
      Image(
        shown,
        null,
        Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp)
          .clip(RoundedCornerShape(10.dp))
          .clickable(enabled = waiting || (isVideo && lf != null)) {
            if (isVideo && lf != null) {
              val src = getLoadedFileSource(lf)
              if (src != null) openFile(src)
            } else if (waiting && file != null) {
              val u = chatModel.currentUser.value
              if (u != null) {
                withLongRunningApi {
                  chatModel.controller.receiveFiles(p.rh, u, listOf(file.fileId), userApprovedRelays = false, auto = false)
                  withContext(Dispatchers.Main) { attempt = attempt + 1 }
                }
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
      } else if (isVideo && lf != null) {
        Text("▶", Modifier.align(Alignment.Center), fontSize = 48.sp, color = Color.White)
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
    if (lf != null) {
      Text(
        "⬇ Guardar en la galería (Zetaria)",
        fontSize = 13.sp,
        color = MaterialTheme.colors.primary,
        modifier = Modifier
          .clickable { withLongRunningApi { zetariaSaveToGallery(lf, isVideo) } }
          .padding(vertical = 8.dp)
      )
    }
  }
}

private fun zfReactorsLabel(names: List<String>): String {
  val shown = names.take(3).joinToString(", ")
  val rest = names.size - 3
  return if (rest > 0) shown + " y " + rest + " más" else shown
}

@Composable
private fun ZfPostCard(p: ZfPost) {
  val scope = rememberCoroutineScope()
  var showComments by remember(p.key) { mutableStateOf(false) }
  var draft by remember(p.key) { mutableStateOf("") }
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
    ZfPostMedia(p)
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
      Spacer(Modifier.height(4.dp))
      Text(zfReactorsLabel(p.reactors), fontSize = 12.sp, color = secondary)
    }
    Text(
      "💬 " + p.comments.size + (if (p.comments.size == 1) " comentario" else " comentarios"),
      fontSize = 13.sp,
      color = primary,
      modifier = Modifier.clickable { showComments = !showComments }.padding(vertical = 8.dp)
    )
    if (showComments) {
      for (c in p.comments) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onBg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(c.tsText, fontSize = 11.sp, color = secondary)
          }
          Text(c.text, fontSize = 14.sp, color = onBg)
        }
      }
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          draft,
          { if (it.length <= ZF_MAX_COMMENT) draft = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text("Escribe un comentario…") },
          maxLines = 4
        )
        TextButton(
          onClick = {
            val t = draft
            busy = true
            withLongRunningApi {
              var ok = false
              try {
                ok = zfComment(p, t)
              } finally {
                withContext(Dispatchers.Main) { busy = false }
              }
              if (ok) withContext(Dispatchers.Main) { draft = "" }
            }
          },
          enabled = !busy && draft.isNotBlank()
        ) { Text("Enviar") }
      }
    }
  }
}

@Composable
private fun ZfPublishDialog(onDismiss: () -> Unit) {
  var text by remember { mutableStateOf("") }
  var mediaUri by remember { mutableStateOf<URI?>(null) }
  var mediaVideo by remember { mutableStateOf(false) }
  var preview by remember { mutableStateOf<ImageBitmap?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val targetCount = remember { zfTargets().size }
  val scope = rememberCoroutineScope()
  val pickImage = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = getBitmapFromUri(uri, withAlertOnException = false)
        withContext(Dispatchers.Main) {
          if (bmp != null) {
            mediaUri = uri
            mediaVideo = false
            preview = bmp
            error = null
          } else {
            error = "No se pudo leer la imagen."
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
            mediaUri = uri
            mediaVideo = true
            preview = frame
            error = null
          } else {
            error = "No se pudo leer el video."
          }
        }
      }
    }
  }
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Nueva publicación", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
          "Se enviará a " + targetCount + (if (targetCount == 1) " contacto." else " contactos."),
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
        val pv = preview
        if (pv != null) {
          Spacer(Modifier.height(10.dp))
          Box(Modifier.fillMaxWidth()) {
            Image(pv, null, Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
            if (mediaVideo) {
              Text("▶", Modifier.align(Alignment.Center), fontSize = 40.sp, color = Color.White)
            }
          }
          Text(
            if (mediaVideo) "✕ Quitar video" else "✕ Quitar imagen",
            color = MaterialTheme.colors.error,
            fontSize = 13.sp,
            modifier = Modifier
              .clickable(enabled = !busy) {
                mediaUri = null
                preview = null
              }
              .padding(vertical = 8.dp)
          )
        } else {
          Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
              "📷 Imagen",
              color = primary,
              modifier = Modifier
                .clickable(enabled = !busy) { scope.launch { pickImage.launch("image/*") } }
                .padding(vertical = 10.dp)
            )
            Text(
              "🎬 Video",
              color = primary,
              modifier = Modifier
                .clickable(enabled = !busy) { scope.launch { pickVideo.launch("video/*") } }
                .padding(vertical = 10.dp)
            )
          }
        }
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
              val u = mediaUri
              val v = mediaVideo
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
            enabled = !busy && (text.isNotBlank() || mediaUri != null)
          ) { Text("Publicar") }
        }
      }
    }
  }
}
