package chat.simplex.common.views.zetaria

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import kotlinx.coroutines.*
import java.io.File

// Explorador de archivos de Zetaria (Datos del chat > Archivos y multimedia).
// Lista los archivos recibidos/enviados que guarda la app (carpeta de archivos), del más pesado al más ligero,
// y deja borrar solo los que la persona marque, sin tocar el resto de la multimedia ni la base de datos.

private fun ztF(es: String, en: String): String =
  if (java.util.Locale.getDefault().language == "es") es else en

private class ZFile(val name: String, val size: Long, val modified: Long)

private enum class ZKind(val emoji: String) { IMAGE("🖼"), VIDEO("🎬"), AUDIO("🎵"), OTHER("📄") }

private fun zKindOf(name: String): ZKind =
  when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> ZKind.IMAGE
    "mp4", "mov", "mkv", "webm", "3gp", "avi" -> ZKind.VIDEO
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> ZKind.AUDIO
    else -> ZKind.OTHER
  }

private fun zListFiles(): List<ZFile> =
  try {
    // los archivos internos de Zetaria (zetaria_*) no se muestran para que nadie los borre sin querer
    appFilesDir.listFiles()
      ?.filter { it.isFile && !it.name.startsWith("zetaria_") }
      ?.map { ZFile(it.name, it.length(), it.lastModified()) }
      ?: emptyList()
  } catch (e: Exception) {
    Log.e(TAG, "ZetariaFiles list error: ${e.stackTraceToString()}")
    emptyList()
  }

/** Miniatura de una imagen o de un fotograma de video (null si no se puede leer, p. ej. archivo cifrado) */
expect fun zetariaThumb(path: String, px: Int, video: Boolean): ImageBitmap?

@Composable
fun ZetariaFilesView(appFilesCountAndSize: MutableState<Pair<Int, Long>>) {
  val files = remember { mutableStateListOf<ZFile>() }
  val selected = remember { mutableStateListOf<String>() }
  val loading = remember { mutableStateOf(true) }
  val sortBySize = remember { mutableStateOf(true) }
  val filter = remember { mutableStateOf<ZKind?>(null) }
  val scope = rememberCoroutineScope()

  suspend fun load() {
    val list = withContext(Dispatchers.IO) { zListFiles() }
    files.clear()
    files.addAll(list)
    selected.removeAll { n -> list.none { it.name == n } }
    loading.value = false
  }
  LaunchedEffect(Unit) { load() }

  val visible = files
    .filter { filter.value == null || zKindOf(it.name) == filter.value }
    .let { l -> if (sortBySize.value) l.sortedByDescending { it.size } else l.sortedByDescending { it.modified } }
  val selectedSize = files.filter { it.name in selected }.sumOf { it.size }
  val allSelected = visible.isNotEmpty() && visible.all { it.name in selected }

  fun deleteSelected() {
    val names = selected.toList()
    if (names.isEmpty()) return
    AlertManager.shared.showAlertDialog(
      title = ztF("¿Eliminar ${names.size} archivo(s)?", "Delete ${names.size} file(s)?"),
      text = ztF(
        "Se liberarán ${formatBytes(selectedSize)}. Los mensajes que usan estos archivos mostrarán el archivo como no disponible. No elimines archivos que se estén enviando ahora mismo.",
        "This frees ${formatBytes(selectedSize)}. Messages using these files will show the file as unavailable. Don't delete files that are being sent right now."
      ),
      confirmText = generalGetString(MR.strings.delete_verb),
      onConfirm = {
        scope.launch {
          withContext(Dispatchers.IO) {
            names.forEach { n ->
              try {
                File(appFilesDir, n).delete()
              } catch (e: Exception) {
                Log.e(TAG, "ZetariaFiles delete error: ${e.stackTraceToString()}")
              }
            }
          }
          clearImageCaches()
          appFilesCountAndSize.value = directoryFileCountAndSize(appFilesDir.absolutePath)
          selected.clear()
          load()
        }
      },
      destructive = true
    )
  }

  Box(Modifier.fillMaxSize()) {
    LazyColumnWithScrollBar(contentPadding = PaddingValues(bottom = if (selected.isNotEmpty()) 96.dp else 16.dp)) {
      item { AppBarTitle(ztF("Archivos pesados", "Large files")) }
      item {
        Column(Modifier.padding(horizontal = DEFAULT_PADDING)) {
          val total = files.sumOf { it.size }
          Text(
            if (loading.value) ztF("Leyendo archivos…", "Reading files…")
            else if (files.isEmpty()) ztF("No hay archivos guardados.", "No saved files.")
            else ztF("${files.size} archivos · ${formatBytes(total)} en total", "${files.size} files · ${formatBytes(total)} total"),
            color = MaterialTheme.colors.secondary
          )
          Spacer(Modifier.height(8.dp))
          Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZChip(ztF("Todo", "All"), filter.value == null) { filter.value = null }
            ZChip(ztF("Videos", "Videos"), filter.value == ZKind.VIDEO) { filter.value = ZKind.VIDEO }
            ZChip(ztF("Imágenes", "Images"), filter.value == ZKind.IMAGE) { filter.value = ZKind.IMAGE }
            ZChip(ztF("Audio", "Audio"), filter.value == ZKind.AUDIO) { filter.value = ZKind.AUDIO }
            ZChip(ztF("Otros", "Other"), filter.value == ZKind.OTHER) { filter.value = ZKind.OTHER }
          }
          Spacer(Modifier.height(8.dp))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ZChip(
              if (sortBySize.value) ztF("Orden: más pesados", "Sort: largest") else ztF("Orden: más recientes", "Sort: newest"),
              false
            ) { sortBySize.value = !sortBySize.value }
            TextButton(onClick = {
              if (allSelected) selected.clear() else { selected.clear(); selected.addAll(visible.map { it.name }) }
            }, enabled = visible.isNotEmpty()) {
              Text(if (allSelected) ztF("Quitar selección", "Clear selection") else ztF("Seleccionar todo", "Select all"))
            }
          }
        }
      }
      items(visible, key = { it.name }) { f ->
        ZFileRow(f, f.name in selected) {
          if (f.name in selected) selected.remove(f.name) else selected.add(f.name)
        }
      }
    }
    if (selected.isNotEmpty()) {
      Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = DEFAULT_PADDING, end = DEFAULT_PADDING, bottom = 24.dp)) {
        Button(
          onClick = { deleteSelected() },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red, contentColor = Color.White)
        ) {
          Text(ztF("Eliminar ${selected.size} · ${formatBytes(selectedSize)}", "Delete ${selected.size} · ${formatBytes(selectedSize)}"))
        }
      }
    }
  }
}

