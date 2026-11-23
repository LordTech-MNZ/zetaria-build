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
//  - La cédula es POR PERFIL: cada perfil de SimpleX tiene la suya (nombre, foto, QR de su dirección y datos básicos),
//    pero todas comparten el mismo ID de la instalación.
//  - Para que los contactos vean el ID y la cédula sin tocar el protocolo de SimpleX, al emitirla se guarda UNA línea
//    en la descripción del perfil (🪪 ID | nacimiento | estado civil | profesión | trabajo | ciudad). Zetaria la oculta
//    en todas partes, no se puede borrar desde Zetaria y se muestra solo como «ID» y botón «Ver cédula» en la ficha.

private fun ztC(es: String, en: String): String =
  if (java.util.Locale.getDefault().language == "es") es else en

private const val LINE_MARK = "🪪 "
private const val EMPTY = "-"

/** Lo que un perfil publica: siempre el ID; los datos y `created` solo si ya creó su cédula. */
class ZetariaCedulaInfo(
  val id: String, val birth: String, val civil: String, val profession: String, val work: String, val city: String,
  val sex: String = "", val created: Boolean = true
)

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
  val sex: String,
  val issued: String,
  val flag: ImageBitmap?
)

/** La cédula emitida por un perfil (foto de los datos al emitirla/renovarla). */
class ZetariaSnapshot(
  val name: String,
  val image: String,
  val link: String,
  val small: Boolean,
  val info: ZetariaCedulaInfo,
  val issued: String
)

object ZetariaCedula {
  private val ID_RX = Regex("^ZET(-[0-9A-F]{4}){5}$")
  private const val MIRROR = "zetaria_id"
  private val idPref = SharedPreference(
    get = { settings.getString("ZetariaCedulaId", "") },
    set = { v: String -> settings.putString("ZetariaCedulaId", v) }
  )

  /** Sube cada vez que se emite/renueva una cédula, para que los botones se actualicen solos. */
  val revision = mutableStateOf(0)

  // La cédula es POR PERFIL (cada perfil de SimpleX tiene la suya); el ID es el mismo para toda la instalación.
  private fun key(field: String, userId: Long) = "ZetariaCedula_${field}_$userId"
  private fun get(field: String, userId: Long): String = settings.getString(key(field, userId), "")
  private fun put(field: String, userId: Long, v: String) { settings.putString(key(field, userId), v) }

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

  fun hasCedula(userId: Long): Boolean = get("issued", userId).isNotEmpty()

  fun buttonLabel(user: User?): String {
    revision.value // suscribe el botón a los cambios
    return if (user != null && hasCedula(user.userId)) ztC("Cédula del Reino Soberano de Zetaria", "Card of the Sovereign Kingdom of Zetaria")
    else ztC("Crear cédula del Reino Soberano de Zetaria", "Create card of the Sovereign Kingdom of Zetaria")
  }

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

  // Estado civil según el sexo (se guarda en español, que es el idioma de la cédula)
  private val CIVIL_M = listOf("Soltero", "Casado", "Unión libre", "Divorciado", "Viudo")
  private val CIVIL_F = listOf("Soltera", "Casada", "Unión libre", "Divorciada", "Viuda")
  private val CIVIL_OLD = listOf("Soltero/a", "Casado/a", "Unión libre", "Divorciado/a", "Viudo/a")
  private val CIVIL_EN = listOf("Single", "Married", "Partnered", "Divorced", "Widowed")

  fun civilOptions(sex: String): List<String> = if (sex == "Femenino") CIVIL_F else CIVIL_M

  fun civilLabel(value: String): String {
    val i = listOf(CIVIL_M, CIVIL_F, CIVIL_OLD).map { it.indexOf(value) }.firstOrNull { it >= 0 } ?: return value
    return ztC(value, CIVIL_EN[i])
  }

  /** Pasa el estado civil elegido a la forma del sexo indicado (Soltero -> Soltera...); sin sexo, no hay estado civil. */
  fun civilFor(civil: String, sex: String): String {
    if (sex.isEmpty()) return ""
    val i = listOf(CIVIL_M, CIVIL_F, CIVIL_OLD).map { it.indexOf(civil) }.firstOrNull { it >= 0 } ?: return civil
    return civilOptions(sex)[i]
  }

