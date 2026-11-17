package chat.simplex.common.views.chat.item

import chat.simplex.common.model.CIFile
import chat.simplex.common.model.decryptCryptoFile
import chat.simplex.common.platform.Log
import chat.simplex.common.platform.TAG
import chat.simplex.common.platform.getLoadedFilePath
import chat.simplex.common.platform.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Escritorio: copia el archivo a ~/Pictures/Zetaria o ~/Videos/Zetaria. */
actual suspend fun zetariaSaveToGallery(file: CIFile?, video: Boolean) {
  val filePath = getLoadedFilePath(file)
  val name = file?.fileName
  if (file == null || filePath == null || name == null) {
    showToast("Archivo no encontrado")
    return
  }
  var saved = false
  try {
    withContext(Dispatchers.IO) {
      val dir = File(File(System.getProperty("user.home"), if (video) "Videos" else "Pictures"), "Zetaria")
      dir.mkdirs()
      var dest = File(dir, name)
      if (dest.exists()) dest = File(dir, System.currentTimeMillis().toString() + "_" + name)
      val args = file.fileSource?.cryptoArgs
      if (args != null) {
        decryptCryptoFile(filePath, args, dest.absolutePath)
      } else {
        File(filePath).copyTo(dest)
      }
      saved = true
    }
  } catch (e: Exception) {
    Log.e(TAG, "zetaria feed save: " + e.stackTraceToString())
  }
  showToast(if (saved) "Guardado en la carpeta Zetaria" else "No se pudo guardar")
}
