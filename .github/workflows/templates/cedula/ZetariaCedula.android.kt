package chat.simplex.common.views.zetaria

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

// Dibuja la cédula como imagen PNG (1080 x 1400) para compartirla.
actual fun zetariaCedulaImage(name: String, photo: ImageBitmap?, qr: ImageBitmap, id: String, dateLine: String): ImageBitmap {
  val w = 1080
  val h = 1400
  val gold = 0xFFE9C46A.toInt()
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val c = Canvas(bmp)
  val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

  // fondo y borde
  p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1E0B3F.toInt(), 0xFF4C1D95.toInt(), Shader.TileMode.CLAMP)
  c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 56f, 56f, p)
  p.shader = null
  p.style = Paint.Style.STROKE
  p.strokeWidth = 6f
  p.color = gold
  c.drawRoundRect(RectF(24f, 24f, w - 24f, h - 24f), 40f, 40f, p)
  p.style = Paint.Style.FILL

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

  // encabezado
  c.drawText("REINO SOBERANO DE ZETARIA", w / 2f, 120f, tp(46f, gold, true, 0.12f, Paint.Align.CENTER))
  c.drawText("CÉDULA", w / 2f, 176f, tp(34f, Color.WHITE, false, 0.3f, Paint.Align.CENTER))

  // foto de perfil (círculo)
  val cx = 190f
  val cy = 380f
  val r = 120f
  if (photo != null) {
    val src = photo.asAndroidBitmap()
    val side = minOf(src.width, src.height)
    val srcRect = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
    val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
    c.save()
    c.clipPath(path)
    c.drawBitmap(src, srcRect, RectF(cx - r, cy - r, cx + r, cy + r), p)
    c.restore()
  } else {
    p.color = 0x55FFFFFF
    c.drawCircle(cx, cy, r, p)
    val initial = name.trim().firstOrNull()?.uppercase() ?: "Z"
    c.drawText(initial, cx, cy + 36f, tp(110f, Color.WHITE, true, 0f, Paint.Align.CENTER))
  }
  p.style = Paint.Style.STROKE
  p.strokeWidth = 6f
  p.color = gold
  c.drawCircle(cx, cy, r, p)
  p.style = Paint.Style.FILL

  // nombre e ID
  val tx = cx + r + 50f
  val maxW = w - tx - 70f
  c.drawText("NOMBRE", tx, 330f, tp(28f, gold, false))
  c.drawText(fit(name, tp(58f, Color.WHITE, true), maxW), tx, 392f, tp(58f, Color.WHITE, true))
  c.drawText("ID", tx, 452f, tp(28f, gold, false))
  c.drawText(fit(id, tp(40f, Color.WHITE, true), maxW), tx, 496f, tp(40f, Color.WHITE, true))

  // QR sobre fondo blanco
  val box = 620f
  val left = (w - box) / 2f
  val top = 620f
  p.color = Color.WHITE
  c.drawRoundRect(RectF(left, top, left + box, top + box), 40f, 40f, p)
  val pad = 30f
  c.drawBitmap(qr.asAndroidBitmap(), null, RectF(left + pad, top + pad, left + box - pad, top + box - pad), p)

  // pie
  c.drawText(fit(dateLine, tp(34f, Color.WHITE, false), w - 140f), w / 2f, top + box + 90f, tp(34f, Color.WHITE, false, 0f, Paint.Align.CENTER))
  c.drawText("Escanea el QR con SimpleX o Zetaria para conectar", w / 2f, top + box + 150f, tp(28f, gold, false, 0f, Paint.Align.CENTER))

  return bmp.asImageBitmap()
}
