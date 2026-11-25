package chat.simplex.common.views.zetaria

import SectionBottomSpacer
import SectionDividerSpaced
import SectionItemView
import SectionTextFooter
import SectionView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.views.database.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.localauth.reinitChatController
import chat.simplex.res.MR
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ---------------------------------------------------------------------------
// AUTORIDAD DE ZETARIA
//
// Zetaria no tiene servidor central: cada teléfono hace cumplir lo que dice la Autoridad. Por eso todo funciona
// con FIRMAS:
//   - La app lleva incrustada SOLO la llave PÚBLICA de la Autoridad (variable ZETARIA_AUTHORITY_PUBKEY del repo).
//   - La llave PRIVADA nunca está en el repositorio ni en el APK: vive cifrada (contraseña) en el teléfono de la
//     Autoridad, y solo al desbloquearla ese teléfono puede firmar «edictos».
//   - Un edicto es una orden firmada (BAN, MUTE, RESTRICT, POST_REMOVE, COMMENT_REMOVE, WIPE, LIFT). Cada teléfono
//     comprueba la firma antes de aceptarlo, así que nadie puede falsificar uno ni transmitirlo «en nombre de» la Autoridad.
//   - Los edictos viajan (1) por mensajes entre contactos (se reenvían de contacto en contacto, una sola vez cada
//     uno) y (2) por el archivo authority/edicts.txt del repositorio, que todas las apps consultan cada 30 minutos.
//
// Formato firmado:  ZE1|<id>|<tipo>|<objetivo>|<arg>|<emitido s>|<vence s, 0 = no vence>|<motivo>
// ---------------------------------------------------------------------------

class ZetariaEdict(
  val id: String,
  val type: String,
  val target: String,
  val arg: String,
  val issued: Long,
  val expires: Long,
  val reason: String,
  val wire: String
)

object ZetariaAuthority {
  private const val PUBKEY = "__ZETARIA_AUTHORITY_PUBKEY__"
  private const val REPO = "__ZETARIA_REPO__"
  private const val WIPE_FLAG = "__ZETARIA_ALLOW_WIPE__"

  /** Los mensajes de edicto empiezan así; se ocultan del chat, la lista y las notificaciones. */
  const val MARK = "⚖ ζA· "

  private const val KEY_EDICTS = "ZetariaAuthorityEdicts"
  private const val KEY_VAULT = "ZetariaAuthorityVault"
  private const val MAX_EDICTS = 1500
  private const val POLL_MS = 30L * 60L * 1000L
  private const val GOSSIP_MAX_AGE_S = 30L * 86400L

  private val TYPES = setOf("BAN", "MUTE", "RESTRICT", "WIPE", "POST_REMOVE", "COMMENT_REMOVE", "LIFT", "TITLE", "DOC")
  private val USER_TYPES = setOf("BAN", "MUTE", "RESTRICT", "WIPE")
  private val ID_RX = Regex("^[A-Za-z0-9]{8,32}$")
  private val TARGET_RX = Regex("^[A-Za-z0-9_.\\-]{1,64}$")
  private val ARG_RX = Regex("^[A-Za-z0-9_.\\-]{0,64}$")

  /** Sube con cada cambio para que las pantallas se refresquen solas. */
  val revision = mutableStateOf(0)

  private val edicts = ArrayList<ZetariaEdict>()
  private var loaded = false
  private var started = false
  @Volatile private var unlocked: PrivateKey? = null
  @Volatile private var lastBlockedNoticeMs = 0L
  @Volatile private var lastNoticeId = ""
  @Volatile private var wipeTriggered = false

  fun wipeAllowed(): Boolean = WIPE_FLAG == "on"
  fun pubkeyConfigured(): Boolean = pubKey() != null

  /** null = la llave pública incrustada es válida; si no, explica qué pasa. */
  fun pubkeyProblem(): String? = when {
    pubKey() != null -> null
    PUBKEY.startsWith("__") -> "Este APK no trae la llave pública: la variable ZETARIA_AUTHORITY_PUBKEY estaba vacía al compilar, o instalaste una compilación anterior a ponerla."
    else -> "La llave pública incrustada está dañada (probablemente copiada incompleta). Cópiala de nuevo completa."
  }
  fun builtInPubkey(): String = if (pubKey() != null) norm(PUBKEY) else ""
  fun hasVault(): Boolean = settings.getString(KEY_VAULT, "").isNotEmpty()
  fun isAuthority(): Boolean = unlocked != null

