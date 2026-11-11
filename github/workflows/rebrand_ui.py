#!/usr/bin/env python3
"""Cambia 'SimpleX' -> 'Zetaria' en los textos visibles de la app, reemplaza los logos de
bienvenida y monta la bienvenida nueva: la página HTML del voto (index) dentro de un WebView
en Android, con el diseño cósmico como respaldo. Deja intactos los textos que hablan de SimpleX
como empresa/servicio real (auditorías, servidores, Flux, nombres SimpleX, etc.)."""
import sys, re, pathlib, shutil
from PIL import Image

mp = pathlib.Path(sys.argv[1]); here = pathlib.Path(__file__).parent
NEW = "Zetaria"

# Claves que siguen diciendo SimpleX porque son afirmaciones sobre SimpleX real
KEEP_EXACT = {
    "v4_2_security_assessment_desc", "v6_1_better_security_descr",
    "onboarding_network_operators_simplex_flux_agreement",
    "onboarding_network_operators_app_will_use_for_routing",
    "you_can_connect_to_simplex_chat_founder", "icon_descr_simplex_team",
    "use_simplex_chat_servers__question", "using_simplex_chat_servers",
    "install_simplex_chat_for_terminal", "follow_simplex_network_news",
    "settings_section_title_support", "v7_0_invest", "invest_banner_title",
    "about_simplex_chat", "v6_5_non_profit_governance_descr",
    "if_you_received_simplex_invitation_link_you_can_open_in_browser",
}
KEEP_PATTERN = re.compile(r"simplex_name|simplex_domain|simplex_names|^badge_|names$")

