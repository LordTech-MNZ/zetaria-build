package chat.simplex.common.views.chat.item

import chat.simplex.common.model.CIFile
import chat.simplex.common.model.decryptCryptoFile
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.VideoPlayer
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Escritorio: copia el archivo a Pictures/Zetaria Chat Images, Pictures/Zetaria Feed o Videos/Zetaria Feed. */
actual suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean, feed: Boolean, quiet: Boolean) {
  val filePath = getLoadedFilePath(file)
  val name = file?.fileName
  if (file == null || filePath == null || name == null) {
    if (!quiet) showToast("Archivo no encontrado")
    return
  }
  var saved = false
  try {
    withContext(Dispatchers.IO) {
      val home = System.getProperty("user.home")
      val dir = if (video) File(File(home, "Videos"), "Zetaria Feed")
      else if (feed) File(File(home, "Pictures"), "Zetaria Feed")
      else File(File(home, "Pictures"), "Zetaria Chat Images")
      dir.mkdirs()
      val dest = File(dir, name)
      if (dest.exists() && dest.length() == file.fileSize) {
        saved = true
      } else {
        val target = if (dest.exists()) File(dir, System.currentTimeMillis().toString() + "_" + name) else dest
        val args = file.fileSource?.cryptoArgs
        if (args != null) {
          decryptCryptoFile(filePath, args, target.absolutePath)
        } else {
          File(filePath).copyTo(target)
        }
        saved = true
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed save: " + e.stackTraceToString())
  }
  if (!quiet) showToast(if (saved) "Guardado" else "No se pudo guardar")
}

/** Escritorio: solo se anota la posición. */
actual fun zetariaVideoSeek(player: VideoPlayer, positionMs: Long) {
  player.progress.value = positionMs
}

/** Escritorio: no hay nada que limpiar. */
actual suspend fun zetariaCleanGalleryDuplicates() {}
