package chat.simplex.common.views.chat.item

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
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
import chat.simplex.common.views.helpers.generateNewFileName
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===========================================================================
// Herramientas de Esteganografía de Zetaria (Android)
//   · Imagen -> audio en color y sin pérdida (códec ZIC2 del núcleo)
//   · Audio -> imagen con detección automática del formato
//   · Ocultar/descubrir archivos y mensajes (AES-256 + LSB)
//   · César y Morse (con audio guardable y transcripción desde audio)
//   · QR y códigos de barras: en el archivo ZetariaStegoCodes.android.kt
// ===========================================================================

internal class ZxEnv(val chat: Chat, val chatsCtx: ChatModel.ChatsContext, val scope: CoroutineScope)

// ---------------------------------------------------------------------------
// Utilidades
// ---------------------------------------------------------------------------
internal fun zxToast(msg: String) {
  Toast.makeText(androidAppContext, msg, Toast.LENGTH_SHORT).show()
}

internal fun zxCopy(text: String) {
  val cm = androidAppContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("Zetaria", text))
  zxToast("Copiado")
}

internal fun zxUriName(uri: Uri): String {
  try {
    androidAppContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        val n = c.getString(0)
        if (!n.isNullOrEmpty()) return n
      }
    }
  } catch (e: Exception) {
  }
  return uri.lastPathSegment ?: "archivo"
}

internal fun zxUriSize(uri: Uri): Long {
  try {
    androidAppContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        val n = c.getLong(0)
        if (n > 0) return n
      }
    }
  } catch (e: Exception) {
  }
  return -1L
}

internal fun zxReadBytes(uri: Uri, maxBytes: Int): ByteArray? {
  try {
    androidAppContext.contentResolver.openInputStream(uri)?.use { ins ->
      val out = ByteArrayOutputStream()
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = ins.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > maxBytes) return null
      }
      return out.toByteArray()
    }
  } catch (e: Exception) {
  }
  return null
}

/** Lee un archivo y devuelve null si supera `maxBytes`, junto con el tamaño real si se conoce */
internal fun zxReadLimited(uri: Uri, maxBytes: Int): Pair<ByteArray?, Long> {
  val known = zxUriSize(uri)
  if (known > maxBytes) return Pair(null, known)
  val bytes = zxReadBytes(uri, maxBytes)
  return Pair(bytes, if (bytes != null) bytes.size.toLong() else known)
}

internal fun zxDecodeBitmap(uri: Uri, maxSide: Int): Bitmap? {
  try {
    val cr = androidAppContext.contentResolver
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    var sample = 1
    while (o.outWidth / sample > maxSide || o.outHeight / sample > maxSide) sample *= 2
    val o2 = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
    }
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
  } catch (e: Exception) {
    return null
  }
}

