package chat.simplex.common.views.chat.item

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import chat.simplex.common.model.CIFile
import chat.simplex.common.model.decryptCryptoFile
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.androidAppContext
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.imageMimeType
import chat.simplex.common.platform.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun zfVideoMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
  "mov" -> "video/quicktime"
  "webm" -> "video/webm"
  "mkv" -> "video/x-matroska"
  "3gp" -> "video/3gpp"
  else -> "video/mp4"
}

/**
 * Guarda en Pictures/Zetaria (imágenes) o Movies/Zetaria (videos) usando MediaStore.
 * En Android 10 o superior no hace falta ningún permiso. En versiones anteriores el archivo
 * queda en la carpeta por defecto de la galería.
 */
actual suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean) {
  val filePath = getLoadedFilePath(file)
  val name = file?.fileName
  if (file == null || filePath == null || name == null) {
    withContext(Dispatchers.Main) { showToast("Archivo no encontrado") }
    return
  }
  var saved = false
  try {
    withContext(Dispatchers.IO) {
      val resolver = androidAppContext.contentResolver
      val values = ContentValues()
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      values.put(MediaStore.MediaColumns.MIME_TYPE, if (video) zfVideoMimeType(name) else imageMimeType(name))
      if (Build.VERSION.SDK_INT >= 29) {
        val root = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, root + "/Zetaria")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      val uri = resolver.insert(collection, values)
      if (uri != null) {
        try {
          val out = resolver.openOutputStream(uri)
          if (out != null) {
            out.use { stream ->
              val args = file.fileSource?.cryptoArgs
              if (args != null) {
                val tmp = File.createTempFile("zetaria", ".tmp", androidAppContext.cacheDir)
                try {
                  decryptCryptoFile(filePath, args, tmp.absolutePath)
                  tmp.inputStream().use { it.copyTo(stream) }
                } finally {
                  tmp.delete()
                }
              } else {
                File(filePath).inputStream().use { it.copyTo(stream) }
              }
            }
            if (Build.VERSION.SDK_INT >= 29) {
              val done = ContentValues()
              done.put(MediaStore.MediaColumns.IS_PENDING, 0)
              resolver.update(uri, done, null, null)
            }
            saved = true
          } else {
            resolver.delete(uri, null, null)
          }
        } catch (e: Exception) {
          resolver.delete(uri, null, null)
          throw e
        }
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed save to gallery: " + e.stackTraceToString())
  }
  withContext(Dispatchers.Main) {
    showToast(if (saved) "Guardado en la galería (carpeta Zetaria)" else "No se pudo guardar")
  }
}
