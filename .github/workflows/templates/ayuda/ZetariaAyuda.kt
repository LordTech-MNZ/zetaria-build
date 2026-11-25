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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.usersettings.SettingsActionItem
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Pantallas propias de Zetaria en Configuración:
//   1) «Sobre Zetaria» (con la imagen de la Capitanía General de Venezuela)
//   2) Donaciones (QR de Binance Pay)
//   3) Documento PDF del Reino (p. ej. «Leer el Manifiesto Zetario»): lo fija la Autoridad, lo lee cualquiera.
// ---------------------------------------------------------------------------

const val ZETARIA_REPO_URL = "__ZETARIA_REPO_URL__"
const val ZETARIA_BINANCE_ID = "MNZ_369"

// ---------------------------------------------------------------------------------------------------------------
// 1) Sobre Zetaria
// ---------------------------------------------------------------------------------------------------------------
@Composable
fun ZetariaAboutView(chatModel: ChatModel) {
  val uriHandler = LocalUriHandler.current
  ColumnWithScrollBar {
    AppBarTitle("Sobre Zetaria")
    Image(
      painterResource(MR.images.zetaria_capitania),
      contentDescription = "Capitanía General de Venezuela",
      modifier = Modifier.fillMaxWidth().padding(horizontal = DEFAULT_PADDING),
      contentScale = ContentScale.FillWidth
    )
    Spacer(Modifier.height(DEFAULT_PADDING))
    SectionView {
      SettingsActionItem(
        painterResource(MR.images.ic_github),
        "Código fuente de Zetaria",
        { uriHandler.openExternalLink(ZETARIA_REPO_URL) },
        textColor = MaterialTheme.colors.primary
      )
    }
    SectionTextFooter("Zetaria es una versión modificada de SimpleX Chat, publicada bajo la licencia AGPL-3.0.")
    SectionBottomSpacer()
  }
}

// ---------------------------------------------------------------------------------------------------------------
// 2) Donaciones
// ---------------------------------------------------------------------------------------------------------------
@Composable
fun ZetariaDonateView() {
  val clipboard = LocalClipboardManager.current
  ColumnWithScrollBar {
    AppBarTitle("Apoyar a Zetaria")
    Text(
      "Zetaria es gratuita. Si quieres apoyar su desarrollo, puedes hacer una donación voluntaria con Binance Pay.",
      modifier = Modifier.padding(horizontal = DEFAULT_PADDING),
      color = MaterialTheme.colors.secondary
    )
    Spacer(Modifier.height(DEFAULT_PADDING))
    Image(
      painterResource(MR.images.zetaria_donate_qr),
      contentDescription = "QR de Binance Pay",
      modifier = Modifier.fillMaxWidth().padding(horizontal = DEFAULT_PADDING),
      contentScale = ContentScale.FillWidth
    )
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    SectionView {
      SectionItemView({
        clipboard.setText(AnnotatedString(ZETARIA_BINANCE_ID))
        showToast("ID copiado: $ZETARIA_BINANCE_ID")
      }) {
        Column {
          Text("Copiar ID de Binance Pay", color = MaterialTheme.colors.primary)
          Text(ZETARIA_BINANCE_ID, fontSize = 12.sp, color = MaterialTheme.colors.secondary)
        }
      }
    }
    SectionTextFooter("Para escanear el QR necesitas otro dispositivo con la app de Binance; en este mismo teléfono usa el ID.")
    SectionBottomSpacer()
  }
}

// ---------------------------------------------------------------------------------------------------------------
// 3) Documento PDF del Reino
// ---------------------------------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------------------------
// 4) Novedades y guía de uso (lo que Zetaria tiene y SimpleX Chat no)
// ---------------------------------------------------------------------------------------------------------------
class ZetariaFeature(val icon: String, val title: String, val what: String, val how: String)