internal fun zxBitmapFromBytes(bytes: ByteArray): Bitmap? = try {
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (e: Exception) {
  null
}

internal fun zxPng(bmp: Bitmap): ByteArray {
  val out = ByteArrayOutputStream()
  bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
  return out.toByteArray()
}

internal fun zxJpeg(bmp: Bitmap, quality: Int): ByteArray {
  val out = ByteArrayOutputStream()
  bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
  return out.toByteArray()
}

/** Píxeles ARGB opacos (se aplana sobre blanco: así el PNG no pierde los bits ocultos por la transparencia) */
internal fun zxOpaquePixels(bmp: Bitmap): IntArray {
  val w = bmp.width
  val h = bmp.height
  val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val c = Canvas(flat)
  c.drawColor(android.graphics.Color.WHITE)
  c.drawBitmap(bmp, 0f, 0f, null)
  val px = IntArray(w * h)
  flat.getPixels(px, 0, w, 0, 0, w, h)
  flat.recycle()
  return px
}

internal fun zxPixels(bmp: Bitmap): IntArray {
  val px = IntArray(bmp.width * bmp.height)
  bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
  return px
}

internal fun zxBitmapFromPixels(px: IntArray, w: Int, h: Int): Bitmap =
  Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

internal fun zxGrayBitmap(gray: IntArray, w: Int, h: Int): Bitmap {
  val px = IntArray(w * h) { (0xFF shl 24) or (gray[it] shl 16) or (gray[it] shl 8) or gray[it] }
  return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

internal fun zxScale(bmp: Bitmap, w: Int, h: Int): Bitmap =
  Bitmap.createScaledBitmap(bmp, w.coerceAtLeast(1), h.coerceAtLeast(1), true)

/** Píxeles de la imagen reescalada; nunca recicla la imagen original */
internal fun zxPixelsScaled(src: Bitmap, w: Int, h: Int): IntArray {
  if (src.width == w && src.height == h) return zxPixels(src)
  val s = Bitmap.createScaledBitmap(src, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
  val px = zxPixels(s)
  s.recycle()
  return px
}

internal fun zxThumb(bmp: Bitmap, maxSide: Int): Bitmap {
  val s = min(1.0, maxSide.toDouble() / max(bmp.width, bmp.height))
  return if (s >= 1.0) bmp else zxScale(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt())
}

/** Decodifica cualquier audio que Android entienda a PCM de 16 bits (WAV directamente) */
internal fun zxDecodeAudio(uri: Uri, maxSamples: Int): ZxPcm? {
  val head = try {
    androidAppContext.contentResolver.openInputStream(uri)?.use {
      val b = ByteArray(12)
      val n = it.read(b)
      if (n == 12) b else null
    }
  } catch (e: Exception) {
    null
  }
  if (head != null && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") {
    val bytes = zxReadBytes(uri, 400_000_000) ?: return null
    return zxWavToPcm16(bytes, maxSamples)
  }
  val ex = MediaExtractor()
  var codec: MediaCodec? = null
  try {
    ex.setDataSource(androidAppContext, uri, null)
    var track = -1
    var fmt: MediaFormat? = null
    for (i in 0 until ex.trackCount) {
      val f = ex.getTrackFormat(i)
      val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("audio/")) {
        track = i
        fmt = f
        break
      }
    }
    if (track < 0 || fmt == null) return null
    ex.selectTrack(track)
    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
    val dec = MediaCodec.createDecoderByType(mime)
    codec = dec
    dec.configure(fmt, null, null, 0)
    dec.start()
    var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var out = ShortArray(1 shl 20)
    var count = 0
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    while (!outputDone && count < maxSamples) {
      if (!inputDone) {
        val ii = dec.dequeueInputBuffer(10_000)
        if (ii >= 0) {
          val ib = dec.getInputBuffer(ii)
          val n = if (ib != null) ex.readSampleData(ib, 0) else -1
          if (n < 0) {
            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
            ex.advance()
          }
        }
      }
      val oi = dec.dequeueOutputBuffer(info, 10_000)
      if (oi >= 0) {
        val ob = dec.getOutputBuffer(oi)
        if (ob != null && info.size > 0) {
          ob.position(info.offset)
          ob.limit(info.offset + info.size)
          val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
          val n = sb.remaining()
          if (count + n > out.size) out = out.copyOf(max(out.size * 2, count + n))
          sb.get(out, count, n)
          count += n
        }
        dec.releaseOutputBuffer(oi, false)
        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
      } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        val nf = dec.outputFormat
        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      }
    }
    if (count == 0) return null
    return ZxPcm(rate, max(1, channels), out.copyOf(min(count, maxSamples) / max(1, channels) * max(1, channels)))
  } catch (e: Exception) {
    return null
  } finally {
    try {
      codec?.stop()
      codec?.release()
    } catch (e: Exception) {
    }
    ex.release()
  }
}

internal fun zxNoiseCarrier(samples: Int): ZxPcm {
  val r = java.util.Random()
  val a = ShortArray(samples) { (r.nextGaussian() * 1800).toInt().coerceIn(-32768, 32767).toShort() }
  return ZxPcm(44100, 2, a)
}

/** Mezcla a mono 44,1 kHz y devuelve muestras de 16 bits */
internal fun zxMono44kShorts(p: ZxPcm, maxSeconds: Int): ShortArray {
  val ch = max(1, p.channels)
  val rate = if (p.rate > 0) p.rate else ZX_SR
  var frames = p.samples.size / ch
  val lim = (maxSeconds.toLong() * rate).toInt()
  if (lim > 0 && frames > lim) frames = lim
  val mono = FloatArray(frames)
  for (f in 0 until frames) {
    var acc = 0
    for (c in 0 until ch) acc += p.samples[f * ch + c]
    mono[f] = acc / (ch * 32768f)
  }
  val outLen = (frames.toLong() * ZX_SR / rate).toInt()
  val out = ShortArray(outLen)
  for (i in 0 until outLen) {
    val pos = i.toDouble() * rate / ZX_SR
    val i0 = pos.toInt()
    val fr = (pos - i0).toFloat()
    val a = mono[min(i0, frames - 1)]
    val b2 = mono[min(i0 + 1, frames - 1)]
    out[i] = ((a + (b2 - a) * fr) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
  }
  return out
}

/** Canal 0 tal cual (sin remuestrear): imprescindible para leer los bits ocultos */
internal fun zxChannel0(p: ZxPcm, maxSeconds: Int): ShortArray {
  val ch = max(1, p.channels)
  var frames = p.samples.size / ch
  val lim = (maxSeconds.toLong() * max(1, p.rate)).toInt()
  if (lim > 0 && frames > lim) frames = lim
  if (ch == 1) return if (frames == p.samples.size) p.samples else p.samples.copyOf(frames)
  val out = ShortArray(frames)
  for (f in 0 until frames) out[f] = p.samples[f * ch]
  return out
}

// Reproducción de audios generados (espectrograma, Morse)
private var zxTrack: AudioTrack? = null

internal fun zxStopPlay() {
  val t = zxTrack
  zxTrack = null
  if (t != null) {
    try {
      t.stop()
    } catch (e: Exception) {
    }
    t.release()
  }
}

internal fun zxPlay(samples: ShortArray, rate: Int, onEnd: () -> Unit) {
  zxStopPlay()
  if (samples.isEmpty()) {
    onEnd()
    return
  }
  val main = Handler(Looper.getMainLooper())
  // Audios largos (por ejemplo el modo sin pérdida) se reproducen en streaming:
  // MODE_STATIC necesita todo el buffer en memoria y fallaría con varios minutos.
  if (samples.size > 44100 * 30) {
    val t = try {
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(max(8192, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    } catch (e: Exception) {
      onEnd()
      return
    }
    zxTrack = t
    Thread {
      try {
        t.play()
        var off = 0
        while (off < samples.size && zxTrack === t) {
          val n = t.write(samples, off, min(16384, samples.size - off))
          if (n <= 0) break
          off += n
        }
      } catch (e: Exception) {
      }
      if (zxTrack === t) {
        zxTrack = null
        try {
          t.stop()
          t.release()
        } catch (e: Exception) {
        }
        main.post { onEnd() }
      }
    }.start()
    return
  }
  val t = AudioTrack.Builder()
    .setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    )
    .setAudioFormat(
      AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()
    )
    .setBufferSizeInBytes(samples.size * 2)
    .setTransferMode(AudioTrack.MODE_STATIC)
    .build()
  t.write(samples, 0, samples.size)
  t.setNotificationMarkerPosition(samples.size)
  t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
    override fun onMarkerReached(track: AudioTrack?) {
      main.post { onEnd() }
    }

    override fun onPeriodicNotification(track: AudioTrack?) {}
  }, main)
  zxTrack = t
  t.play()
}

// Envío al chat actual (mismo API que el compositor)
internal suspend fun zxSendMessage(env: ZxEnv, composed: ComposedMessage): Boolean {
  val cInfo = env.chat.chatInfo
  val list = listOf(composed)
  val sent = if (cInfo.chatType == ChatType.Local) {
    chatModel.controller.apiCreateChatItems(rh = env.chat.remoteHostId, noteFolderId = cInfo.apiId, composedMessages = list)
  } else {
    chatModel.controller.apiSendMessages(
      rh = env.chat.remoteHostId,
      type = cInfo.chatType,
      id = cInfo.apiId,
      scope = cInfo.groupChatScope(),
      sendAsGroup = cInfo.sendAsGroup,
      live = false,
      ttl = null,
      sign = false,
      composedMessages = list
    )
  }
  if (sent.isNullOrEmpty()) return false
  for (a in sent) {
    withContext(Dispatchers.Main) {
      env.chatsCtx.addChatItem(env.chat.remoteHostId, a.chatInfo, a.chatItem)
    }
  }
  return true
}

internal suspend fun zxSendText(env: ZxEnv, text: String): Boolean =
  zxSendMessage(env, ComposedMessage(null, null, MsgContent.MCText(text), emptyMap()))

/** asImage = true: como imagen (códigos QR/barras); false: como archivo tal cual (WAV/PNG con datos ocultos) */
internal suspend fun zxSendFile(env: ZxEnv, bytes: ByteArray, prefix: String, ext: String, asImage: Boolean): Boolean {
  val destName = generateNewFileName(prefix, ext, File(getAppFilePath("")))
  val destFile = File(getAppFilePath(destName))
  val cf: CryptoFile = if (chatController.appPrefs.privacyEncryptLocalFiles.get()) {
    CryptoFile(destName, writeCryptoFile(destFile.absolutePath, bytes))
  } else {
    destFile.writeBytes(bytes)
    CryptoFile.plain(destName)
  }
  val mc: MsgContent = if (asImage) {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
    MsgContent.MCImage("", resizeImageToStrSize(bmp.asImageBitmap(), 14000))
  } else {
    MsgContent.MCFile("")
  }
  return zxSendMessage(env, ComposedMessage(cf, null, mc, emptyMap()))
}

internal fun zxSend(env: ZxEnv, block: suspend () -> Boolean) {
  env.scope.launch(Dispatchers.IO) {
    val ok = try {
      block()
    } catch (e: Exception) {
      false
    }
    withContext(Dispatchers.Main) { zxToast(if (ok) "Enviado al chat" else "No se pudo enviar") }
  }
}

/** Envía varios archivos seguidos (una imagen por cada parte del código) */
internal fun zxSendMany(env: ZxEnv, parts: List<ByteArray>, prefix: String, ext: String, asImage: Boolean) {
  if (parts.isEmpty()) return
  env.scope.launch(Dispatchers.IO) {
    var ok = 0
    for (i in parts.indices) {
      val sent = try {
        zxSendFile(env, parts[i], "${prefix}_${i + 1}", ext, asImage)
      } catch (e: Exception) {
        false
      }
      if (sent) ok++
    }
    withContext(Dispatchers.Main) {
      zxToast(if (ok == parts.size) "Enviados $ok archivos al chat" else "Enviados $ok de ${parts.size}")
    }
  }
}

// ---------------------------------------------------------------------------
// Componentes visuales
// ---------------------------------------------------------------------------
@Composable
internal fun ZxIcon(name: String, size: Dp = 24.dp, tint: Color = MaterialTheme.colors.primary) {
  val res = when (name) {
    "image" -> MR.images.zx_image
    "wave" -> MR.images.zx_wave
    "lock" -> MR.images.zx_lock
    "unlock" -> MR.images.zx_unlock
    "text" -> MR.images.zx_text
    "key" -> MR.images.zx_key
    "morse" -> MR.images.zx_morse
    "qr" -> MR.images.zx_qr
    "barcode" -> MR.images.zx_barcode
    else -> MR.images.zx_camera
  }
  Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
internal fun ZxInfo(text: String) {
  Text(text, fontSize = 13.sp, color = MaterialTheme.colors.secondary, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
internal fun ZxError(text: String?) {
  if (text != null) Text(text, fontSize = 13.sp, color = MaterialTheme.colors.error, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
internal fun ZxOk(text: String?) {
  if (text != null) Text(text, fontSize = 13.sp, color = MaterialTheme.colors.primary, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
internal fun ZxBusy(text: String) {
  Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(10.dp))
    Text(text, fontSize = 13.sp)
  }
}

@Composable
internal fun ZxBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(text) }
}

@Composable
internal fun ZxRow(content: @Composable RowScope.() -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun RowScope.ZxSmallBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  OutlinedButton(onClick, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
    Text(text, fontSize = 12.sp, maxLines = 1)
  }
}

@Composable
internal fun ZxPass(value: String, onChange: (String) -> Unit, label: String) {
  var show by remember { mutableStateOf(false) }
  OutlinedTextField(
    value,
    onChange,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
      Text(if (show) "ocultar" else "ver", fontSize = 12.sp, modifier = Modifier.clickable { show = !show }.padding(8.dp))
    },
    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
  )
}

@Composable
internal fun ZxChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    options.forEachIndexed { i, o ->
      val on = i == selected
      OutlinedButton(
        onClick = { onSelect(i) },
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
          backgroundColor = if (on) MaterialTheme.colors.primary.copy(alpha = 0.2f) else Color.Transparent
        )
      ) { Text(o, fontSize = 12.sp, maxLines = 1) }
    }
  }
}

