package chat.simplex.common.views.zetaria

import SectionBottomSpacer
import SectionDividerSpaced
import SectionItemView
import SectionView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import chat.simplex.common.views.usersettings.SettingsActionItem
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.*
import java.io.File
import java.security.SecureRandom

// Cédula del Reino Soberano de Zetaria.
//  - ID: se genera una sola vez, al instalar la app (aleatorio, 80 bits), y no cambia nunca. Se guarda en un archivo
//    interno (zetaria_id) de la carpeta de archivos de la base de datos y se copia en los ajustes de la app; así, si la
//    persona exporta su base de datos y la importa en otro teléfono, ese archivo trae de vuelta el mismo ID.
//  - La cédula es una «foto» de nombre + foto de perfil + QR de la dirección + datos básicos al emitirla/renovarla.
//  - Para que los contactos vean el ID (y, si la persona quiere, sus datos) sin tocar el protocolo de SimpleX, al emitir
//    la cédula se añade UNA línea al final de la descripción del perfil:
//        🪪 ZET-XXXX-XXXX-XXXX-XXXX-XXXX | nacimiento | estado civil | profesión | trabajo | ciudad
//    Es texto normal (cualquier SimpleX la ve) y Zetaria la lee en la ficha del contacto. Quien la borre deja de mostrarla.

private fun ztC(es: String, en: String): String =
  if (java.util.Locale.getDefault().language == "es") es else en

private const val LINE_MARK = "🪪 "
private const val EMPTY = "-"

class ZetariaCedulaInfo(val id: String, val birth: String, val civil: String, val profession: String, val work: String, val city: String)

/** Todo lo que dibuja la cédula (la imagen la dibuja cada plataforma). */
class ZetariaCedulaCard(
  val name: String,
  val photo: ImageBitmap?,
  val qr: ImageBitmap?,
  val id: String,
  val birth: String,
  val age: Int?,
  val civil: String,
  val profession: String,
  val work: String,
  val city: String,
  val issued: String,
  val flag: ImageBitmap?
)

object ZetariaCedula {
  private val ID_RX = Regex("^ZET(-[0-9A-F]{4}){5}$")
  private const val MIRROR = "zetaria_id"

  private fun strPref(key: String, default: String = "") = SharedPreference(
    get = { settings.getString(key, default) },
    set = { v: String -> settings.putString(key, v) }
  )

  private val idPref = strPref("ZetariaCedulaId")
  val namePref = strPref("ZetariaCedulaName")
  val imagePref = strPref("ZetariaCedulaImage")
  val linkPref = strPref("ZetariaCedulaLink")
  val smallPref = strPref("ZetariaCedulaQrSmall")
  val issuedAt = strPref("ZetariaCedulaIssued")
  val renewedAt = strPref("ZetariaCedulaRenewed")
  val renewals = strPref("ZetariaCedulaRenewals")
  val birthPref = strPref("ZetariaCedulaBirth")
  val civilPref = strPref("ZetariaCedulaCivil")
  val professionPref = strPref("ZetariaCedulaProfession")
  val workPref = strPref("ZetariaCedulaWork")
  val cityPref = strPref("ZetariaCedulaCity")
  val sharePref = strPref("ZetariaCedulaShareData", "1")

  // ---------------------------------------------------------------- ID
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

  // ---------------------------------------------------------------- datos
  private fun clean(s: String): String = s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(60)

