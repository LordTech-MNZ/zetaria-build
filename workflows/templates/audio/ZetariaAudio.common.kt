package %%PKG%%

import androidx.compose.runtime.Composable
%%IMPORTS_COMMON%%

// Zetaria: archivos de audio enviados como archivo normal (compatible con SimpleX estándar),
// pero mostrados con un reproductor propio y un ícono de "archivo de audio".

private val ZETARIA_AUDIO_EXTENSIONS = setOf(
  "mp3", "wav", "wave", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac",
  "amr", "awb", "wma", "mid", "midi", "mka", "weba", "aif", "aiff", "mp2"
)

fun zetariaIsAudioName(name: String?): Boolean {
  if (name == null) return false
  val dot = name.lastIndexOf('.')
  if (dot < 0 || dot == name.length - 1) return false
  return name.substring(dot + 1).lowercase() in ZETARIA_AUDIO_EXTENSIONS
}

fun zetariaIsAudioFile(file: CIFile?): Boolean = file != null && zetariaIsAudioName(file.fileName)

/** true si es audio, ya está descargado y esta plataforma sabe reproducirlo */
expect fun zetariaAudioAvailable(file: CIFile?): Boolean

@Composable
expect fun ZetariaAudioFileView(file: CIFile, onLongClick: () -> Unit)

// Se activa al pulsar el botón "Audio"; el selector de archivos lo consume una sola vez.
var zetariaPickAudio: Boolean = false

fun zetariaFilePickerMime(): String {
  val m = if (zetariaPickAudio) "audio/*" else "*/*"
  zetariaPickAudio = false
  return m
}

fun zetariaSetPickAudio() {
  zetariaPickAudio = true
}
