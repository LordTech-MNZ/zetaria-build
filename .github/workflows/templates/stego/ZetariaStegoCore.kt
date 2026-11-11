package chat.simplex.common.views.chat.item

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ===========================================================================
// Núcleo de Esteganografía de Zetaria (Kotlin puro, sin Android).
//   1) César y Morse (clásicos, sin seguridad real: son didácticos)
//   2) Imagen <-> audio: códec propio "ZIC2" (Zetaria Image Codec v2)
//        - Modo COLOR: la imagen se dibuja en el espectrograma con los tres canales
//          (R, G y B) en tres bandas de frecuencia independientes. Cada columna del
//          dibujo es una trama de 1024 muestras con frecuencias exactas del DFT, así
//          que la decodificación recupera la imagen sin desenfoque ni pérdida de color.
//        - Modo EXACTO: la imagen completa (PNG/JPEG original) viaja en los 4 bits
//          menos significativos de las muestras con una cabecera y un CRC32. Se
//          recupera píxel a píxel, sin pérdida, y muy rápido (no hace falta FFT).
//   3) Contenedor oculto en los bits menos significativos (LSB) de audio PCM16 o de
//      píxeles, cifrado con AES-256-GCM (clave: PBKDF2-HMAC-SHA256, 200 000
//      iteraciones) y repartido por posiciones pseudoaleatorias derivadas de la
//      clave. Sin la contraseña, el contenido es indistinguible del ruido.
//   4) Paquetes "ZET1" para códigos QR y de barras: trocean cualquier imagen, archivo
//      o video en partes con nombre, tamaño y CRC32 para poder reensamblarlo.
//   5) Decodificador de Morse por audio: detecta el tono, mide las duraciones y
//      reconstruye el texto sin necesidad de saber la velocidad ni la frecuencia.
// ===========================================================================

// ---------------------------------------------------------------------------
// César
// ---------------------------------------------------------------------------
class ZxAlphabet(val name: String, val letters: String)

val ZX_ALPHABETS = listOf(
  ZxAlphabet("Inglés (26 letras)", "abcdefghijklmnopqrstuvwxyz"),
  ZxAlphabet("Español (27 letras, con Ñ)", "abcdefghijklmnñopqrstuvwxyz")
)

fun zxCaesar(text: String, shift: Int, alphabet: String, decode: Boolean): String {
  val n = alphabet.length
  val s = (((if (decode) -shift else shift) % n) + n) % n
  val sb = StringBuilder()
  for (ch in text) {
    val idx = alphabet.indexOf(ch.lowercaseChar())
    if (idx >= 0) {
      val r = alphabet[(idx + s) % n]
      sb.append(if (ch.isUpperCase()) r.uppercaseChar() else r)
    } else {
      sb.append(ch)
    }
  }
  return sb.toString()
}

// ---------------------------------------------------------------------------
// Morse
// ---------------------------------------------------------------------------
private val ZX_MORSE: Map<Char, String> = mapOf(
  'a' to ".-", 'b' to "-...", 'c' to "-.-.", 'd' to "-..", 'e' to ".", 'f' to "..-.", 'g' to "--.", 'h' to "....",
  'i' to "..", 'j' to ".---", 'k' to "-.-", 'l' to ".-..", 'm' to "--", 'n' to "-.", 'o' to "---", 'p' to ".--.",
  'q' to "--.-", 'r' to ".-.", 's' to "...", 't' to "-", 'u' to "..-", 'v' to "...-", 'w' to ".--", 'x' to "-..-",
  'y' to "-.--", 'z' to "--..", 'ñ' to "--.--",
  '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....",
  '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
  '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.", '!' to "-.-.--", '/' to "-..-.",
  '(' to "-.--.", ')' to "-.--.-", '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
  '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '"' to ".-..-.", '$' to "...-..-", '@' to ".--.-."
)

private val ZX_MORSE_REV: Map<String, Char> = ZX_MORSE.entries.associate { it.value to it.key }

private fun zxStripAccent(c: Char): Char = when (c) {
  'á', 'à', 'â', 'ä', 'ã' -> 'a'
  'é', 'è', 'ê', 'ë' -> 'e'
  'í', 'ì', 'î', 'ï' -> 'i'
  'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
  'ú', 'ù', 'û', 'ü' -> 'u'
  'ç' -> 'c'
  else -> c
}

/** Letras separadas por un espacio y palabras por " / ". Los caracteres sin código se omiten. */
fun zxMorseEncode(text: String): String {
  val words = text.trim().split(Regex("\\s+"))
  val out = ArrayList<String>()
  for (w in words) {
    val letters = ArrayList<String>()
    for (ch in w) {
      val code = ZX_MORSE[zxStripAccent(ch.lowercaseChar())]
      if (code != null) letters.add(code)
    }
    if (letters.isNotEmpty()) out.add(letters.joinToString(" "))
  }
  return out.joinToString(" / ")
}

/** Acepta . - (también • · – — −), "/" o "|" entre palabras, o 3 o más espacios. Lo desconocido sale como "?" */
fun zxMorseDecode(code: String): String {
  var s = code.trim()
    .replace('•', '.').replace('·', '.')
    .replace('–', '-').replace('—', '-').replace('−', '-')
  s = s.replace(Regex("\\s{3,}"), " / ").replace("|", " / ")
  val sb = StringBuilder()
  for (tok in s.split(Regex("\\s+"))) {
    if (tok.isEmpty()) continue
    if (tok == "/") {
      if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
    } else {
      sb.append(ZX_MORSE_REV[tok] ?: '?')
    }
  }
  return sb.toString().trim().uppercase()
}

/** Audio del Morse: tonos de 650 Hz, 16 bits, mono, 22 050 Hz */
fun zxMorsePcm(code: String, wpm: Int = 16): ShortArray {
  val rate = 22050
  val unit = (1.2 / wpm * rate).toInt().coerceAtLeast(40)
  val out = ArrayList<Short>()
  fun silence(units: Int) { repeat(units * unit) { out.add(0) } }
  fun tone(units: Int) {
    val len = units * unit
    val fade = min(110, len / 4)
    for (i in 0 until len) {
      var a = sin(2 * PI * 650.0 * i / rate)
      if (i < fade) a *= i.toDouble() / fade
      if (i >= len - fade) a *= (len - i).toDouble() / fade
      out.add((a * 16000).toInt().toShort())
    }
  }
  for (tok in code.trim().split(Regex("\\s+"))) {
    if (tok.isEmpty()) continue
    if (tok == "/") {
      silence(4) // 3 del espacio entre letras ya puesto + 4 = 7
      continue
    }
    for ((i, c) in tok.withIndex()) {
      if (c == '.') tone(1) else if (c == '-') tone(3) else continue
      if (i < tok.length - 1) silence(1)
    }
    silence(3)
  }
  return out.toShortArray()
}

// ---------------------------------------------------------------------------
// FFT (radix-2). Antes solo existía N = 2048; ahora el tamaño es un parámetro para
// que el códec de color use 1024 (dos veces más columnas por segundo) y el Morse
// reutilice 2048 para localizar el tono.
// ---------------------------------------------------------------------------
class ZxFftPlan(val n: Int) {
  private val twRe = DoubleArray(n / 2) { cos(2 * PI * it / n) }
  private val twIm = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
  private val rev = IntArray(n)

  init {
    require(n >= 2 && (n and (n - 1)) == 0) { "El tamaño de la FFT debe ser potencia de 2" }
    var bits = 0
    while ((1 shl bits) < n) bits++
    for (i in 0 until n) {
      var x = i
      var r = 0
      for (b in 0 until bits) {
        r = (r shl 1) or (x and 1)
        x = x shr 1
      }
      rev[i] = r
    }
  }

