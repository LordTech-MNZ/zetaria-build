package chat.simplex.common.views.zetaria

// Enlaces propios de Zetaria.
//
// El núcleo de SimpleX sigue generando y entendiendo sus enlaces de siempre; aquí solo se TRADUCEN en
// la frontera de la app (lo que sale al compartir y lo que entra al pegar, escanear o abrir):
//
//   SALIDA   simplex:/contact#...                  ->  <BASE>/contact#...
//            https://simplex.chat/contact#...      ->  <BASE>/contact#...
//            https://smp6.simplex.im/a#KEY         ->  <BASE>/h~smp6.simplex.im/a#KEY   (enlace corto)
//   ENTRADA  <BASE>/...  o  zetaria:/...           ->  el enlace original (simplex:/... o https://host/...)
//
// La traducción no pierde nada (es solo cambiar el prefijo), así que el núcleo recibe exactamente el enlace
// original. El marcador de BASE lo sustituye zlinks.py al copiar este archivo.
object ZetariaLinks {
  const val BASE = "__ZETARIA_LINK_BASE__" // sin barra final
  private const val BASE_PREFIX_SLASH = "/"
  private const val SCHEME = "zetaria:/"
  private const val HOST_MARK = "h~"

  private val basePrefix: String = BASE + BASE_PREFIX_SLASH
  private val inRegex = Regex("(" + Regex.escape(basePrefix) + "|" + Regex.escape(SCHEME) + ")\\S*")

  fun isZetariaLink(s: String): Boolean = s.startsWith(basePrefix) || s.startsWith(SCHEME)

  // Enlace original -> enlace de Zetaria (lo que se muestra, copia, comparte o pone en el QR)
  fun toZetaria(uri: String): String {
    if (isZetariaLink(uri)) return uri
    if (uri.startsWith("simplex:/")) return basePrefix + uri.removePrefix("simplex:/")
    if (uri.startsWith("https://simplex.chat/")) return basePrefix + uri.removePrefix("https://simplex.chat/")
    if (uri.startsWith("https://") && uri.contains('#')) {
      val rest = uri.removePrefix("https://")
      if (rest.indexOf('/') > 0) return basePrefix + HOST_MARK + rest // host/ruta#clave
    }
    return uri
  }

  // Enlace de Zetaria -> enlace original (lo que entiende el núcleo)
  fun toSimplex(link: String): String {
    val rest = when {
      link.startsWith(basePrefix) -> link.removePrefix(basePrefix)
      link.startsWith(SCHEME) -> link.removePrefix(SCHEME)
      else -> return link
    }
    if (rest.startsWith(HOST_MARK)) return "https://" + rest.removePrefix(HOST_MARK)
    if (rest.startsWith("h%7E", ignoreCase = true)) return "https://" + rest.substring(4)
    return "simplex:/" + rest
  }

  // Traduce todos los enlaces de Zetaria que haya dentro de un texto (pegado, escaneado, abierto...)
  fun normalizeText(s: String): String =
    if (!s.contains(basePrefix) && !s.contains(SCHEME)) s else inRegex.replace(s) { toSimplex(it.value) }
}