  // ---------------------------------------------------------------------------------------------------------------
  // Criptografía: ECDSA P-256 (disponible en todas las versiones de Android, sin librerías extra)
  // ---------------------------------------------------------------------------------------------------------------
  private fun enc(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
  private fun norm(s: String): String = s.trim().replace('+', '-').replace('/', '_').replace("=", "").replace("\n", "").replace(" ", "")
  private fun dec(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(norm(s))

  private fun pubKey(): PublicKey? {
    if (PUBKEY.length < 60 || PUBKEY.startsWith("__")) return null
    return try {
      KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(dec(PUBKEY)))
    } catch (e: Exception) {
      null
    }
  }

  private fun verify(data: ByteArray, sig: ByteArray): Boolean {
    return try {
      val pk = pubKey() ?: return false
      val s = Signature.getInstance("SHA256withECDSA")
      s.initVerify(pk)
      s.update(data)
      s.verify(sig)
    } catch (e: Exception) {
      false
    }
  }

  private fun sign(data: ByteArray, key: PrivateKey): ByteArray {
    val s = Signature.getInstance("SHA256withECDSA")
    s.initSign(key)
    s.update(data)
    return s.sign()
  }

  private fun deriveKey(pass: String, salt: ByteArray): SecretKeySpec {
    val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val k = f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, 150_000, 256)).encoded
    return SecretKeySpec(k, "AES")
  }

  /** Llave privada cifrada con la contraseña: sal(16) + iv(12) + AES-256-GCM(PKCS#8). */
  private fun seal(pkcs8: ByteArray, pass: String): String {
    val rnd = SecureRandom()
    val salt = ByteArray(16)
    val iv = ByteArray(12)
    rnd.nextBytes(salt)
    rnd.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, deriveKey(pass, salt), GCMParameterSpec(128, iv))
    return enc(salt + iv + c.doFinal(pkcs8))
  }

  private fun unseal(blob: String, pass: String): PrivateKey? {
    return try {
      val raw = dec(blob)
      val salt = raw.copyOfRange(0, 16)
      val iv = raw.copyOfRange(16, 28)
      val ct = raw.copyOfRange(28, raw.size)
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, deriveKey(pass, salt), GCMParameterSpec(128, iv))
      KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(ct)))
    } catch (e: Exception) {
      null
    }
  }

  /** ¿Esta llave privada corresponde a la pública incrustada en la app? (firma de prueba) */
  private fun matchesBuiltIn(key: PrivateKey): Boolean {
    return try {
      val probe = "zetaria-authority-probe".toByteArray()
      verify(probe, sign(probe, key))
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Crea la llave de la Autoridad EN ESTE TELÉFONO. Devuelve la llave PÚBLICA (para la variable ZETARIA_AUTHORITY_PUBKEY).
   * La privada queda cifrada con la contraseña y no sale del teléfono, salvo el respaldo cifrado que tú copies.
   */
  fun generate(pass: String): String? {
    if (pass.length < 10) return null
    return try {
      val g = KeyPairGenerator.getInstance("EC")
      g.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
      val kp = g.generateKeyPair()
      settings.putString(KEY_VAULT, seal(kp.private.encoded, pass))
      revision.value += 1
      enc(kp.public.encoded)
    } catch (e: Exception) {
      Log.e(TAG, "zetaria authority generate: " + e.stackTraceToString())
      null
    }
  }

  /** Importa un respaldo cifrado (el texto que da «Respaldo de la llave»). */
  fun importVault(blob: String, pass: String): Boolean {
    if (unseal(blob, pass) == null) return false
    settings.putString(KEY_VAULT, norm(blob))
    revision.value += 1
    return true
  }

  fun vaultBackup(): String = settings.getString(KEY_VAULT, "")

  /** Desbloquea la autoridad. Solo funciona si la llave coincide con la pública incrustada en esta compilación. */
  fun unlock(pass: String): Boolean {
    val key = unseal(settings.getString(KEY_VAULT, ""), pass) ?: return false
    if (!matchesBuiltIn(key)) return false
    unlocked = key
    revision.value += 1
    try {
      val mine = ZetariaCedula.id()
      if (mine.isNotEmpty() && footerFor(mine).isEmpty()) issue("TITLE", mine, "MONARCA", 0, "Monarca fundador del Reino")
    } catch (e: Exception) {
      Log.e(TAG, "zetaria authority title: " + e.stackTraceToString())
    }
    return true
  }

  fun lock() {
    unlocked = null
    revision.value += 1
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Edictos: formato, firma, comprobación y almacén
  // ---------------------------------------------------------------------------------------------------------------
  private fun clean(s: String, n: Int): String = s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(n)
  private fun nowS(): Long = Clock.System.now().toEpochMilliseconds() / 1000L

  private fun canon(id: String, type: String, target: String, arg: String, issued: Long, expires: Long, reason: String): String =
    "ZE1|" + id + "|" + type + "|" + target + "|" + arg + "|" + issued + "|" + expires + "|" + reason

  /** Devuelve el edicto solo si la firma es de la Autoridad y todos los campos son válidos. */
  private fun parse(wire: String): ZetariaEdict? {
    return try {
      val t = wire.trim()
      val dot = t.indexOf('.')
      if (dot <= 0 || t.length > 1200) return null
      val payload = dec(t.substring(0, dot))
      val sig = dec(t.substring(dot + 1))
      if (!verify(payload, sig)) return null
      val p = String(payload, Charsets.UTF_8).split("|", limit = 8)
      if (p.size != 8 || p[0] != "ZE1") return null
      if (!ID_RX.matches(p[1]) || p[2] !in TYPES || !TARGET_RX.matches(p[3]) || !ARG_RX.matches(p[4])) return null
      val issued = p[5].toLongOrNull() ?: return null
      val expires = p[6].toLongOrNull() ?: return null
      ZetariaEdict(p[1], p[2], p[3], p[4], issued, expires, p[7], t)
    } catch (e: Exception) {
      null
    }
  }

  @Synchronized private fun load() {
    if (loaded) return
    loaded = true
    for (line in settings.getString(KEY_EDICTS, "").lines()) {
      val e = parse(line) ?: continue
      if (edicts.none { it.id == e.id }) edicts.add(e)
    }
  }

  @Synchronized private fun add(e: ZetariaEdict): Boolean {
    load()
    if (edicts.any { it.id == e.id }) return false
    edicts.add(e)
    while (edicts.size > MAX_EDICTS) edicts.removeAt(0)
    settings.putString(KEY_EDICTS, edicts.joinToString("\n") { it.wire })
    return true
  }

  @Synchronized fun all(): List<ZetariaEdict> {
    load()
    return edicts.toList().sortedByDescending { it.issued }
  }

  @Synchronized private fun lifted(id: String): Boolean = edicts.any { it.type == "LIFT" && it.target == id }

  @Synchronized fun active(e: ZetariaEdict): Boolean {
    load()
    return e.type != "LIFT" && (e.expires == 0L || e.expires > nowS()) && !lifted(e.id)
  }

  @Synchronized fun sanctionsOn(zid: String): List<ZetariaEdict> {
    load()
    return edicts.filter { it.target == zid && it.type in USER_TYPES && active(it) }
  }

  /** La sanción más fuerte que pesa sobre ESTA instalación (null = ninguna). WIPE cuenta como BAN si el borrado está desactivado. */
  private fun mySanction(): ZetariaEdict? {
    val mine = sanctionsOn(ZetariaCedula.id())
    return mine.firstOrNull { it.type == "WIPE" && wipeAllowed() }
      ?: mine.firstOrNull { it.type == "BAN" || it.type == "WIPE" }
      ?: mine.firstOrNull { it.type == "MUTE" }
      ?: mine.firstOrNull { it.type == "RESTRICT" }
  }

  fun typeLabel(t: String): String = when (t) {
    "BAN" -> "Baneo"
    "MUTE" -> "Silencio"
    "RESTRICT" -> "Restricción del feed"
    "WIPE" -> "Borrado de datos"
    "POST_REMOVE" -> "Publicación retirada"
    "COMMENT_REMOVE" -> "Comentario retirado"
    "LIFT" -> "Sanción levantada"
    "TITLE" -> "Título honorífico"
    "DOC" -> "Documento PDF"
    else -> t
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Emitir edictos (solo con la autoridad desbloqueada)
  // ---------------------------------------------------------------------------------------------------------------
  fun issue(type: String, target: String, arg: String, days: Int, reason: String): ZetariaEdict? {
    val key = unlocked ?: return null
    if (type !in TYPES || !TARGET_RX.matches(target) || !ARG_RX.matches(arg)) return null
    if (type == "WIPE" && !wipeAllowed()) return null
    val rnd = ByteArray(8)
    SecureRandom().nextBytes(rnd)
    val id = rnd.joinToString("") { b -> "%02x".format(b) }
    val issued = nowS()
    val expires = if (days > 0 && type != "LIFT" && type != "POST_REMOVE" && type != "COMMENT_REMOVE") issued + days * 86400L else 0L
    val text = canon(id, type, target, arg, issued, expires, clean(reason, 140))
    val payload = text.toByteArray(Charsets.UTF_8)
    val wire = enc(payload) + "." + enc(sign(payload, key))
    val e = parse(wire) ?: return null
    add(e)
    revision.value += 1
    withLongRunningApi { sendWire(wire, null) }
    return e
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Recepción: por mensajes de contactos y por el archivo del repositorio
  // ---------------------------------------------------------------------------------------------------------------
  fun isEdictItem(ci: ChatItem): Boolean {
    val mc = ci.content.msgContent ?: return false
    return mc is MsgContent.MCText && mc.text.startsWith(MARK)
  }

  fun onNewItem(rhId: Long?, user: UserLike, cInfo: ChatInfo, cItem: ChatItem) {
    if (!isEdictItem(cItem) || cItem.chatDir.sent) return
    val wire = (cItem.content.msgContent?.text ?: return).removePrefix(MARK).trim()
    withLongRunningApi {
      try {
        if (cItem.isRcvNew) {
          val updated = chatModel.controller.apiChatItemsRead(rhId, cInfo.chatType, cInfo.apiId, cInfo.groupChatScope(), listOf(cItem.id))
          if (updated != null) {
            withContext(Dispatchers.Main) { chatModel.chatsContext.updateChatInfo(rhId, updated) }
          }
        }
        ingest(wire, cInfo.id)
      } catch (e: Exception) {
        Log.e(TAG, "zetaria authority recv: " + e.stackTraceToString())
      }
    }
  }

  /** Acepta un edicto firmado. Si es nuevo y vino de un contacto, lo reenvía al resto (una sola vez por edicto). */
  private suspend fun ingest(wire: String, fromChatId: String?) {
    val e = parse(wire) ?: return
    if (!add(e)) return
    revision.value += 1
    withContext(Dispatchers.Main) { checkSelf() }
    if (fromChatId != null && nowS() - e.issued < GOSSIP_MAX_AGE_S) sendWire(wire, fromChatId)
  }

  private suspend fun sendWire(wire: String, exceptChatId: String?) {
    val targets = withContext(Dispatchers.Main) {
      chatModel.chats.value.filter { c ->
        val ci = c.chatInfo
        c.remoteHostId == null && ci is ChatInfo.Direct && c.id != exceptChatId &&
          ci.contact.ready && ci.contact.active && !ci.contact.chatDeleted && !ci.contact.sendMsgToConnect
      }
    }
    for (c in targets) {
      try {
        chatModel.controller.apiSendMessages(
          rh = c.remoteHostId,
          type = c.chatInfo.chatType,
          id = c.chatInfo.apiId,
          scope = c.chatInfo.groupChatScope(),
          sendAsGroup = c.chatInfo.sendAsGroup,
          live = false,
          ttl = null,
          sign = false,
          composedMessages = listOf(ComposedMessage(null, null, MsgContent.MCText(MARK + wire), emptyMap()))
        )
      } catch (e: Exception) {
        Log.e(TAG, "zetaria authority relay: " + e.stackTraceToString())
      }
    }
  }

  private suspend fun pollRepo() {
    withContext(Dispatchers.IO) {
      try {
        val c = URL("https://raw.githubusercontent.com/" + REPO + "/main/authority/edicts.txt").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.requestMethod = "GET"
        if (c.responseCode != 200) return@withContext
        val body = c.inputStream.bufferedReader().use { it.readText() }.take(2_000_000)
        for (line in body.lines()) {
          val t = line.trim()
          if (t.isEmpty() || t.startsWith("#")) continue
          ingest(t, null)
        }
      } catch (e: Exception) {
        Log.e(TAG, "zetaria authority poll: " + e.message)
      }
    }
  }

  /** Se llama una vez al abrir la app: carga los edictos guardados y consulta el repositorio cada 30 minutos. */
  fun start() {
    if (started) return
    started = true
    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
      load()
      delay(8000)
      withContext(Dispatchers.Main) { checkSelf() }
      while (true) {
        pollRepo()
        withContext(Dispatchers.Main) { checkSelf() }
        delay(POLL_MS)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Cumplimiento en ESTE teléfono
  // ---------------------------------------------------------------------------------------------------------------
  /** ¿Debe bloquearse este envío? Los edictos mismos nunca se bloquean (para que la sanción siga propagándose). */
  fun blocksSend(chatType: ChatType, texts: List<String>): Boolean {
    if (unlocked != null) return false
    if (texts.any { it.startsWith(MARK) }) return false
    val s = mySanction() ?: return false
    val feed = texts.any { it.take(16).contains("ζ·") }
    val blocked = when (s.type) {
      "BAN", "WIPE" -> true
      "MUTE" -> feed || chatType == ChatType.Group
      "RESTRICT" -> feed
      else -> false
    }
    if (blocked) {
      val now = System.currentTimeMillis()
      if (now - lastBlockedNoticeMs > 30_000L) {
        lastBlockedNoticeMs = now
        AlertManager.shared.showAlertMsg(noticeTitle(s), noticeText(s))
      }
    }
    return blocked
  }

  private fun noticeTitle(s: ZetariaEdict): String = when (s.type) {
    "BAN", "WIPE" -> "Baneado del Reino Soberano de Zetaria"
    "MUTE" -> "Silenciado en el Reino Soberano de Zetaria"
    else -> "Restringido en el Reino Soberano de Zetaria"
  }

  private fun noticeText(s: ZetariaEdict): String {
    val until = if (s.expires == 0L) "sin fecha de fin" else "hasta " + java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.expires * 1000L))
    return "El Rey Supremo de Zetaria te impuso esta sanción (" + until + ").\nMotivo: " + s.reason.ifEmpty { "no indicado" } + "\nID del edicto: " + s.id
  }

  /**
   * Avisa de la sanción vigente y, si la Autoridad ordenó borrar los datos (y esta compilación lo permite),
   * los borra de inmediato: el aviso es solo informativo, no una confirmación. El borrado no depende de que
   * la persona toque nada ni de que el aviso siga en pantalla; tocar fuera de él o cerrarlo no lo detiene.
   */
  fun checkSelf() {
    if (unlocked != null) return
    val s = mySanction() ?: return
    if (s.type == "WIPE" && wipeAllowed()) {
      if (wipeTriggered) return
      wipeTriggered = true
      AlertManager.shared.showAlertMsg(
        title = "Sanción grave del Reino Soberano de Zetaria",
        text = "El Rey Supremo ordenó borrar los datos de tu app.\nMotivo: " + s.reason.ifEmpty { "no indicado" } + "\nID del edicto: " + s.id
      )
      wipeAll()
    } else if (lastNoticeId != s.id) {
      lastNoticeId = s.id
      AlertManager.shared.showAlertMsg(noticeTitle(s), noticeText(s))
    }
  }

  /** Mismo procedimiento que usa SimpleX para su código de autodestrucción. */
  private fun wipeAll() {
    withLongRunningApi {
      try {
        val m = chatModel
        while (m.ctrlInitInProgress.value) delay(50)
        if (m.chatRunning.value == true) stopChatAsync(m)
        val ctrl = m.controller.getChatCtrl()
        if (ctrl != null && ctrl != -1L) chatCloseStore(ctrl)
        deleteChatDatabaseFilesAndState()
        ntfManager.cancelAllNotifications()
        reinitChatController()
      } catch (e: Exception) {
        Log.e(TAG, "zetaria authority wipe: " + e.stackTraceToString())
      }
    }
  }

  /** ¿Se oculta esta publicación del feed? (retirada por la Autoridad, o su autor está baneado / silenciado después de la sanción) */
  fun hidesPost(pid: String, chatId: String, tsMs: Long): Boolean {
    load()
    if (all().any { it.type == "POST_REMOVE" && it.target == pid && active(it) }) return true
    val c = chatModel.chats.value.firstOrNull { it.id == chatId } ?: return false
    val ci = c.chatInfo as? ChatInfo.Direct ?: return false
    val zid = ZetariaCedula.parse(ci.contact.profile.description)?.id ?: return false
    return sanctionsOn(zid).any { s ->
      s.type == "BAN" || s.type == "WIPE" || (tsMs / 1000L >= s.issued)
    }
  }

  /**
   * Texto del pie de la cédula de este ID. Solo existe un título si la Autoridad lo firmó (edicto TITLE), así que nadie
   * puede ponérselo por su cuenta: la cédula de otra persona se dibuja en tu teléfono con TUS edictos verificados.
   * Vacío = pie normal («CIUDADANO DEL REINO SOBERANO DE ZETARIA»).
   */
  fun footerFor(zid: String): String {
    load()
    val has = all().any { it.type == "TITLE" && it.target == zid && it.arg == "MONARCA" && active(it) }
    return if (has) "MONARCA FUNDADOR DEL REINO SOBERANO DE ZETARIA" else ""
  }

  /**
   * Documento PDF vigente (botón de Configuración). Lo fija la Autoridad con un edicto DOC firmado:
   *   target = SHA-256 del PDF (64 hex), reason = texto del botón. Gana el más reciente que siga vigente.
   * El PDF se descarga de authority/manifiesto.pdf del repositorio y SOLO se abre si su SHA-256 coincide con el del edicto,
   * así que nadie puede cambiar el documento tocando el repositorio sin la llave de la Autoridad.
   */
  fun currentDoc(): ZetariaEdict? = all().firstOrNull { it.type == "DOC" && active(it) && it.target.length == 64 }

  fun docUrl(): String = "https://raw.githubusercontent.com/" + REPO + "/main/authority/manifiesto.pdf"

  fun hidesComment(pid: String, cid: String): Boolean =
    all().any { it.type == "COMMENT_REMOVE" && it.target == pid && it.arg == cid && active(it) }

  /** Contactos con ID de Zetaria, para elegir a quién sancionar. */
  fun contactsWithId(): List<Triple<String, String, String>> {
    val out = ArrayList<Triple<String, String, String>>()
    for (c in chatModel.chats.value) {
      val ci = c.chatInfo as? ChatInfo.Direct ?: continue
      val zid = ZetariaCedula.parse(ci.contact.profile.description)?.id ?: continue
      out.add(Triple(zid, ci.contact.profile.displayName, c.id))
    }
    return out
  }

  fun zidOfChat(chatId: String): Pair<String, String>? {
    val c = chatModel.chats.value.firstOrNull { it.id == chatId } ?: return null
    val ci = c.chatInfo as? ChatInfo.Direct ?: return null
    val zid = ZetariaCedula.parse(ci.contact.profile.description)?.id ?: return null
    return Pair(zid, ci.contact.profile.displayName)
  }

  fun openSanctionFromFeed(chatId: String, pid: String) {
    val who = zidOfChat(chatId)
    ModalManager.start.showCustomModal { close ->
      ZetariaSanctionView(who?.first ?: "", who?.second ?: "", pid, close)
    }
  }
}

// ---------------------------------------------------------------------------
// Pantallas
// ---------------------------------------------------------------------------
@Composable
fun ZetariaAuthorityView(m: ChatModel) {
  val rev = ZetariaAuthority.revision.value
  val clipboard = LocalClipboardManager.current
  var pass by remember { mutableStateOf("") }
  var message by remember { mutableStateOf("") }
  var showGenerate by remember { mutableStateOf(false) }
  var showImport by remember { mutableStateOf(false) }
  var newPub by remember { mutableStateOf("") }
  val unlocked = ZetariaAuthority.isAuthority()
  val list = remember(rev) { ZetariaAuthority.all() }

  ColumnWithScrollBar {
    AppBarTitle("Autoridad de Zetaria")

    SectionView("Estado") {
      SectionItemView {
        Column {
          Text(
            when {
              unlocked -> "Autoridad ACTIVA en este teléfono"
              !ZetariaAuthority.pubkeyConfigured() -> "Esta compilación aún no tiene llave de Autoridad"
              else -> "Autoridad bloqueada"
            },
            fontWeight = FontWeight.SemiBold
          )
          val problem = ZetariaAuthority.pubkeyProblem()
          if (problem != null) Text(problem, fontSize = 12.sp, color = MaterialTheme.colors.error)
          if (ZetariaAuthority.pubkeyConfigured()) {
            Text("Llave pública del Reino:", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
            Text(ZetariaAuthority.builtInPubkey().take(40) + "…", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
          }
        }
      }
    }
    SectionDividerSpaced()

    if (!unlocked) {
      SectionView("Acceso de la Autoridad") {
        SectionItemView {
          Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
              value = pass,
              onValueChange = { pass = it },
              singleLine = true,
              label = { Text("Contraseña de la llave") },
              visualTransformation = PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth()
            )
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colors.error, fontSize = 13.sp)
          }
        }
        if (ZetariaAuthority.hasVault()) {
          SectionItemView({
            val ok = ZetariaAuthority.unlock(pass)
            message = if (ok) "" else (ZetariaAuthority.pubkeyProblem() ?: "Contraseña incorrecta, o esa llave no es la de la Autoridad de esta compilación.")
            if (ok) pass = ""
          }) { Text("Desbloquear", color = MaterialTheme.colors.primary) }
        }
        SectionItemView({ showImport = true }) { Text("Importar respaldo de la llave", color = MaterialTheme.colors.primary) }
        if (!ZetariaAuthority.hasVault()) {
          SectionItemView({
            if (pass.length < 10) message = "Escribe una contraseña de al menos 10 caracteres."
            else showGenerate = true
          }) { Text("Crear llave de Autoridad en este teléfono", color = MaterialTheme.colors.primary) }
        }
      }
      SectionTextFooter(
        "Solo quien tenga la llave privada puede dar órdenes. Aunque alguien tenga esta app o el código del repositorio, sin la llave no tiene acceso."
      )
      SectionDividerSpaced()
    } else {
      SectionView("Acciones") {
        SectionItemView({
          ModalManager.start.showCustomModal { close -> ZetariaPickContactView(close) }
        }) { Text("Sancionar a un contacto…", color = MaterialTheme.colors.primary) }
        SectionItemView({
          clipboard.setText(AnnotatedString(list.joinToString("\n") { it.wire }))
          showToast("Edictos copiados: pégalos en authority/edicts.txt del repositorio")
        }) { Text("Copiar todos los edictos (para el repositorio)", color = MaterialTheme.colors.primary) }
        SectionItemView({
          clipboard.setText(AnnotatedString(ZetariaAuthority.vaultBackup()))
          showToast("Respaldo cifrado copiado")
        }) { Text("Respaldo cifrado de la llave", color = MaterialTheme.colors.primary) }
        SectionItemView({ ZetariaAuthority.lock() }) { Text("Bloquear autoridad", color = MaterialTheme.colors.error) }
      }
      SectionTextFooter(
        "Los edictos se envían a tus contactos al emitirlos y se reenvían de contacto en contacto. Para llegar a TODOS, pégalos en authority/edicts.txt del repositorio: cada app lo consulta cada 30 minutos."
      )
      SectionDividerSpaced()
    }

    SectionView("Registro público de edictos") {
      if (list.isEmpty()) {
        SectionItemView { Text("No hay edictos.", color = MaterialTheme.colors.secondary) }
      }
      for (e in list.take(60)) {
        val live = ZetariaAuthority.active(e)
        SectionItemView {
          Column(Modifier.fillMaxWidth()) {
            Text(
              ZetariaAuthority.typeLabel(e.type) + " · " + (if (e.type == "LIFT") "edicto " else "") + e.target + (if (e.arg.isNotEmpty()) " / " + e.arg else ""),
              fontWeight = FontWeight.SemiBold,
              fontSize = 14.sp
            )
            Text(
              (if (e.type == "LIFT") "" else if (live) "VIGENTE · " else "inactivo · ") + e.reason.ifEmpty { "sin motivo" },
              fontSize = 12.sp,
              color = MaterialTheme.colors.secondary
            )
            if (unlocked && live) {
              Text(
                "Levantar",
                color = MaterialTheme.colors.primary,
                fontSize = 13.sp,
                modifier = Modifier.clickable { ZetariaAuthority.issue("LIFT", e.id, "", 0, "levantado") }.padding(top = 4.dp)
              )
            }
          }
        }
      }
    }
    SectionBottomSpacer()
  }

  if (showGenerate) {
    AlertDialog(
      onDismissRequest = { showGenerate = false },
      title = { Text("Crear llave de Autoridad") },
      text = {
        Text(
          "Se creará una llave privada cifrada con tu contraseña. Cópiala y guárdala: si pierdes el teléfono y la contraseña, " +
            "no habrá forma de recuperar la autoridad."
        )
      },
      confirmButton = {
        TextButton({
          val pub = ZetariaAuthority.generate(pass)
          showGenerate = false
          if (pub != null) {
            newPub = pub
            pass = ""
          } else message = "No se pudo crear la llave."
        }) { Text("Crear") }
      },
      dismissButton = { TextButton({ showGenerate = false }) { Text("Cancelar") } }
    )
  }

  if (newPub.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = { newPub = "" },
      title = { Text("Llave pública creada") },
      text = {
        Column {
          Text("Copia esta llave PÚBLICA y guárdala en GitHub (Settings > Secrets and variables > Actions > Variables) como ZETARIA_AUTHORITY_PUBKEY. Luego compila y actualiza la app.")
          Spacer(Modifier.height(8.dp))
          Text(newPub, fontSize = 11.sp)
        }
      },
      confirmButton = {
        TextButton({
          clipboard.setText(AnnotatedString(newPub))
          showToast("Llave pública copiada")
          newPub = ""
        }) { Text("Copiar") }
      }
    )
  }

  if (showImport) {
    var blob by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showImport = false },
      title = { Text("Importar respaldo") },
      text = {
        Column {
          OutlinedTextField(value = blob, onValueChange = { blob = it }, label = { Text("Respaldo cifrado") }, modifier = Modifier.fillMaxWidth())
          Text("Se usa la contraseña escrita arriba.", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
        }
      },
      confirmButton = {
        TextButton({
          message = if (ZetariaAuthority.importVault(blob, pass)) "" else "Respaldo o contraseña incorrectos."
          showImport = false
        }) { Text("Importar") }
      },
      dismissButton = { TextButton({ showImport = false }) { Text("Cancelar") } }
    )
  }
}

@Composable
fun ZetariaPickContactView(close: () -> Unit) {
  val contacts = remember { ZetariaAuthority.contactsWithId() }
  ModalView(close = close, cardScreen = true) {
    ColumnWithScrollBar {
      AppBarTitle("Elegir contacto")
      SectionView {
        if (contacts.isEmpty()) SectionItemView { Text("Ningún contacto tiene ID de Zetaria todavía.") }
        for (c in contacts) {
          SectionItemView({
            close()
            ModalManager.start.showCustomModal { c2 -> ZetariaSanctionView(c.first, c.second, "", c2) }
          }) {
            Column {
              Text(c.second, fontWeight = FontWeight.SemiBold)
              Text(c.first, fontSize = 12.sp, color = MaterialTheme.colors.secondary)
            }
          }
        }
      }
    }
  }
}

/** Formulario de sanción. Si viene un pid, también permite retirar esa publicación. */
@Composable
fun ZetariaSanctionView(zid: String, name: String, pid: String, close: () -> Unit) {
  var type by remember { mutableStateOf(if (pid.isNotEmpty()) "POST_REMOVE" else "MUTE") }
  var days by remember { mutableStateOf("7") }
  var reason by remember { mutableStateOf("") }
  var confirmWord by remember { mutableStateOf("") }
  var error by remember { mutableStateOf("") }
  val options = ArrayList<String>()
  if (pid.isNotEmpty()) options.add("POST_REMOVE")
  if (zid.isNotEmpty()) {
    options.add("RESTRICT"); options.add("MUTE"); options.add("BAN")
    if (ZetariaAuthority.wipeAllowed()) options.add("WIPE")
  }
  ModalView(close = close, cardScreen = true) {
    ColumnWithScrollBar {
      AppBarTitle("Sancionar")
      SectionView(if (name.isNotEmpty()) name + (if (zid.isNotEmpty()) "  ·  " + zid else "") else "Edicto") {
        for (o in options) {
          SectionItemView({ type = o }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              RadioButton(selected = type == o, onClick = { type = o })
              Spacer(Modifier.width(8.dp))
              Text(
                when (o) {
                  "POST_REMOVE" -> "Retirar esta publicación del feed"
                  "RESTRICT" -> "Restringir: no puede publicar ni comentar en el feed"
                  "MUTE" -> "Silenciar: sin feed ni grupos (los mensajes directos siguen)"
                  "BAN" -> "Banear: no puede enviar nada"
                  else -> "BORRAR LOS DATOS de su app (infracción gravísima)"
                }
              )
            }
          }
        }
        SectionItemView {
          Column(Modifier.fillMaxWidth()) {
            if (type != "POST_REMOVE") {
              OutlinedTextField(
                value = days, onValueChange = { days = it.filter { ch -> ch.isDigit() }.take(4) }, singleLine = true,
                label = { Text("Días (0 = sin fin)") }, modifier = Modifier.fillMaxWidth()
              )
            }
            OutlinedTextField(
              value = reason, onValueChange = { reason = it.take(140) },
              label = { Text("Motivo (lo verá el sancionado y el registro público)") }, modifier = Modifier.fillMaxWidth()
            )
            if (type == "WIPE") {
              OutlinedTextField(
                value = confirmWord, onValueChange = { confirmWord = it }, singleLine = true,
                label = { Text("Escribe BORRAR para confirmar") }, modifier = Modifier.fillMaxWidth()
              )
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colors.error, fontSize = 13.sp)
          }
        }
        SectionItemView({
          when {
            reason.trim().length < 5 -> error = "Escribe un motivo."
            type == "WIPE" && confirmWord.trim() != "BORRAR" -> error = "Escribe BORRAR para confirmar."
            else -> {
              val target = if (type == "POST_REMOVE") pid else zid
              val e = ZetariaAuthority.issue(type, target, "", days.toIntOrNull() ?: 0, reason)
              if (e == null) error = "No se pudo emitir (¿autoridad bloqueada?)." else close()
            }
          }
        }) { Text("Emitir edicto firmado", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold) }
      }
      SectionBottomSpacer()
    }
  }
}
