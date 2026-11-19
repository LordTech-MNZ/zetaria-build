package chat.simplex.common.views.chat.item

import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import chat.simplex.common.model.CIFile
import chat.simplex.common.model.decryptCryptoFile
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.VideoPlayer
import chat.simplex.common.platform.androidAppContext
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.imageMimeType
import chat.simplex.common.platform.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val ZF_DIR_CHAT_IMAGES = "Pictures/Zetaria Chat Images"
private const val ZF_DIR_FEED_VIDEOS = "Movies/Zetaria Feed"
private const val ZF_DIR_FEED_IMAGES_FALLBACK = "Pictures/Zetaria Feed"

private fun zfVideoMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
  "mov" -> "video/quicktime"
  "webm" -> "video/webm"
  "mkv" -> "video/x-matroska"
  "3gp" -> "video/3gpp"
  else -> "video/mp4"
}

/**
 * Copia el archivo a la galería, en la carpeta indicada (relativa, p. ej. «Pictures/Zetaria Chat Images»).
 * Si ya hay un archivo igual en esa carpeta (mismo nombre y tamaño) no lo vuelve a guardar.
 * Puede lanzar IllegalArgumentException si Android no admite ese tipo de archivo en esa carpeta.
 */
private fun zfInsertAndCopy(file: CIFile, filePath: String, name: String, mime: String, video: Boolean, relativeDir: String): Boolean {
  val resolver = androidAppContext.contentResolver
  val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  if (Build.VERSION.SDK_INT >= 29) {
    val cursor = resolver.query(
      collection,
      arrayOf(MediaStore.MediaColumns._ID),
      MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
      arrayOf(name, file.fileSize.toString(), relativeDir + "%"),
      null
    )
    val exists = cursor?.use { it.moveToFirst() } ?: false
    if (exists) return true
  }
  val values = ContentValues()
  values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
  values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
  if (Build.VERSION.SDK_INT >= 29) {
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
    values.put(MediaStore.MediaColumns.IS_PENDING, 1)
  }
  val uri = resolver.insert(collection, values) ?: return false
  try {
    val out = resolver.openOutputStream(uri)
    if (out == null) {
      resolver.delete(uri, null, null)
      return false
    }
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
    return true
  } catch (e: Exception) {
    resolver.delete(uri, null, null)
    throw e
  }
}

/**
 * Imágenes de los chats: Pictures/Zetaria Chat Images (una sola copia, nada en Pictures a secas).
 * Videos del feed: Movies/Zetaria Feed. Imágenes del feed: Movies/Zetaria Feed si Android lo permite
 * (normalmente no admite imágenes en Movies) y, si no, Pictures/Zetaria Feed.
 * En Android 9 o anterior no existen carpetas por ruta y se guarda en la ubicación por defecto.
 */
actual suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean, feed: Boolean, quiet: Boolean) {
  val filePath = getLoadedFilePath(file)
  val name = file?.fileName
  if (file == null || filePath == null || name == null) {
    if (!quiet) withContext(Dispatchers.Main) { showToast("Archivo no encontrado") }
    return
  }
  var saved = false
  try {
    withContext(Dispatchers.IO) {
      saved = if (video) {
        zfInsertAndCopy(file, filePath, name, zfVideoMimeType(name), true, ZF_DIR_FEED_VIDEOS)
      } else if (!feed) {
        zfInsertAndCopy(file, filePath, name, imageMimeType(name), false, ZF_DIR_CHAT_IMAGES)
      } else {
        try {
          zfInsertAndCopy(file, filePath, name, imageMimeType(name), false, ZF_DIR_FEED_VIDEOS)
        } catch (e: IllegalArgumentException) {
          zfInsertAndCopy(file, filePath, name, imageMimeType(name), false, ZF_DIR_FEED_IMAGES_FALLBACK)
        }
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed save to gallery: " + e.stackTraceToString())
  }
  if (!quiet) {
    withContext(Dispatchers.Main) {
      showToast(if (saved) "Guardado en la galería" else "No se pudo guardar")
    }
  }
}

/** Mueve el video a esa posición (ExoPlayer) y la anota para que «reproducir» continúe desde ahí. */
actual fun zetariaVideoSeek(player: VideoPlayer, positionMs: Long) {
  val p = if (positionMs < 0L) 0L else positionMs
  try {
    player.player.seekTo(p)
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed seek: " + e.stackTraceToString())
  }
  player.progress.value = p
}

/**
 * Versiones anteriores (y el botón «Guardar» original) dejaban copias en «Pictures» a secas.
 * Aquí se borran, solo las que guardó esta app y solo si ya hay una igual (mismo nombre y tamaño)
 * en «Pictures/Zetaria Chat Images»: no se pierde ninguna foto.
 */
actual suspend fun zetariaCleanGalleryDuplicates() {
  if (Build.VERSION.SDK_INT < 29) return
  try {
    withContext(Dispatchers.IO) {
      val resolver = androidAppContext.contentResolver
      val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      val cursor = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
        MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?",
        arrayOf("Pictures/", androidAppContext.packageName),
        null
      )
      val rows = ArrayList<Triple<Long, String, Long>>()
      if (cursor != null) {
        cursor.use { c ->
          while (c.moveToNext()) {
            rows.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getLong(2)))
          }
        }
      }
      for (r in rows) {
        try {
          val same = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
            arrayOf(r.second, r.third.toString(), ZF_DIR_CHAT_IMAGES + "%"),
            null
          )?.use { it.moveToFirst() } ?: false
          if (same) resolver.delete(ContentUris.withAppendedId(collection, r.first), null, null)
        } catch (e: Exception) {
          Log.e(TAG, "zetaria feed clean one: " + e.stackTraceToString())
        }
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed clean: " + e.stackTraceToString())
  }
}