@Composable
internal fun rememberZxPicker(onPicked: (Uri) -> Unit): (String) -> Unit {
  val current = rememberUpdatedState(onPicked)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) current.value(uri)
  }
  return { mime -> launcher.launch(mime) }
}

/** Selector múltiple: sirve para cargar de una vez todas las partes de un código troceado */
@Composable
internal fun rememberZxMultiPicker(onPicked: (List<Uri>) -> Unit): (String) -> Unit {
  val current = rememberUpdatedState(onPicked)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
    if (uris.isNotEmpty()) current.value(uris)
  }
  return { mime -> launcher.launch(mime) }
}

@Composable
internal fun rememberZxSaver(): (String, ByteArray) -> Unit {
  var pending by remember { mutableStateOf<ByteArray?>(null) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
    val b = pending
    pending = null
    if (uri != null && b != null) {
      try {
        androidAppContext.contentResolver.openOutputStream(uri)?.use { it.write(b) }
        zxToast("Guardado")
      } catch (e: Exception) {
        zxToast("No se pudo guardar")
      }
    }
  }
  return { name, bytes ->
    pending = bytes
    launcher.launch(name)
  }
}

// ---------------------------------------------------------------------------
// Panel: lista de herramientas
// ---------------------------------------------------------------------------
private class ZxToolDef(val id: Int, val title: String, val sub: String, val icons: List<String>)

private val ZX_SECTIONS: List<Pair<String, List<ZxToolDef>>> = listOf(
  "Audio e imágenes" to listOf(
    ZxToolDef(1, "Imagen → audio", "Color y sin pérdida: el audio devuelve la imagen exacta", listOf("image", "→", "wave")),
    ZxToolDef(2, "Audio → imagen", "Detecta el formato y reconstruye la imagen en color", listOf("wave", "→", "image"))
  ),
  "Ocultar con contraseña (AES-256)" to listOf(
    ZxToolDef(3, "Ocultar archivo en audio", "Esconde cualquier archivo en los bits del audio", listOf("lock", "wave")),
    ZxToolDef(4, "Descubrir archivo en audio", "Recupera el archivo oculto con la contraseña", listOf("unlock", "wave")),
    ZxToolDef(5, "Mensaje en píxeles", "Implanta un texto secreto en una imagen", listOf("lock", "image")),
    ZxToolDef(6, "Revelar mensaje de imagen", "Descifra el texto de los píxeles", listOf("unlock", "image"))
  ),
  "Códigos clásicos" to listOf(
    ZxToolDef(7, "Cifrado César", "Cifra y descifra con cualquier desplazamiento", listOf("key")),
    ZxToolDef(8, "Código Morse", "Texto a Morse, con sonido y audio guardable", listOf("text", "→", "morse")),
    ZxToolDef(9, "Traductor de Morse", "Morse a texto, también desde un audio de Morse", listOf("morse", "→", "text"))
  ),
  "QR y códigos de barras" to listOf(
    ZxToolDef(10, "Crear código QR", "Texto, imágenes, archivos y videos", listOf("qr")),
    ZxToolDef(11, "Leer código QR", "Con la cámara o cargando una imagen", listOf("camera", "qr")),
    ZxToolDef(12, "Crear código de barras", "Texto, imágenes, archivos y videos", listOf("barcode")),
    ZxToolDef(13, "Leer código de barras", "Con la cámara o cargando una imagen", listOf("camera", "barcode"))
  )
)

