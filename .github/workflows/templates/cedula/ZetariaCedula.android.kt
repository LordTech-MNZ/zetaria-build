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
  val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 44f, 44f, Path.Direction.CW) })

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
  p.shader = LinearGradient(
    0f, 0f, w.toFloat(), h.toFloat(),
    intArrayOf(0xFFF3ECFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFE9E9.toInt()), null, Shader.TileMode.CLAMP
  )
  c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
  p.shader = null

  // marca de agua: la bandera, muy tenue
  card.flag?.let { f ->
    p.alpha = 38
    c.drawBitmap(f.asAndroidBitmap(), null, RectF(150f, 260f, 690f, 619f), p)
    p.alpha = 255
  }

  // franja superior (rojo de la bandera + línea morada)
  p.color = red
  c.drawRect(0f, 0f, w.toFloat(), 34f, p)
  p.color = purple
  c.drawRect(0f, 34f, w.toFloat(), 40f, p)

  // bandera + cabecera
  card.flag?.let { f ->
    c.drawBitmap(f.asAndroidBitmap(), null, RectF(46f, 66f, 196f, 166f), p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 3f
    p.color = ink
    c.drawRect(46f, 66f, 196f, 166f, p)
    p.style = Paint.Style.FILL
  }
  val hx = (220f + 1154f) / 2f
  c.drawText("REINO SOBERANO DE ZETARIA", hx, 106f, tp(44f, ink, true, 0.04f, Paint.Align.CENTER))
  c.drawText("CÉDULA DE IDENTIDAD", hx, 152f, tp(32f, purple, true, 0.08f, Paint.Align.CENTER))
  c.drawText(fit(card.id, tp(34f, ink, false, 0.05f, Paint.Align.CENTER), 900f), hx, 198f, tp(34f, ink, false, 0.05f, Paint.Align.CENTER))
  p.color = 0x33000000
  c.drawRect(46f, 216f, 1154f, 219f, p)

  // datos
  val valuePaint = tp(36f, ink, true)
  fun field(label: String, value: String, x: Float, y: Float, maxW: Float) {
    c.drawText(label.uppercase(), x, y, tp(22f, muted, false, 0.08f))
    c.drawText(fit(value.ifBlank { "—" }, valuePaint, maxW), x, y + 40f, valuePaint)
  }
  field("Nombre", card.name, 50f, 250f, 650f)
  field("Fecha de nacimiento", card.birth, 50f, 334f, 310f)
  field("Edad", card.age?.let { "$it años" } ?: "", 380f, 334f, 320f)
  field("Estado civil", card.civil, 50f, 418f, 310f)
  field("Profesión", card.profession, 380f, 418f, 320f)
  field("Trabajo", card.work, 50f, 502f, 310f)
  field("Ciudad", card.city, 380f, 502f, 320f)
  field("Emitida", card.issued, 50f, 586f, 310f)

  // foto
  val photoRect = RectF(730f, 236f, 960f, 506f)
  val photo = card.photo
  if (photo != null) {
    val src = photo.asAndroidBitmap()
    val dstRatio = photoRect.width() / photoRect.height()
    val srcRatio = src.width.toFloat() / src.height
    val srcRect = if (srcRatio > dstRatio) {
      val nw = (src.height * dstRatio).toInt()
      Rect((src.width - nw) / 2, 0, (src.width + nw) / 2, src.height)
    } else {
      val nh = (src.width / dstRatio).toInt()
      Rect(0, (src.height - nh) / 2, src.width, (src.height + nh) / 2)
    }
    c.save()
    c.clipPath(Path().apply { addRoundRect(photoRect, 20f, 20f, Path.Direction.CW) })
    c.drawBitmap(src, srcRect, photoRect, p)
    c.restore()
  } else {
    p.color = 0x22000000
    c.drawRoundRect(photoRect, 20f, 20f, p)
    val initial = card.name.trim().firstOrNull()?.uppercase() ?: "Z"
    c.drawText(initial, photoRect.centerX(), photoRect.centerY() + 40f, tp(120f, muted, true, 0f, Paint.Align.CENTER))
  }
  p.style = Paint.Style.STROKE
  p.strokeWidth = 5f
  p.color = purple
  c.drawRoundRect(photoRect, 20f, 20f, p)
  p.style = Paint.Style.FILL

  // QR (el mismo de la dirección Zetaria)
  val qrBox = RectF(980f, 236f, 1150f, 406f)
  p.color = Color.WHITE
  c.drawRoundRect(qrBox, 18f, 18f, p)
  p.style = Paint.Style.STROKE
  p.strokeWidth = 2f
  p.color = 0x33000000
  c.drawRoundRect(qrBox, 18f, 18f, p)
  p.style = Paint.Style.FILL
  val qr = card.qr
  if (qr != null) {
    c.drawBitmap(qr.asAndroidBitmap(), null, RectF(qrBox.left + 8f, qrBox.top + 8f, qrBox.right - 8f, qrBox.bottom - 8f), p)
    c.drawText("DIRECCIÓN ZETARIA", qrBox.centerX(), qrBox.bottom + 30f, tp(17f, muted, false, 0.05f, Paint.Align.CENTER))
  } else {
    c.drawText("Sin enlace", qrBox.centerX(), qrBox.centerY() + 8f, tp(22f, muted, false, 0f, Paint.Align.CENTER))
  }

  // pie
  p.color = purple
  c.drawRect(0f, 690f, w.toFloat(), h.toFloat(), p)
  c.drawText("CIUDADANO DEL REINO SOBERANO DE ZETARIA", w / 2f, 736f, tp(26f, Color.WHITE, true, 0.1f, Paint.Align.CENTER))

  return bmp.asImageBitmap()
}

actual fun zetariaFlagImage(): ImageBitmap? =
  try {
    androidAppContext.resources.getDrawable(R.drawable.zet_flag, null).toBitmap().asImageBitmap()
  } catch (e: Throwable) {
    null
  }
