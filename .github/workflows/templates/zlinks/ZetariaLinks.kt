package chat.simplex.common.views.zetaria

// Enlaces propios de Zetaria.
//
// El núcleo de SimpleX sigue generando y entendiendo sus enlaces de siempre; aquí solo se TRADUCEN en
// la frontera de la app (lo que sale al compartir y lo que entra al pegar, escanear o abrir):
//
//   SALIDA   simplex:/contact#...                  ->  <BASE>/#contact~...
//            https://simplex.chat/contact#...      ->  <BASE>/#contact~...
//            https://smp6.simplex.im/a#KEY         ->  <BASE>/#h~smp6.simplex.im/a~KEY   (enlace corto)
//   ENTRADA  <BASE>/#...  <BASE>#/...  <BASE>/...  o  zetaria:/...  ->  el enlace original (simplex:/... o https://host/...)
//            (también se aceptan los formatos anteriores, con el segundo «#»: <BASE>/#contact#... y <BASE>/contact#...)
//
// Por qué «/#» y no «/contact#»: todo lo que va tras el primer # es fragmento y NO se envía al servidor, así que
// Messenger, WhatsApp, Telegram y Discord piden solo <BASE>/ (la página raíz, HTTP 200 con las etiquetas
// og:image de la bandera) y muestran la vista previa. Con una ruta como <BASE>/contact GitHub Pages responde
// 404 y no hay vista previa. Además, Messenger NO genera vista previa si hay un segundo «#» dentro de la URL
// (probado), por eso el «#» interno del enlace de SimpleX viaja como «~» (nunca aparece en esa parte del enlace).
//
// La traducción no pierde nada (es solo cambiar el prefijo), así que el núcleo recibe exactamente el enlace
// original. El marcador de BASE lo sustituye zlinks.py al copiar este archivo.
object ZetariaLinks {
  const val BASE = "__ZETARIA_LINK_BASE__" // sin barra final
  private const val SCHEME = "zetaria:/"
  private const val HOST_MARK = "h~"
  private const val SEP = "~"

  // Prefijo de SALIDA (con vista previa): <BASE>/#
  private val outPrefix: String = "$BASE/#"
  // Prefijos aceptados de ENTRADA, del más largo al más corto
  private val inPrefixes: List<String> = listOf("$BASE/#", "$BASE#/", "$BASE/", "$BASE#", SCHEME)

  private val inRegex = Regex("(" + Regex.escape(BASE) + "[/#]|" + Regex.escape(SCHEME) + ")\\S*")

  fun isZetariaLink(s: String): Boolean = inPrefixes.any { s.startsWith(it) }

  // Enlace original -> enlace de Zetaria (lo que se muestra, copia, comparte o pone en el QR)
  fun toZetaria(uri: String): String {
    if (isZetariaLink(uri)) return uri
    if (uri.startsWith("simplex:/")) return outPrefix + hashToSep(uri.removePrefix("simplex:/"))
    if (uri.startsWith("https://simplex.chat/")) return outPrefix + hashToSep(uri.removePrefix("https://simplex.chat/"))
    if (uri.startsWith("https://") && uri.contains('#')) {
      val rest = uri.removePrefix("https://")
      if (rest.indexOf('/') > 0) return outPrefix + HOST_MARK + hashToSep(rest) // host/ruta~clave
    }
    return uri
  }

  // Enlace de Zetaria -> enlace original (lo que entiende el núcleo)
  fun toSimplex(link: String): String {
    val prefix = inPrefixes.firstOrNull { link.startsWith(it) } ?: return link
    val rest = link.removePrefix(prefix).trimStart('/')
    if (rest.startsWith(HOST_MARK)) return "https://" + sepToHash(rest.removePrefix(HOST_MARK))
    if (rest.startsWith("h%7E", ignoreCase = true)) return "https://" + sepToHash(rest.substring(4))
    return "simplex:/" + sepToHash(rest)
  }

  // El primer «#» del enlace original pasa a «~» (así la URL de Zetaria solo lleva un «#»)
  private fun hashToSep(s: String): String {
    val i = s.indexOf('#')
    return if (i < 0) s else s.substring(0, i) + SEP + s.substring(i + 1)
  }

  // Inverso. Si ya trae un «#» es un formato anterior y se deja tal cual; si no, el primer «~» (o %7E) vuelve a ser «#»
  private fun sepToHash(s: String): String {
    if (s.contains('#')) return s
    val m = Regex("~|%7E", RegexOption.IGNORE_CASE).find(s) ?: return s
    return s.substring(0, m.range.first) + "#" + s.substring(m.range.last + 1)
  }

  // Traduce todos los enlaces de Zetaria que haya dentro de un texto (pegado, escaneado, abierto...)
  fun normalizeText(s: String): String =
    if (!s.contains(BASE) && !s.contains(SCHEME)) s else inRegex.replace(s) { toSimplex(it.value) }
}
