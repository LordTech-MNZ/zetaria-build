package chat.simplex.common.views.chat.item

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import chat.simplex.common.platform.androidAppContext
import chat.simplex.common.views.newchat.QRCodeScanner
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

// ===========================================================================
// Códigos QR y de barras de Zetaria
//   · Crear: texto, imágenes, archivos y videos (troceados en varias partes
//     cuando no caben en un solo código, con nombre, tamaño y CRC32).
//   · Leer: con la cámara o cargando una o varias imágenes desde el teléfono.
//   · El lector de códigos de barras usa CameraX directamente (previsualización
//     arreglada), con cambio de cámara, linterna y reintento.
// ===========================================================================

private const val ZX_QR_MAX_BYTES = 160 * 1024
private const val ZX_BAR_MAX_BYTES = 64 * 1024
private const val ZX_MAX_PARTS = 120

// ---------------------------------------------------------------------------
// Utilidades de códigos
// ---------------------------------------------------------------------------
private fun zxMatrixToBitmap(m: BitMatrix): Bitmap {
  val w = m.width
  val h = m.height
  val px = IntArray(w * h)
  for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
  return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun zxQrBitmapFor(content: String, level: ErrorCorrectionLevel): Bitmap {
  val side = when {
    content.length < 300 -> 900
    content.length < 900 -> 1024
    else -> 1200
  }
  val hints = mapOf<EncodeHintType, Any>(
    EncodeHintType.ERROR_CORRECTION to level,
    EncodeHintType.MARGIN to 2,
    EncodeHintType.CHARACTER_SET to "UTF-8"
  )
  return zxMatrixToBitmap(MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, side, side, hints))
}

private fun zxIs2D(f: BarcodeFormat): Boolean =
  f == BarcodeFormat.PDF_417 || f == BarcodeFormat.AZTEC || f == BarcodeFormat.DATA_MATRIX

private fun zxBarcodeBitmapFor(content: String, f: BarcodeFormat): Bitmap {
  val hints = mapOf<EncodeHintType, Any>(
    EncodeHintType.MARGIN to 12,
    EncodeHintType.CHARACTER_SET to "UTF-8"
  )
  if (zxIs2D(f)) {
    val (w, h) = if (f == BarcodeFormat.PDF_417) Pair(1100, 440) else Pair(960, 960)
    return zxMatrixToBitmap(MultiFormatWriter().encode(content, f, w, h, hints))
  }
  val width = when {
    content.length <= 24 -> 1000
    content.length <= 48 -> 1400
    else -> 1800
  }
  val bars = zxMatrixToBitmap(MultiFormatWriter().encode(content, f, width, 300, hints))
  val out = Bitmap.createBitmap(bars.width, bars.height + 70, Bitmap.Config.ARGB_8888)
  val c = Canvas(out)
  c.drawColor(android.graphics.Color.WHITE)
  c.drawBitmap(bars, 0f, 0f, null)
  val p = Paint()
  p.color = android.graphics.Color.BLACK
  p.textSize = 40f
  p.isAntiAlias = true
  p.textAlign = Paint.Align.CENTER
  val label = if (content.length > 44) content.substring(0, 44) + "…" else content
  c.drawText(label, bars.width / 2f, bars.height + 50f, p)
  return out
}

private fun zxReaderFor(formats: List<BarcodeFormat>): MultiFormatReader {
  val hints = mapOf<DecodeHintType, Any>(
    DecodeHintType.POSSIBLE_FORMATS to formats,
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8"
  )
  return MultiFormatReader().apply { setHints(hints) }
}

private class ZxPix(val px: IntArray, val w: Int, val h: Int)

private fun zxRotatePixels(src: IntArray, w: Int, h: Int, deg: Int): ZxPix = when (deg % 360) {
  90 -> {
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
    ZxPix(out, h, w)
  }
  180 -> {
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
    ZxPix(out, w, h)
  }
  270 -> {
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = src[y * w + x]
    ZxPix(out, h, w)
  }
  else -> ZxPix(src, w, h)
}

/** Intenta leer un código desde una imagen fija: cuatro giros y también en negativo */
private fun zxDecodeFromBitmap(bmp: Bitmap, formats: List<BarcodeFormat>): String? {
  val w = bmp.width
  val h = bmp.height
  if (w <= 0 || h <= 0) return null
  val px = IntArray(w * h)
  try {
    bmp.getPixels(px, 0, w, 0, 0, w, h)
  } catch (e: Exception) {
    return null
  }
  val reader = zxReaderFor(formats)
  for (inv in 0..1) {
    val base = if (inv == 0) px else IntArray(px.size) { px[it] xor 0x00FFFFFF }
    for (rot in intArrayOf(0, 90, 180, 270)) {
      val p = zxRotatePixels(base, w, h, rot)
      try {
        val src = RGBLuminanceSource(p.w, p.h, p.px)
        val res = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
        reader.reset()
        return res.text
      } catch (e: Throwable) {
        reader.reset()
      }
    }
  }
  return null
}

/** Carga una o varias imágenes y devuelve los textos que se hayan podido leer */
private fun zxReadCodeImages(uris: List<Uri>, formats: List<BarcodeFormat>): List<String> {
  val out = ArrayList<String>()
  for (u in uris) {
    val bmp = zxDecodeBitmap(u, 1800) ?: continue
    val text = zxDecodeFromBitmap(bmp, formats)
    bmp.recycle()
    if (text != null) out.add(text)
  }
  return out
}

// ---------------------------------------------------------------------------
// Contenido: imagen, archivo o video -> datos para el código
// ---------------------------------------------------------------------------
private class ZxCodeJob(
  val kind: Int,
  val name: String,
  val contents: List<String>,
  val preview: Bitmap?,
  val detail: String
)