  fun snapshot(userId: Long): ZetariaSnapshot? {
    if (!hasCedula(userId)) return null
    val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
    val when_ = get("renewed", userId).ifEmpty { get("issued", userId) }
    val issued = when_.toLongOrNull()?.let { df.format(java.util.Date(it)) } ?: ""
    return ZetariaSnapshot(
      get("name", userId), get("image", userId), get("link", userId), get("small", userId) == "1",
      ZetariaCedulaInfo(id(), get("birth", userId), get("civil", userId), get("profession", userId), get("work", userId), get("city", userId), get("sex", userId), true),
      issued
    )
  }

  /** Emite la cédula de ESTE perfil (o la renueva): guarda datos, nombre, foto y enlace actuales. El ID no se toca. */
  fun issue(
    user: User, link: String, small: Boolean,
    birth: String, civil: String, profession: String, work: String, city: String, sex: String
  ) {
    id()
    val uid = user.userId
    val first = get("issued", uid).isEmpty()
    val now = System.currentTimeMillis().toString()
    put("name", uid, user.profile.displayName)
    put("image", uid, user.profile.image ?: "")
    put("link", uid, link)
    put("small", uid, if (small) "1" else "0")
    put("birth", uid, clean(birth))
    put("civil", uid, clean(civil))
    put("profession", uid, clean(profession))
    put("work", uid, clean(work))
    put("city", uid, clean(city))
    put("sex", uid, clean(sex))
    if (first) {
      put("issued", uid, now)
      put("renewals", uid, "0")
    } else {
      put("renewals", uid, ((get("renewals", uid).toIntOrNull() ?: 0) + 1).toString())
    }
    put("renewed", uid, now)
    revision.value = revision.value + 1
    publish(user)
  }

  fun isOutdated(user: User, snap: ZetariaSnapshot, link: String): Boolean =
    snap.name != user.profile.displayName || snap.image != (user.profile.image ?: "") || snap.link != link

  // ---------------------------------------------------------------- línea oculta en la descripción del perfil
  // Es el «transporte» del ID y de los datos hacia los contactos (SimpleX no tiene un campo propio para esto).
  // Zetaria la OCULTA en todas partes (ficha del contacto, descripción, editor de perfil) y la vuelve a poner sola,
  // así que dentro de Zetaria no se puede borrar. Solo se ve con otros clientes de SimpleX.
  private fun buildLine(info: ZetariaCedulaInfo): String =
    LINE_MARK + (listOf(info.id, info.birth, info.civil, info.profession, info.work, info.city, info.sex) + (if (info.created) "C" else ""))
      .joinToString(" | ") { if (it.isBlank()) EMPTY else clean(it) }

  private fun isLine(l: String) = l.startsWith(LINE_MARK + "ZET-")

  /** La descripción sin la línea de cédula (lo que la persona escribió y lo que se muestra). */
  fun stripLine(description: String?): String =
    (description ?: "").lines().filterNot { isLine(it) }.joinToString("\n").trim()

  /** Descripción para guardar: el texto de la persona + la línea de cédula que ya tenía el perfil (si la tenía). */
  fun withLine(text: String, existingDescription: String?): String? {
    val line = (existingDescription ?: "").lines().firstOrNull { isLine(it) }
    val t = text.trim()
    val r = if (line == null) t else if (t.isEmpty()) line else "$t\n$line"
    return r.ifEmpty { null }
  }

  /** Lee la línea de cédula de la descripción de un perfil (el propio o el de un contacto). */
  fun parse(description: String?): ZetariaCedulaInfo? {
    val line = description?.lines()?.firstOrNull { isLine(it) } ?: return null
    val parts = line.removePrefix(LINE_MARK).split(" | ").map { it.trim() }
    val id = parts.firstOrNull() ?: return null
    if (!ID_RX.matches(id)) return null
    fun p(i: Int): String = parts.getOrElse(i) { "" }.let { if (it == EMPTY) "" else it }
    // formato anterior (6 partes): solo lo escribían perfiles con cédula; el actual (8 partes) marca con «C» la cédula creada
    val created = if (parts.size <= 6) true else p(7) == "C"
    return ZetariaCedulaInfo(id, p(1), p(2), p(3), p(4), p(5), p(6), created)
  }

