package %%PKG%%

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
%%IMPORTS_ANDROID%%

// ---------------------------------------------------------------------------
// Motor de reproducción: un solo audio a la vez; el estado vive aquí para que
// sobreviva al desplazamiento de la lista del chat.
// ---------------------------------------------------------------------------
internal object ZetariaAudioEngine {
  var currentKey by mutableStateOf<String?>(null)
  var playing by mutableStateOf(false)
  var loading by mutableStateOf(false)
  var positionMs by mutableStateOf(0)
  var durationMs by mutableStateOf(0)
  var failedKey by mutableStateOf<String?>(null)

  private var player: MediaPlayer? = null
  private var tmpFile: File? = null
  private var job: Job? = null
  private val handler = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val ticker = object : Runnable {
    override fun run() {
      val p = player ?: return
      if (playing) {
        try { positionMs = p.currentPosition } catch (e: Exception) {}
        handler.postDelayed(this, 150)
      }
    }
  }

  private fun tmpDir() = File(androidAppContext.cacheDir, "zetaria_audio")

  init {
    // restos de copias descifradas de una sesión anterior
    try { tmpDir().deleteRecursively() } catch (e: Exception) {}
  }

  fun toggle(key: String, path: String, args: CryptoFileArgs?) {
    if (currentKey == key) {
      if (loading) return
      val p = player
      if (p != null) {
        try {
          if (playing) {
            p.pause()
            playing = false
          } else {
            p.start()
            playing = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
          }
        } catch (e: Exception) {
          stop()
          failedKey = key
        }
        return
      }
    }
    start(key, path, args)
  }

  private fun start(key: String, path: String, args: CryptoFileArgs?) {
    stop()
    %%STOP_VOICE%%
    currentKey = key
    loading = true
    failedKey = null
    job = scope.launch {
      var tmp: File? = null
      try {
        val src: String = withContext(Dispatchers.IO) {
          if (args == null) {
            path
          } else {
            val dir = tmpDir()
            dir.mkdirs()
            val t = File.createTempFile("zet", ".bin", dir)
            tmp = t
            %%DECRYPT%%
            t.absolutePath
          }
        }
        tmpFile = tmp
        val mp = MediaPlayer()
        player = mp
        FileInputStream(src).use { mp.setDataSource(it.fd) }
        mp.setOnPreparedListener { m ->
          if (player === m) {
            durationMs = m.duration
            loading = false
            m.start()
            playing = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
          }
        }
        mp.setOnCompletionListener { m ->
          if (player === m) {
            playing = false
            positionMs = 0
            try { m.seekTo(0) } catch (e: Exception) {}
          }
        }
        mp.setOnErrorListener { m, _, _ ->
          if (player === m) {
            val k = currentKey
            stop()
            failedKey = k
          }
          true
        }
        mp.prepareAsync()
      } catch (e: CancellationException) {
        try { tmp?.delete() } catch (x: Exception) {}
        throw e
      } catch (e: Exception) {
        try { tmp?.delete() } catch (x: Exception) {}
        stop()
        failedKey = key
      }
    }
  }

  fun seekTo(key: String, ms: Int) {
    if (currentKey == key && !loading) {
      try { player?.seekTo(ms) } catch (e: Exception) {}
      positionMs = ms
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    handler.removeCallbacks(ticker)
    try { player?.release() } catch (e: Exception) {}
    player = null
    try { tmpFile?.delete() } catch (e: Exception) {}
    tmpFile = null
    playing = false
    loading = false
    currentKey = null
    positionMs = 0
    durationMs = 0
  }
}

// ---------------------------------------------------------------------------
// Utilidades
// ---------------------------------------------------------------------------
private fun zetariaLoadedAudioPath(file: CIFile?): String? {
  if (file == null || !file.loaded) return null
  val name = file.fileSource?.filePath ?: return null
  val p = getAppFilePath(name)
  return if (File(p).exists()) p else null
}

private fun zetariaFmtTime(ms: Int): String {
  val total = (ms / 1000).coerceAtLeast(0)
  return "%d:%02d".format(total / 60, total % 60)
}

private fun zetariaFmtSize(bytes: Long): String = when {
  bytes < 1024L -> "$bytes B"
  bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
  else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

actual fun zetariaAudioAvailable(file: CIFile?): Boolean =
  zetariaIsAudioFile(file) && zetariaLoadedAudioPath(file) != null

// ---------------------------------------------------------------------------
// Vista: ícono de archivo de audio + nombre + botón play/pausa + barra + tiempos.
// (La nota de voz de SimpleX es un círculo con onda; esta lleva el ícono de archivo.)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun ZetariaAudioFileView(file: CIFile, onLongClick: () -> Unit) {
  val path = zetariaLoadedAudioPath(file) ?: return
  val args = file.fileSource?.cryptoArgs
  val engine = ZetariaAudioEngine
  val active = engine.currentKey == path
  val playing = active && engine.playing
  val loading = active && engine.loading
  val failed = engine.failedKey == path
  val duration = if (active) engine.durationMs else 0
  val position = if (active) engine.positionMs else 0
  var dragPos by remember { mutableStateOf<Float?>(null) }
  val primary = MaterialTheme.colors.primary

  Row(
    Modifier
      .width(250.dp)
      .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 18.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      Modifier
        .size(46.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(primary.copy(alpha = 0.18f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        painterResource(MR.images.zet_audio_file),
        contentDescription = null,
        tint = primary,
        modifier = Modifier.size(30.dp)
      )
    }
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text(
        file.fileName,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.onSurface
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(primary)
            .combinedClickable(
              onClick = { engine.toggle(path, path, args) },
              onLongClick = onLongClick
            ),
          contentAlignment = Alignment.Center
        ) {
          if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
          } else {
            Icon(
              painterResource(if (playing) MR.images.zet_pause else MR.images.zet_play),
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(22.dp)
            )
          }
        }
        Slider(
          value = dragPos ?: (if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f),
          onValueChange = { if (active && !loading && duration > 0) dragPos = it },
          onValueChangeFinished = {
            val d = dragPos
            if (d != null) engine.seekTo(path, (d * duration).toInt())
            dragPos = null
          },
          colors = SliderDefaults.colors(
            thumbColor = primary,
            activeTrackColor = primary,
            inactiveTrackColor = primary.copy(alpha = 0.25f)
          ),
          modifier = Modifier.weight(1f).height(34.dp)
        )
      }
      val sizeText = zetariaFmtSize(file.fileSize)
      if (failed) {
        Text(
          stringResource(MR.strings.zetaria_audio_error),
          fontSize = 11.sp,
          maxLines = 2,
          color = MaterialTheme.colors.error
        )
      } else {
        val times = if (duration > 0) "${zetariaFmtTime(position)} / ${zetariaFmtTime(duration)}" else zetariaFmtTime(0)
        Text(
          "$times  ·  $sizeText",
          fontSize = 11.sp,
          maxLines = 1,
          color = MaterialTheme.colors.secondary
        )
      }
    }
  }
}