private fun zxBuildJob(kind: Int, name: String, data: ByteArray, partSize: Int, preview: Bitmap?, detail: String): ZxCodeJob =
  ZxCodeJob(kind, name, ZxDataCode.encode(kind, name, data, partSize), preview, detail)

private fun zxVideoMeta(uri: Uri): Pair<Long, Bitmap?> {
  var duration = 0L
  var frame: Bitmap? = null
  try {
    val r = MediaMetadataRetriever()
    r.setDataSource(androidAppContext, uri)
    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    if (frame == null) frame = r.getFrameAtTime()
    r.release()
  } catch (e: Exception) {
  }
  return Pair(duration, frame?.let { zxThumb(it, 720) })
}

private fun zxFmtBytes(n: Long): String = when {
  n < 1024 -> "$n B"
  n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
  else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

// ---------------------------------------------------------------------------
// Resultado de un escaneo o de una carga de imagen
// ---------------------------------------------------------------------------
private class ZxScanResult(
  val kind: Int,
  val name: String,
  val data: ByteArray?,
  val text: String?
)

@Composable
private fun ZxScanResultView(env: ZxEnv, result: ZxScanResult, onAgain: () -> Unit) {
  val save = rememberZxSaver()
  val data = result.data
  val text = result.text
  val bmp = remember(result) {
    if (result.kind == ZxDataCode.KIND_IMAGE && data != null) zxBitmapFromBytes(data) else null
  }
  when {
    text != null -> {
      ZxInfo("Contenido leído:")
      SelectionContainer { Text(text, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp)) }
      if (text.startsWith("http://") || text.startsWith("https://")) {
        ZxInfo("Es un enlace: no lo abras si no confías en quien te lo dio.")
      }
      ZxRow {
        ZxSmallBtn("Copiar") { zxCopy(text) }
        ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendText(env, text) } }
        ZxSmallBtn("Otro", onClick = onAgain)
      }
    }
    data == null -> {
      ZxError("El contenido llegó incompleto o dañado")
      ZxBtn("Volver a leer", onClick = onAgain)
    }
    result.kind == ZxDataCode.KIND_TEXT -> {
      val t = String(data, Charsets.UTF_8)
      ZxInfo("Texto leído:")
      SelectionContainer { Text(t, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp)) }
      ZxRow {
        ZxSmallBtn("Copiar") { zxCopy(t) }
        ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendText(env, t) } }
        ZxSmallBtn("Otro", onClick = onAgain)
      }
    }
    else -> {
      ZxInfo("${ZxDataCode.kindName(result.kind)}: ${result.name.ifEmpty { "sin nombre" }} · ${zxFmtBytes(data.size.toLong())}")
      if (bmp != null) {
        Image(
          bmp.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
          contentScale = ContentScale.Fit
        )
      }
      val fname = result.name.ifEmpty { "codigo" }
      val ext = fname.substringAfterLast('.', "").ifEmpty { if (result.kind == ZxDataCode.KIND_IMAGE) "png" else "bin" }
      ZxRow {
        ZxSmallBtn("Guardar") { save(fname, data) }
        ZxSmallBtn("Enviar al chat") { zxSend(env) { zxSendFile(env, data, "recibido", ext.take(8), result.kind == ZxDataCode.KIND_IMAGE) } }
        ZxSmallBtn("Otro", onClick = onAgain)
      }
      if (result.kind == ZxDataCode.KIND_VIDEO) {
        ZxInfo("Es un video: guárdalo y ábrelo con tu reproductor.")
      }
    }
  }
}

/**
 * Procesa un texto leído: si es un paquete ZET1 lo acumula; si está completo,
 * devuelve el contenido. Si no es un paquete Zetaria, lo trata como texto normal.
 */
private fun zxHandleScan(
  text: String,
  assembler: ZxDataAssembler,
  onProgress: (ZxDataProgress?) -> Unit,
  onResult: (ZxScanResult) -> Unit,
  onError: (String?) -> Unit
) {
  val packet = ZxDataCode.parse(text)
  if (packet == null) {
    if (text.trim().startsWith(ZxDataCode.PREFIX)) {
      onError("Código Zetaria dañado: no se pudo interpretar")
      return
    }
    onProgress(null)
    onResult(ZxScanResult(ZxDataCode.KIND_TEXT, "", null, text))
    return
  }
  val prog = assembler.add(packet)
  onProgress(prog)
  if (prog.complete) {
    if (!prog.crcOk || prog.data == null) {
      onProgress(null)
      onError("El contenido llegó dañado (no coincide el CRC). Vuelve a leer las partes.")
    } else {
      onResult(ZxScanResult(prog.kind, prog.name, prog.data, null))
    }
  }
}

@Composable
private fun ZxProgressInfo(prog: ZxDataProgress?) {
  if (prog != null && !prog.complete) {
    val faltan = max(0, prog.total - prog.have)
    ZxOk("Parte ${prog.have} de ${prog.total} recibida · faltan $faltan. Sigue leyendo los demás códigos (${ZxDataCode.kindName(prog.kind)}: ${prog.name.ifEmpty { "sin nombre" }}).")
  }
}

