package chat.simplex.common.views.zetaria

import SectionBottomSpacer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import boofcv.alg.fiducial.qrcode.QrCode
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.newchat.qrCodeBitmap
import chat.simplex.common.views.newchat.replaceColor
import kotlinx.coroutines.*
import java.io.File
import java.security.SecureRandom

// Cédula del Reino Soberano de Zetaria.
//  - ID: se genera una sola vez, al instalar la app (aleatorio, 80 bits), y no cambia nunca. Se guarda en un archivo
//    interno (zetaria_id) de la carpeta de archivos de la base de datos y se copia en los ajustes de la app; así, si la
//    persona exporta su base de datos y la importa en otro teléfono, ese archivo trae de vuelta el mismo ID.
//  - La cédula es una «foto» de nombre de perfil + foto de perfil + enlace/QR de SimpleX en el momento de emitirla.
//    Al renovarla se toma otra foto con los datos actuales, pero el ID es el mismo.

private fun ztC(es: String, en: String): String =
  if (java.util.Locale.getDefault().language == "es") es else en

object ZetariaCedula {
  private val ID_RX = Regex("^ZET(-[0-9A-F]{4}){5}$")
  private const val MIRROR = "zetaria_id"

  private fun strPref(key: String) = SharedPreference(
    get = { settings.getString(key, "") },
    set = { v: String -> settings.putString(key, v) }
  )

  private val idPref = strPref("ZetariaCedulaId")
  val namePref = strPref("ZetariaCedulaName")
  val imagePref = strPref("ZetariaCedulaImage")
  val linkPref = strPref("ZetariaCedulaLink")
  val issuedAt = strPref("ZetariaCedulaIssued")
  val renewedAt = strPref("ZetariaCedulaRenewed")
  val renewals = strPref("ZetariaCedulaRenewals")

  private fun generate(): String {
    val b = ByteArray(10)
    SecureRandom().nextBytes(b)
    val hex = b.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
    return "ZET-" + hex.chunked(4).joinToString("-")
  }

  private fun writeMirror(v: String) {
    try {
      val f = File(appFilesDir, MIRROR)
      if (!f.exists() || f.readText().trim() != v) {
        f.parentFile?.mkdirs()
        f.writeText(v)
      }
    } catch (e: Throwable) {
      Log.e(TAG, "ZetariaCedula mirror error: ${e.message}")
    }
  }

  private fun readMirror(): String =
    try {
      val f = File(appFilesDir, MIRROR)
      if (f.exists()) f.readText().trim() else ""
    } catch (e: Throwable) {
      ""
    }

  /**
   * El ID de esta instalación. Manda el archivo interno de la carpeta de la base de datos (viaja con la exportación
   * e importación de la base de datos); si no existe se usa el de los ajustes, y si tampoco, se crea uno nuevo.
   */
  fun id(): String {
    val fromDb = readMirror()
    val saved = idPref.get()
    val v = when {
      ID_RX.matches(fromDb) -> fromDb
      ID_RX.matches(saved) -> saved
      else -> generate()
    }
    if (v != saved) idPref.set(v)
    writeMirror(v)
    return v
  }

  /** Se llama al abrir la app: así el ID existe desde la primera vez que se usa. */
  fun ensureId() {
    try { id() } catch (e: Throwable) { Log.e(TAG, "ZetariaCedula ensureId error: ${e.message}") }
  }

  fun hasCedula(): Boolean = issuedAt.get().isNotEmpty()

  fun buttonLabel(): String =
    if (issuedAt.state.value.isNotEmpty()) ztC("Cédula del Reino Soberano de Zetaria", "Card of the Sovereign Kingdom of Zetaria")
    else ztC("Crear cédula del Reino Soberano de Zetaria", "Create card of the Sovereign Kingdom of Zetaria")

  /** Emite la cédula (o la renueva): guarda nombre, foto y enlace actuales. El ID no se toca. */
  fun issue(user: User?, link: String) {
    id()
    val first = issuedAt.get().isEmpty()
    val now = System.currentTimeMillis().toString()
    namePref.set(user?.profile?.displayName ?: "")
    imagePref.set(user?.profile?.image ?: "")
    linkPref.set(link)
    if (first) {
      issuedAt.set(now)
      renewals.set("0")
    } else {
      renewals.set(((renewals.get().toIntOrNull() ?: 0) + 1).toString())
    }
    renewedAt.set(now)
  }

  fun isOutdated(user: User?, link: String): Boolean =
    namePref.get() != (user?.profile?.displayName ?: "") ||
      imagePref.get() != (user?.profile?.image ?: "") ||
      linkPref.get() != link

  fun dateLine(): String {
    val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
    fun d(s: String) = s.toLongOrNull()?.let { df.format(java.util.Date(it)) } ?: ""
    val iss = d(issuedAt.get())
    val ren = d(renewedAt.get())
    val n = renewals.get().toIntOrNull() ?: 0
    return if (n == 0 || ren.isEmpty()) ztC("Emitida: $iss", "Issued: $iss")
    else ztC("Renovada: $ren · Emitida: $iss", "Renewed: $ren · Issued: $iss")
  }
}

private val ZET_PURPLE = Color(0xff4c1d95)

private fun zQr(link: String): ImageBitmap =
  qrCodeBitmap(link, 768, if (link.length < 200) QrCode.ErrorLevel.M else QrCode.ErrorLevel.L)
    .replaceColor(Color.Black.toArgb(), ZET_PURPLE.toArgb())