  private val DATE_FMT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)

  /** Edad calculada a partir de «dd/mm/aaaa»; null si la fecha no es válida (o es futura). */
  fun ageOf(birth: String): Int? =
    try {
      val d = java.time.LocalDate.parse(birth.trim(), DATE_FMT)
      val age = java.time.Period.between(d, java.time.LocalDate.now()).years
      if (d.isAfter(java.time.LocalDate.now()) || age > 130) null else age
    } catch (e: Throwable) {
      null
    }

  fun validBirth(s: String): Boolean = s.isBlank() || ageOf(s) != null

  /** Emite la cédula (o la renueva): guarda datos, nombre, foto y enlace actuales. El ID no se toca. */
  fun issue(
    user: User?, link: String, small: Boolean,
    birth: String, civil: String, profession: String, work: String, city: String, share: Boolean
  ) {
    id()
    val first = issuedAt.get().isEmpty()
    val now = System.currentTimeMillis().toString()
    namePref.set(user?.profile?.displayName ?: "")
    imagePref.set(user?.profile?.image ?: "")
    linkPref.set(link)
    smallPref.set(if (small) "1" else "0")
    birthPref.set(clean(birth))
    civilPref.set(clean(civil))
    professionPref.set(clean(profession))
    workPref.set(clean(work))
    cityPref.set(clean(city))
    sharePref.set(if (share) "1" else "0")
    if (first) {
      issuedAt.set(now)
      renewals.set("0")
    } else {
      renewals.set(((renewals.get().toIntOrNull() ?: 0) + 1).toString())
    }
    renewedAt.set(now)
    publish(user)
  }

  fun isOutdated(user: User?, link: String): Boolean =
    namePref.get() != (user?.profile?.displayName ?: "") ||
      imagePref.get() != (user?.profile?.image ?: "") ||
      linkPref.get() != link

  fun myInfo(): ZetariaCedulaInfo =
    ZetariaCedulaInfo(id(), birthPref.get(), civilPref.get(), professionPref.get(), workPref.get(), cityPref.get())

  /** Fecha de la última emisión/renovación, p. ej. «21 nov. 2026» */
  fun issuedText(): String {
    val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
    val s = renewedAt.get().ifEmpty { issuedAt.get() }
    return s.toLongOrNull()?.let { df.format(java.util.Date(it)) } ?: ""
  }

  // ---------------------------------------------------------------- línea en la descripción del perfil
  private fun buildLine(info: ZetariaCedulaInfo, withData: Boolean): String =
    if (!withData) LINE_MARK + info.id
    else LINE_MARK + listOf(info.id, info.birth, info.civil, info.profession, info.work, info.city)
      .joinToString(" | ") { if (it.isBlank()) EMPTY else clean(it) }

  private fun mergeLine(description: String?, line: String): String {
    val rest = (description ?: "").lines().filterNot { it.startsWith(LINE_MARK + "ZET-") }.joinToString("\n").trim()
    return if (rest.isEmpty()) line else "$rest\n$line"
  }

  /** Lee la línea de cédula de la descripción de un perfil (el propio o el de un contacto). */
  fun parse(description: String?): ZetariaCedulaInfo? {
    val line = description?.lines()?.firstOrNull { it.startsWith(LINE_MARK + "ZET-") } ?: return null
    val parts = line.removePrefix(LINE_MARK).split(" | ").map { it.trim() }
    val id = parts.firstOrNull() ?: return null
    if (!ID_RX.matches(id)) return null
    fun p(i: Int): String = parts.getOrElse(i) { "" }.let { if (it == EMPTY) "" else it }
    return ZetariaCedulaInfo(id, p(1), p(2), p(3), p(4), p(5))
  }

  /** Escribe (o actualiza) la línea de cédula en la descripción del perfil activo; SimpleX la envía a los contactos. */
  fun publish(user: User?) {
    if (user == null) return
    val line = buildLine(myInfo(), sharePref.get() != "0")
    withBGApi {
      try {
        val profile = user.profile.toProfile()
        val newDesc = mergeLine(profile.description, line)
        if (newDesc != (profile.description ?: "")) {
          val updated = chatModel.controller.apiUpdateProfile(user.remoteHostId, profile.copy(description = newDesc))
          if (updated != null) chatModel.updateCurrentUser(user.remoteHostId, updated.first)
        }
      } catch (e: Throwable) {
        Log.e(TAG, "ZetariaCedula publish error: ${e.stackTraceToString()}")
      }
    }
  }
}

private val ZET_PURPLE = Color(0xff4c1d95)

/** El mismo QR que se ve en «Dirección Zetaria» (mismo color, logo y nivel de corrección). */
private fun zQr(link: String, small: Boolean): ImageBitmap =
  qrCodeBitmap(link, 1024, if (small) QrCode.ErrorLevel.M else QrCode.ErrorLevel.L)
    .replaceColor(Color.Black.toArgb(), ZET_PURPLE.toArgb())
    .addLogo(if (small) 0.21f else 0.16f)