@Composable
actual fun ZetariaStegoHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {
  var show by remember { mutableStateOf(false) }
  LaunchedEffect(attachmentOption.value) {
    if (attachmentOption.value == AttachmentOption.Stego) {
      attachmentOption.value = null
      show = true
    }
  }
  if (show) ZetariaStegoPanel(chatsCtx, chat) { show = false }
}

@Composable
private fun ZetariaStegoPanel(chatsCtx: ChatModel.ChatsContext, chat: Chat, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  val env = remember { ZxEnv(chat, chatsCtx, scope) }
  var tool by remember { mutableStateOf(0) }
  val primary = MaterialTheme.colors.primary
  Dialog(
    onDismissRequest = {
      if (tool == 0) {
        onDismiss()
      } else {
        tool = 0
      }
    },
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(Modifier.fillMaxWidth(0.96f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (tool == 0) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Esteganografía", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onDismiss) { Text("Cerrar") }
          }
          for ((section, defs) in ZX_SECTIONS) {
            Text(section, fontSize = 12.sp, color = MaterialTheme.colors.secondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            for (d in defs) {
              Row(
                Modifier
                  .fillMaxWidth()
                  .padding(vertical = 3.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(primary.copy(alpha = 0.08f))
                  .clickable { tool = d.id }
                  .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically) {
                  for (ic in d.icons) {
                    if (ic == "→") Text("→", fontSize = 14.sp, color = primary, modifier = Modifier.padding(horizontal = 2.dp))
                    else ZxIcon(ic, 24.dp)
                  }
                }
                Column(Modifier.weight(1f)) {
                  Text(d.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                  Text(d.sub, fontSize = 11.sp, color = MaterialTheme.colors.secondary)
                }
              }
            }
          }
        } else {
          val def = ZX_SECTIONS.flatMap { it.second }.first { it.id == tool }
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { tool = 0 }) { Text("← Herramientas") }
          }
          Text(def.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
          when (tool) {
            1 -> ZxImgToAudio(env)
            2 -> ZxAudioToImg(env)
            3 -> ZxHideInAudio(env)
            4 -> ZxRevealFromAudio(env)
            5 -> ZxHideInImage(env)
            6 -> ZxRevealFromImage(env)
            7 -> ZxCaesarTool(env)
            8 -> ZxMorseTool(env, true)
            9 -> ZxMorseTool(env, false)
            10 -> ZxQrCreate(env)
            11 -> ZxQrRead(env)
            12 -> ZxBarcodeCreate(env)
            13 -> ZxBarcodeRead(env)
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 1) Imagen -> audio (color, sin pérdida)
// ---------------------------------------------------------------------------
private const val ZX_MAX_PAYLOAD = 3 * 1024 * 1024

/** Tamaño del dibujo a partir del aspecto de la imagen y las filas de calidad */
private fun zxSpecDims(bmp: Bitmap, rows: Int): Pair<Int, Int> {
  val aspect = bmp.width.toDouble() / max(1, bmp.height)
  var r = rows.coerceIn(16, zx2MaxRows())
  var c = (r * aspect).roundToInt().coerceIn(24, 900)
  if (c > 900) {
    c = 900
    r = (c / aspect).roundToInt().coerceIn(16, zx2MaxRows())
  }
  return Pair(c, r)
}

@Composable
private fun ZxImgToAudio(env: ZxEnv) {
  var name by remember { mutableStateOf<String?>(null) }
  var bmp by remember { mutableStateOf<Bitmap?>(null) }
  var mode by remember { mutableStateOf(0) }        // 0 = dibujo en color, 1 = imagen exacta
  var rowsChoice by remember { mutableStateOf(1) }  // 0 = 48, 1 = 96, 2 = 144
  var losslessCodec by remember { mutableStateOf(0) } // 0 = JPEG alta calidad, 1 = PNG sin pérdida
  var includeVisual by remember { mutableStateOf(true) }
  var pcm by remember { mutableStateOf<ShortArray?>(null) }
  var wav by remember { mutableStateOf<ByteArray?>(null) }
  var detail by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var playing by remember { mutableStateOf(false) }
  val save = rememberZxSaver()
  val pick = rememberZxPicker { uri ->
    env.scope.launch(Dispatchers.Default) {
      val b = zxDecodeBitmap(uri, 2048)
      withContext(Dispatchers.Main) {
        if (b == null) {
          zxToast("No se pudo leer la imagen")
        } else {
          bmp = b
          name = zxUriName(uri)
          pcm = null
          wav = null
          detail = null
          err = null
        }
      }
    }
  }
  DisposableEffect(Unit) { onDispose { zxStopPlay() } }

  val rowsList = intArrayOf(48, 96, 144)
  val b = bmp
  val dims = if (b != null) zxSpecDims(b, rowsList[rowsChoice]) else null

  ZxInfo("Convierte la imagen completa (hasta 2048 px) en un audio que la devuelve en color. Tienes dos caminos: dibujarla en el espectrograma (audio corto y bonito) o guardarla sin pérdida en los bits del audio (idéntica, audio más largo).")
  ZxBtn(if (name == null) "Elegir imagen" else "Imagen: $name (cambiar)") { pick("image/*") }

  ZxInfo("Forma de codificarla:")
  ZxChoice(listOf("Dibujo en color", "Imagen exacta"), mode) {
    mode = it
    pcm = null
    wav = null
    detail = null
  }

  if (mode == 0) {
    ZxInfo("Calidad del dibujo (más filas = más detalle y audio más largo):")
    ZxChoice(listOf("Rápida", "Buena", "Alta"), rowsChoice) {
      rowsChoice = it
      pcm = null
      wav = null
      detail = null
    }
    if (dims != null) {
      ZxInfo("Se dibujará a ${dims.first} × ${dims.second} píxeles · ${"%.1f".format((dims.first + 1) * ZX2_N.toDouble() / ZX2_RATE)} s de audio.")
    }
    ZxInfo("El color se conserva al 100 %: cada canal (rojo, verde y azul) viaja en su propia banda de frecuencias.")
  } else {
    ZxInfo("Formato de la imagen guardada:")
    ZxChoice(listOf("Alta calidad", "Sin pérdida"), losslessCodec) {
      losslessCodec = it
      pcm = null
      wav = null
      detail = null
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
      Switch(includeVisual, { includeVisual = it; pcm = null; wav = null; detail = null })
      Spacer(Modifier.width(8.dp))
      Text("Añadir el dibujo al principio del audio", fontSize = 12.sp)
    }
    ZxInfo("«Sin pérdida» (PNG) devuelve la imagen idéntica. «Alta calidad» (JPEG 92) ocupa mucho menos y se ve igual. Los bits van en las 4 posiciones menos significativas: el audio se oye limpio. Cuanto más grande sea la foto, más largo será el audio (se muestra al generarlo).")
  }

  ZxError(err)
  ZxBtn("Generar audio", enabled = b != null && !busy) {
    val src = b
    if (src != null) {
      busy = true
      err = null
      pcm = null
      wav = null
      detail = null
      zxStopPlay()
      playing = false
      val rows = rowsList[rowsChoice]
      val m = mode
      val codec = losslessCodec
      val visualFlag = includeVisual
      env.scope.launch(Dispatchers.Default) {
        var out: ByteArray? = null
        var samples: ShortArray? = null
        var msg: String? = null
        var info: String? = null
        try {
          if (m == 0) {
            val (cols, r) = zxSpecDims(src, rows)
            val px = zxPixelsScaled(src, cols, r)
            val audio = zxColorSpectrogram(px, cols, r)
            samples = audio.samples
            out = zxWavWrite(audio.samples, 1, ZX2_RATE)
            info = "Dibujo de ${cols} × $r en color · ${"%.1f".format(audio.seconds)} s · ${out.size / 1024} KB (WAV sin pérdida)"
          } else {
            val full = zxThumb(src, 2048)
            val bytes = if (codec == 0) zxJpeg(full, 92) else zxPng(full)
            if (bytes.size > ZX_MAX_PAYLOAD) {
              msg = "La imagen ocupa ${bytes.size / 1024} KB y el máximo es ${ZX_MAX_PAYLOAD / 1024} KB. Usa «Alta calidad» o una imagen más pequeña."
            } else {
              var visual: ZxImageAudio? = null
              if (visualFlag) {
                val (vc, vr) = zxSpecDims(full, 72)
                val vpx = zxPixelsScaled(full, vc, vr)
                visual = zxColorSpectrogram(vpx, vc, vr)
              }
              val audio = zxImageLosslessAudio(bytes, full.width, full.height, if (codec == 0) ZX2_CODEC_JPEG else ZX2_CODEC_PNG, visual)
              samples = audio.samples
              out = zxWavWrite(audio.samples, 1, ZX2_RATE)
              info = "Imagen completa ${full.width} × ${full.height} (${if (codec == 0) "JPEG alta calidad" else "PNG sin pérdida"}) · ${"%.1f".format(audio.seconds)} s · ${out.size / 1024} KB"
            }
          }
        } catch (e: OutOfMemoryError) {
          msg = "La imagen es demasiado grande para este dispositivo"
        } catch (e: Exception) {
          msg = "Error: ${e.message}"
        }
        withContext(Dispatchers.Main) {
          busy = false
          err = msg
          detail = info
          pcm = samples
          wav = out
        }
      }
    }
  }
  if (busy) ZxBusy("Codificando la imagen…")
  val s = pcm
  val w = wav
  if (s != null && w != null) {
    ZxOk(detail)
    ZxRow {
      ZxSmallBtn(if (playing) "Detener" else "Reproducir") {
        if (playing) {
          zxStopPlay()
          playing = false
        } else {
          playing = true
          zxPlay(s, ZX2_RATE) { playing = false }
        }
      }
      ZxSmallBtn("Guardar") { save("imagen_audio.wav", w) }
      ZxSmallBtn("Enviar") { zxSend(env) { zxSendFile(env, w, "imagen_audio", "wav", false) } }
    }
    ZxInfo("Envíalo como archivo (no como nota de voz): la compresión destruiría los datos. Para recuperarla, usa «Audio → imagen».")
  }
}

// ---------------------------------------------------------------------------
// 2) Audio -> imagen
// ---------------------------------------------------------------------------
@Composable
private fun ZxAudioToImg(env: ZxEnv) {
  var name by remember { mutableStateOf<String?>(null) }
  var pcmCache by remember { mutableStateOf<ZxPcm?>(null) }
  var generic by remember { mutableStateOf(false) }
  var logScale by remember { mutableStateOf(false) }
  var bmp by remember { mutableStateOf<Bitmap?>(null) }
  var info by remember { mutableStateOf<String?>(null) }
  var err by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  val save = rememberZxSaver()

  /** Modo genérico: espectrograma en grises de cualquier audio (es el que usa logScale) */
  fun showGeneric(p: ZxPcm, log: Boolean, keepInfo: Boolean) {
    generic = true
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      val m = zxToMono44k(p, 180)
      val img = if (m.size >= ZxFft.N) zxSpectrogramDecode(m, log) else null
      withContext(Dispatchers.Main) {
        busy = false
        if (img == null) {
          err = "No se pudo leer el espectrograma del audio (demasiado corto)"
        } else {
          bmp = zxGrayBitmap(img.gray, img.w, ZX_ROWS)
          if (!keepInfo) info = "Modo genérico (escala de grises): ${img.w} columnas. Activa la escala logarítmica si la imagen no se ve."
        }
      }
    }
  }

  val pick = rememberZxPicker { uri ->
    busy = true
    err = null
    info = null
    bmp = null
    generic = false
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var label: String? = null
      var image: Bitmap? = null
      var isGeneric = false
      var loaded: ZxPcm? = null
      try {
        val bytes = zxReadBytes(uri, 400_000_000)
        val strict = if (bytes != null) zxWavPcm16Strict(bytes) else null
        val pcm = strict ?: zxDecodeAudio(uri, 180 * 48000 * 2)
        loaded = pcm
        if (pcm == null) {
          message = "No se pudo leer el audio (formato no compatible)"
        } else {
          if (pcm.rate == ZX2_RATE) {
            val raw = zxChannel0(pcm, 180)
            val head = zxImageAudioInfo(raw)
            if (head != null) {
              if (head.mode == ZX2_MODE_LOSSLESS) {
                val payload = zxImageAudioPayload(raw, head)
                if (payload == null) {
                  message = "El audio lleva una imagen pero está dañado (no coincide el CRC)"
                } else {
                  image = zxBitmapFromBytes(payload)
                  if (image == null) {
                    message = "La imagen interna no se pudo abrir"
                  } else {
                    label = "Imagen exacta recuperada: ${image.width} × ${image.height} · ${if (head.codec == ZX2_CODEC_PNG) "PNG sin pérdida" else "JPEG"} · ${payload.size / 1024} KB"
                  }
                }
              } else {
                val px = zxColorSpectrogramDecode(raw, head.cols, head.rows, head.valueScale)
                if (px == null) {
                  message = "No se pudo reconstruir el dibujo del espectrograma"
                } else {
                  image = zxBitmapFromPixels(px, head.cols, head.rows)
                  label = "Dibujo recuperado en color: ${head.cols} × ${head.rows} · ${"%.1f".format(raw.size.toDouble() / ZX2_RATE)} s de audio"
                }
              }
            }
          }
          if (image == null && message == null) {
            isGeneric = true
            val m = zxToMono44k(pcm, 180)
            val img = if (m.size >= ZxFft.N) zxSpectrogramDecode(m, false) else null
            if (img == null) {
              message = "No se pudo leer el espectrograma del audio (demasiado corto)"
            } else {
              image = zxGrayBitmap(img.gray, img.w, ZX_ROWS)
              label = "Modo genérico (escala de grises): ${img.w} columnas. Activa la escala logarítmica si la imagen no se ve."
            }
          }
        }
      } catch (e: OutOfMemoryError) {
        message = "El audio es demasiado grande para este dispositivo"
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        err = message
        info = label
        bmp = image
        name = zxUriName(uri)
        pcmCache = loaded
        generic = isGeneric
      }
    }
  }

  ZxInfo("Recupera la imagen de un audio hecho con «Imagen → audio». Detecta solo el formato: si el audio lleva la imagen exacta la devuelve idéntica; si es un dibujo de color lo reconstruye; y si es cualquier otro audio, muestra su espectrograma en grises. Usa el WAV original (hasta 3 minutos).")
  ZxBtn(if (name == null) "Elegir audio" else "Audio: $name (cambiar)") { pick("audio/*") }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
    Switch(logScale, {
      logScale = it
      val cached = pcmCache
      if (generic && cached != null) showGeneric(cached, it, false)
    })
    Spacer(Modifier.width(8.dp))
    Text("Escala logarítmica (solo modo genérico)", fontSize = 12.sp)
  }
  ZxError(err)
  if (busy) ZxBusy("Analizando el audio…")
  ZxOk(info)
  val b = bmp
  if (b != null) {
    Image(
      b.asImageBitmap(),
      contentDescription = null,
      modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)),
      contentScale = ContentScale.Fit
    )
    ZxRow {
      ZxSmallBtn("Guardar PNG") { save("imagen_recuperada.png", zxPng(b)) }
      ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendFile(env, zxPng(b), "imagen", "png", true) } }
    }
  }
}