pat = re.compile(r'(<string\b[^>]*\bname="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)
res = mp / "common/src/commonMain/resources/MR"
changed = kept = 0
for f in sorted(res.glob("*/strings.xml")):
    txt = f.read_text(encoding="utf-8")
    def sub(m):
        global changed, kept
        head, key, body, tail = m.groups()
        if "SimpleX" not in body:
            return m.group(0)
        if key in KEEP_EXACT or KEEP_PATTERN.search(key):
            kept += 1; return m.group(0)
        changed += 1
        return head + body.replace("SimpleX", NEW) + tail
    new = pat.sub(sub, txt)
    if new != txt:
        f.write_text(new, encoding="utf-8")
print(f"   textos cambiados: {changed}, conservados como SimpleX: {kept}")

# Logos de la pantalla de bienvenida (modo claro/oscuro) y avatar por defecto
img = res / "images"
def put(src, dst_name):
    d = img / dst_name
    if d.exists():
        size = Image.open(d).size
        Image.open(src).convert("RGBA").resize(size, Image.LANCZOS).save(d)
        return 1
    return 0
import shutil
shutil.copy(here / "branding/zetaria_logo.png", img / "zetaria_logo@4x.png")
shutil.copy(here / "branding/zetaria_logo_light.png", img / "zetaria_logo_light@4x.png")
n = 2
n += put(here / "branding/zetaria_icon.png", "ic_simplex_dark@4x.png")
n += put(here / "branding/zetaria_icon.png", "ic_simplex_light@4x.png")
print(f"   {n} imágenes de logo reemplazadas")


# ---------- Bienvenida de Zetaria: página HTML del voto + respaldo cósmico ----------
def patch(path, old, new):
    t = path.read_text(encoding="utf-8")
    if old not in t:
        sys.exit(f"ERROR: no encontré el texto a modificar en {path}:\n{old[:120]}")
    path.write_text(t.replace(old, new, 1), encoding="utf-8")

kt = mp / "common/src/commonMain/kotlin/chat/simplex/common"
info = kt / "views/onboarding/SimpleXInfo.kt"
patch(info, """          SimpleXInfoLayout(
            user = chatModel.currentUser.value,
            onboardingStage = chatModel.controller.appPrefs.onboardingStage
          )
        }
      }
    }
  } else {""", """          ZetariaWelcome(chatModel.currentUser.value, chatModel.controller.appPrefs.onboardingStage)
        }
      }
    }
  } else {""")
patch(info, "painterResource(if (isInDarkTheme()) MR.images.logo_light else MR.images.logo)",
            "painterResource(if (isInDarkTheme()) MR.images.zetaria_logo_light else MR.images.zetaria_logo)")

# Capturas de pantalla permitidas por defecto (el ajuste sigue disponible en Privacidad)
patch(kt / "model/SimpleXAPI.kt", "mkBoolPreference(SHARED_PREFS_PRIVACY_PROTECT_SCREEN, true)",
                                  "mkBoolPreference(SHARED_PREFS_PRIVACY_PROTECT_SCREEN, false)")

# Código de la pantalla de bienvenida: la bienvenida HTML del voto (Android, WebView) y el
# respaldo cósmico (escritorio / si el WebView no puede mostrarse).
# El HTML viaja empaquetado en los assets del APK; el Kotlin lo sirve con WebViewAssetLoader
# desde https://appassets.androidplatform.net/assets/zetaria_welcome.html
welcome = here / "templates" / "welcome"
html_src = welcome / "zetaria_welcome.html"
if not html_src.exists():
    sys.exit(f"ERROR: no existe la página de bienvenida {html_src}")
html_bytes = html_src.read_bytes()
if len(html_bytes) < 5_000_000:
    sys.exit(
        f"ERROR: {html_src} parece incompleto ({len(html_bytes)} bytes).\n"
        "Debe ser el index HTML completo (unos 10 MB, con las imágenes en base64)."
    )
if not html_bytes.lstrip()[:64].lower().startswith(b"<!doctype html"):
    sys.exit(f"ERROR: {html_src} no empieza con <!DOCTYPE html> (¿archivo corrupto?)")

kt_onboarding = kt / "views/onboarding"
plantillas = [
    (kt_onboarding / "ZetariaWelcome.kt", welcome / "ZetariaWelcome.common.kt"),
    (mp / "common/src/androidMain/kotlin/chat/simplex/common/views/onboarding/ZetariaWelcome.android.kt",
     welcome / "ZetariaWelcome.android.kt"),
    (mp / "common/src/desktopMain/kotlin/chat/simplex/common/views/onboarding/ZetariaWelcome.desktop.kt",
     welcome / "ZetariaWelcome.desktop.kt"),
]
for destino, plantilla in plantillas:
    if not plantilla.exists():
        sys.exit(f"ERROR: no existe la plantilla {plantilla}")
    destino.parent.mkdir(parents=True, exist_ok=True)
    # Copia binaria: conserva exactamente el contenido de la plantilla (sin cambiar saltos de línea).
    shutil.copyfile(plantilla, destino)
    print(f"   bienvenida: {destino.relative_to(mp)}")

assets_dir = mp / "common/src/commonMain/resources/assets"
assets_dir.mkdir(parents=True, exist_ok=True)
if not assets_dir.is_dir():
    sys.exit(f"ERROR: no se pudo crear la carpeta de assets {assets_dir}")
shutil.copyfile(html_src, assets_dir / "zetaria_welcome.html")
print(f"   bienvenida HTML en el APK: assets/zetaria_welcome.html ({len(html_bytes) // 1024} KB)")


# Textos nuevos (el resto de idiomas usa este base como respaldo)
TEXT = (
 "El Reino Soberano de Zetaria existe en el cosmos, en la nave nodriza «Corazón de Zetaria», que viaja por dimensiones a través del universo. "
 "Se manifestó en este mundo terrenal por medio de un humano, en el hogar físico del Soberano, con 770 m² de terreno, "
 "y se extiende como un servidor a los espacios virtuales, como esta aplicación que acabas de instalar.\\n\\n"
 "Al instalar y usar esta app anónima, eres visitante de mi Reino, que te protegerá del espionaje de gobiernos corruptos, de izquierda y de derecha, "
 "y de los oligopolios tecnofeudales de empresas hambreadas.\\n\\n"
 "Siéntete cómodo de usar esta app, que modifiqué por medio de Vibecoding. Todo lo que haga falta corregir, me lo vas notificando y haré lo posible por arreglarlo. "
 "Seguirán habiendo actualizaciones con el objetivo de evolucionar a la humanidad para bien y evitar su extinción.")
base = res / "base/strings.xml"
x = base.read_text(encoding="utf-8")
new = ('  <string name="zetaria_welcome_title">Bienvenido al Reino Soberano de Zetaria</string>\n'
       f'  <string name="zetaria_welcome_text">{TEXT}</string>\n'
       '  <string name="zetaria_welcome_credit">Zetaria es una versión modificada de SimpleX Chat (licencia AGPL-3.0).</string>\n')
x = x.replace("</resources>", new + "</resources>")
base.write_text(x, encoding="utf-8")
print("   bienvenida HTML (voto) + respaldo cósmico instalados")


# ---------- PNG sin pérdida en el chat + mejor compresión de fotos de perfil ----------
utils = kt / "views/helpers/Utils.kt"
patch(utils, """fun saveImage(uri: URI): CryptoFile? {
  val bitmap = getBitmapFromUri(uri) ?: return null
  return saveImage(bitmap)
}""", """// Zetaria: los PNG se envían tal cual (sin recomprimir ni reducir), hasta 30 MB
const val ZETARIA_MAX_ORIGINAL_PNG: Long = 31_457_280

private fun isPngFile(uri: URI): Boolean = try {
  uri.inputStream()?.use { s ->
    val h = ByteArray(8)
    s.read(h) == 8 && h[0] == 0x89.toByte() && h[1] == 0x50.toByte() && h[2] == 0x4E.toByte() && h[3] == 0x47.toByte()
  } ?: false
} catch (e: Exception) {
  false
}

fun saveOriginalPng(uri: URI): CryptoFile? {
  return try {
    val size = getFileSize(uri)
    if (size == null || size > ZETARIA_MAX_ORIGINAL_PNG || !isPngFile(uri)) return null
    val encrypted = chatController.appPrefs.privacyEncryptLocalFiles.get()
    val destFileName = generateNewFileName("IMG", "png", File(getAppFilePath("")))
    val destFile = File(getAppFilePath(destFileName))
    if (encrypted) {
      val bytes = uri.inputStream()?.use { it.readBytes() } ?: return null
      val args = writeCryptoFile(destFile.absolutePath, bytes)
      CryptoFile(destFileName, args)
    } else {
      uri.inputStream()?.use { Files.copy(it, destFile.toPath()) } ?: return null
      CryptoFile.plain(destFileName)
    }
  } catch (e: Exception) {
    Log.e(TAG, "Util.kt saveOriginalPng error: ${e.stackTraceToString()}")
    null
  }
}

fun saveImage(uri: URI): CryptoFile? {
  val original = saveOriginalPng(uri)
  if (original != null) return original
  val bitmap = getBitmapFromUri(uri) ?: return null
  return saveImage(bitmap)
}""")
# los PNG grandes se descargan solos en el receptor (hasta 15 MB)
patch(utils, "const val MAX_IMAGE_SIZE_AUTO_RCV: Long = MAX_IMAGE_SIZE * 2",
             "const val MAX_IMAGE_SIZE_AUTO_RCV: Long = 15_728_640")

imgs = mp / "common/src/androidMain/kotlin/chat/simplex/common/platform/Images.android.kt"
patch(imgs, """actual fun resizeImageToStrSize(image: ImageBitmap, maxDataSize: Long): String {
  var img = image""", """actual fun resizeImageToStrSize(image: ImageBitmap, maxDataSize: Long): String {
  // Zetaria: para fotos de perfil y miniaturas usa el mayor tamaño/calidad JPEG que quepa en el límite
  if (maxDataSize <= 100_000 && !image.hasAlpha()) return zetariaResizeJpeg(image, maxDataSize)
  var img = image""")
with open(imgs, "a", encoding="utf-8") as f:
    f.write("""

private fun zetariaJpegStr(img: Bitmap, quality: Int): String {
  val stream = ByteArrayOutputStream()
  img.compress(Bitmap.CompressFormat.JPEG, quality, stream)
  return "data:image/jpg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

private fun zetariaResizeJpeg(image: ImageBitmap, maxDataSize: Long): String {
  var bmp = image.asAndroidBitmap()
  val maxSide = 1024
  val longest = maxOf(bmp.width, bmp.height)
  if (longest > maxSide) {
    val f = maxSide.toDouble() / longest
    bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true)
  }
  val probe = zetariaJpegStr(bmp, 75)
  if (probe.length > maxDataSize) {
    val f = minOf(1.0, sqrt(maxDataSize.toDouble() / probe.length) * 1.15)
    bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true)
  }
  while (true) {
    var lo = 55
    var hi = 95
    var best: String? = null
    while (lo <= hi) {
      val mid = (lo + hi) / 2
      val s = zetariaJpegStr(bmp, mid)
      if (s.length <= maxDataSize) {
        best = s
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    if (best != null) return best
    bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * 0.9).toInt()), maxOf(1, (bmp.height * 0.9).toInt()), true)
  }
}
""")
print("   PNG original + compresión de perfil aplicados")


# ---------- PNG fluido en pantalla completa ----------
# Pantalla completa: limitar la decodificación a 2560 px (antes: tamaño original, que traba la app)
patch(mp / "common/src/androidMain/kotlin/chat/simplex/common/views/chat/item/ImageFullScreenView.android.kt",
      ".size(Size.ORIGINAL)", ".size(Size(2560, 2560))")
print("   PNG fluido en pantalla completa aplicado")


# ---------- "Barra de herramientas accesible" desactivada por defecto ----------
# Solo cambia el valor inicial de instalaciones nuevas; quien ya la tenga activada la conserva.
import re
api = kt / "model/SimpleXAPI.kt"
if api.exists():
    src = api.read_text(encoding="utf-8")
    new_src, n = re.subn(r"(SHARED_PREFS_ONE_HAND_UI\s*,\s*)(true|appPlatform\.isAndroid)", r"\1false", src, count=1)
    if n:
        api.write_text(new_src, encoding="utf-8")
        print("   Barra de herramientas accesible: desactivada por defecto")
    else:
        print("   AVISO: no encontré la preferencia SHARED_PREFS_ONE_HAND_UI; se omite")
else:
    print("   AVISO: no existe SimpleXAPI.kt; se omite")


# ---------- Interfaz morada oscura por defecto (la bienvenida usa colores propios y no cambia) ----------
# El tema que la app usa por defecto en modo oscuro es el de SimpleX (azul marino), que aquí se llama
# "Zetaria". Se pinta de morado ESE tema; el tema "Oscuro" queda como el original.
# Parches opcionales: si algo no coincide con el código de SimpleX, se avisa con AVISO y se omite.
def _value_end(t, i):
    """Devuelve el índice donde termina el valor que empieza en i (coma o salto de línea
    fuera de paréntesis, o el paréntesis que cierra el bloque)."""
    depth = 0
    while i < len(t):
        c = t[i]
        if c == "(":
            depth += 1
        elif c == ")":
            if depth == 0:
                return i
            depth -= 1
        elif c in ",\n" and depth == 0:
            return i
        i += 1
    return i

def set_palette(text, block, values):
    """Cambia valores `clave = ...` dentro de `val <block> = algo(...)`. Devuelve (texto, faltantes)."""
    m = re.search(r"\bval\s+" + re.escape(block) + r"\b", text)
    if not m:
        return text, None
    open_i = text.find("(", m.end())
    if open_i < 0:
        return text, None
    depth, j = 0, open_i
    while j < len(text):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = text[open_i + 1:j]
    missing = []
    for key, color in values.items():
        km = re.search(r"(?<![\w.])" + re.escape(key) + r"\s*=\s*", body)
        if not km:
            missing.append(key)
            continue
        end = _value_end(body, km.end())
        body = body[:km.end()] + color + body[end:]
    return text[:open_i + 1] + body + text[j:], missing

theme_dir = kt / "ui/theme"
palette = {
    "SimplexColorPalette": {
        "primary": "Color(0xFFA678FF)",      # acento violeta
        "primaryVariant": "Color(0xFFA678FF)",
        "background": "Color(0xFF0F0A1A)",   # fondo morado casi negro
        "surface": "Color(0xFF1B1230)",      # tarjetas y listas
    },
    "SimplexColorPaletteApp": {
        "title": "Color(0xFFA678FF)",
        "primaryVariant2": "Color(0xFF2E1A5C)",
        "sentMessage": "Color(0xFF2E1A5C)",
        "sentQuote": "Color(0xFF3B2477)",
        "receivedMessage": "Color(0xFF221A33)",
        "receivedQuote": "Color(0xFF2E2445)",
    },
}
done = 0
for block, values in palette.items():
    applied = False
    for f in sorted(theme_dir.glob("*.kt")) if theme_dir.exists() else []:
        src = f.read_text(encoding="utf-8")
        new_src, missing = set_palette(src, block, values)
        if missing is None:
            continue
        f.write_text(new_src, encoding="utf-8")
        applied = True
        done += 1
        if missing:
            print(f"   AVISO: en {block} no encontré: {', '.join(missing)} (el resto sí se cambió)")
        break
    if not applied:
        found = []
        for f in sorted(theme_dir.glob("*.kt")) if theme_dir.exists() else []:
            found += re.findall(r"\bval\s+(\w*Palette\w*)\b", f.read_text(encoding="utf-8"))
        print(f"   AVISO: no encontré {block} en ui/theme; se omite. Paletas existentes: {sorted(set(found))}")
print(f"   paleta morada oscura aplicada a {done} de {len(palette)} bloques")

# Modo de color por defecto: oscuro (antes seguía al sistema)
if api.exists():
    src = api.read_text(encoding="utf-8")
    new_src, n = re.subn(r"(SHARED_PREFS_CURRENT_THEME\s*,\s*)DefaultTheme\.SYSTEM_THEME_NAME", r'\1"DARK"', src, count=1)
    if n:
        api.write_text(new_src, encoding="utf-8")
        print("   modo de color por defecto: oscuro")
    else:
        print("   AVISO: no encontré el modo de color por defecto; se omite")


# ---------- Quitar el crowdfunding de SimpleX (Wefunder) ----------
# Se ELIMINA del código (no se oculta): la sección/bloque que contiene los textos, y si es una
# función propia (p. ej. el banner) se borra la función entera, sus llamadas y sus imports.
# Cada cambio se imprime en el log; lo que no se pueda resolver sale como AVISO.
def bracket_pairs(t):
    pairs, stack, i, n = {}, [], 0, len(t)
    while i < n:
        c = t[i]
        if t.startswith('"""', i):
            j = t.find('"""', i + 3); i = n if j < 0 else j + 3; continue
        if c == '"':
            i += 1
            while i < n and t[i] != '"':
                if t[i] == "\\": i += 1
                elif t[i] == "\n": break
                i += 1
            i += 1; continue
        if c == "'":
            j = i + 1
            j += 2 if (j < n and t[j] == "\\") else 1
            i = j + 1 if (j < n and t[j] == "'") else i + 1
            continue
        if t.startswith("//", i):
            j = t.find("\n", i); i = n if j < 0 else j; continue
        if t.startswith("/*", i):
            j = t.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if c in "({[":
            stack.append(i)
        elif c in ")}]" and stack:
            pairs[stack.pop()] = i
        i += 1
    return pairs

def _ident_before(t, j):
    k = j
    while k >= 0 and (t[k].isalnum() or t[k] == "_"):
        k -= 1
    return k + 1, t[k + 1:j + 1]

def call_span(t, pairs, rev, o):
    """Sentencia-llamada (p. ej. `Row(...) { ... }`) a la que pertenece el paréntesis/llave en o."""
    if t[o] == "{":
        j = o - 1
        while j >= 0 and t[j] in " \t\n":
            j -= 1
        if j >= 0 and t[j] == ")":
            o2 = rev.get(j)
            if o2 is None or t[o2] != "(":
                return None
            o = o2
        elif j >= 0 and (t[j].isalnum() or t[j] == "_"):
            start, name = _ident_before(t, j)
            if not name[0].isupper():
                return None
            ls = t.rfind("\n", 0, start) + 1
            if t[ls:start].strip():
                return None
            return start, pairs[o] + 1
        else:
            return None
    j = o - 1
    if j < 0 or not (t[j].isalnum() or t[j] == "_"):
        return None
    start, name = _ident_before(t, j)
    if not (name[0].isupper() or name == "if"):
        return None
    ls = t.rfind("\n", 0, start) + 1
    if t[ls:start].strip():
        return None
    end = pairs[o] + 1
    m = re.match(r"\s*\{", t[end:])
    if m:
        end = pairs[end + m.end() - 1] + 1
    if name == "if":
        while True:
            m = re.match(r"\s*else\s*\{", t[end:])
            if not m:
                break
            end = pairs[end + m.end() - 1] + 1
    return start, end

def find_statement(t, pairs, p, limit=1200):
    rev = {c: o for o, c in pairs.items()}
    opens = sorted((o for o, c in pairs.items() if o < p < c and t[o] in "({"), reverse=True)
    best = None
    for o in opens:
        sp = call_span(t, pairs, rev, o)
        if not sp:
            continue
        s, e = sp
        if e - s > limit:
            break
        if len(re.findall(r"\b(?:SettingsActionItem|SectionItemView|SectionItemWithValue)\(", t[s:e])) > 2:
            break
        best = sp
        if re.match(r"SectionView\b", t[s:e]):
            break  # una sección de ajustes completa: no seguir creciendo
        if re.match(r"\s*,", t[e:]):
            break  # elemento de una lista/argumentos (p. ej. una novedad en "Qué hay de nuevo"): solo ese elemento
    return best

def line_expand(t, s, e):
    ls = t.rfind("\n", 0, s) + 1
    if not t[ls:s].strip():
        s = ls
    le = t.find("\n", e)
    le = len(t) if le < 0 else le
    if not t[e:le].strip():
        e = min(le + 1, len(t))
    return s, e

def sole_function(t, pairs, s, e):
    """Si t[s:e] es todo el cuerpo de `fun Nombre(...) { ... }`, devuelve (nombre, inicio, fin)."""
    best = None
    for o, c in pairs.items():
        if t[o] == "{" and o < s and e <= c and not t[o + 1:s].strip() and not t[e:c].strip():
            if best is None or o > best:
                best = o
    if best is None:
        return None
    o = best
    base = max(0, o - 800)
    funs = list(re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", t[base:o]))
    if not funs:
        return None
    m = funs[-1]
    paren = base + m.end() - 1
    if paren not in pairs or not re.fullmatch(r"\s*(?::\s*[\w<>?.]+)?\s*", t[pairs[paren] + 1:o]):
        return None
    fs = base + m.start()
    fs = t.rfind("\n", 0, fs) + 1
    while True:  # incluir anotaciones (@Composable...) de las líneas anteriores
        pls = t.rfind("\n", 0, fs - 1) + 1 if fs > 0 else 0
        if fs > 0 and t[pls:fs].strip().startswith("@"):
            fs = pls
        else:
            break
    return m.group(1), fs, pairs[o] + 1

def remove_crowdfunding(files, needle_re):
    texts = {f: f.read_text(encoding="utf-8") for f in files}
    pairs_of = {f: bracket_pairs(t) for f, t in texts.items()}
    edits = {f: [] for f in files}
    report, skipped, funcs = [], [], {}
    for f, t in texts.items():
        for m in needle_re.finditer(t):
            ls = t.rfind("\n", 0, m.start()) + 1
            line = t[ls:t.find("\n", m.start())].strip()
            if line.startswith(("//", "*", "/*", "import ")):
                continue
            sp = find_statement(t, pairs_of[f], m.start())
            if not sp:
                skipped.append((f, t.count("\n", 0, m.start()) + 1, line))
                continue
            fn = sole_function(t, pairs_of[f], *sp)
            if fn:
                funcs[(f, fn[0])] = (fn[1], fn[2], sp)
            else:
                edits[f].append(sp)
                report.append(f"{f.name}:{t.count(chr(10), 0, sp[0]) + 1} bloque")
    for (f, name), (fs, fe, inner) in funcs.items():
        uses, ok = [], True
        for g, t in texts.items():
            for m in re.finditer(r"\b" + re.escape(name) + r"\b", t):
                if g == f and fs <= m.start() < fe:
                    continue
                ls = t.rfind("\n", 0, m.start()) + 1
                line = t[ls:t.find("\n", m.start())]
                if line.strip().startswith("import "):
                    uses.append((g, ls, ls + len(line) + 1))
                    continue
                k = m.end()
                while k < len(t) and t[k] in " \t":
                    k += 1
                sp = None
                if k < len(t) and t[k] == "(" and k in pairs_of[g]:
                    rev = {c: o for o, c in pairs_of[g].items()}
                    sp = call_span(t, pairs_of[g], rev, k)
                if sp and sp[0] == m.start():
                    uses.append((g, sp[0], sp[1]))
                else:
                    ok = False
        if ok:
            edits[f].append((fs, fe))
            for g, s, e in uses:
                edits[g].append((s, e))
            report.append(f"{f.name}: función {name} eliminada con {len(uses)} uso(s)/import(s)")
        else:
            edits[f].append(inner)
            report.append(f"{f.name}: función {name} vaciada (tiene usos que no se pudieron quitar)")
    for f, spans in edits.items():
        if not spans:
            continue
        t = texts[f]
        exp = []
        for s, e in spans:
            mc = re.match(r"[ \t]*,", t[e:])
            if mc:
                e += mc.end()  # si era un elemento de una lista, quitar también su coma
            s, e = line_expand(t, s, e)
            pls = t.rfind("\n", 0, s - 1) + 1 if s > 0 else 0
            if t[pls:s].strip() == "SectionDividerSpaced()":
                s = pls
            exp.append((s, e))
        exp.sort()
        merged = []
        for s, e in exp:
            if merged and s <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(merged[-1][1], e))
            else:
                merged.append((s, e))
        for s, e in reversed(merged):
            t = t[:s] + t[e:]
        f.write_text(t, encoding="utf-8")
    return list(dict.fromkeys(report)), skipped

common_src = mp / "common/src"
kt_files = [f for f in common_src.rglob("*.kt") if "/build/" not in str(f).replace("\\", "/")]
needles = [r"wefunder", r"invest in simplex"]
for xml in common_src.rglob("strings.xml"):
    if xml.parent.name == "base":
        for name, val in re.findall(r'<string name="([^"]+)"[^>]*>([^<]*)</string>', xml.read_text(encoding="utf-8")):
            if re.search(r"wefunder|invest in simplex", val, re.I):
                needles.append(r"MR\.strings\." + re.escape(name) + r"\b")
needle_re = re.compile("|".join(needles), re.I)
report, skipped = remove_crowdfunding(kt_files, needle_re)
for r in report:
    print(f"   crowdfunding eliminado: {r}")
for f, line_no, line in skipped:
    print(f"   AVISO: crowdfunding sin resolver en {f.name}:{line_no}: {line[:120]}")
left = []
for f in kt_files:
    for n, line in enumerate(f.read_text(encoding="utf-8").split("\n"), 1):
        if needle_re.search(line) and not line.strip().startswith(("//", "*", "/*")):
            left.append((f.name, n, line.strip()[:100]))
for name, n, line in left:
    print(f"   AVISO: queda una referencia en {name}:{n}: {line}")
if not report and not skipped and not left:
    print("   AVISO: no encontré textos de crowdfunding/Wefunder en el código")
