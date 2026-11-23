package chat.simplex.common.views.zetaria

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import chat.simplex.common.R
import chat.simplex.common.platform.androidAppContext

// Dibuja la cédula horizontal (1200 x 756, proporción de tarjeta) como en una cédula electrónica:
// franja superior, bandera, título e ID en la cabecera, datos a la izquierda, foto y QR a la derecha,
// marca de agua con la bandera y pie con «Ciudadano del Reino Soberano de Zetaria».
actual fun zetariaCedulaImage(card: ZetariaCedulaCard): ImageBitmap {
  val w = 1200
  val h = 756
  val ink = 0xFF1B1030.toInt()
  val muted = 0xFF6B5B8C.toInt()
  val purple = 0xFF4C1D95.toInt()
  val red = 0xFFE10000.toInt()

  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val c = Canvas(bmp)
  c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 44f, 44f, Path.Direction.CW) })

  // pinceles separados: así ningún cambio de color/transparencia afecta a las imágenes (foto y QR siempre opacos)
  fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
  fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width }
  val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

  fun tp(size: Float, color: Int, bold: Boolean, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
      letterSpacing = spacing
      textAlign = align
    }

  fun fit(s: String, paint: TextPaint, maxW: Float): String =
    TextUtils.ellipsize(s, paint, maxW, TextUtils.TruncateAt.END).toString()

  // fondo pastel
  c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
    shader = LinearGradient(
      0f, 0f, w.toFloat(), h.toFloat(),
      intArrayOf(0xFFF3ECFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFE9E9.toInt()), null, Shader.TileMode.CLAMP
    )
  })

  // marca de agua: la bandera, muy tenue (solo ella es translúcida)
  card.flag?.let { f ->
    c.drawBitmap(f.asAndroidBitmap(), null, RectF(190f, 262f, 730f, 621f), Paint(imagePaint).apply { alpha = 36 })
  }

  // franja superior
  c.drawRect(0f, 0f, w.toFloat(), 34f, fill(red))
  c.drawRect(0f, 34f, w.toFloat(), 40f, fill(purple))

  // bandera + cabecera
  card.flag?.let { f ->
    c.drawBitmap(f.asAndroidBitmap(), null, RectF(46f, 66f, 196f, 166f), imagePaint)
    c.drawRect(46f, 66f, 196f, 166f, stroke(ink, 3f))
  }
  val hx = (220f + 1154f) / 2f
  c.drawText("REINO SOBERANO DE ZETARIA", hx, 106f, tp(44f, ink, true, 0.04f, Paint.Align.CENTER))
  c.drawText("CÉDULA DE IDENTIDAD", hx, 152f, tp(32f, purple, true, 0.08f, Paint.Align.CENTER))
  val idPaint = tp(34f, ink, false, 0.05f, Paint.Align.CENTER)
  c.drawText(fit(card.id, idPaint, 900f), hx, 198f, idPaint)
  c.drawRect(46f, 216f, 1154f, 219f, fill(0x33000000))

  // datos (el texto se achica solo si no cabe, y si aun así no cabe se corta con «…»)
  fun field(label: String, value: String, x: Float, y: Float, maxW: Float) {
    c.drawText(label.uppercase(), x, y, tp(22f, muted, false, 0.08f))
    val v = value.ifBlank { "—" }
    var size = 36f
    var paint = tp(size, ink, true)
    while (paint.measureText(v) > maxW && size > 26f) {
      size -= 2f
      paint = tp(size, ink, true)
    }
    c.drawText(fit(v, paint, maxW), x, y + 40f, paint)
  }
  field("Nombre", card.name, 50f, 250f, 780f)
  field("Fecha de nacimiento", card.birth, 50f, 334f, 360f)
  field("Edad", card.age?.let { "$it años" } ?: "", 440f, 334f, 390f)
  field("Estado civil", card.civil, 50f, 418f, 360f)
  field("Profesión", card.profession, 440f, 418f, 390f)
  field("Trabajo", card.work, 50f, 502f, 360f)
  field("Ciudad", card.city, 440f, 502f, 390f)
  field("Emitida", card.issued, 50f, 586f, 360f)

  // columna derecha: QR arriba (más grande) y foto cuadrada debajo (más grande), ambos opacos
  val cx = 1005f
  val qrBox = RectF(cx - 100f, 228f, cx + 100f, 428f)
  c.drawRoundRect(qrBox, 18f, 18f, fill(Color.WHITE))
  c.drawRoundRect(qrBox, 18f, 18f, stroke(0x33000000, 2f))
  val qr = card.qr
  if (qr != null) {
    c.drawBitmap(qr.asAndroidBitmap(), null, RectF(qrBox.left + 8f, qrBox.top + 8f, qrBox.right - 8f, qrBox.bottom - 8f), imagePaint)
    c.drawText("DIRECCIÓN ZETARIA", cx, 452f, tp(17f, muted, false, 0.05f, Paint.Align.CENTER))
  } else {
    c.drawText("Sin enlace", cx, qrBox.centerY() + 8f, tp(22f, muted, false, 0f, Paint.Align.CENTER))
  }

  val photoRect = RectF(cx - 113f, 466f, cx + 113f, 692f)
  val photo = card.photo
  if (photo != null) {
    val src = photo.asAndroidBitmap()
    // la foto de perfil ya es cuadrada: se dibuja entera; si no lo fuera, se centra el recorte cuadrado
    val side = minOf(src.width, src.height)
    val srcRect = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
    c.save()
    c.clipPath(Path().apply { addRoundRect(photoRect, 22f, 22f, Path.Direction.CW) })
    c.drawBitmap(src, srcRect, photoRect, imagePaint)
    c.restore()
  } else {
    c.drawRoundRect(photoRect, 22f, 22f, fill(0x22000000))
    val initial = card.name.trim().firstOrNull()?.uppercase() ?: "Z"
    c.drawText(initial, photoRect.centerX(), photoRect.centerY() + 40f, tp(120f, muted, true, 0f, Paint.Align.CENTER))
  }
  c.drawRoundRect(photoRect, 22f, 22f, stroke(purple, 5f))

  // pie
  c.drawRect(0f, 704f, w.toFloat(), h.toFloat(), fill(purple))
  c.drawText("CIUDADANO DEL REINO SOBERANO DE ZETARIA", w / 2f, 739f, tp(25f, Color.WHITE, true, 0.1f, Paint.Align.CENTER))

  return bmp.asImageBitmap()
}

actual fun zetariaFlagImage(): ImageBitmap? =
  try {
    androidAppContext.resources.getDrawable(R.drawable.zet_flag, null).toBitmap().asImageBitmap()
  } catch (e: Throwable) {
    null
  }