// ---------------------------------------------------------------------------
// 3) Ocultar archivo en audio (con contraseña)
// ---------------------------------------------------------------------------
@Composable
private fun ZxHideInAudio(env: ZxEnv) {
  var audioUri by remember { mutableStateOf<Uri?>(null) }
  var audioName by remember { mutableStateOf<String?>(null) }
  var fileUri by remember { mutableStateOf<Uri?>(null) }
  var fileName by remember { mutableStateOf<String?>(null) }
  var pass by remember { mutableStateOf("") }
  var pass2 by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var wav by remember { mutableStateOf<ByteArray?>(null) }
  val save = rememberZxSaver()
  val pickAudio = rememberZxPicker { audioUri = it; audioName = zxUriName(it); wav = null }
  val pickFile = rememberZxPicker { fileUri = it; fileName = zxUriName(it); wav = null }

  ZxInfo("Esconde un archivo en los bits menos significativos de un audio. Se cifra con AES-256 y se reparte de forma pseudoaleatoria: sin la contraseña no hay forma de saber que existe. El resultado es un WAV (sin pérdida).")
  ZxBtn(if (audioName == null) "Elegir audio portador (opcional)" else "Portador: $audioName") { pickAudio("audio/*") }
  if (audioName == null) ZxInfo("Si no eliges ninguno, se genera uno de ruido suave con la duración necesaria.")
  ZxBtn(if (fileName == null) "Elegir archivo a ocultar" else "Archivo: $fileName") { pickFile("*/*") }
  ZxPass(pass, { pass = it }, "Contraseña (mínimo 6 caracteres)")
  ZxPass(pass2, { pass2 = it }, "Repite la contraseña")
  ZxError(err)
  val ready = fileUri != null && pass.length >= 6 && pass == pass2 && !busy
  ZxBtn("Ocultar", enabled = ready) {
    val fu = fileUri
    if (fu != null) {
      busy = true
      err = null
      wav = null
      val au = audioUri
      val password = pass
      val fname = fileName ?: "archivo"
      env.scope.launch(Dispatchers.Default) {
        var message: String? = null
        var out: ByteArray? = null
        try {
          val data = zxReadBytes(fu, 40_000_000)
          if (data == null) {
            message = "No se pudo leer el archivo (máximo 40 MB)"
          } else {
            val need = 224 + 32 + (data.size + fname.length * 3 + 2 + 16) * 8L
            if (au == null && need > 24_000_000L) {
              message = "El archivo es demasiado grande para ocultarlo en audio (máximo unos 2,9 MB)"
            }
            val carrier: ZxPcm? = if (message != null) {
              null
            } else if (au == null) {
              zxNoiseCarrier(((need + 44100L) / 2 * 2).toInt().coerceAtLeast(441000))
            } else {
              zxDecodeAudio(au, 24_000_000)
            }
            if (message != null) {
              // ya hay un mensaje de error
            } else if (carrier == null) {
              message = "No se pudo leer el audio portador"
            } else {
              zxEmbed(ZxShortBits(carrier.samples), password, ZX_T_FILE, fname, data)
              out = zxWavWrite(carrier.samples, carrier.channels, carrier.rate)
            }
          }
        } catch (e: ZxStegoException) {
          message = e.message
        } catch (e: Exception) {
          message = "Error: ${e.message}"
        } catch (e: OutOfMemoryError) {
          message = "Archivo demasiado grande para este dispositivo"
        }
        withContext(Dispatchers.Main) {
          busy = false
          err = message
          wav = out
        }
      }
    }
  }
  if (busy) ZxBusy("Cifrando y ocultando…")
  val w = wav
  if (w != null) {
    ZxInfo("Listo: audio de ${w.size / 1024} KB con el archivo oculto. Guárdalo o envíalo como ARCHIVO; nunca como nota de voz ni lo conviertas a MP3.")
    ZxRow {
      ZxSmallBtn("Guardar") { save("audio_oculto.wav", w) }
      ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendFile(env, w, "audio", "wav", false) } }
    }
  }
}