// ---------------------------------------------------------------------------
// Escáner con cámara (CameraX + ZXing)
// ---------------------------------------------------------------------------
private class ZxCodeAnalyzer(
  private val formats: List<BarcodeFormat>,
  private val onText: (String) -> Unit
) : ImageAnalysis.Analyzer {
  private val reader = zxReaderFor(formats)
  private var lastText = ""
  private var lastAt = 0L

  private fun decode(data: ByteArray, stride: Int, w: Int, h: Int, invert: Boolean): String? {
    try {
      val src = PlanarYUVLuminanceSource(if (invert) zxInvert(data) else data, stride, h, 0, 0, w, h, false)
      val res = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
      return res.text
    } catch (e: Throwable) {
      return null
    } finally {
      reader.reset()
    }
  }

  /** Prueba el fotograma normal y en negativo (códigos claros sobre fondo oscuro) */
  private fun both(data: ByteArray, stride: Int, w: Int, h: Int): String? =
    decode(data, stride, w, h, false) ?: decode(data, stride, w, h, true)

  override fun analyze(image: ImageProxy) {
    try {
      val w = image.width
      val h = image.height
      val plane = image.planes.firstOrNull() ?: return
      val rowStride = plane.rowStride
      if (w <= 0 || h <= 0 || rowStride <= 0) return
      val buf = plane.buffer
      buf.rewind()
      val y = ByteArray(rowStride * h)
      val n = min(buf.remaining(), y.size)
      buf.get(y, 0, n)
      val rot = image.imageInfo.rotationDegrees
      val found: String? = when (rot) {
        90 -> both(zxRotate90(y, rowStride, w, h), h, h, w)
        180 -> both(zxRotate180(y, rowStride, w, h), w, w, h)
        270 -> both(zxRotate270(y, rowStride, w, h), h, h, w)
        else -> both(y, rowStride, w, h)
      }
      if (found != null) {
        val now = System.currentTimeMillis()
        if (found != lastText || now - lastAt > 2000) {
          lastText = found
          lastAt = now
          onText(found)
        }
      }
    } catch (e: Exception) {
    } finally {
      try {
        image.close()
      } catch (e: Exception) {
      }
    }
  }
}

private fun zxInvert(data: ByteArray): ByteArray {
  val out = ByteArray(data.size)
  for (i in data.indices) out[i] = (255 - (data[i].toInt() and 0xFF)).toByte()
  return out
}

private fun zxRotate90(src: ByteArray, stride: Int, w: Int, h: Int): ByteArray {
  val out = ByteArray(w * h)
  for (y in 0 until h) {
    val row = y * stride
    for (x in 0 until w) out[x * h + (h - 1 - y)] = src[row + x]
  }
  return out
}

private fun zxRotate180(src: ByteArray, stride: Int, w: Int, h: Int): ByteArray {
  val out = ByteArray(w * h)
  for (y in 0 until h) {
    val row = y * stride
    for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = src[row + x]
  }
  return out
}

private fun zxRotate270(src: ByteArray, stride: Int, w: Int, h: Int): ByteArray {
  val out = ByteArray(w * h)
  for (y in 0 until h) {
    val row = y * stride
    for (x in 0 until w) out[(w - 1 - x) * h + y] = src[row + x]
  }
  return out
}

@Composable
private fun ZxCameraPreview(formats: List<BarcodeFormat>, onText: (String) -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val permission = rememberPermissionState(Manifest.permission.CAMERA)
  var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
  var previewView by remember { mutableStateOf<PreviewView?>(null) }
  var camera by remember { mutableStateOf<Camera?>(null) }
  var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
  var torch by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var attempt by remember { mutableStateOf(0) }
  var requested by remember { mutableStateOf(false) }
  val executor = remember { Executors.newSingleThreadExecutor() }
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

  LaunchedEffect(permission.status) {
    if (permission.status == PermissionStatus.Granted) {
      if (provider == null) {
        val p = withContext(Dispatchers.IO) {
          try {
            ProcessCameraProvider.getInstance(context).get()
          } catch (e: Exception) {
            null
          }
        }
        provider = p
        if (p == null) error = "No se pudo iniciar el servicio de cámara"
      }
    } else if (!requested) {
      requested = true
      permission.launchPermissionRequest()
    }
  }

  LaunchedEffect(provider, previewView, permission.status, lensFacing, attempt) {
    val p = provider ?: return@LaunchedEffect
    val pv = previewView ?: return@LaunchedEffect
    if (permission.status != PermissionStatus.Granted) return@LaunchedEffect
    try {
      val preview = Preview.Builder().build()
      preview.setSurfaceProvider(pv.surfaceProvider)
      val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setImageQueueDepth(1)
        .build()
      analysis.setAnalyzer(executor, ZxCodeAnalyzer(formats) { text ->
        mainHandler.post { onText(text) }
      })
      val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
      p.unbindAll()
      camera = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
      error = null
    } catch (e: Exception) {
      error = "No se pudo abrir la cámara: ${e.message ?: "error desconocido"}"
      try {
        p.unbindAll()
      } catch (e2: Exception) {
      }
    }
  }

  LaunchedEffect(torch, camera) {
    try {
      camera?.cameraControl?.enableTorch(torch)
    } catch (e: Exception) {
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      try {
        provider?.unbindAll()
      } catch (e: Exception) {
      }
      try {
        executor.shutdown()
      } catch (e: Exception) {
      }
    }
  }

  Box(
    Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black),
    contentAlignment = Alignment.Center
  ) {
    if (permission.status == PermissionStatus.Granted && hasCamera) {
      AndroidView(
        factory = { ctx ->
          PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
          }.also { previewView = it }
        },
        modifier = Modifier.fillMaxSize()
      )
    } else {
      Text(
        if (!hasCamera) "Este dispositivo no tiene cámara" else "Permiso de cámara necesario para escanear",
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier.padding(16.dp)
      )
    }
  }
  if (permission.status != PermissionStatus.Granted) {
    ZxRow {
      ZxSmallBtn("Permitir cámara") { permission.launchPermissionRequest() }
      if (error != null) ZxSmallBtn("Reintentar") { error = null; attempt += 1 }
    }
    if (!hasCamera) ZxInfo("Este dispositivo no tiene cámara: usa «Cargar imagen».")
  } else if (error != null) {
    ZxError(error)
    ZxRow {
      ZxSmallBtn("Reintentar") {
        error = null
        attempt += 1
      }
      ZxSmallBtn("Permitir cámara") { permission.launchPermissionRequest() }
    }
  } else if (hasCamera) {
    ZxRow {
      ZxSmallBtn("Cambiar cámara") {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
      }
      ZxSmallBtn(if (torch) "Linterna: sí" else "Linterna: no") { torch = !torch }
    }
  }
}