  /**
   * Escribe (o actualiza) la línea en el perfil; SimpleX la envía a los contactos. El ID se publica SIEMPRE, haya o no
   * cédula (el ID nace al instalar la app); los datos de la cédula, solo cuando el perfil ya la creó.
   */
  fun publish(user: User?) {
    if (user == null) return
    val info = snapshot(user.userId)?.info ?: ZetariaCedulaInfo(id(), "", "", "", "", "", "", false)
    val line = buildLine(info)
    withBGApi {
      try {
        val profile = user.profile.toProfile()
        val rest = stripLine(profile.description)
        val newDesc = if (rest.isEmpty()) line else "$rest\n$line"
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
    ZetariaCedulaCard(name, photo, qr, info.id, info.birth, ZetariaCedula.ageOf(info.birth), info.civil, info.profession, info.work, info.city, info.sex, issued, flag)
  )
}

/** Guarda la imagen en la galería, en la carpeta «Identificación Zetaria». */
expect fun zetariaSaveToGallery(img: ImageBitmap, fileName: String): Boolean

private suspend fun saveCardToGallery(img: ImageBitmap, id: String) {
  val ok = try {
    withContext(Dispatchers.IO) { zetariaSaveToGallery(img, "Cedula_Zetaria_${id.removePrefix("ZET-")}_${System.currentTimeMillis()}.png") }
  } catch (e: Throwable) {
    Log.e(TAG, "ZetariaCedula gallery error: ${e.stackTraceToString()}")
    false
  }
  showToast(
    if (ok) ztC("Guardada en la galería, carpeta «Identificación Zetaria»", "Saved to gallery, folder «Identificación Zetaria»")
    else ztC("No se pudo guardar en la galería", "Could not save to gallery")
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
      Text(id, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = false)
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
  if (user == null) return
  val short = connLink.connShortLink != null
  val liveUri = connLink.simplexChatUri(short)
  val version = remember { mutableStateOf(0) }
  val snap = remember(version.value) { ZetariaCedula.snapshot(user.userId) }
  val editing = remember { mutableStateOf(ZetariaCedula.snapshot(user.userId) == null) }
  val id = remember(version.value) { ZetariaCedula.id() }
  val scope = rememberCoroutineScope()
  // se asegura de que el ID esté publicado en el perfil
  LaunchedEffect(Unit) { ZetariaCedula.publish(user) }

  ColumnWithScrollBar {
    AppBarTitle(ztC("Cédula de Zetaria", "Zetaria card"))
    Column(Modifier.padding(horizontal = DEFAULT_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
      if (editing.value || snap == null) {
        CedulaForm(
          snap = snap,
          onCancel = { editing.value = false },
          onSubmit = { birth, civil, profession, work, city, sex ->
            ZetariaCedula.issue(user, liveUri, short, birth, civil, profession, work, city, sex)
            version.value = version.value + 1
            editing.value = false
          }
        )
      } else {
        val img = remember(version.value) {
          renderCard(snap.name, snap.image, snap.link, snap.small, snap.info, snap.issued)
        }
        val outdated = ZetariaCedula.isOutdated(user, snap, liveUri)
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
        OutlinedButton(onClick = { scope.launch { saveCardToGallery(img, id) } }, modifier = Modifier.fillMaxWidth()) {
          Text(ztC("Guardar en la galería", "Save to gallery"))
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
  snap: ZetariaSnapshot?,
  onCancel: () -> Unit,
  onSubmit: (String, String, String, String, String, String) -> Unit
) {
  val birth = remember { mutableStateOf(snap?.info?.birth ?: "") }
  val civil = remember { mutableStateOf(snap?.info?.civil ?: "") }
  val profession = remember { mutableStateOf(snap?.info?.profession ?: "") }
  val work = remember { mutableStateOf(snap?.info?.work ?: "") }
  val city = remember { mutableStateOf(snap?.info?.city ?: "") }
  val sex = remember { mutableStateOf(snap?.info?.sex ?: "") }
  val birthOk = ZetariaCedula.validBirth(birth.value)

  Column(Modifier.fillMaxWidth()) {
    Text(
      ztC(
        "Tu cédula reúne el nombre y la foto de este perfil, el mismo QR de tu dirección Zetaria y tu ID, que se generó al instalar la app y nunca cambia. " +
          "Tu ID y estos datos los ven tus contactos en tu perfil, con el botón «Ver cédula». Deja vacío lo que no quieras mostrar.",
        "Your card gathers this profile's name and photo, the same QR as your Zetaria address and your ID, which was generated when the app was installed and never changes. " +
          "Your contacts see your ID and these details on your profile, with the «View card» button. Leave empty what you don't want to show."
      ),
      color = MaterialTheme.colors.secondary
    )
    Spacer(Modifier.height(DEFAULT_PADDING))
    OutlinedTextField(
      value = birth.value, onValueChange = { birth.value = it.take(10) },
      label = { Text(ztC("Nacimiento (dd/mm/aaaa)", "Birth (dd/mm/yyyy)")) },
      singleLine = true, isError = !birthOk, modifier = Modifier.fillMaxWidth()
    )
    if (!birthOk) {
      Text(ztC("Usa el formato dd/mm/aaaa, por ejemplo 25/03/1990.", "Use dd/mm/yyyy, for example 25/03/1990."), color = Color.Red, style = MaterialTheme.typography.body2)
    } else if (ZetariaCedula.ageOf(birth.value) != null) {
      Text(ztC("Edad: ${ZetariaCedula.ageOf(birth.value)} años (se calcula sola)", "Age: ${ZetariaCedula.ageOf(birth.value)} (calculated automatically)"), color = MaterialTheme.colors.secondary, style = MaterialTheme.typography.body2)
    }
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    Text(ztC("Sexo", "Sex"), style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("Masculino" to ztC("Masculino", "Male"), "Femenino" to ztC("Femenino", "Female")).forEach { (v, label) ->
        CedChip(label, sex.value == v) {
          val newSex = if (sex.value == v) "" else v
          civil.value = ZetariaCedula.civilFor(civil.value, newSex) // Soltero <-> Soltera, etc.
          sex.value = newSex
        }
      }
    }
    Spacer(Modifier.height(DEFAULT_PADDING_HALF))
    Text(ztC("Estado civil", "Marital status"), style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
    Spacer(Modifier.height(4.dp))
    if (sex.value.isEmpty()) {
      Text(ztC("Elige primero el sexo para ver las opciones.", "Choose sex first to see the options."), style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
    } else {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZetariaCedula.civilOptions(sex.value).forEach { o ->
          CedChip(ZetariaCedula.civilLabel(o), civil.value == o) { civil.value = if (civil.value == o) "" else o }
        }
      }
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
    Button(
      onClick = { onSubmit(birth.value, ZetariaCedula.civilFor(civil.value, sex.value), profession.value, work.value, city.value, sex.value) },
      enabled = birthOk,
      modifier = Modifier.fillMaxWidth()
    ) {
      Text(
        if (snap != null) ztC("Renovar cédula", "Renew card")
        else ztC("Crear cédula del Reino Soberano de Zetaria", "Create card of the Sovereign Kingdom of Zetaria"),
        textAlign = TextAlign.Center
      )
    }
    if (snap != null) {
      TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(ztC("Cancelar", "Cancel")) }
    }
  }
}

// ------------------------------------------------------------------ ficha del contacto

/** Sección en la ficha de un contacto: su ID de Zetaria (siempre) y, si ya creó su cédula, el botón para verla. */
@Composable
fun ZetariaContactSection(contact: Contact) {
  val info = remember(contact.profile.description) { ZetariaCedula.parse(contact.profile.description) }
  val clipboard = LocalClipboardManager.current
  SectionView(ztC("Identificación de Zetaria", "Zetaria identification")) {
    if (info == null) {
      SectionItemView {
        Text(ztC("Sin ID de Zetaria", "No Zetaria ID"), color = MaterialTheme.colors.secondary)
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
      if (info.created) {
        SettingsActionItem(
          painterResource(MR.images.ic_qr_code),
          ztC("Ver cédula", "View card"),
          click = { ModalManager.end.showModal(cardScreen = true) { ZetariaContactCedulaView(contact) } },
          iconColor = MaterialTheme.colors.secondary
        )
      }
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
      if (info == null || !info.created) {
        Text(ztC("Este contacto aún no creó su cédula de Zetaria.", "This contact has not created a Zetaria card yet."), color = MaterialTheme.colors.secondary)
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
        Spacer(Modifier.height(DEFAULT_PADDING_HALF))
        OutlinedButton(onClick = { scope.launch { saveCardToGallery(img, info.id) } }, modifier = Modifier.fillMaxWidth()) {
          Text(ztC("Guardar en la galería", "Save to gallery"))
        }
      }
    }
    SectionBottomSpacer()
  }
}