/** Imagen de la cédula (horizontal) lista para mostrar y compartir; la dibuja cada plataforma. */
expect fun zetariaCedulaImage(card: ZetariaCedulaCard): ImageBitmap

/** La bandera del reino (recurso drawable de Android; en escritorio no hay). */
expect fun zetariaFlagImage(): ImageBitmap?

private fun renderCard(name: String, image: String?, link: String?, small: Boolean, info: ZetariaCedulaInfo, issued: String): ImageBitmap {
  val photo = if (!image.isNullOrEmpty()) base64ToBitmap(image) else null
  val qr = if (!link.isNullOrEmpty()) zQr(link, small) else null
  val flag = try { zetariaFlagImage() } catch (e: Throwable) { null }
  return zetariaCedulaImage(
    ZetariaCedulaCard(name, photo, qr, info.id, info.birth, ZetariaCedula.ageOf(info.birth), info.civil, info.profession, info.work, info.city, issued, flag)
  )
}

private suspend fun shareCardImage(img: ImageBitmap) {
  val file = try {
    saveTempImageUncompressed(img, true)
  } catch (e: Throwable) {
    Log.e(TAG, "ZetariaCedula share error: ${e.stackTraceToString()}")
    null
  }
  if (file != null) shareFile("", CryptoFile.plain(file.absolutePath))
}

@Composable
private fun CardImage(img: ImageBitmap) {
  Image(
    bitmap = img,
    contentDescription = ztC("Cédula de Zetaria", "Zetaria card"),
    modifier = Modifier.fillMaxWidth().aspectRatio(1200f / 756f).clip(RoundedCornerShape(16.dp))
  )
}