object ZetariaFeatures {
  val all = listOf(
    ZetariaFeature("📰", "Feed de publicaciones",
      "Publica texto, fotos, videos, audios y encuestas para la gente que tienes agregada, y mira lo que publican ellos, con reacciones y comentarios.",
      "Toca la pestaña «Feed», junto a «Chats». Con el botón «Actualizar» se envían tus publicaciones anteriores a los contactos nuevos."),
    ZetariaFeature("🪪", "Cédula del Reino Soberano de Zetaria",
      "Un ID único (ZET-…) y una cédula con tu foto, tu QR y tus datos, que puedes compartir como imagen.",
      "Ve a Ajustes > Dirección y abre la cédula. «Renovar cédula» actualiza tus datos; el ID nunca cambia."),
    ZetariaFeature("🎧", "Audios en el chat",
      "Envía audios (mp3, wav, m4a, ogg…) y escúchalos con un reproductor propio, con pausa y barra de avance.",
      "En el chat toca el botón de adjuntar y elige «Archivo y audios»."),
    ZetariaFeature("📊", "Encuestas",
      "Crea encuestas en el chat; cada persona vota y la tarjeta muestra los resultados.",
      "Botón de adjuntar > «Encuesta»."),
    ZetariaFeature("🎨", "Stickers",
      "Panel de stickers para enviar, guardar e importar los tuyos.",
      "Botón de adjuntar > «Stickers»."),
    ZetariaFeature("🎞️", "GIFs",
      "Buscador de GIFs integrado para enviarlos sin salir del chat.",
      "Botón de adjuntar > «GIFs». Si te pide una clave de KLIPY, pégala una sola vez dentro de la app."),
    ZetariaFeature("🕵️", "Esteganografía y códigos",
      "13 herramientas: ocultar mensajes y archivos dentro de imágenes y audios (cifrado AES-256), convertir imagen y audio entre sí, cifrado César, Morse, y QR y códigos de barras.",
      "Botón de adjuntar > «Esteganografía». Elige una herramienta y sigue los pasos."),
    ZetariaFeature("👥", "Roles de miembros",
      "En los grupos puedes renombrar los roles y asignarlos a los miembros, y hasta ponerles un apodo de rol.",
      "Abre la información del grupo y toca «Roles de miembros». Solo propietario, administrador y moderador pueden cambiarlos."),
    ZetariaFeature("📞", "Llamadas grupales",
      "Crea una sala de llamada con un toque y comparte el enlace en el grupo; quien lo toque entra a la llamada dentro de la app.",
      "En un grupo, toca el botón de llamada de la barra superior (Android)."),
    ZetariaFeature("💬", "Soporte de Zetaria",
      "Un chat directo con el soporte del movimiento para preguntas y novedades.",
      "Toca el botón de nuevo chat y elige «Soporte de Zetaria»."),
    ZetariaFeature("⚖️", "Autoridad del Reino",
      "La Autoridad puede restringir, silenciar o banear con un motivo visible. Cada orden va firmada y tu teléfono comprueba la firma antes de obedecerla.",
      "Cualquiera puede ver el registro público en Configuración avanzada > «Autoridad de Zetaria»."),
    ZetariaFeature("🔗", "Enlaces y QR de Zetaria",
      "Los enlaces que compartes son de Zetaria, y los códigos QR son morados con el logo MNZ.",
      "Se usan igual que siempre: añadir contacto, crear grupo y compartir enlace o QR."),
    ZetariaFeature("📁", "Explorador de archivos",
      "Mira qué archivos pesan más en tu teléfono y borra solo los que quieras, uno a uno.",
      "Ajustes > Datos del chat > «Archivos y multimedia»."),
    ZetariaFeature("🔄", "Actualizaciones automáticas",
      "La app avisa cuando hay una versión nueva y la instala desde las Releases oficiales.",
      "Configuración avanzada > «Buscar actualizaciones»."),
    ZetariaFeature("📄", "Documento del Reino",
      "La Autoridad puede publicar un PDF (por ejemplo, el Manifiesto) y se lee con un botón en Configuración.",
      "Aparece en la sección «Zetaria» de Configuración cuando hay un documento publicado."),
    ZetariaFeature("❤️", "Apoyar a Zetaria",
      "Zetaria es gratuita. Si quieres, puedes hacer una donación voluntaria.",
      "Configuración > sección «Zetaria» > «Apoyar a Zetaria (donar)»."),
    ZetariaFeature("✨", "Interfaz fluida",
      "Transiciones suaves entre pantallas, animación de arranque y nombres de chat y grupo completos en la barra superior.",
      "No necesitas hacer nada: ya está activo.")
  )
}