// ---------------------------------------------------------------------------
// 10) Crear código QR (texto, imagen, archivo o video)
// ---------------------------------------------------------------------------
private val ZX_EC_LEVELS = listOf(ErrorCorrectionLevel.L, ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H)
private fun zxQrPartSize(level: Int): Int = intArrayOf(1200, 1000, 900, 700)[level.coerceIn(0, 3)]

@Composable
internal fun ZxQrCreate(env: ZxEnv) {
  var kind by remember { mutableStateOf(0) }        // 0 texto, 1 imagen, 2 archivo, 3 video
  var text by remember { mutableStateOf("") }
  var level by remember { mutableStateOf(1) }
  var lossless by remember { mutableStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var picked by remember { mutableStateOf<String?>(null) }
  var srcKind by remember { mutableStateOf(ZxDataCode.KIND_IMAGE) }
  var srcName by remember { mutableStateOf("") }
  var srcBytes by remember { mutableStateOf<ByteArray?>(null) }
  var srcPreview by remember { mutableStateOf<Bitmap?>(null) }
  var srcDetail by remember { mutableStateOf("") }
  var job by remember { mutableStateOf<ZxCodeJob?>(null) }
  var part by remember { mutableStateOf(0) }
  val save = rememberZxSaver()
  val maxParts = 160

  fun clearSource() {
    srcBytes = null
    srcPreview = null
    srcName = ""
    srcDetail = ""
    job = null
    part = 0
    err = null
  }

  fun rebuild() {
    val bytes = srcBytes
    part = 0
    if (bytes == null) {
      job = null
      return
    }
    try {
      val parts = ZxDataCode.encode(srcKind, srcName, bytes, zxQrPartSize(level))
      if (parts.size > maxParts) {
        err = "El contenido necesita ${parts.size} códigos y el máximo práctico es $maxParts. Usa un archivo más pequeño."
        job = null
      } else {
        job = ZxCodeJob(srcKind, srcName, parts, srcPreview, "$srcDetail · ${zxFmtBytes(bytes.size.toLong())} · ${parts.size} código(s)")
        err = null
      }
    } catch (e: Exception) {
      err = "Error: ${e.message}"
      job = null
    }
  }

  val pickImage = rememberZxPicker { uri ->
    busy = true
    err = null
    val usePng = lossless == 1
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var bytes: ByteArray? = null
      var preview: Bitmap? = null
      var detail = ""
      try {
        val bmp = zxDecodeBitmap(uri, 1600)
        if (bmp == null) {
          message = "No se pudo leer la imagen"
        } else {
          // se reduce la imagen lo necesario hasta que quepa en el máximo del QR
          var side = 1280
          while (side >= 320) {
            val small = zxThumb(bmp, side)
            val b = if (usePng) zxPng(small) else zxJpeg(small, if (side >= 1024) 90 else 85)
            if (b.size <= ZX_QR_MAX_BYTES) {
              bytes = b
              preview = zxThumb(small, 320)
              detail = "${small.width} × ${small.height} · ${if (usePng) "PNG sin pérdida" else "JPEG alta calidad"}"
              break
            }
            side = side * 3 / 4
          }
          if (bytes == null) {
            message = "La imagen no cabe en un QR ni reduciéndola. Prueba con otra más pequeña."
          }
        }
      } catch (e: OutOfMemoryError) {
        message = "La imagen es demasiado grande para este dispositivo"
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        if (message != null) {
          err = message
        } else {
          srcKind = ZxDataCode.KIND_IMAGE
          srcName = zxUriName(uri)
          srcBytes = bytes
          srcPreview = preview
          srcDetail = detail
          picked = srcName
          rebuild()
        }
      }
    }
  }

  val pickFile = rememberZxPicker { uri ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var bytes: ByteArray? = null
      var size = 0L
      try {
        val (b, s) = zxReadLimited(uri, ZX_QR_MAX_BYTES)
        bytes = b
        size = s
        if (b == null) {
          message = "El archivo pesa ${zxFmtBytes(size)} y para un QR el máximo es ${zxFmtBytes(ZX_QR_MAX_BYTES.toLong())}"
        }
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        if (message != null) {
          err = message
        } else {
          srcKind = ZxDataCode.KIND_FILE
          srcName = zxUriName(uri)
          srcBytes = bytes
          srcPreview = null
          srcDetail = ""
          picked = srcName
          rebuild()
        }
      }
    }
  }

  val pickVideo = rememberZxPicker { uri ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var bytes: ByteArray? = null
      var preview: Bitmap? = null
      var detail = ""
      var kindCode = ZxDataCode.KIND_VIDEO
      var fname = zxUriName(uri)
      try {
        val (duration, poster) = zxVideoMeta(uri)
        val (b, size) = zxReadLimited(uri, ZX_QR_MAX_BYTES)
        if (b != null) {
          bytes = b
          preview = poster
          detail = "Video de ${duration / 1000} s"
        } else if (poster != null) {
          val posterBytes = zxJpeg(poster, 85)
          if (posterBytes.size <= ZX_QR_MAX_BYTES) {
            bytes = posterBytes
            preview = zxThumb(poster, 320)
            kindCode = ZxDataCode.KIND_IMAGE
            fname = "cartel_" + fname.substringBeforeLast('.').take(40) + ".jpg"
            detail = "El video pesa ${zxFmtBytes(size)}: se codifica su cartel"
          } else {
            message = "El video es demasiado grande y su cartel tampoco cabe"
          }
        } else {
          message = "El video pesa ${zxFmtBytes(size)} y para un QR el máximo es ${zxFmtBytes(ZX_QR_MAX_BYTES.toLong())}"
        }
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        if (message != null) {
          err = message
        } else {
          srcKind = kindCode
          srcName = fname
          srcBytes = bytes
          srcPreview = preview
          srcDetail = detail
          picked = zxUriName(uri)
          rebuild()
        }
      }
    }
  }

  // Texto: un QR normal; si es muy largo, se trocea en paquetes
  val textJob: ZxCodeJob? = remember(text) {
    if (text.isEmpty()) {
      null
    } else {
      val bytes = text.toByteArray(Charsets.UTF_8)
      if (bytes.size <= 900) {
        ZxCodeJob(ZxDataCode.KIND_TEXT, "", listOf(text), null, "Texto de ${text.length} caracteres · 1 código")
      } else {
        try {
          val parts = ZxDataCode.encode(ZxDataCode.KIND_TEXT, "", bytes, 800)
          ZxCodeJob(ZxDataCode.KIND_TEXT, "", parts, null, "Texto de ${text.length} caracteres · ${parts.size} códigos")
        } catch (e: Exception) {
          null
        }
      }
    }
  }

  val active = if (kind == 0) textJob else job
  val totalParts = active?.contents?.size ?: 0
  val idx = if (totalParts > 0) part.coerceIn(0, totalParts - 1) else 0
  val current = active?.contents?.getOrNull(idx)

  ZxInfo("Genera un código QR con texto, una imagen, un archivo o un video. Lo que no cabe en un solo QR se reparte en varias partes numeradas: el lector de Zetaria las vuelve a unir. Un QR guarda unos 2,9 KB, así que los archivos grandes ocupan varios códigos.")
  ZxChoice(listOf("Texto", "Imagen", "Archivo", "Video"), kind) {
    kind = it
    clearSource()
    picked = null
  }
  when (kind) {
    0 -> OutlinedTextField(
      text,
      { text = it },
      label = { Text("Texto o enlace") },
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 90.dp)
    )
    1 -> {
      ZxBtn(if (picked == null) "Elegir imagen" else "Imagen: $picked (cambiar)") { pickImage("image/*") }
      ZxChoice(listOf("Alta calidad", "Sin pérdida"), lossless) { lossless = it }
      ZxInfo("«Sin pérdida» (PNG) es ideal para dibujos, capturas y logos: guarda la imagen tal cual. En fotos ocupa mucho, así que la app reduce el tamaño hasta que quepa; para fotos conviene «Alta calidad» (JPEG 92), que se ve igual y ocupa mucho menos.")
    }
    2 -> ZxBtn(if (picked == null) "Elegir archivo" else "Archivo: $picked (cambiar)") { pickFile("*/*") }
    else -> {
      ZxBtn(if (picked == null) "Elegir video" else "Video: $picked (cambiar)") { pickVideo("video/*") }
      ZxInfo("Un video completo solo cabe si es muy pequeño. Si no cabe, se genera un código con su cartel (miniatura) y sus datos.")
    }
  }
  ZxInfo("Corrección de errores del QR:")
  ZxChoice(listOf("L (7%)", "M (15%)", "Q (25%)", "H (30%)"), level) {
    level = it
    rebuild()
  }
  ZxError(err)
  if (busy) ZxBusy("Preparando el contenido…")
  if (active != null && current != null) {
    ZxOk(active.detail)
    val prev = active.preview
    if (prev != null) {
      Image(
        prev.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit
      )
    }
    if (totalParts > 1) {
      ZxRow {
        ZxSmallBtn("◀") { part = (idx - 1).coerceAtLeast(0) }
        ZxSmallBtn("Parte ${idx + 1} / $totalParts") { }
        ZxSmallBtn("▶") { part = (idx + 1).coerceAtMost(totalParts - 1) }
      }
      ZxInfo("Cada parte es un QR distinto: guarda o envía todas y lee los códigos en cualquier orden.")
    }
    val bmp: Bitmap? = remember(current, level) {
      try {
        zxQrBitmapFor(current, ZX_EC_LEVELS[level])
      } catch (e: Exception) {
        null
      }
    }
    if (bmp == null) {
      ZxError("El contenido no cabe con esa corrección de errores: baja a L o M.")
    } else {
      Image(
        bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 6.dp),
        contentScale = ContentScale.Fit
      )
      ZxRow {
        ZxSmallBtn("Guardar PNG") { save("codigo_qr_${idx + 1}.png", zxPng(bmp)) }
        ZxSmallBtn("Enviar esta") { zxSend(env) { zxSendFile(env, zxPng(bmp), "qr_${idx + 1}", "png", true) } }
      }
      if (totalParts > 1) {
        ZxRow {
          ZxSmallBtn("Enviar todas") {
            val contents = active.contents
            val lvl = ZX_EC_LEVELS[level]
            env.scope.launch(Dispatchers.IO) {
              var ok = 0
              for (i in contents.indices) {
                val b = try {
                  zxQrBitmapFor(contents[i], lvl)
                } catch (e: Exception) {
                  null
                } ?: break
                val bytes = zxPng(b)
                b.recycle()
                if (zxSendFile(env, bytes, "qr_${i + 1}", "png", true)) ok++
              }
              withContext(Dispatchers.Main) { zxToast("Enviados $ok de ${contents.size} códigos") }
            }
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 11) Leer código QR (cámara o imagen)
// ---------------------------------------------------------------------------
@Composable
internal fun ZxQrRead(env: ZxEnv) {
  val formats = listOf(BarcodeFormat.QR_CODE)
  val assembler = remember { ZxDataAssembler() }
  var result by remember { mutableStateOf<ZxScanResult?>(null) }
  var prog by remember { mutableStateOf<ZxDataProgress?>(null) }
  var err by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var loaded by remember { mutableStateOf<String?>(null) }
  val show = remember { mutableStateOf(true) }

  fun handle(text: String) {
    zxHandleScan(text, assembler, { prog = it }, { result = it }, { err = it })
  }

  val pickOne = rememberZxPicker { uri ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      val texts = zxReadCodeImages(listOf(uri), formats)
      withContext(Dispatchers.Main) {
        busy = false
        loaded = zxUriName(uri)
        if (texts.isEmpty()) {
          err = "No se encontró ningún código QR en esa imagen"
        } else {
          for (t in texts) handle(t)
        }
      }
    }
  }
  val pickMany = rememberZxMultiPicker { uris ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      val texts = zxReadCodeImages(uris, formats)
      withContext(Dispatchers.Main) {
        busy = false
        loaded = "${uris.size} imágenes"
        if (texts.isEmpty()) {
          err = "No se encontró ningún código QR en esas imágenes"
        } else {
          for (t in texts) handle(t)
        }
      }
    }
  }

  val r = result
  if (r == null) {
    ZxInfo("Apunta la cámara a un código QR o carga una imagen desde el teléfono. Si el código forma parte de un archivo troceado, lee todas las partes: se van uniendo solas.")
    QRCodeScanner(show, PaddingValues(0.dp)) { text ->
      withContext(Dispatchers.Main) { handle(text) }
      true
    }
    ZxProgressInfo(prog)
    ZxError(err)
    if (busy) ZxBusy("Leyendo la imagen…")
    ZxBtn("Cargar imagen del QR") { pickOne("image/*") }
    ZxRow {
      ZxSmallBtn("Cargar varias imágenes") { pickMany("image/*") }
      ZxSmallBtn("Reiniciar partes") {
        assembler.reset()
        prog = null
        err = null
        zxToast("Partes borradas")
      }
    }
    if (loaded != null) ZxInfo("Última imagen: $loaded")
  } else {
    ZxScanResultView(env, r) {
      result = null
      err = null
      show.value = true
    }
  }
}

// ---------------------------------------------------------------------------
// 12) Crear código de barras (texto, imagen, archivo o video)
// ---------------------------------------------------------------------------
private class ZxBarFmt(val name: String, val fmt: BarcodeFormat, val hint: String)

private val ZX_BAR_FORMATS = listOf(
  ZxBarFmt("Code 128", BarcodeFormat.CODE_128, "Letras, números y símbolos ASCII"),
  ZxBarFmt("EAN-13", BarcodeFormat.EAN_13, "12 o 13 dígitos (el último es de control)"),
  ZxBarFmt("EAN-8", BarcodeFormat.EAN_8, "7 u 8 dígitos"),
  ZxBarFmt("UPC-A", BarcodeFormat.UPC_A, "11 o 12 dígitos"),
  ZxBarFmt("Code 39", BarcodeFormat.CODE_39, "Mayúsculas, números y - . espacio $ / + %"),
  ZxBarFmt("ITF", BarcodeFormat.ITF, "Solo dígitos y en cantidad par"),
  ZxBarFmt("Codabar", BarcodeFormat.CODABAR, "Dígitos y - $ : / . +"),
  ZxBarFmt("PDF417 (2D)", BarcodeFormat.PDF_417, "Hasta ~1 KB: sirve para archivos pequeños"),
  ZxBarFmt("Aztec (2D)", BarcodeFormat.AZTEC, "Hasta ~1,9 KB: el que más cabe"),
  ZxBarFmt("Data Matrix (2D)", BarcodeFormat.DATA_MATRIX, "Hasta ~1,5 KB, muy compacto")
)

private fun zxBarPartSize(f: BarcodeFormat): Int = when (f) {
  BarcodeFormat.PDF_417 -> 600
  BarcodeFormat.AZTEC -> 900
  BarcodeFormat.DATA_MATRIX -> 700
  else -> 0
}

@Composable
internal fun ZxBarcodeCreate(env: ZxEnv) {
  var kind by remember { mutableStateOf(0) }
  var text by remember { mutableStateOf("") }
  var fi by remember { mutableStateOf(0) }
  var menu by remember { mutableStateOf(false) }
  var lossless by remember { mutableStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }
  var picked by remember { mutableStateOf<String?>(null) }
  var srcKind by remember { mutableStateOf(ZxDataCode.KIND_IMAGE) }
  var srcName by remember { mutableStateOf("") }
  var srcBytes by remember { mutableStateOf<ByteArray?>(null) }
  var srcPreview by remember { mutableStateOf<Bitmap?>(null) }
  var srcDetail by remember { mutableStateOf("") }
  var job by remember { mutableStateOf<ZxCodeJob?>(null) }
  var part by remember { mutableStateOf(0) }
  val save = rememberZxSaver()
  val maxParts = 60

  val binaryKind = kind != 0
  fun fmtFor(index: Int): ZxBarFmt =
    if (binaryKind && !zxIs2D(ZX_BAR_FORMATS[index].fmt)) ZX_BAR_FORMATS[7] else ZX_BAR_FORMATS[index]
  val effective = fmtFor(fi)

  fun clearSource() {
    srcBytes = null
    srcPreview = null
    srcName = ""
    srcDetail = ""
    job = null
    part = 0
    err = null
  }

  fun rebuild(fmt: ZxBarFmt) {
    val bytes = srcBytes
    part = 0
    if (bytes == null) {
      job = null
      return
    }
    val ps = zxBarPartSize(fmt.fmt)
    if (ps <= 0) {
      err = "Ese formato es lineal y no puede llevar archivos. Elige PDF417, Aztec o Data Matrix."
      job = null
      return
    }
    try {
      val parts = ZxDataCode.encode(srcKind, srcName, bytes, ps)
      if (parts.size > maxParts) {
        err = "El contenido necesita ${parts.size} códigos y el máximo práctico es $maxParts. Usa un archivo más pequeño."
        job = null
      } else {
        job = ZxCodeJob(srcKind, srcName, parts, srcPreview, "$srcDetail · ${zxFmtBytes(bytes.size.toLong())} · ${parts.size} código(s)")
        err = null
      }
    } catch (e: Exception) {
      err = "Error: ${e.message}"
      job = null
    }
  }

  val pickImage = rememberZxPicker { uri ->
    busy = true
    err = null
    val usePng = lossless == 1
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var bytes: ByteArray? = null
      var preview: Bitmap? = null
      var detail = ""
      try {
        val bmp = zxDecodeBitmap(uri, 1200)
        if (bmp == null) {
          message = "No se pudo leer la imagen"
        } else {
          var side = 900
          while (side >= 320) {
            val small = zxThumb(bmp, side)
            val b = if (usePng) zxPng(small) else zxJpeg(small, if (side >= 700) 88 else 82)
            if (b.size <= ZX_BAR_MAX_BYTES) {
              bytes = b
              preview = zxThumb(small, 320)
              detail = "${small.width} × ${small.height}"
              break
            }
            side = side * 3 / 4
          }
          if (bytes == null) {
            message = "La imagen no cabe en un código de barras ni reduciéndola. Prueba con otra más pequeña."
          }
        }
      } catch (e: OutOfMemoryError) {
        message = "La imagen es demasiado grande para este dispositivo"
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        if (message != null) {
          err = message
        } else {
          srcKind = ZxDataCode.KIND_IMAGE
          srcName = zxUriName(uri)
          srcBytes = bytes
          srcPreview = preview
          srcDetail = detail
          picked = srcName
          rebuild(effective)
        }
      }
    }
  }

  val pickFile = rememberZxPicker { uri ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var bytes: ByteArray? = null
      var size = 0L
      try {
        val (b, s) = zxReadLimited(uri, ZX_BAR_MAX_BYTES)
        bytes = b
        size = s
        if (b == null) {
          message = "El archivo pesa ${zxFmtBytes(size)} y el máximo para códigos de barras es ${zxFmtBytes(ZX_BAR_MAX_BYTES.toLong())}"
        }
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        if (message != null) {
          err = message
        } else {
          srcKind = ZxDataCode.KIND_FILE
          srcName = zxUriName(uri)
          srcBytes = bytes
          srcPreview = null
          srcDetail = "Archivo"
          picked = srcName
          rebuild(effective)
        }
      }
    }
  }

  val pickVideo = rememberZxPicker { uri ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      var message: String? = null
      var bytes: ByteArray? = null
      var preview: Bitmap? = null
      var detail = ""
      var kindCode = ZxDataCode.KIND_VIDEO
      var fname = zxUriName(uri)
      try {
        val (duration, poster) = zxVideoMeta(uri)
        val (b, size) = zxReadLimited(uri, ZX_BAR_MAX_BYTES)
        if (b != null) {
          bytes = b
          preview = poster
          detail = "Video de ${duration / 1000} s"
        } else if (poster != null) {
          val pb = zxJpeg(poster, 85)
          if (pb.size <= ZX_BAR_MAX_BYTES) {
            bytes = pb
            preview = zxThumb(poster, 320)
            kindCode = ZxDataCode.KIND_IMAGE
            fname = "cartel_" + fname.substringBeforeLast('.').take(40) + ".jpg"
            detail = "Cartel del video (${zxFmtBytes(size)})"
          } else {
            message = "El video es demasiado grande y su cartel tampoco cabe"
          }
        } else {
          message = "El video pesa ${zxFmtBytes(size)} y el máximo es ${zxFmtBytes(ZX_BAR_MAX_BYTES.toLong())}"
        }
      } catch (e: Exception) {
        message = "Error: ${e.message}"
      }
      withContext(Dispatchers.Main) {
        busy = false
        if (message != null) {
          err = message
        } else {
          srcKind = kindCode
          srcName = fname
          srcBytes = bytes
          srcPreview = preview
          srcDetail = detail
          picked = zxUriName(uri)
          rebuild(effective)
        }
      }
    }
  }

  val textJob: ZxCodeJob? = remember(text, kind) {
    if (text.isEmpty() || kind != 0) null else ZxCodeJob(ZxDataCode.KIND_TEXT, "", listOf(text), null, "")
  }
  val active = if (kind == 0) textJob else job
  val totalParts = active?.contents?.size ?: 0
  val idx = if (totalParts > 0) part.coerceIn(0, totalParts - 1) else 0
  val current = active?.contents?.getOrNull(idx)

  ZxInfo("Genera un código de barras. Los formatos lineales (Code 128, EAN, UPC…) solo llevan texto corto; para imágenes, archivos y videos se usan los 2D (PDF417, Aztec, Data Matrix), que admiten datos binarios troceados en varias partes.")
  ZxChoice(listOf("Texto", "Imagen", "Archivo", "Video"), kind) {
    kind = it
    clearSource()
    picked = null
  }
  Box {
    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text("Formato: ${effective.name} ▾") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      ZX_BAR_FORMATS.forEachIndexed { i, f ->
        DropdownMenuItem(onClick = {
          fi = i
          menu = false
          val next = if (kind != 0 && !zxIs2D(f.fmt)) ZX_BAR_FORMATS[7] else ZX_BAR_FORMATS[i]
          rebuild(next)
        }) { Text(f.name) }
      }
    }
  }
  ZxInfo(effective.hint)
  if (binaryKind && !zxIs2D(ZX_BAR_FORMATS[fi].fmt)) {
    ZxInfo("Para imágenes, archivos o videos se usa automáticamente PDF417 (2D).")
  }
  when (kind) {
    0 -> OutlinedTextField(
      text,
      { text = it },
      label = { Text("Datos") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth()
    )
    1 -> {
      ZxBtn(if (picked == null) "Elegir imagen" else "Imagen: $picked (cambiar)") { pickImage("image/*") }
      ZxChoice(listOf("Alta calidad", "Sin pérdida"), lossless) { lossless = it }
    }
    2 -> ZxBtn(if (picked == null) "Elegir archivo" else "Archivo: $picked (cambiar)") { pickFile("*/*") }
    else -> {
      ZxBtn(if (picked == null) "Elegir video" else "Video: $picked (cambiar)") { pickVideo("video/*") }
      ZxInfo("Si el video no cabe, se genera un código con su cartel.")
    }
  }
  ZxError(err)
  if (busy) ZxBusy("Preparando el contenido…")
  if (active != null && current != null) {
    if (active.detail.isNotEmpty()) ZxOk(active.detail)
    val prev = active.preview
    if (prev != null) {
      Image(
        prev.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit
      )
    }
    if (totalParts > 1) {
      ZxRow {
        ZxSmallBtn("◀") { part = (idx - 1).coerceAtLeast(0) }
        ZxSmallBtn("Parte ${idx + 1} / $totalParts") { }
        ZxSmallBtn("▶") { part = (idx + 1).coerceAtMost(totalParts - 1) }
      }
    }
    val bmp: Bitmap? = remember(current, effective.fmt) {
      try {
        zxBarcodeBitmapFor(current, effective.fmt)
      } catch (e: Exception) {
        null
      }
    }
    if (bmp == null) {
      ZxError("Los datos no son válidos para ${effective.name}. ${effective.hint}")
    } else {
      Image(
        bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).padding(vertical = 6.dp),
        contentScale = ContentScale.Fit
      )
      ZxRow {
        ZxSmallBtn("Guardar PNG") { save("codigo_barras_${idx + 1}.png", zxPng(bmp)) }
        ZxSmallBtn("Enviar esta") { zxSend(env) { zxSendFile(env, zxPng(bmp), "barras_${idx + 1}", "png", true) } }
      }
      if (totalParts > 1) {
        ZxRow {
          ZxSmallBtn("Enviar todas") {
            val contents = active.contents
            val fmt = effective.fmt
            env.scope.launch(Dispatchers.IO) {
              var ok = 0
              for (i in contents.indices) {
                val b = try {
                  zxBarcodeBitmapFor(contents[i], fmt)
                } catch (e: Exception) {
                  null
                } ?: break
                val bytes = zxPng(b)
                b.recycle()
                if (zxSendFile(env, bytes, "barras_${i + 1}", "png", true)) ok++
              }
              withContext(Dispatchers.Main) { zxToast("Enviados $ok de ${contents.size} códigos") }
            }
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 13) Leer código de barras (cámara o imagen)
// ---------------------------------------------------------------------------
private val ZX_SCAN_FORMATS = listOf(
  BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
  BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.CODABAR,
  BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX, BarcodeFormat.QR_CODE
)

@Composable
internal fun ZxBarcodeRead(env: ZxEnv) {
  val assembler = remember { ZxDataAssembler() }
  var result by remember { mutableStateOf<ZxScanResult?>(null) }
  var prog by remember { mutableStateOf<ZxDataProgress?>(null) }
  var err by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var loaded by remember { mutableStateOf<String?>(null) }

  fun handle(text: String) {
    zxHandleScan(text, assembler, { prog = it }, { result = it }, { err = it })
  }

  val pickOne = rememberZxPicker { uri ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      val texts = zxReadCodeImages(listOf(uri), ZX_SCAN_FORMATS)
      withContext(Dispatchers.Main) {
        busy = false
        loaded = zxUriName(uri)
        if (texts.isEmpty()) {
          err = "No se encontró ningún código de barras en esa imagen"
        } else {
          for (t in texts) handle(t)
        }
      }
    }
  }
  val pickMany = rememberZxMultiPicker { uris ->
    busy = true
    err = null
    env.scope.launch(Dispatchers.Default) {
      val texts = zxReadCodeImages(uris, ZX_SCAN_FORMATS)
      withContext(Dispatchers.Main) {
        busy = false
        loaded = "${uris.size} imágenes"
        if (texts.isEmpty()) {
          err = "No se encontró ningún código de barras en esas imágenes"
        } else {
          for (t in texts) handle(t)
        }
      }
    }
  }

  val r = result
  if (r == null) {
    ZxInfo("Apunta la cámara al código de barras, o carga una imagen desde el teléfono. Funciona con códigos lineales y con los 2D (PDF417, Aztec, Data Matrix).")
    ZxCameraPreview(ZX_SCAN_FORMATS) { text ->
      if (result == null) handle(text)
    }
    ZxProgressInfo(prog)
    ZxError(err)
    if (busy) ZxBusy("Leyendo la imagen…")
    ZxBtn("Cargar imagen del código de barras") { pickOne("image/*") }
    ZxRow {
      ZxSmallBtn("Cargar varias imágenes") { pickMany("image/*") }
      ZxSmallBtn("Reiniciar partes") {
        assembler.reset()
        prog = null
        err = null
        zxToast("Partes borradas")
      }
    }
    if (loaded != null) ZxInfo("Última imagen: $loaded")
  } else {
    ZxScanResultView(env, r) {
      result = null
      err = null
    }
  }
}