  /** Transformada directa en el sitio (e^{-j...}) */
  fun fft(re: DoubleArray, im: DoubleArray) {
    for (i in 0 until n) {
      val j = rev[i]
      if (j > i) {
        val tr = re[i]; re[i] = re[j]; re[j] = tr
        val ti = im[i]; im[i] = im[j]; im[j] = ti
      }
    }
    var size = 2
    while (size <= n) {
      val half = size / 2
      val step = n / size
      var start = 0
      while (start < n) {
        var k = 0
        for (j in start until start + half) {
          val wr = twRe[k]
          val wi = twIm[k]
          val tr = re[j + half] * wr - im[j + half] * wi
          val ti = re[j + half] * wi + im[j + half] * wr
          re[j + half] = re[j] - tr
          im[j + half] = im[j] - ti
          re[j] += tr
          im[j] += ti
          k += step
        }
        start += size
      }
      size *= 2
    }
  }
}

object ZxFft {
  const val N = 2048
  private val plan = ZxFftPlan(N)
  fun fft(re: DoubleArray, im: DoubleArray) = plan.fft(re, im)
}

internal val ZX_FFT_1024 = ZxFftPlan(1024)
internal val ZX_FFT_2048 = ZxFftPlan(2048)

// ---------------------------------------------------------------------------
// Imagen <-> audio por espectrograma (formato antiguo, solo para poder leer audios
// creados con versiones anteriores de Zetaria: 256 filas en escala de grises)
//   44 100 Hz, ventana Hann de 2048, salto de 1024. La imagen tiene 256 filas: la fila r usa el
//   bin 24 + 2*(255-r) (de ~0,5 a ~11,5 kHz; un bin libre entre filas evita interferencias).
//   Cada columna es un fotograma. La fase avanza pi*k por salto para que cada tono sea continuo.
// ---------------------------------------------------------------------------
const val ZX_SR = 44100
const val ZX_HOP = 1024
const val ZX_KB = 24
const val ZX_ROWS = 256

private fun zxHann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

/** gray: ZX_ROWS * w valores 0..255 (fila 0 = arriba). Devuelve audio mono PCM16 a 44 100 Hz. */
fun zxSpectrogramEncode(gray: IntArray, w: Int): ShortArray {
  val n = ZxFft.N
  val phi0 = DoubleArray(n / 2 + 1)
  var seed = 12345L
  for (i in phi0.indices) {
    seed = seed * 6364136223846793005L + 1442695040888963407L
    phi0[i] = ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2 * PI
  }
  val win = zxHann(n)
  val out = DoubleArray((w + 1) * ZX_HOP + n)
  val re = DoubleArray(n)
  val im = DoubleArray(n)
  for (c in 0 until w) {
    java.util.Arrays.fill(re, 0.0)
    java.util.Arrays.fill(im, 0.0)
    for (r in 0 until ZX_ROWS) {
      val a = gray[r * w + c] / 255.0
      if (a <= 0.0) continue
      val k = ZX_KB + 2 * (ZX_ROWS - 1 - r)
      val ph = phi0[k] + (if ((k.toLong() * c) % 2L == 1L) PI else 0.0)
      re[k] = a * cos(ph)
      im[k] = -a * sin(ph)
    }
    ZxFft.fft(re, im)
    val base = c * ZX_HOP
    for (i in 0 until n) out[base + i] += re[i] * win[i]
  }
  var peak = 1e-9
  for (v in out) peak = max(peak, abs(v))
  val scale = 0.9 * 32767.0 / peak
  return ShortArray(out.size) { (out[it] * scale).toInt().coerceIn(-32768, 32767).toShort() }
}

class ZxSpecImage(val w: Int, val gray: IntArray)

/** x: audio mono a 44 100 Hz (-1..1). logScale = true para audios normales (no hechos por Zetaria). */
fun zxSpectrogramDecode(x: FloatArray, logScale: Boolean): ZxSpecImage? {
  val n = ZxFft.N
  if (x.size < n) return null
  val frames = (x.size - n) / ZX_HOP + 1
  val win = zxHann(n)
  val mags = FloatArray(ZX_ROWS * frames)
  val re = DoubleArray(n)
  val im = DoubleArray(n)
  for (c in 0 until frames) {
    val base = c * ZX_HOP
    for (i in 0 until n) {
      re[i] = x[base + i] * win[i]
      im[i] = 0.0
    }
    ZxFft.fft(re, im)
    for (r in 0 until ZX_ROWS) {
      val k = ZX_KB + 2 * (ZX_ROWS - 1 - r)
      mags[r * frames + c] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
    }
  }
  val gray = IntArray(mags.size)
  if (logScale) {
    var mx = 1e-9f
    for (m in mags) if (m > mx) mx = m
    val top = 20.0 * log10(mx.toDouble())
    for (i in mags.indices) {
      val db = 20.0 * log10(mags[i].toDouble() + 1e-9)
      gray[i] = (((db - (top - 60.0)) / 60.0).coerceIn(0.0, 1.0) * 255).toInt()
    }
  } else {
    val sorted = mags.copyOf()
    sorted.sort()
    var p = sorted[min(sorted.size - 1, (sorted.size * 0.998).toInt())]
    if (p <= 0f) p = 1f
    for (i in mags.indices) gray[i] = (min(1f, mags[i] / p) * 255).toInt()
  }
  return ZxSpecImage(frames, gray)
}

// ---------------------------------------------------------------------------
// WAV
// ---------------------------------------------------------------------------
fun zxWavWrite(samples: ShortArray, channels: Int, rate: Int): ByteArray {
  val dataLen = samples.size * 2
  val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
  bb.put("RIFF".toByteArray(Charsets.ISO_8859_1))
  bb.putInt(36 + dataLen)
  bb.put("WAVE".toByteArray(Charsets.ISO_8859_1))
  bb.put("fmt ".toByteArray(Charsets.ISO_8859_1))
  bb.putInt(16)
  bb.putShort(1)
  bb.putShort(channels.toShort())
  bb.putInt(rate)
  bb.putInt(rate * channels * 2)
  bb.putShort((channels * 2).toShort())
  bb.putShort(16)
  bb.put("data".toByteArray(Charsets.ISO_8859_1))
  bb.putInt(dataLen)
  for (s in samples) bb.putShort(s)
  return bb.array()
}

class ZxWavInfo(val rate: Int, val channels: Int, val bits: Int, val format: Int, val dataStart: Int, val dataLen: Int)

private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