/** Imagen de la cédula lista para compartir (la dibuja cada plataforma). */
expect fun zetariaCedulaImage(name: String, photo: ImageBitmap?, qr: ImageBitmap, id: String, dateLine: String): ImageBitmap

@Composable
fun ZetariaCedulaView(user: User?, connLink: String) {
  val version = remember { mutableStateOf(0) }
  val scope = rememberCoroutineScope()
  val issued = remember(version.value) { ZetariaCedula.hasCedula() }
  val id = remember(version.value) { ZetariaCedula.id() }
  // Con cédula: se muestran los datos guardados al emitirla. Sin cédula: vista previa con los datos actuales.
  val name = if (issued) ZetariaCedula.namePref.get() else (user?.profile?.displayName ?: "")
  val image = if (issued) ZetariaCedula.imagePref.get() else (user?.profile?.image ?: "")
  val link = if (issued) ZetariaCedula.linkPref.get() else connLink
  val dateLine = if (issued) ZetariaCedula.dateLine() else ""
  val outdated = issued && ZetariaCedula.isOutdated(user, connLink)

  fun issueNow() {
    ZetariaCedula.issue(user, connLink)
    version.value = version.value + 1
  }

  ColumnWithScrollBar {
    AppBarTitle(ztC("Cédula de Zetaria", "Zetaria card"))
    Column(Modifier.padding(horizontal = DEFAULT_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
      if (!issued) {
        Text(
          ztC(
            "Tu cédula reúne el nombre y la foto de tu perfil, el código QR de tu dirección de SimpleX y tu ID de Zetaria. " +
              "Tu ID se generó al instalar la app y nunca cambia: si renuevas la cédula con otro nombre, otra foto u otro enlace, el ID sigue siendo el mismo.",
            "Your card gathers your profile name and photo, the QR code of your SimpleX address and your Zetaria ID. " +
              "Your ID was generated when the app was installed and never changes: if you renew the card with another name, photo or link, the ID stays the same."
          ),
          color = MaterialTheme.colors.secondary
        )
        Spacer(Modifier.height(DEFAULT_PADDING))
      }
      CedulaCard(name, image, link, id, dateLine)
      Spacer(Modifier.height(DEFAULT_PADDING))
      if (!issued) {
        Button(onClick = { issueNow() }, modifier = Modifier.fillMaxWidth()) {
          Text(ztC("Crear cédula del Reino Soberano de Zetaria", "Create card of the Sovereign Kingdom of Zetaria"), textAlign = TextAlign.Center)
        }
      } else {
        Text(
          if (outdated) ztC("Tu nombre, tu foto o tu enlace cambiaron desde que emitiste la cédula. Renuévala para actualizarla.", "Your name, photo or link changed since the card was issued. Renew it to update it.")
          else ztC("Tu cédula está al día.", "Your card is up to date."),
          color = MaterialTheme.colors.secondary
        )
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        Button(
          onClick = {
            AlertManager.shared.showAlertDialog(
              title = ztC("¿Renovar cédula?", "Renew card?"),
              text = ztC(
                "Se actualizará con tu nombre, foto y enlace actuales. Tu ID no cambia.",
                "It will be updated with your current name, photo and link. Your ID does not change."
              ),
              confirmText = ztC("Renovar", "Renew"),
              onConfirm = { issueNow() }
            )
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(ztC("Renovar cédula", "Renew card"))
        }
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        OutlinedButton(
          onClick = {
            scope.launch {
              val file = try {
                val photo = if (image.isNotEmpty()) base64ToBitmap(image) else null
                val img = withContext(Dispatchers.Default) { zetariaCedulaImage(name, photo, zQr(link), id, dateLine) }
                saveTempImageUncompressed(img, true)
              } catch (e: Throwable) {
                Log.e(TAG, "ZetariaCedula share error: ${e.stackTraceToString()}")
                null
              }
              if (file != null) shareFile("", CryptoFile.plain(file.absolutePath))
            }
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(ztC("Compartir cédula (imagen)", "Share card (image)"))
        }
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        Text(
          ztC(
            "Quien reciba la imagen verá tu ID, tu nombre, tu foto y tu enlace de SimpleX.",
            "Whoever receives the image will see your ID, name, photo and SimpleX link."
          ),
          style = MaterialTheme.typography.body2,
          color = MaterialTheme.colors.secondary
        )
      }
    }
    SectionBottomSpacer()
  }
}

@Composable
private fun CedulaCard(name: String, image: String, link: String, id: String, dateLine: String) {
  val qr = remember(link) { zQr(link) }
  val gold = Color(0xffe9c46a)
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(24.dp))
      .background(Brush.linearGradient(listOf(Color(0xff1e0b3f), ZET_PURPLE)))
      .border(2.dp, gold, RoundedCornerShape(24.dp))
      .padding(20.dp)
  ) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("REINO SOBERANO DE ZETARIA", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center)
      Text(ztC("CÉDULA", "CARD"), color = Color.White, fontSize = 12.sp, letterSpacing = 4.sp)
      Spacer(Modifier.height(16.dp))
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ProfileImage(84.dp, image.ifEmpty { null })
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
          Text(ztC("NOMBRE", "NAME"), color = gold, fontSize = 10.sp)
          Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
          Spacer(Modifier.height(6.dp))
          Text("ID", color = gold, fontSize = 10.sp)
          Text(id, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
      }
      Spacer(Modifier.height(16.dp))
      Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
        Image(bitmap = qr, contentDescription = null, modifier = Modifier.size(190.dp))
      }
      if (dateLine.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(dateLine, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
      }
    }
  }
}
