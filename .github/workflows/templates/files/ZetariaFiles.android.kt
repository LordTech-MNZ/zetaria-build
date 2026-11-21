package chat.simplex.common.views.zetaria

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun zetariaThumb(path: String, px: Int, video: Boolean): ImageBitmap? {
  try {
    if (video) {
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(path)
        val frame = r.getFrameAtTime(0) ?: return null
        val side = maxOf(frame.width, frame.height).coerceAtLeast(1)
        val scale = px.toFloat() / side
        if (scale >= 1f) return frame.asImageBitmap()
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, w, h, true).asImageBitmap()
      } finally {
        try { r.release() } catch (e: Throwable) {}
      }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
  } catch (e: Throwable) {
    return null
  }
}