fun zxWavInfo(b: ByteArray): ZxWavInfo? {
  if (b.size < 44) return null
  if (String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
  var pos = 12
  var rate = 0
  var ch = 0
  var bits = 0
  var fmt = 0
  var haveFmt = false
  while (pos + 8 <= b.size) {
    val id = String(b, pos, 4, Charsets.ISO_8859_1)
    val len = le32(b, pos + 4)
    val body = pos + 8
    if (id == "fmt " && body + 16 <= b.size) {
      fmt = le16(b, body)
      ch = le16(b, body + 2)
      rate = le32(b, body + 4)
      bits = le16(b, body + 14)
      if (fmt == 0xFFFE && len >= 26 && body + 26 <= b.size) fmt = le16(b, body + 24)
      haveFmt = true
    } else if (id == "data") {
      if (!haveFmt) return null
      val avail = b.size - body
      val dl = if (len <= 0 || len > avail) avail else len
      return ZxWavInfo(rate, ch, bits, fmt, body, dl)
    }
    if (len < 0) return null
    pos = body + len + (len and 1)
  }
  return null
}

class ZxPcm(val rate: Int, val channels: Int, val samples: ShortArray)

/** Solo si es PCM de 16 bits (necesario para leer los datos ocultos tal cual) */
fun zxWavPcm16Strict(b: ByteArray): ZxPcm? {
  val i = zxWavInfo(b) ?: return null
  if (i.format != 1 || i.bits != 16 || i.channels < 1) return null
  val count = i.dataLen / 2
  val s = ShortArray(count)
  ByteBuffer.wrap(b, i.dataStart, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
  return ZxPcm(i.rate, i.channels, s)
}

/** PCM 8/16/24/32 bits o float32 -> PCM16 (para usar como portadora o para decodificar) */
fun zxWavToPcm16(b: ByteArray, maxSamples: Int): ZxPcm? {
  val i = zxWavInfo(b) ?: return null
  if (i.channels < 1) return null
  val bytesPer = i.bits / 8
  if (bytesPer < 1) return null
  val total = min(i.dataLen / bytesPer, maxSamples)
  val out = ShortArray(total)
  val bb = ByteBuffer.wrap(b, i.dataStart, i.dataLen).order(ByteOrder.LITTLE_ENDIAN)
  for (n in 0 until total) {
    val o = i.dataStart + n * bytesPer
    val v: Int = when {
      i.format == 3 && i.bits == 32 -> (bb.getFloat(o) * 32767f).toInt().coerceIn(-32768, 32767)
      i.format != 1 && i.format != 3 -> return null
      i.bits == 16 -> bb.getShort(o).toInt()
      i.bits == 8 -> ((bb.get(o).toInt() and 0xFF) - 128) shl 8
      i.bits == 24 -> (bb.get(o + 2).toInt() shl 8) or ((bb.get(o + 1).toInt() and 0xFF))
      i.bits == 32 -> bb.getInt(o) shr 16
      else -> return null
    }
    out[n] = v.toShort()
  }
  return ZxPcm(i.rate, i.channels, out)
}

/** Mezcla a mono, lleva a 44 100 Hz y limita la duración */
fun zxToMono44k(p: ZxPcm, maxSeconds: Int): FloatArray {
  val rate = if (p.rate > 0) p.rate else ZX_SR
  val frames = p.samples.size / p.channels
  val mono = FloatArray(frames)
  for (f in 0 until frames) {
    var acc = 0
    for (c in 0 until p.channels) acc += p.samples[f * p.channels + c]
    mono[f] = acc / (p.channels * 32768f)
  }
  val src = if (rate == ZX_SR) {
    mono
  } else {
    val outLen = (frames.toLong() * ZX_SR / rate).toInt()
    FloatArray(outLen) {
      val pos = it.toDouble() * rate / ZX_SR
      val i0 = pos.toInt()
      val fr = (pos - i0).toFloat()
      val a = mono[min(i0, frames - 1)]
      val b2 = mono[min(i0 + 1, frames - 1)]
      a + (b2 - a) * fr
    }
  }
  val limit = maxSeconds * ZX_SR
  return if (src.size > limit) src.copyOf(limit) else src
}

// ---------------------------------------------------------------------------
// Contenedor oculto en bits (audio PCM16 o píxeles)
// ---------------------------------------------------------------------------
interface ZxBits {
  val size: Int
  fun get(i: Int): Int
  fun set(i: Int, bit: Int)
}

/** 1 bit por muestra: el bit menos significativo */
class ZxShortBits(val a: ShortArray) : ZxBits {
  override val size: Int get() = a.size
  override fun get(i: Int): Int = a[i].toInt() and 1
  override fun set(i: Int, bit: Int) {
    a[i] = ((a[i].toInt() and -2) or bit).toShort()
  }
}

/** 3 bits por píxel: el menos significativo de R, G y B (el alfa no se toca) */
class ZxPixelBits(val px: IntArray) : ZxBits {
  override val size: Int get() = px.size * 3
  private fun shift(i: Int) = 16 - 8 * (i % 3)
  override fun get(i: Int): Int = (px[i / 3] ushr shift(i)) and 1
  override fun set(i: Int, bit: Int) {
    val s = shift(i)
    px[i / 3] = (px[i / 3] and (1 shl s).inv()) or (bit shl s)
  }
}

const val ZX_T_FILE = 1
const val ZX_T_TEXT = 2

class ZxPayload(val type: Int, val name: String, val data: ByteArray)
class ZxStegoException(msg: String) : Exception(msg)

private const val ZX_HDR_BITS = 28 * 8 // sal (16) + nonce (12), en claro y consecutivos
private const val ZX_PBKDF2_ITER = 200_000
private const val ZX_MIN_BITS = ZX_HDR_BITS + 32 + 8 * (16 + 2) + 1024

private fun zxDeriveKey(password: String, salt: ByteArray): ByteArray {
  val spec = PBEKeySpec(password.toCharArray(), salt, ZX_PBKDF2_ITER, 256)
  return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
}

private fun zxHmac(key: ByteArray, label: String): ByteArray {
  val m = Mac.getInstance("HmacSHA256")
  m.init(SecretKeySpec(key, "HmacSHA256"))
  return m.doFinal(label.toByteArray(Charsets.UTF_8))
}

private fun zxGcd(a: Long, b: Long): Long {
  var x = a
  var y = b
  while (y != 0L) {
    val t = x % y
    x = y
    y = t
  }
  return x
}

/** Posición i-ésima = (inicio + i * paso) mod región; paso coprimo con la región => es una permutación */
private class ZxLayout(val region: Int, val start: Long, val stride: Long) {
  fun pos(i: Int): Int = ZX_HDR_BITS + ((start + i.toLong() * stride) % region).toInt()
}

private fun zxLayout(totalBits: Int, key: ByteArray): ZxLayout {
  val region = totalBits - ZX_HDR_BITS
  val h = zxHmac(key, "zetaria-stego-pos")
  fun u63(o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (h[o + i].toLong() and 0xFF)
    return v and Long.MAX_VALUE
  }
  val start = u63(0) % region
  var stride = u63(8) % region
  if (stride < 1L) stride = 1L
  if (stride % 2L == 0L) stride++
  while (zxGcd(stride, region.toLong()) != 1L) stride += 2L
  return ZxLayout(region, start, stride)
}

/** Bytes útiles aproximados que caben (sin contar el nombre del archivo) */
fun zxCapacityBytes(totalBits: Int): Int = max(0, (totalBits - ZX_HDR_BITS - 32) / 8 - 16 - 2)

fun zxEmbed(bits: ZxBits, password: String, type: Int, name: String, data: ByteArray) {
  if (bits.size < ZX_MIN_BITS) throw ZxStegoException("La portadora es demasiado pequeña")
  var nameBytes = name.toByteArray(Charsets.UTF_8)
  if (nameBytes.size > 255) nameBytes = nameBytes.copyOf(255)
  val plain = ByteArray(2 + nameBytes.size + data.size)
  plain[0] = type.toByte()
  plain[1] = nameBytes.size.toByte()
  nameBytes.copyInto(plain, 2)
  data.copyInto(plain, 2 + nameBytes.size)
  val salt = ByteArray(16)
  val nonce = ByteArray(12)
  val rnd = SecureRandom()
  rnd.nextBytes(salt)
  rnd.nextBytes(nonce)
  val key = zxDeriveKey(password, salt)
  val cipher = Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
  val ct = cipher.doFinal(plain)
  val region = bits.size - ZX_HDR_BITS
  val needBits = 32L + ct.size.toLong() * 8
  if (needBits > region) {
    val needSamples = needBits + ZX_HDR_BITS
    throw ZxStegoException("No cabe: hacen falta ${(needBits + 7) / 8} bytes de capacidad y la portadora ofrece ${max(0, (region - 32) / 8)}. Necesitas una portadora con al menos $needSamples bits (muestras o canales de píxel).")
  }
  // cabecera en claro (sal + nonce): parece ruido
  for (i in 0 until 28) {
    val v = (if (i < 16) salt[i] else nonce[i - 16]).toInt() and 0xFF
    for (b in 0 until 8) bits.set(i * 8 + b, (v shr (7 - b)) and 1)
  }
  val lay = zxLayout(bits.size, key)
  val mask = zxHmac(key, "zetaria-stego-len")
  var masked = 0
  for (i in 0 until 4) masked = (masked shl 8) or (((ct.size ushr (8 * (3 - i))) and 0xFF) xor (mask[i].toInt() and 0xFF))
  var j = 0
  for (i in 3 downTo 0) {
    val v = (masked ushr (8 * i)) and 0xFF
    for (b in 7 downTo 0) {
      bits.set(lay.pos(j), (v shr b) and 1)
      j++
    }
  }
  for (byte in ct) {
    val v = byte.toInt() and 0xFF
    for (b in 7 downTo 0) {
      bits.set(lay.pos(j), (v shr b) and 1)
      j++
    }
  }
}

fun zxExtract(bits: ZxBits, password: String): ZxPayload {
  val bad = "Contraseña incorrecta o no hay datos ocultos en este archivo"
  if (bits.size < ZX_MIN_BITS) throw ZxStegoException(bad)
  val salt = ByteArray(16)
  val nonce = ByteArray(12)
  for (i in 0 until 28) {
    var v = 0
    for (b in 0 until 8) v = (v shl 1) or bits.get(i * 8 + b)
    if (i < 16) salt[i] = v.toByte() else nonce[i - 16] = v.toByte()
  }
  val key = zxDeriveKey(password, salt)
  val lay = zxLayout(bits.size, key)
  val mask = zxHmac(key, "zetaria-stego-len")
  var j = 0
  var clen = 0
  for (i in 0 until 4) {
    var v = 0
    for (b in 0 until 8) {
      v = (v shl 1) or bits.get(lay.pos(j))
      j++
    }
    clen = (clen shl 8) or ((v xor (mask[i].toInt() and 0xFF)) and 0xFF)
  }
  val region = bits.size - ZX_HDR_BITS
  val avail = (region - 32) / 8
  if (clen < 16 + 2 || clen < 0 || clen > avail) throw ZxStegoException(bad)
  val ct = ByteArray(clen)
  for (i in 0 until clen) {
    var v = 0
    for (b in 0 until 8) {
      v = (v shl 1) or bits.get(lay.pos(j))
      j++
    }
    ct[i] = v.toByte()
  }
  val plain = try {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.doFinal(ct)
  } catch (e: Exception) {
    throw ZxStegoException(bad)
  }
  if (plain.size < 2) throw ZxStegoException(bad)
  val nl = plain[1].toInt() and 0xFF
  if (2 + nl > plain.size) throw ZxStegoException(bad)
  val name = String(plain, 2, nl, Charsets.UTF_8)
  return ZxPayload(plain[0].toInt() and 0xFF, name, plain.copyOfRange(2 + nl, plain.size))
}

// ===========================================================================
// UTILIDADES: CRC32, Base64 y números pseudoaleatorios deterministas
// (se implementan aquí para no depender de java.util.zip ni de android.util.Base64,
//  así el mismo código sirve en Android y en escritorio)
// ===========================================================================
private val ZX_CRC_TABLE = IntArray(256) { i ->
  var c = i
  for (k in 0 until 8) c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
  c
}

fun zxCrc32(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
  var c = -1
  val end = min(to, data.size)
  var i = max(0, from)
  while (i < end) {
    c = ZX_CRC_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
    i++
  }
  return c.inv()
}

object ZxBase64 {
  private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  private val REV = IntArray(128) { -1 }.also { arr ->
    for (i in ALPHA.indices) arr[ALPHA[i].code] = i
  }

  fun encode(src: ByteArray): String {
    val sb = StringBuilder((src.size + 2) / 3 * 4 + 4)
    var i = 0
    while (i + 2 < src.size) {
      val a = src[i].toInt() and 0xFF
      val b = src[i + 1].toInt() and 0xFF
      val c = src[i + 2].toInt() and 0xFF
      sb.append(ALPHA[a ushr 2])
      sb.append(ALPHA[((a and 3) shl 4) or (b ushr 4)])
      sb.append(ALPHA[((b and 15) shl 2) or (c ushr 6)])
      sb.append(ALPHA[c and 63])
      i += 3
    }
    when (src.size - i) {
      1 -> {
        val a = src[i].toInt() and 0xFF
        sb.append(ALPHA[a ushr 2])
        sb.append(ALPHA[(a and 3) shl 4])
        sb.append("==")
      }
      2 -> {
        val a = src[i].toInt() and 0xFF
        val b = src[i + 1].toInt() and 0xFF
        sb.append(ALPHA[a ushr 2])
        sb.append(ALPHA[((a and 3) shl 4) or (b ushr 4)])
        sb.append(ALPHA[(b and 15) shl 2])
        sb.append('=')
      }
    }
    return sb.toString()
  }

  fun decode(text: String): ByteArray? {
    val t = StringBuilder(text.length)
    for (ch in text) if (!ch.isWhitespace() && ch != '=') t.append(ch)
    val s = t.toString()
    if (s.isEmpty()) return ByteArray(0)
    if (s.length % 4 == 1) return null
    val out = ByteArray(s.length * 3 / 4)
    var oi = 0
    var i = 0
    while (i < s.length) {
      val c0 = if (i < s.length) idx(s[i]) else -1
      val c1 = if (i + 1 < s.length) idx(s[i + 1]) else -1
      val c2 = if (i + 2 < s.length) idx(s[i + 2]) else -1
      val c3 = if (i + 3 < s.length) idx(s[i + 3]) else -1
      if (c0 < 0 || c1 < 0) return null
      out[oi++] = (((c0 shl 2) or (c1 ushr 4)) and 0xFF).toByte()
      if (c2 >= 0) {
        out[oi++] = ((((c1 and 15) shl 4) or (c2 ushr 2)) and 0xFF).toByte()
        if (c3 >= 0) out[oi++] = ((((c2 and 3) shl 6) or c3) and 0xFF).toByte()
      }
      i += 4
    }
    return if (oi == out.size) out else out.copyOf(oi)
  }

  private fun idx(c: Char): Int = if (c.code < 128) REV[c.code] else -1
}

internal fun zxSplitMix(x: Long): Long {
  var z = x + -7046029254386353131L
  z = (z xor (z ushr 30)) * -4658895280553007687L
  z = (z xor (z ushr 27)) * -7723592293110705685L
  return z xor (z ushr 31)
}

// ===========================================================================
// CÓDEC DE IMAGEN <-> AUDIO  "ZIC2"
// ===========================================================================
const val ZX2_RATE = 44100
const val ZX2_N = 1024
const val ZX2_HOP = 1024
const val ZX2_BITS = 4          // bits útiles por muestra (los 4 menos significativos)
const val ZX2_BASE_BIN = 6      // primer bin usado (~258 Hz)
const val ZX2_GUARD = 4         // bins de separación entre los canales R, G y B
const val ZX2_MAX_BIN = 500
const val ZX2_MAX_ROWS = 162
const val ZX2_HDR_REGION = 512  // muestras reservadas para la cabecera
const val ZX2_HDR_BYTES = 40

const val ZX2_MODE_LOSSLESS = 1
const val ZX2_MODE_SPECTRO = 2

const val ZX2_CODEC_PNG = 0
const val ZX2_CODEC_JPEG = 1
const val ZX2_CODEC_WEBP = 2

const val ZX2_FLAG_VISUAL = 1

/** Bin de frecuencia de la fila `row` del canal `ch` (0 = R, 1 = G, 2 = B) */
fun zx2Bin(ch: Int, row: Int, rows: Int): Int = ZX2_BASE_BIN + ch * (rows + ZX2_GUARD) + row

/** Filas máximas que caben en el espectro sin pasar de ZX2_MAX_BIN bins */
fun zx2MaxRows(): Int = (ZX2_MAX_BIN - ZX2_BASE_BIN - 2 * ZX2_GUARD + 1) / 3

/** Columnas por segundo del códec de color */
fun zx2ColumnsPerSecond(): Double = ZX2_RATE.toDouble() / ZX2_N

class ZxImageAudio(
  val samples: ShortArray,
  val rate: Int,
  val mode: Int,
  val width: Int,
  val height: Int,
  val payloadSize: Int,
  val rows: Int,
  val cols: Int
) {
  val seconds: Double get() = samples.size.toDouble() / rate
}

class ZxImageAudioInfo(
  val mode: Int,
  val codec: Int,
  val flags: Int,
  val width: Int,
  val height: Int,
  val rows: Int,
  val cols: Int,
  val payloadLen: Int,
  val crc: Int,
  val valueScale: Float
)

// --- lectura/escritura de nibbles repartidos por una permutación determinista ----
private class ZxNibbleReader(val a: ShortArray, val base: Int, val region: Int, seed: Long) {
  private val lay = ZxNibbleLayout(region, seed)
  fun nibble(q: Int): Int = a[base + lay.sample(q)].toInt() and 0xF
  fun byteAt(q: Int): Int = (nibble(q * 2) shl 4) or nibble(q * 2 + 1)
}

private class ZxNibbleWriter(val a: ShortArray, val base: Int, val region: Int, seed: Long) {
  private val lay = ZxNibbleLayout(region, seed)
  fun putNibble(q: Int, v: Int) {
    val i = base + lay.sample(q)
    a[i] = ((a[i].toInt() and 0xF0) or (v and 0x0F)).toShort()
  }
  fun putByte(q: Int, v: Int) {
    putNibble(q * 2, (v ushr 4) and 0x0F)
    putNibble(q * 2 + 1, v and 0x0F)
  }
}

private class ZxNibbleLayout(val n: Int, seed: Long) {
  private val start: Int
  private val stride: Int

  init {
    require(n > 3) { "región demasiado pequeña" }
    val s = zxSplitMix(seed)
    start = ((s ushr 1) % n).toInt()
    var st = ((zxSplitMix(s) ushr 1) % n).toInt()
    if (st < 1) st = 1
    while (zxGcd(st.toLong(), n.toLong()) != 1L) {
      st += 1
      if (st >= n) st = 1
    }
    stride = st
  }

  fun sample(i: Int): Int = ((start.toLong() + i.toLong() * stride) % n).toInt()
}

private const val ZX2_SEED_HDR = 0x5A17C0DEL
private const val ZX2_SEED_PAYLOAD = 0x2E1A7F53L

private fun zx2Put16(b: ByteArray, o: Int, v: Int) {
  b[o] = (v ushr 8).toByte()
  b[o + 1] = v.toByte()
}

private fun zx2Put32(b: ByteArray, o: Int, v: Int) {
  b[o] = (v ushr 24).toByte()
  b[o + 1] = (v ushr 16).toByte()
  b[o + 2] = (v ushr 8).toByte()
  b[o + 3] = v.toByte()
}

private fun zx2Get16(b: ByteArray, o: Int): Int =
  ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

private fun zx2Get32(b: ByteArray, o: Int): Int =
  ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
    ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

private fun zx2Header(
  mode: Int, codec: Int, flags: Int, width: Int, height: Int,
  rows: Int, cols: Int, payloadLen: Int, crc: Int, valueScale: Float
): ByteArray {
  val b = ByteArray(ZX2_HDR_BYTES)
  b[0] = 'Z'.code.toByte(); b[1] = 'I'.code.toByte(); b[2] = 'C'.code.toByte(); b[3] = '2'.code.toByte()
  b[4] = 2
  b[5] = mode.toByte()
  b[6] = codec.toByte()
  b[7] = flags.toByte()
  zx2Put16(b, 8, width)
  zx2Put16(b, 10, height)
  zx2Put16(b, 12, rows)
  zx2Put16(b, 14, cols)
  zx2Put32(b, 16, payloadLen)
  zx2Put32(b, 20, crc)
  zx2Put32(b, 24, valueScale.toRawBits())
  return b
}

private fun zx2WriteHeader(samples: ShortArray, hdr: ByteArray) {
  if (samples.size < ZX2_HDR_REGION) return
  val w = ZxNibbleWriter(samples, 0, ZX2_HDR_REGION, ZX2_SEED_HDR)
  for (i in hdr.indices) w.putByte(i, hdr[i].toInt() and 0xFF)
}

/** Devuelve la cabecera del códec, o null si el audio no lo lleva (o está dañado) */
fun zxImageAudioInfo(samples: ShortArray): ZxImageAudioInfo? {
  if (samples.size < ZX2_HDR_REGION + 64) return null
  val r = ZxNibbleReader(samples, 0, ZX2_HDR_REGION, ZX2_SEED_HDR)
  val b = ByteArray(ZX2_HDR_BYTES)
  for (i in b.indices) b[i] = r.byteAt(i).toByte()
  if (b[0] != 'Z'.code.toByte() || b[1] != 'I'.code.toByte() || b[2] != 'C'.code.toByte() || b[3] != '2'.code.toByte()) return null
  if (b[4].toInt() != 2) return null
  val mode = b[5].toInt() and 0xFF
  if (mode != ZX2_MODE_LOSSLESS && mode != ZX2_MODE_SPECTRO) return null
  val rows = zx2Get16(b, 12)
  val cols = zx2Get16(b, 14)
  if (rows < 0 || rows > ZX2_MAX_ROWS || cols < 0) return null
  if (mode == ZX2_MODE_SPECTRO && (rows <= 0 || cols <= 0)) return null
  val payloadLen = zx2Get32(b, 16)
  if (payloadLen < 0) return null
  return ZxImageAudioInfo(
    mode = mode,
    codec = b[6].toInt() and 0xFF,
    flags = b[7].toInt() and 0xFF,
    width = zx2Get16(b, 8),
    height = zx2Get16(b, 10),
    rows = rows,
    cols = cols,
    payloadLen = payloadLen,
    crc = zx2Get32(b, 20),
    valueScale = Float.fromBits(zx2Get32(b, 24))
  )
}

/** Muestras necesarias (a 44 100 Hz) para llevar `payloadBytes` bytes en modo exacto */
fun zxImageAudioSamples(payloadBytes: Int): Int = ZX2_HDR_REGION + payloadBytes * 2 + 64

fun zxImageAudioSeconds(payloadBytes: Int): Double = zxImageAudioSamples(payloadBytes).toDouble() / ZX2_RATE

/** Bytes máximos que caben en un audio de `samples` muestras */
fun zxImageAudioCapacity(samples: Int): Int = max(0, (samples - ZX2_HDR_REGION - 64) / 2)

/**
 * Dibuja una imagen en color dentro del espectrograma.
 * `argb` debe tener exactamente cols * rows píxeles (0xAARRGGBB); fila 0 = arriba.
 * La primera trama (ZX2_N muestras) es un preámbulo que aloja la cabecera; la imagen
 * empieza en la trama 1, así que los bits de la cabecera no tocan ningún píxel.
 * Cada columna ocupa ZX2_N muestras y usa frecuencias exactas del DFT, así que la
 * decodificación recupera el valor de cada píxel sin desenfoque entre columnas.
 */
fun zxColorSpectrogram(argb: IntArray, cols: Int, rows: Int): ZxImageAudio {
  require(cols > 0 && rows > 0) { "Tamaño de imagen no válido" }
  require(rows <= zx2MaxRows()) { "Demasiadas filas: el máximo es ${zx2MaxRows()}" }
  require(argb.size >= cols * rows) { "Faltan píxeles" }
  val n = ZX2_N
  val frames = cols + 1
  val total = frames * n
  val acc = DoubleArray(total)
  val re = DoubleArray(n)
  val im = DoubleArray(n)
  // fase fija por bin: mantiene la continuidad entre tramas y reparte los picos
  val psi = DoubleArray(n / 2)
  var seed = 0x2545F4914F6CDD1DL
  for (i in psi.indices) {
    seed = zxSplitMix(seed)
    psi[i] = ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 * PI
  }
  val baseAmp = 0.30
  val twoPi = 2.0 * PI
  for (fi in 0 until frames) {
    val c = if (fi == 0) 0 else fi - 1
    java.util.Arrays.fill(re, 0.0)
    java.util.Arrays.fill(im, 0.0)
    var any = false
    for (ch in 0 until 3) {
      val shift = 16 - 8 * ch
      for (r in 0 until rows) {
        val v = (argb[r * cols + c] ushr shift) and 0xFF
        if (v == 0) continue
        any = true
        val k = zx2Bin(ch, r, rows)
        if (k <= 0 || k >= n / 2) continue
        val amp = v / 255.0 * baseAmp * n / 2.0
        val phase = twoPi * ((k.toLong() * fi) % n.toLong()).toDouble() / n + psi[k]
        val cr = cos(phase)
        val si = sin(phase)
        re[k] = amp * cr
        im[k] = amp * si
        re[n - k] = amp * cr
        im[n - k] = -amp * si
      }
    }
    if (!any) continue
    ZX_FFT_1024.fft(re, im)
    val base = fi * n
    for (i in 0 until n) acc[base + i] = re[i] / n
  }
  var peak = 1e-9
  for (v in acc) {
    val a = abs(v)
    if (a > peak) peak = a
  }
  val scale = if (peak > 1e-9) 0.90 / peak else 1.0
  val valueScale = (255.0 / (baseAmp * scale)).toFloat()
  val samples = ShortArray(total) {
    (acc[it] * scale * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
  }
  val hdr = zx2Header(
    mode = ZX2_MODE_SPECTRO,
    codec = 0,
    flags = 0,
    width = cols,
    height = rows,
    rows = rows,
    cols = cols,
    payloadLen = 0,
    crc = 0,
    valueScale = valueScale
  )
  zx2WriteHeader(samples, hdr)
  return ZxImageAudio(samples, ZX2_RATE, ZX2_MODE_SPECTRO, cols, rows, 0, rows, cols)
}

/**
 * Recupera la imagen de un audio de color creado con [zxColorSpectrogram].
 * Si `valueScale` <= 0 se ajusta solo con el percentil 99,5 de las magnitudes.
 */
fun zxColorSpectrogramDecode(samples: ShortArray, cols: Int, rows: Int, valueScale: Float): IntArray? {
  val n = ZX2_N
  if (cols <= 0 || rows <= 0 || rows > ZX2_MAX_ROWS) return null
  if (samples.size < (cols + 1) * n) return null
  val fft = ZX_FFT_1024
  val re = DoubleArray(n)
  val im = DoubleArray(n)
  val mags = FloatArray(cols * rows * 3)
  for (c in 0 until cols) {
    val base = (c + 1) * n
    for (i in 0 until n) {
      re[i] = samples[base + i] / 32768.0
      im[i] = 0.0
    }
    fft.fft(re, im)
    for (ch in 0 until 3) {
      for (r in 0 until rows) {
        val k = zx2Bin(ch, r, rows)
        val m = sqrt(re[k] * re[k] + im[k] * im[k]) / (n / 2.0)
        mags[(r * cols + c) * 3 + ch] = m.toFloat()
      }
    }
  }
  val vs = if (valueScale > 0f) valueScale.toDouble() else {
    val sorted = mags.copyOf()
    sorted.sort()
    val p = sorted[min(sorted.size - 1, (sorted.size * 0.995).toInt())].toDouble()
    if (p > 1e-12) 255.0 / p else 0.0
  }
  val px = IntArray(cols * rows)
  for (y in 0 until rows) {
    for (x in 0 until cols) {
      val idx = (y * cols + x) * 3
      val r = (mags[idx] * vs).roundToInt().coerceIn(0, 255)
      val g = (mags[idx + 1] * vs).roundToInt().coerceIn(0, 255)
      val b = (mags[idx + 2] * vs).roundToInt().coerceIn(0, 255)
      px[y * cols + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
  }
  return px
}

/** Colchón sonoro suave y estable para el modo exacto (los bits van en los 4 LSB) */
private fun zx2WriteBed(a: ShortArray, from: Int, to: Int) {
  if (from >= to) return
  val fs = ZX2_RATE.toDouble()
  var rnd = 0x1234ABCDL
  val f1 = 110.0
  val f2 = 164.81
  val f3 = 220.0
  var i = from
  while (i < to) {
    val t = i / fs
    val trem = 0.62 + 0.38 * sin(2.0 * PI * 0.17 * t)
    var v = 0.45 * sin(2.0 * PI * f1 * t) + 0.28 * sin(2.0 * PI * f2 * t + 0.7) + 0.16 * sin(2.0 * PI * f3 * t + 1.9)
    v *= trem
    rnd = zxSplitMix(rnd)
    v += ((rnd ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5) * 0.02
    a[i] = (v * 0.30 * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
    i++
  }
}

/**
 * Modo exacto: la imagen completa (codificada en PNG/JPEG) viaja en los 4 bits menos
 * significativos de las muestras. `visual` puede ser un audio de color ya generado
 * que se coloca al principio para que el espectrograma muestre el dibujo.
 */
fun zxImageLosslessAudio(
  payload: ByteArray,
  width: Int,
  height: Int,
  codec: Int,
  visual: ZxImageAudio?
): ZxImageAudio {
  require(payload.isNotEmpty()) { "La imagen está vacía" }
  val need = zxImageAudioSamples(payload.size)
  val visualSamples = visual?.samples?.size ?: 0
  val total = max(need, visualSamples) + ZX2_RATE / 5
  val out = ShortArray(total)
  val visualLen = min(visualSamples, total)
  // el colchón solo hace falta donde no hay dibujo (y bajo el cruce final)
  zx2WriteBed(out, if (visualLen > 0) max(0, visualLen - 512) else 0, total)
  if (visual != null && visualLen > 0) {
    visual.samples.copyInto(out, 0, 0, visualLen)
    // cruce suave del dibujo con el colchón para que no haya chasquidos
    val fade = min(512, visualLen)
    for (k in 0 until fade) {
      val idx = visualLen - fade + k
      val g = k.toDouble() / fade
      val v = (out[idx] / 32767.0) * g + (zx2BedSample(idx) / 32767.0) * (1.0 - g)
      out[idx] = (v * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
    }
  }
  val hdr = zx2Header(
    mode = ZX2_MODE_LOSSLESS,
    codec = codec,
    flags = if (visual != null) ZX2_FLAG_VISUAL else 0,
    width = width,
    height = height,
    rows = visual?.rows ?: 0,
    cols = visual?.cols ?: 0,
    payloadLen = payload.size,
    crc = zxCrc32(payload),
    valueScale = 0f
  )
  zx2WriteHeader(out, hdr)
  val region = out.size - ZX2_HDR_REGION
  val w = ZxNibbleWriter(out, ZX2_HDR_REGION, region, ZX2_SEED_PAYLOAD)
  for (i in payload.indices) w.putByte(i, payload[i].toInt() and 0xFF)
  return ZxImageAudio(out, ZX2_RATE, ZX2_MODE_LOSSLESS, width, height, payload.size, visual?.rows ?: 0, visual?.cols ?: 0)
}

/** Muestra del colchón sonoro para la posición `i` (misma fórmula que zx2WriteBed) */
private fun zx2BedSample(i: Int): Double {
  val t = i / ZX2_RATE.toDouble()
  val trem = 0.62 + 0.38 * sin(2.0 * PI * 0.17 * t)
  var v = 0.45 * sin(2.0 * PI * 110.0 * t) + 0.28 * sin(2.0 * PI * 164.81 * t + 0.7) + 0.16 * sin(2.0 * PI * 220.0 * t + 1.9)
  v *= trem
  return v * 0.30 * 32767.0
}

/** Extrae y verifica (CRC32) el contenido del modo exacto */
fun zxImageAudioPayload(samples: ShortArray, info: ZxImageAudioInfo): ByteArray? {
  if (info.mode != ZX2_MODE_LOSSLESS || info.payloadLen <= 0) return null
  val region = samples.size - ZX2_HDR_REGION
  if (info.payloadLen * 2 > region) return null
  val r = ZxNibbleReader(samples, ZX2_HDR_REGION, region, ZX2_SEED_PAYLOAD)
  val out = ByteArray(info.payloadLen)
  for (i in out.indices) out[i] = r.byteAt(i).toByte()
  if (zxCrc32(out) != info.crc) return null
  return out
}

// ===========================================================================
// PAQUETES "ZET1" PARA CÓDIGOS QR Y DE BARRAS
// Permiten repartir una imagen, un archivo o un video en varias partes (varios
// códigos) y reensamblarlos al leerlos, verificando el CRC32 del conjunto.
// El paquete es binario, pero se transporta como texto Base64 para que cualquier
// lector de QR (incluido el de SimpleX) lo devuelva intacto.
// ===========================================================================
object ZxDataCode {
  const val PREFIX = "ZET1:"
  const val KIND_TEXT = 0
  const val KIND_IMAGE = 1
  const val KIND_FILE = 2
  const val KIND_VIDEO = 3

  fun kindName(kind: Int): String = when (kind) {
    KIND_IMAGE -> "Imagen"
    KIND_FILE -> "Archivo"
    KIND_VIDEO -> "Vídeo"
    else -> "Texto"
  }

  fun partCount(size: Int, partSize: Int): Int = max(1, (size + max(1, partSize) - 1) / max(1, partSize))

  fun encode(kind: Int, name: String, data: ByteArray, partSize: Int): List<String> {
    val ps = max(64, partSize)
    var nameBytes = name.toByteArray(Charsets.UTF_8)
    if (nameBytes.size > 96) nameBytes = nameBytes.copyOf(96)
    val crc = zxCrc32(data)
    val id = (zxCrc32(nameBytes) xor data.size xor (kind shl 3)) and 0xFFFF
    val total = partCount(data.size, ps)
    val out = ArrayList<String>(total)
    for (i in 0 until total) {
      val from = i * ps
      val to = min(data.size, from + ps)
      val chunk = data.copyOfRange(from, to)
      val pkt = ByteArray(19 + nameBytes.size + 2 + chunk.size)
      pkt[0] = 1
      pkt[1] = kind.toByte()
      pkt[2] = if (total > 1) 1 else 0
      pkt[3] = 0
      zx2Put16(pkt, 4, id)
      zx2Put16(pkt, 6, i)
      zx2Put16(pkt, 8, total)
      zx2Put32(pkt, 10, data.size)
      zx2Put32(pkt, 14, crc)
      pkt[18] = nameBytes.size.toByte()
      nameBytes.copyInto(pkt, 19)
      val cl = 19 + nameBytes.size
      zx2Put16(pkt, cl, chunk.size)
      chunk.copyInto(pkt, cl + 2)
      out.add(PREFIX + ZxBase64.encode(pkt))
    }
    return out
  }

  fun parse(text: String): ZxDataPacket? {
    val t = text.trim()
    if (!t.startsWith(PREFIX)) return null
    val raw = ZxBase64.decode(t.substring(PREFIX.length)) ?: return null
    if (raw.size < 21) return null
    if (raw[0].toInt() != 1) return null
    val kind = raw[1].toInt() and 0xFF
    val id = zx2Get16(raw, 4)
    val index = zx2Get16(raw, 6)
    val total = zx2Get16(raw, 8)
    val totalLen = zx2Get32(raw, 10)
    val crc = zx2Get32(raw, 14)
    val nameLen = raw[18].toInt() and 0xFF
    if (nameLen < 0 || 19 + nameLen + 2 > raw.size) return null
    if (total <= 0 || index < 0 || index >= total || totalLen < 0) return null
    val name = String(raw, 19, nameLen, Charsets.UTF_8)
    val cl = 19 + nameLen
    val chunkLen = zx2Get16(raw, cl)
    if (chunkLen < 0 || cl + 2 + chunkLen > raw.size) return null
    val chunk = raw.copyOfRange(cl + 2, cl + 2 + chunkLen)
    return ZxDataPacket(kind, id, index, total, totalLen, crc, name, chunk)
  }
}

class ZxDataPacket(
  val kind: Int,
  val id: Int,
  val index: Int,
  val total: Int,
  val totalLen: Int,
  val crc: Int,
  val name: String,
  val chunk: ByteArray
)

class ZxDataProgress(
  val kind: Int,
  val name: String,
  val have: Int,
  val total: Int,
  val complete: Boolean,
  val crcOk: Boolean,
  val data: ByteArray?,
  val bytes: Int
)

/** Acumula las partes escaneadas (en cualquier orden) hasta completar el contenido */
class ZxDataAssembler {
  private val groups = HashMap<Int, HashMap<Int, ZxDataPacket>>()
  private var lastKind = 0
  private var lastName = ""
  private var lastId = 0

  fun reset() {
    groups.clear()
    lastKind = 0
    lastName = ""
    lastId = 0
  }

  fun isEmpty(): Boolean = groups.isEmpty()

  fun add(p: ZxDataPacket): ZxDataProgress {
    val g = groups.getOrPut(p.id) { HashMap() }
    g[p.index] = p
    lastKind = p.kind
    lastName = p.name
    lastId = p.id
    val total = p.total
    val have = g.size
    if (have < total) {
      return ZxDataProgress(p.kind, p.name, have, total, false, true, null, p.totalLen)
    }
    val parts = g
    if (parts.size != total) return ZxDataProgress(p.kind, p.name, parts.size, total, false, true, null, p.totalLen)
    val out = ByteArray(p.totalLen)
    var off = 0
    for (i in 0 until total) {
      val part = parts[i] ?: return ZxDataProgress(p.kind, p.name, parts.size, total, false, true, null, p.totalLen)
      if (off + part.chunk.size > out.size) {
        groups.remove(p.id)
        return ZxDataProgress(p.kind, p.name, parts.size, total, false, true, null, p.totalLen)
      }
      part.chunk.copyInto(out, off)
      off += part.chunk.size
    }
    val ok = off == out.size && zxCrc32(out) == p.crc
    if (ok) groups.remove(p.id)
    return ZxDataProgress(p.kind, p.name, parts.size, total, true, ok, if (ok) out else null, out.size)
  }

  /** Datos completos que ya están listos (si el usuario escaneó todas las partes) */
  fun completed(): ZxDataProgress? {
    for ((id, parts) in groups.toList()) {
      val any = parts.values.firstOrNull() ?: continue
      if (parts.size != any.total) continue
      val out = ByteArray(any.totalLen)
      var off = 0
      var ok = true
      for (i in 0 until any.total) {
        val part = parts[i]
        if (part == null) {
          ok = false
          break
        }
        if (off + part.chunk.size > out.size) {
          ok = false
          break
        }
        part.chunk.copyInto(out, off)
        off += part.chunk.size
      }
      if (ok && off == out.size && zxCrc32(out) == any.crc) {
        groups.remove(id)
        return ZxDataProgress(any.kind, any.name, parts.size, any.total, true, true, out, out.size)
      }
    }
    return null
  }
}

// ===========================================================================
// MORSE POR AUDIO: del sonido al texto
// Detecta la frecuencia del tono, sigue la envolvente con un demodulador en
// cuadratura, separa puntos y rayas por duraciones y reconstruye el mensaje.
// No hace falta saber la velocidad (WPM) ni la frecuencia: se deducen del audio.
// ===========================================================================
class ZxMorseAudioResult(
  val text: String,
  val wpm: Int,
  val toneHz: Int,
  val seconds: Double,
  val quality: Int
)

private fun zxMorseMixMono(p: ZxPcm, maxSeconds: Int): Pair<FloatArray, Int> {
  val rate = if (p.rate > 0) p.rate else ZX_SR
  val ch = max(1, p.channels)
  var frames = p.samples.size / ch
  val lim = (maxSeconds.toLong() * rate).toInt()
  if (lim > 0 && frames > lim) frames = lim
  val mono = FloatArray(frames)
  for (f in 0 until frames) {
    var acc = 0f
    for (c in 0 until ch) acc += p.samples[f * ch + c]
    mono[f] = acc / (ch * 32768f)
  }
  return Pair(mono, rate)
}

/** Reduce la frecuencia de muestreo promediando grupos (suficiente para tonos < 2 kHz) */
private fun zxMorseDecimate(x: FloatArray, rate: Int, target: Int): Pair<FloatArray, Int> {
  if (rate <= (target * 1.25).toInt()) return Pair(x, rate)
  val step = max(1, rate / target)
  val nr = rate / step
  val out = FloatArray(x.size / step)
  for (i in out.indices) {
    var s = 0f
    val base = i * step
    for (j in 0 until step) s += x[base + j]
    out[i] = s / step
  }
  return Pair(out, nr)
}

/** Busca la frecuencia dominante entre 250 y 2500 Hz analizando varias tramas */
private fun zxMorseTone(x: FloatArray, rate: Int): Double {
  val n = 2048
  if (x.size < n) return 700.0
  val fft = ZX_FFT_2048
  val re = DoubleArray(n)
  val im = DoubleArray(n)
  val acc = DoubleArray(n / 2)
  val win = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
  var frames = 0
  val stride = max(n, x.size / 96)
  var k = 0
  while (k + n <= x.size && frames < 96) {
    var e = 0.0
    for (i in 0 until n) e += x[k + i] * x[k + i].toDouble()
    if (e > 1e-5) {
      for (i in 0 until n) {
        re[i] = x[k + i] * win[i]
        im[i] = 0.0
      }
      fft.fft(re, im)
      for (i in 0 until n / 2) acc[i] += re[i] * re[i] + im[i] * im[i]
      frames++
    }
    k += stride
  }
  if (frames == 0) return 700.0
  var lo = (250.0 * n / rate).toInt()
  var hi = (2500.0 * n / rate).toInt()
  lo = lo.coerceIn(1, n / 2 - 2)
  hi = hi.coerceIn(lo + 1, n / 2 - 1)
  var best = lo
  for (i in lo..hi) if (acc[i] > acc[best]) best = i
  // interpolación parabólica para afinar
  var delta = 0.0
  if (best > lo && best < hi) {
    val a = acc[best - 1]
    val b = acc[best]
    val c = acc[best + 1]
    val den = a - 2 * b + c
    if (abs(den) > 1e-12) delta = 0.5 * (a - c) / den
  }
  return (best + delta) * rate.toDouble() / n
}

/** Envolvente de la amplitud del tono (demodulación en cuadratura + paso bajo) */
private fun zxMorseEnvelope(x: FloatArray, rate: Int, f0: Double): FloatArray {
  val w = 2.0 * PI * f0 / rate
  val cw = cos(w)
  val sw = sin(w)
  val a = exp(-2.0 * PI * 90.0 / rate)
  var cr = 1.0
  var sr = 0.0
  var l1r = 0.0
  var l1i = 0.0
  var l2r = 0.0
  var l2i = 0.0
  val env = FloatArray(x.size)
  for (i in x.indices) {
    val v = x[i].toDouble()
    val r = v * cr
    val im = -v * sr
    l1r = a * l1r + (1 - a) * r
    l1i = a * l1i + (1 - a) * im
    l2r = a * l2r + (1 - a) * l1r
    l2i = a * l2i + (1 - a) * l1i
    env[i] = sqrt(l2r * l2r + l2i * l2i).toFloat()
    val nr = cr * cw - sr * sw
    sr = cr * sw + sr * cw
    cr = nr
    if ((i and 1023) == 0) {
      val m = sqrt(cr * cr + sr * sr)
      if (m > 1e-12) {
        cr /= m
        sr /= m
      }
    }
  }
  return env
}

private fun zxPercentile(values: FloatArray, q: Double): Float {
  if (values.isEmpty()) return 0f
  val sorted = values.copyOf()
  sorted.sort()
  val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
  return sorted[idx]
}

/**
 * Decodifica un audio de Morse (de Zetaria o de cualquier otra fuente).
 * Devuelve el texto, la velocidad detectada, el tono y una medida de calidad (0-100),
 * o null si no hay ningún tono claro.
 */
fun zxMorseAudioDecode(pcm: ZxPcm, maxSeconds: Int = 300): ZxMorseAudioResult? {
  val (mono0, rate0) = zxMorseMixMono(pcm, maxSeconds)
  if (mono0.size < 1024) return null
  val (x, rate) = zxMorseDecimate(mono0, rate0, 12000)
  if (x.size < 512) return null
  val f0 = zxMorseTone(x, rate)
  if (f0 < 200.0 || f0 > 3000.0) return null
  val env = zxMorseEnvelope(x, rate, f0)
  val ms = max(1, rate / 1000)
  val envMs = FloatArray(x.size / ms)
  for (i in envMs.indices) {
    var m = 0f
    val base = i * ms
    for (j in 0 until ms) {
      val v = env[base + j]
      if (v > m) m = v
    }
    envMs[i] = m
  }
  if (envMs.size < 30) return null
  val p10 = zxPercentile(envMs, 0.10)
  val p95 = zxPercentile(envMs, 0.95)
  if (p95 <= 1e-6f || p95 - p10 <= 1e-6f) return null
  val hi = p10 + 0.42f * (p95 - p10)
  val lo = p10 + 0.22f * (p95 - p10)
  // runs con histéresis
  val raw = ArrayList<IntArray>() // [inicioMs, finMs, encendido]
  var on = envMs[0] > hi
  var start = 0
  for (i in 1 until envMs.size) {
    val v = envMs[i]
    val stillOn = if (on) v >= lo else v > hi
    if (stillOn != on) {
      raw.add(intArrayOf(start, i, if (on) 1 else 0))
      start = i
      on = stillOn
    }
  }
  raw.add(intArrayOf(start, envMs.size, if (on) 1 else 0))
  // limpieza: quita parásitos cortos y une cortes breves
  val minOn = 8
  val minGap = 7
  val clean = ArrayList<IntArray>()
  for (r in raw) {
    val len = r[1] - r[0]
    val isOn = r[2] == 1
    if (clean.isEmpty()) {
      if (!isOn) continue
      clean.add(intArrayOf(r[0], r[1], 1))
      continue
    }
    val prev = clean[clean.size - 1]
    val prevOn = prev[2] == 1
    if (isOn) {
      if (len < minOn) continue
      if (!prevOn) clean.add(intArrayOf(r[0], r[1], 1)) else prev[1] = r[1]
    } else {
      if (len < minGap) continue
      if (prevOn) clean.add(intArrayOf(r[0], r[1], 0)) else prev[1] = r[1]
    }
  }
  while (clean.isNotEmpty() && clean[0][2] == 0) clean.removeAt(0)
  while (clean.isNotEmpty() && clean[clean.size - 1][2] == 0) clean.removeAt(clean.size - 1)
  if (clean.isEmpty()) return null
  val onLens = clean.filter { it[2] == 1 }.map { (it[1] - it[0]).toFloat() }.sorted()
  if (onLens.isEmpty()) return null
  // unidad = duración del punto; se refina separando puntos y rayas
  var unit = onLens[(onLens.size * 0.20).toInt().coerceIn(0, onLens.size - 1)].toDouble()
  for (pass in 0 until 4) {
    val dots = onLens.filter { it.toDouble() < 2.0 * unit }
    if (dots.isEmpty()) break
    val mid = dots[dots.size / 2].toDouble()
    if (mid <= 0.0) break
    if (abs(mid - unit) < 0.5) break
    unit = mid
  }
  unit = unit.coerceIn(6.0, 400.0)
  if (unit < 6.0) return null
  val sb = StringBuilder()
  val sym = StringBuilder()
  var good = 0
  var totalOn = 0
  for (i in clean.indices) {
    val r = clean[i]
    if (r[2] != 1) continue
    val len = (r[1] - r[0]).toDouble()
    totalOn++
    val isDot = len < 2.0 * unit
    if (isDot) {
      sym.append('.')
      if (len in (unit * 0.55)..(unit * 1.6)) good++
    } else {
      sym.append('-')
      if (len in (unit * 2.2)..(unit * 4.2)) good++
    }
    val next = clean.getOrNull(i + 1)
    val gap = if (next != null && next[2] == 0) (next[1] - next[0]).toDouble() else unit * 7.0
    if (gap >= 2.0 * unit) {
      if (sym.isNotEmpty()) {
        val code = sym.toString()
        sym.setLength(0)
        val ch = ZX_MORSE_REV[code]
        if (ch != null) sb.append(ch) else if (sb.isNotEmpty() && sb.last() != '?') sb.append('?')
      }
      if (gap >= 5.0 * unit) {
        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
      }
    }
  }
  if (sym.isNotEmpty()) {
    val ch = ZX_MORSE_REV[sym.toString()]
    if (ch != null) sb.append(ch)
  }
  val text = sb.toString().trim().replace(Regex("\\s+"), " ").uppercase()
  if (text.isEmpty()) return null
  val quality = if (totalOn == 0) 0 else (100 * good / totalOn).coerceIn(0, 100)
  val wpm = (1200.0 / unit).roundToInt().coerceIn(3, 80)
  return ZxMorseAudioResult(text, wpm, f0.roundToInt(), x.size.toDouble() / rate, quality)
}