@Composable
private fun ZChip(text: String, on: Boolean, click: () -> Unit) {
  Box(
    Modifier
      .clip(RoundedCornerShape(50))
      .background(if (on) MaterialTheme.colors.primary.copy(alpha = 0.25f) else MaterialTheme.colors.secondary.copy(alpha = 0.12f))
      .clickable(onClick = click)
      .padding(horizontal = 12.dp, vertical = 6.dp)
  ) {
    Text(text, style = MaterialTheme.typography.body2, color = if (on) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground)
  }
}

@Composable
private fun ZFileRow(f: ZFile, checked: Boolean, onToggle: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = DEFAULT_PADDING, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = checked, onCheckedChange = { onToggle() })
    Spacer(Modifier.width(8.dp))
    ZThumb(f)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
      val date = try {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.modified))
      } catch (e: Exception) { "" }
      Text("${formatBytes(f.size)} · $date", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary)
    }
  }
}

@Composable
private fun ZThumb(f: ZFile) {
  val kind = zKindOf(f.name)
  val bmp = remember(f.name) { mutableStateOf<ImageBitmap?>(null) }
  if (kind == ZKind.IMAGE || kind == ZKind.VIDEO) {
    LaunchedEffect(f.name) {
      bmp.value = withContext(Dispatchers.IO) {
        try {
          zetariaThumb(File(appFilesDir, f.name).absolutePath, 128, kind == ZKind.VIDEO)
        } catch (e: Throwable) {
          null
        }
      }
    }
  }
  Box(
    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colors.secondary.copy(alpha = 0.15f)),
    contentAlignment = Alignment.Center
  ) {
    val b = bmp.value
    if (b != null) {
      Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
      Text(kind.emoji, fontSize = 22.sp)
    }
  }
}