@Composable
private fun ZetariaFeatureBlock(f: ZetariaFeature, withHow: Boolean) {
  Column(Modifier.fillMaxWidth().padding(horizontal = DEFAULT_PADDING, vertical = DEFAULT_PADDING_HALF)) {
    Text(f.icon + "  " + f.title, style = MaterialTheme.typography.h3, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(f.what, lineHeight = 22.sp)
    if (withHow) {
      Spacer(Modifier.height(4.dp))
      Text("Cómo usarlo: " + f.how, lineHeight = 22.sp, color = MaterialTheme.colors.secondary)
    }
  }
}

@Composable
fun ZetariaNewsView() {
  ColumnWithScrollBar {
    AppBarTitle("Novedades de Zetaria")
    Text(
      "Lo que Zetaria tiene y SimpleX Chat no:",
      modifier = Modifier.padding(horizontal = DEFAULT_PADDING),
      color = MaterialTheme.colors.secondary
    )
    for (f in ZetariaFeatures.all) ZetariaFeatureBlock(f, withHow = false)
    SectionBottomSpacer()
  }
}

@Composable
fun ZetariaGuideView() {
  ColumnWithScrollBar {
    AppBarTitle("Guía de uso")
    Column(Modifier.fillMaxWidth().padding(horizontal = DEFAULT_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Para empezar", style = MaterialTheme.typography.h2)
      Text(
        "Para iniciar un chat nuevo, toca el botón de añadir contacto y elige «Añadir contacto» (crea un enlace de invitación o usa uno recibido) " +
          "o «Crear grupo». Si recibiste un enlace de Zetaria, ábrelo y toca «Conectar» en la app.",
        lineHeight = 22.sp
      )
      Text("Zetaria funciona sobre la tecnología de SimpleX: mensajería privada, sin números de teléfono.", lineHeight = 22.sp, color = MaterialTheme.colors.secondary)
      Spacer(Modifier.height(DEFAULT_PADDING_HALF))
      Text("Funciones de Zetaria", style = MaterialTheme.typography.h2)
    }
    for (f in ZetariaFeatures.all) ZetariaFeatureBlock(f, withHow = true)
    SectionBottomSpacer()
  }
}

// ZETARIA_DOC_BEGIN
object ZetariaDocs {
  private const val MAX_BYTES = 25 * 1024 * 1024

  fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun looksLikePdf(b: ByteArray): Boolean = b.size > 8 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

  private fun localFile(hash: String): File = File(tmpDir, "zetaria_doc_" + hash.take(16) + ".pdf")

  /** Texto del botón de un documento vigente. */
  fun label(doc: ZetariaEdict): String = doc.reason.trim().ifEmpty { "Leer el documento" }

  /** PDF verificado contra el SHA-256 firmado por la Autoridad: (archivo, "") o (null, motivo del fallo). */
  fun obtain(doc: ZetariaEdict): Pair<File?, String> {
    val hash = doc.target.lowercase()
    val local = localFile(hash)
    try {
      if (local.exists() && sha256Hex(local.readBytes()) == hash) return Pair(local, "")
      val c = URL(ZetariaAuthority.docUrl() + "?v=" + hash.take(12)).openConnection() as HttpURLConnection
      c.connectTimeout = 15000
      c.readTimeout = 30000
      c.requestMethod = "GET"
      if (c.responseCode != 200) {
        return Pair(null, "El PDF todavía no está en el repositorio (código " + c.responseCode + "). Inténtalo de nuevo en unos minutos.")
      }
      val out = ByteArrayOutputStream()
      val buf = ByteArray(16384)
      var total = 0
      c.inputStream.use { ins ->
        while (true) {
          val n = ins.read(buf)
          if (n < 0) break
          total += n
          if (total > MAX_BYTES) return Pair(null, "El PDF es demasiado grande.")
          out.write(buf, 0, n)
        }
      }
      val bytes = out.toByteArray()
      if (sha256Hex(bytes) != hash) {
        return Pair(null, "El PDF del repositorio aún no coincide con el que firmó la Autoridad (puede ser la caché de GitHub). Inténtalo en unos minutos.")
      }
      tmpDir.mkdirs()
      local.writeBytes(bytes)
      return Pair(local, "")
    } catch (e: Exception) {
      Log.e(TAG, "zetaria doc: " + e.stackTraceToString())
      return Pair(null, "No se pudo descargar el documento. Revisa tu conexión.")
    }
  }

  /** Autoridad: lee el PDF elegido y devuelve (sha256, "") o (null, motivo). Deja una copia local para poder leerlo ya. */
  fun importPicked(uri: URI): Pair<String?, String> {
    return try {
      val bytes = uri.inputStream()?.use { it.readBytes() } ?: return Pair(null, "No se pudo leer el archivo.")
      if (bytes.size > MAX_BYTES) return Pair(null, "El PDF pesa más de 25 MB.")
      if (!looksLikePdf(bytes)) return Pair(null, "Ese archivo no parece un PDF.")
      val hash = sha256Hex(bytes)
      tmpDir.mkdirs()
      localFile(hash).writeBytes(bytes)
      Pair(hash, "")
    } catch (e: Exception) {
      Log.e(TAG, "zetaria doc import: " + e.stackTraceToString())
      Pair(null, "No se pudo leer el archivo.")
    }
  }
}

/** Botón de Configuración: usuarios normales ven «Leer…»; la Autoridad ve el mismo botón (y dentro puede cambiar el PDF). */
@Composable
fun ZetariaManifestoItem() {
  val rev = ZetariaAuthority.revision.value
  val doc = remember(rev) { ZetariaAuthority.currentDoc() }
  val admin = remember(rev) { ZetariaAuthority.isAuthority() }
  if (doc == null && !admin) return
  SettingsActionItem(
    painterResource(MR.images.ic_article),
    if (doc != null) ZetariaDocs.label(doc) else "Publicar documento PDF",
    { ModalManager.start.showModal(settings = true, cardScreen = true) { ZetariaDocView() } }
  )
}

@Composable
fun ZetariaDocView() {
  val rev = ZetariaAuthority.revision.value
  val doc = remember(rev) { ZetariaAuthority.currentDoc() }
  val admin = remember(rev) { ZetariaAuthority.isAuthority() }
  var busy by remember { mutableStateOf(false) }
  var pickedHash by remember { mutableStateOf("") }
  var title by remember { mutableStateOf("") }
  var error by remember { mutableStateOf("") }
  var published by remember { mutableStateOf(false) }

  val picker = rememberFileChooserLauncher(true) { uri: URI? ->
    if (uri != null) {
      busy = true
      error = ""
      published = false
      withLongRunningApi {
        val (hash, err) = ZetariaDocs.importPicked(uri)
        if (hash != null) pickedHash = hash else error = err
        busy = false
      }
    }
  }

  ColumnWithScrollBar {
    AppBarTitle(if (doc != null) ZetariaDocs.label(doc) else "Documento PDF")

    if (doc != null) {
      SectionView {
        SectionItemView({
          if (!busy) {
            busy = true
            withLongRunningApi {
              val (file, err) = ZetariaDocs.obtain(doc)
              busy = false
              withContext(Dispatchers.Main) {
                if (file != null) openFile(CryptoFile.plain(file.absolutePath))
                else AlertManager.shared.showAlertMsg("No se pudo abrir el documento", err)
              }
            }
          }
        }, disabled = busy) {
          Text(if (busy) "Descargando…" else ZetariaDocs.label(doc), color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
        }
      }
      SectionTextFooter("Se abre con tu lector de PDF. El documento se comprueba con la firma de la Autoridad antes de abrirlo.")
      SectionDividerSpaced()
    } else {
      SectionTextFooter("Todavía no hay ningún documento publicado.")
    }

    if (admin) {
      SectionView("Autoridad: publicar o cambiar el PDF") {
        SectionItemView({ if (!busy) withLongRunningApi { picker.launch("application/pdf") } }, disabled = busy) {
          Text(if (pickedHash.isEmpty()) "Elegir PDF…" else "Elegir otro PDF…", color = MaterialTheme.colors.primary)
        }
        if (pickedHash.isNotEmpty()) {
          SectionItemView {
            Column(Modifier.fillMaxWidth()) {
              Text("PDF elegido (SHA-256): " + pickedHash.take(16) + "…", fontSize = 12.sp, color = MaterialTheme.colors.secondary)
              OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(60) },
                singleLine = true,
                label = { Text("Texto del botón (ej.: Leer el Manifiesto Zetario)") },
                modifier = Modifier.fillMaxWidth()
              )
            }
          }
          SectionItemView({
            if (title.trim().length < 3) {
              error = "Escribe el texto del botón."
            } else {
              val e = ZetariaAuthority.issue("DOC", pickedHash, "", 0, title.trim())
              if (e == null) {
                error = "No se pudo publicar (¿autoridad bloqueada?)."
              } else {
                error = ""
                published = true
                pickedHash = ""
                title = ""
              }
            }
          }) { Text("Publicar documento (edicto firmado)", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold) }
        }
        if (doc != null) {
          SectionItemView({
            ZetariaAuthority.issue("LIFT", doc.id, "", 0, "documento retirado")
          }) { Text("Retirar el documento", color = MaterialTheme.colors.error) }
        }
        if (error.isNotEmpty()) SectionItemView { Text(error, color = MaterialTheme.colors.error, fontSize = 13.sp) }
        if (published) SectionItemView { Text("Edicto emitido. Falta subir el archivo al repositorio (ver abajo).", fontSize = 13.sp) }
      }
      SectionTextFooter(
        "Cómo se actualiza: 1) elige el PDF y escribe el texto del botón; 2) publícalo; 3) sube ESE MISMO archivo al repositorio como " +
          "authority/manifiesto.pdf (reemplazando el anterior); 4) en «Autoridad de Zetaria» copia todos los edictos y pégalos en " +
          "authority/edicts.txt para que lleguen a todos. Si el archivo del repositorio no coincide con el firmado, la app no lo abre."
      )
    }
    SectionBottomSpacer()
  }
}
// ZETARIA_DOC_END