// ---------------------------------------------------------------------------
// 4) Descubrir archivo en audio
// ---------------------------------------------------------------------------
@Composable
private fun ZxRevealFromAudio(env: ZxEnv) {
  var uri by remember { mutableStateOf<Uri?>(null) }
  var name by remember { mutableStateOf<String?>(null) }
  var pass by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var found by remember { mutableStateOf<ZxPayload?>(null) }
  val save = rememberZxSaver()
  val pick = rememberZxPicker { uri = it; name = zxUriName(it); found = null; err = null }

  ZxInfo("Busca un archivo oculto en un WAV creado con «Ocultar archivo en audio». Tiene que ser el WAV original, sin convertir ni comprimir.")
  ZxBtn(if (name == null) "Elegir audio (WAV)" else "Audio: $name (cambiar)") { pick("audio/*") }
  ZxPass(pass, { pass = it }, "Contraseña")
  ZxError(err)
  ZxBtn("Descubrir", enabled = uri != null && pass.isNotEmpty() && !busy) {
    val u = uri
    if (u != null) {
      busy = true
      err = null
      found = null
      val password = pass
      env.scope.launch(Dispatchers.Default) {
        var message: String? = null
        var res: ZxPayload? = null
        try {
          val bytes = zxReadBytes(u, 300_000_000)
          val pcm = if (bytes != null) zxWavPcm16Strict(bytes) else null
          if (pcm == null) {
            message = "El archivo debe ser un WAV de 16 bits sin comprimir (el que generó Zetaria)"
          } else {
            res = zxExtract(ZxShortBits(pcm.samples), password)
          }
        } catch (e: ZxStegoException) {
          message = e.message
        } catch (e: Exception) {
          message = "Error: ${e.message}"
        } catch (e: OutOfMemoryError) {
          message = "Audio demasiado grande para este dispositivo"
        }
        withContext(Dispatchers.Main) {
          busy = false
          err = message
          found = res
        }
      }
    }
  }
  if (busy) ZxBusy("Descifrando…")
  val f = found
  if (f != null) {
    if (f.type == ZX_T_TEXT) {
      val t = String(f.data, Charsets.UTF_8)
      ZxInfo("Mensaje encontrado:")
      SelectionContainer { Text(t, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
      ZxRow { ZxSmallBtn("Copiar") { zxCopy(t) } }
    } else {
      val fname = f.name.ifEmpty { "archivo_oculto" }
      ZxInfo("Archivo encontrado: $fname (${f.data.size / 1024} KB)")
      ZxRow {
        ZxSmallBtn("Guardar") { save(fname, f.data) }
        ZxSmallBtn("Enviar al chat") {
          zxSend(env) {
            val ext = fname.substringAfterLast('.', "bin").take(8).ifEmpty { "bin" }
            zxSendFile(env, f.data, "oculto", ext, false)
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 5) Mensaje de texto en píxeles (con contraseña)
// ---------------------------------------------------------------------------
@Composable
private fun ZxHideInImage(env: ZxEnv) {
  var uri by remember { mutableStateOf<Uri?>(null) }
  var name by remember { mutableStateOf<String?>(null) }
  var text by remember { mutableStateOf("") }
  var pass by remember { mutableStateOf("") }
  var pass2 by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var png by remember { mutableStateOf<ByteArray?>(null) }
  var preview by remember { mutableStateOf<Bitmap?>(null) }
  val save = rememberZxSaver()
  val pick = rememberZxPicker { uri = it; name = zxUriName(it); png = null; preview = null }

  ZxInfo("Implanta un texto en los bits menos significativos de los píxeles. Se cifra con AES-256 y se reparte al azar: la imagen se ve igual. El resultado es un PNG (sin pérdida).")
  ZxBtn(if (name == null) "Elegir imagen portadora" else "Imagen: $name (cambiar)") { pick("image/*") }
  OutlinedTextField(
    text,
    { text = it },
    label = { Text("Mensaje secreto") },
    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 90.dp)
  )
  ZxPass(pass, { pass = it }, "Contraseña (mínimo 6 caracteres)")
  ZxPass(pass2, { pass2 = it }, "Repite la contraseña")
  ZxError(err)
  val ready = uri != null && text.isNotEmpty() && pass.length >= 6 && pass == pass2 && !busy
  ZxBtn("Implantar mensaje", enabled = ready) {
    val u = uri
    if (u != null) {
      busy = true
      err = null
      png = null
      preview = null
      val password = pass
      val msg = text
      env.scope.launch(Dispatchers.Default) {
        var message: String? = null
        var out: ByteArray? = null
        var prev: Bitmap? = null
        try {
          val bmp = zxDecodeBitmap(u, 3000)
          if (bmp == null) {
            message = "No se pudo leer la imagen"
          } else {
            val px = zxOpaquePixels(bmp)
            zxEmbed(ZxPixelBits(px), password, ZX_T_TEXT, "", msg.toByteArray(Charsets.UTF_8))
            val o = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            o.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            out = zxPng(o)
            prev = o
          }
        } catch (e: ZxStegoException) {
          message = e.message
        } catch (e: Exception) {
          message = "Error: ${e.message}"
        } catch (e: OutOfMemoryError) {
          message = "Imagen demasiado grande para este dispositivo"
        }
        withContext(Dispatchers.Main) {
          busy = false
          err = message
          png = out
          preview = prev
        }
      }
    }
  }
  if (busy) ZxBusy("Cifrando e implantando…")
  val p = png
  val pv = preview
  if (p != null && pv != null) {
    ZxInfo("Listo. Guarda o envía el PNG como ARCHIVO: si se envía como imagen normal, SimpleX la recomprime y el mensaje se pierde.")
    Image(
      pv.asImageBitmap(),
      contentDescription = null,
      modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)),
      contentScale = ContentScale.Fit
    )
    ZxRow {
      ZxSmallBtn("Guardar PNG") { save("imagen_secreta.png", p) }
      ZxSmallBtn("Enviar como archivo") { zxSend(env) { zxSendFile(env, p, "imagen", "png", false) } }
    }
  }
}

// ---------------------------------------------------------------------------
// 6) Revelar mensaje de una imagen
// ---------------------------------------------------------------------------
@Composable
private fun ZxRevealFromImage(env: ZxEnv) {
  var uri by remember { mutableStateOf<Uri?>(null) }
  var name by remember { mutableStateOf<String?>(null) }
  var pass by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var secret by remember { mutableStateOf<String?>(null) }
  val pick = rememberZxPicker { uri = it; name = zxUriName(it); secret = null; err = null }

  ZxInfo("Lee el mensaje oculto en un PNG creado con «Mensaje en píxeles». Tiene que ser el PNG original, sin recomprimir ni reducir.")
  ZxBtn(if (name == null) "Elegir imagen" else "Imagen: $name (cambiar)") { pick("image/*") }
  ZxPass(pass, { pass = it }, "Contraseña")
  ZxError(err)
  ZxBtn("Revelar", enabled = uri != null && pass.isNotEmpty() && !busy) {
    val u = uri
    if (u != null) {
      busy = true
      err = null
      secret = null
      val password = pass
      env.scope.launch(Dispatchers.Default) {
        var message: String? = null
        var res: String? = null
        try {
          val bmp = zxDecodeBitmap(u, Int.MAX_VALUE)
          if (bmp == null) {
            message = "No se pudo leer la imagen"
          } else {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val payload = zxExtract(ZxPixelBits(px), password)
            res = String(payload.data, Charsets.UTF_8)
          }
        } catch (e: ZxStegoException) {
          message = e.message
        } catch (e: Exception) {
          message = "Error: ${e.message}"
        } catch (e: OutOfMemoryError) {
          message = "Imagen demasiado grande para este dispositivo"
        }
        withContext(Dispatchers.Main) {
          busy = false
          err = message
          secret = res
        }
      }
    }
  }
  if (busy) ZxBusy("Descifrando…")
  val s = secret
  if (s != null) {
    ZxInfo("Mensaje encontrado:")
    SelectionContainer { Text(s, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
    ZxRow { ZxSmallBtn("Copiar") { zxCopy(s) } }
  }
}

// ---------------------------------------------------------------------------
// 7) César
// ---------------------------------------------------------------------------
@Composable
private fun ZxCaesarTool(env: ZxEnv) {
  var text by remember { mutableStateOf("") }
  var shift by remember { mutableStateOf(3) }
  var alpha by remember { mutableStateOf(0) }
  var decode by remember { mutableStateOf(false) }
  var showAll by remember { mutableStateOf(false) }
  val letters = ZX_ALPHABETS[alpha].letters
  val n = letters.length
  val sh = shift.coerceIn(1, n - 1)
  val out = zxCaesar(text, sh, letters, decode)

  ZxInfo("Sustituye cada letra por la que está N posiciones más adelante. Es un cifrado histórico: sirve para jugar y aprender, no para proteger secretos.")
  ZxChoice(listOf("Cifrar", "Descifrar"), if (decode) 1 else 0) { decode = it == 1 }
  ZxChoice(ZX_ALPHABETS.map { it.name }, alpha) { alpha = it }
  OutlinedTextField(
    text,
    { text = it },
    label = { Text("Texto") },
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 80.dp)
  )
  Text("Desplazamiento: $sh", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
  Slider(
    value = sh.toFloat(),
    onValueChange = { shift = it.roundToInt() },
    valueRange = 1f..(n - 1).toFloat(),
    steps = n - 3
  )
  if (text.isNotEmpty()) {
    ZxInfo(if (decode) "Texto descifrado:" else "Texto cifrado:")
    SelectionContainer { Text(out, fontSize = 15.sp, fontFamily = FontFamily.Monospace) }
    ZxRow {
      ZxSmallBtn("Copiar") { zxCopy(out) }
      ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendText(env, out) } }
    }
    ZxRow { ZxSmallBtn(if (showAll) "Ocultar todos los desplazamientos" else "Probar todos los desplazamientos") { showAll = !showAll } }
    if (showAll) {
      for (s in 1 until n) {
        val r = zxCaesar(text, s, letters, decode)
        Row(
          Modifier.fillMaxWidth().clickable { zxCopy(r) }.padding(vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("%2d".format(s), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colors.primary, modifier = Modifier.width(28.dp))
          Text(r, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
      }
      ZxInfo("Toca una línea para copiarla.")
    }
  }
}

// ---------------------------------------------------------------------------
// 8) y 9) Morse (texto <-> Morse, con audio guardable y audio -> texto)
// ---------------------------------------------------------------------------
@Composable
private fun ZxMorseTool(env: ZxEnv, toMorse: Boolean) {
  var text by remember { mutableStateOf("") }
  var wpm by remember { mutableStateOf(16) }
  var playing by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var audioName by remember { mutableStateOf<String?>(null) }
  var decoded by remember { mutableStateOf<ZxMorseAudioResult?>(null) }
  var err by remember { mutableStateOf<String?>(null) }
  val save = rememberZxSaver()
  DisposableEffect(Unit) { onDispose { zxStopPlay() } }
  val out = if (toMorse) zxMorseEncode(text) else zxMorseDecode(text)

  val pickAudio = rememberZxPicker { uri ->
    busy = true
    err = null
    decoded = null
    env.scope.launch(Dispatchers.Default) {
      var res: ZxMorseAudioResult? = null
      var message: String? = null
      try {
        val pcm = zxDecodeAudio(uri, 180 * 48000 * 2)
        if (pcm == null) {
          message = "No se pudo leer el audio (prueba con WAV, MP3 u OGG)"
        } else {
          res = zxMorseAudioDecode(pcm, 300)
          if (res == null) message = "No se detectó ningún mensaje Morse en ese audio"
        }
      } catch (e: OutOfMemoryError) {
        message = "El audio es demasiado grande para este dispositivo"
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        err = message
        decoded = res
        audioName = zxUriName(uri)
        if (res != null) text = zxMorseEncode(res.text)
      }
    }
  }

  ZxInfo(
    if (toMorse) "Escribe un texto y obtén su código Morse (letras separadas por espacio, palabras por « / »). Puedes oírlo y guardarlo como WAV para enviarlo."
    else "Pega código Morse con puntos y rayas, o carga el audio de un Morse y el algoritmo lo transcribe solo: detecta el tono y la velocidad sin que se los indiques."
  )
  if (!toMorse) {
    ZxBtn(if (audioName == null) "Cargar audio de Morse" else "Audio: $audioName (cambiar)") { pickAudio("audio/*") }
  }
  OutlinedTextField(
    text,
    { text = it },
    label = { Text(if (toMorse) "Texto" else "Código Morse") },
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 90.dp)
  )
  if (toMorse) {
    Text("Velocidad: $wpm palabras por minuto", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(value = wpm.toFloat(), onValueChange = { wpm = it.roundToInt() }, valueRange = 6f..30f, steps = 23)
  }
  ZxError(err)
  if (busy) ZxBusy("Escuchando el audio…")
  val d = decoded
  if (!toMorse && d != null) {
    ZxOk("Detectado: ${d.wpm} palabras por minuto · tono ${d.toneHz} Hz · ${"%.1f".format(d.seconds)} s · calidad ${d.quality} %")
    ZxInfo("Mensaje del audio:")
    SelectionContainer { Text(d.text, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
    ZxRow {
      ZxSmallBtn("Copiar") { zxCopy(d.text) }
      ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendText(env, d.text) } }
    }
  }
  if (text.isNotEmpty() && out.isNotEmpty()) {
    ZxInfo(if (toMorse) "Código Morse:" else "Texto:")
    SelectionContainer { Text(out, fontSize = 17.sp, fontFamily = FontFamily.Monospace) }
    ZxRow {
      ZxSmallBtn("Copiar") { zxCopy(out) }
      ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendText(env, out) } }
    }
    if (toMorse) {
      ZxRow {
        ZxSmallBtn(if (playing) "Detener" else "Reproducir") {
          if (playing) {
            zxStopPlay()
            playing = false
          } else {
            playing = true
            zxPlay(zxMorsePcm(out, wpm), 22050) { playing = false }
          }
        }
        ZxSmallBtn("Guardar audio") {
          try {
            save("morse.wav", zxWavWrite(zxMorsePcm(out, wpm), 1, 22050))
          } catch (e: Exception) {
            zxToast("No se pudo crear el audio")
          }
        }
        ZxSmallBtn("Enviar el audio") {
          val pcm = zxMorsePcm(out, wpm)
          val wav = zxWavWrite(pcm, 1, 22050)
          zxSend(env) { zxSendFile(env, wav, "morse", "wav", false) }
        }
      }
      ZxInfo("El botón «Guardar audio» crea un WAV con el Morse tal como se oye. Puedes enviarlo al chat y luego transcribirlo con «Traductor de Morse → Cargar audio de Morse».")
    }
  }
}