@Composable
private fun IdBlock(id: String) {
  val clipboard = LocalClipboardManager.current
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(ztC("ID de la cédula", "Card ID"), style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
    SelectionContainer {
      Text(id, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
    TextButton(onClick = {
      clipboard.setText(AnnotatedString(id))
      showToast(ztC("ID copiado", "ID copied"))
    }) {
      Text(ztC("Copiar ID", "Copy ID"))
    }
  }
}

@Composable
fun ZetariaCedulaView(user: User?, connLink: CreatedConnLink) {
  val short = connLink.connShortLink != null
  val liveUri = connLink.simplexChatUri(short)
  val version = remember { mutableStateOf(0) }
  val issued = remember(version.value) { ZetariaCedula.hasCedula() }
  val editing = remember { mutableStateOf(!ZetariaCedula.hasCedula()) }
  val id = remember(version.value) { ZetariaCedula.id() }
  val scope = rememberCoroutineScope()

  ColumnWithScrollBar {
    AppBarTitle(ztC("Cédula de Zetaria", "Zetaria card"))
    Column(Modifier.padding(horizontal = DEFAULT_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
      if (editing.value) {
        CedulaForm(
          issued = issued,
          onCancel = { editing.value = false },
          onSubmit = { birth, civil, profession, work, city, share ->
            ZetariaCedula.issue(user, liveUri, short, birth, civil, profession, work, city, share)
            version.value = version.value + 1
            editing.value = false
          }
        )
      } else {
        val name = ZetariaCedula.namePref.get()
        val img = remember(version.value) {
          renderCard(
            name, ZetariaCedula.imagePref.get(), ZetariaCedula.linkPref.get(), ZetariaCedula.smallPref.get() == "1",
            ZetariaCedula.myInfo(), ZetariaCedula.issuedText()
          )
        }
        val outdated = ZetariaCedula.isOutdated(user, liveUri)
        CardImage(img)
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        IdBlock(id)
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        Text(
          if (outdated) ztC("Tu nombre, tu foto o tu enlace cambiaron desde que emitiste la cédula. Renuévala para actualizarla.", "Your name, photo or link changed since the card was issued. Renew it to update it.")
          else ztC("Tu cédula está al día.", "Your card is up to date."),
          color = MaterialTheme.colors.secondary,
          textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        Button(onClick = { editing.value = true }, modifier = Modifier.fillMaxWidth()) {
          Text(ztC("Renovar cédula", "Renew card"))
        }
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        OutlinedButton(onClick = { scope.launch { shareCardImage(img) } }, modifier = Modifier.fillMaxWidth()) {
          Text(ztC("Compartir cédula (imagen)", "Share card (image)"))
        }
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        Text(
          ztC(
            "Quien reciba la imagen verá tu ID y tus datos, y tu enlace de Zetaria.",
            "Whoever receives the image will see your ID and your details, and your Zetaria link."
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
private fun CedChip(text: String, on: Boolean, click: () -> Unit) {
  Box(
    Modifier
      .clip(RoundedCornerShape(50))
      .background(if (on) MaterialTheme.colors.primary.copy(alpha = 0.25f) else MaterialTheme.colors.secondary.copy(alpha = 0.12f))
      .clickable(onClick = click)
      .padding(horizontal = 12.dp, vertical = 6.dp)
  ) {
    Text(text, style = MaterialTheme.typography.body2, color = if (on) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground)
  }
}

@Composable
private fun CedulaForm(
  issued: Boolean,
  onCancel: () -> Unit,
  onSubmit: (String, String, String, String, String, Boolean) -> Unit
) {
  val birth = remember { mutableStateOf(ZetariaCedula.birthPref.get()) }
  val civil = remember { mutableStateOf(ZetariaCedula.civilPref.get()) }
  val profession = remember { mutableStateOf(ZetariaCedula.professionPref.get()) }
  val work = remember { mutableStateOf(ZetariaCedula.workPref.get()) }
  val city = remember { mutableStateOf(ZetariaCedula.cityPref.get()) }
  val share = remember { mutableStateOf(ZetariaCedula.sharePref.get() != "0") }
  val birthOk = ZetariaCedula.validBirth(birth.value)
  val civilOptions = listOf(ztC("Soltero/a", "Single"), ztC("Casado/a", "Married"), ztC("Unión libre", "Partnered"), ztC("Divorciado/a", "Divorced"), ztC("Viudo/a", "Widowed"))

  Column(Modifier.fillMaxWidth()) {
    Text(
      ztC(
        "Tu cédula reúne el nombre y la foto de tu perfil, el mismo QR de tu dirección Zetaria y tu ID, que se generó al instalar la app y nunca cambia. " +
          "Estos datos son opcionales: aparecen en la cédula.",
        "Your card gathers your profile name and photo, the same QR as your Zetaria address and your ID, which was generated when the app was installed and never changes. " +
          "These details are optional: they appear on the card."
      ),
      color = MaterialTheme.colors.secondary
    )
    Spacer(Modifier.height(DEFAULT_PADDING))
    OutlinedTextField(
      value = birth.value, onValueChange = { birth.value = it.take(10) },
      label = { Text(ztC("Fecha de nacimiento (dd/mm/aaaa)", "Date of birth (dd/mm/yyyy)")) },
      singleLine = true, isError = !birthOk, modifier = Modifier.fillMaxWidth()
    )
    if (!birthOk) {
      Text(ztC("Usa el formato dd/mm/aaaa, por ejemplo 25/03/1990.", "Use dd/mm/yyyy, for example 25/03/1990."), color = Color.Red, style = MaterialTheme.typography.body2)
    } else if (ZetariaCedula.ageOf(birth.value) != null) {
      Text(ztC("Edad: ${ZetariaCedula.ageOf(birth.value)} años (se calcula sola)", "Age: ${ZetariaCedula.ageOf(birth.value)} (calculated automatically)"), color = MaterialTheme.colors.secondary, style = MaterialTheme.typography.body2)
    }
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    Text(ztC("Estado civil", "Marital status"), style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
    Spacer(Modifier.height(4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      civilOptions.forEach { o -> CedChip(o, civil.value == o) { civil.value = if (civil.value == o) "" else o } }
    }
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    OutlinedTextField(
      value = profession.value, onValueChange = { profession.value = it.take(60) },
      label = { Text(ztC("Profesión", "Profession")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    OutlinedTextField(
      value = work.value, onValueChange = { work.value = it.take(60) },
      label = { Text(ztC("Trabajo / ocupación", "Job / occupation")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    OutlinedTextField(
      value = city.value, onValueChange = { city.value = it.take(60) },
      label = { Text(ztC("Ciudad / lugar de residencia", "City / place of residence")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(DEFAULT_PADDING))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(ztC("Mostrar mis datos en mi perfil", "Show my details on my profile"), Modifier.weight(1f))
      Switch(checked = share.value, onCheckedChange = { share.value = it })
    }
    Text(
      ztC(
        "Tu ID siempre aparece en tu perfil para tus contactos. Con el interruptor activado, también ven los datos de arriba (se añade una línea a la descripción de tu perfil, que puedes borrar cuando quieras).",
        "Your ID always appears on your profile for your contacts. With the switch on they also see the details above (a line is added to your profile description, which you can delete any time)."
      ),
      style = MaterialTheme.typography.body2,
      color = MaterialTheme.colors.secondary
    )
    Spacer(Modifier.height(DEFAULT_PADDING))
    Button(
      onClick = { onSubmit(birth.value, civil.value, profession.value, work.value, city.value, share.value) },
      enabled = birthOk,
      modifier = Modifier.fillMaxWidth()
    ) {
      Text(
        if (issued) ztC("Renovar cédula", "Renew card")
        else ztC("Crear cédula del Reino Soberano de Zetaria", "Create card of the Sovereign Kingdom of Zetaria"),
        textAlign = TextAlign.Center
      )
    }
    if (issued) {
      TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(ztC("Cancelar", "Cancel")) }
    }
  }
}

// ------------------------------------------------------------------ ficha del contacto

/** Sección «Cédula de Zetaria» en la ficha de un contacto: su ID (copiable) y el botón para ver su cédula. */
@Composable
fun ZetariaContactSection(contact: Contact) {
  val info = remember(contact.profile.description) { ZetariaCedula.parse(contact.profile.description) }
  val clipboard = LocalClipboardManager.current
  SectionView(ztC("Cédula de Zetaria", "Zetaria card")) {
    if (info == null) {
      SectionItemView {
        Text(ztC("Sin cédula de Zetaria", "No Zetaria card"), color = MaterialTheme.colors.secondary)
      }
    } else {
      SectionItemView(click = {
        clipboard.setText(AnnotatedString(info.id))
        showToast(ztC("ID copiado", "ID copied"))
      }) {
        Column {
          Text("ID", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
          Text(info.id, fontWeight = FontWeight.Medium)
        }
      }
      SettingsActionItem(
        painterResource(MR.images.ic_qr_code),
        ztC("Ver cédula", "View card"),
        click = { ModalManager.end.showModal(cardScreen = true) { ZetariaContactCedulaView(contact) } },
        iconColor = MaterialTheme.colors.secondary
      )
    }
  }
  SectionDividerSpaced()
}

@Composable
fun ZetariaContactCedulaView(contact: Contact) {
  val info = ZetariaCedula.parse(contact.profile.description)
  val scope = rememberCoroutineScope()
  ColumnWithScrollBar {
    AppBarTitle(ztC("Cédula de Zetaria", "Zetaria card"))
    Column(Modifier.padding(horizontal = DEFAULT_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
      if (info == null) {
        Text(ztC("Este contacto no tiene cédula de Zetaria.", "This contact has no Zetaria card."), color = MaterialTheme.colors.secondary)
      } else {
        val link = contact.profile.contactLink
        val img = remember(info.id, info.birth, info.civil, info.profession, info.work, info.city, contact.profile.image, link, contact.profile.displayName) {
          renderCard(
            contact.profile.displayName, contact.profile.image, if (link != null) simplexChatLink(link) else null,
            link != null && link.count() < 200, info, ""
          )
        }
        CardImage(img)
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        IdBlock(info.id)
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        OutlinedButton(onClick = { scope.launch { shareCardImage(img) } }, modifier = Modifier.fillMaxWidth()) {
          Text(ztC("Compartir cédula (imagen)", "Share card (image)"))
        }
      }
    }
    SectionBottomSpacer()
  }
}
