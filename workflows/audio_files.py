#!/usr/bin/env python3
"""Zetaria: envío y reproducción de audios como archivo.

 1. El botón "Archivo" del menú de adjuntos pasa a llamarse "Archivo y audios". Los audios
    (mp3, wav, m4a, aac, ogg, opus, flac...) se envían como ARCHIVO normal, así que también
    llegan a cualquier cliente SimpleX estándar.
 2. En el chat, los archivos de audio ya descargados se muestran con un reproductor propio
    (ícono de archivo de audio + nombre + play/pausa + barra + tiempos), claramente distinto
    de la nota de voz.
 3. En la vista previa del redactor, los audios llevan el ícono de audio.

El script es TRANSACCIONAL: calcula todos los cambios en memoria y solo escribe si todo
encaja con el código de SimpleX. Si algo no coincide, no modifica nada, imprime un
diagnóstico y sale con error (el workflow sigue compilando sin la función de audio).

Uso: audio_files.py <ruta a apps/multiplatform>
"""
import re, sys, pathlib

mp = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
tpl_dir = here / "templates" / "audio"
src = mp / "common/src"

texts = {}      # ruta -> texto (original o modificado)
orig = {}
new_files = {}  # ruta -> texto


def die(msg, snippet=None):
    print(f"\n::warning::Función de audio NO aplicada: {msg.splitlines()[0]}")
    print(f"ERROR (audio): {msg}")
    if snippet:
        print("----- contexto -----")
        print(snippet)
        print("--------------------")
    print("No se modificó ningún archivo.")
    sys.exit(1)


def rd(p):
    if p not in texts:
        orig[p] = p.read_text(encoding="utf-8")
        texts[p] = orig[p]
    return texts[p]


kt_files = [f for f in src.rglob("*.kt") if "/build/" not in str(f).replace("\\", "/")]
if not kt_files:
    die(f"no encuentro archivos .kt en {src}")


def package_of(t):
    m = re.search(r"^package\s+([\w.]+)", t, re.M)
    return m.group(1) if m else None


def find_def(regex, what):
    """(ruta, texto, match) de la primera definición top-level que coincide."""
    rx = re.compile(regex, re.M)
    for f in kt_files:
        t = rd(f)
        m = rx.search(t)
        if m:
            return f, t, m
    die(f"no encuentro {what} en el código de SimpleX (patrón: {regex})")


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


def line_start(t, i):
    return t.rfind("\n", 0, i) + 1


def split_params(sig):
    parts, depth, cur = [], 0, ""
    for ch in sig:
        if ch in "(<[{": depth += 1
        elif ch in ")>]}": depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur); cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts]


# ---------------------------------------------------------------------------
# 0. Comprobaciones del código base y localización de símbolos
# ---------------------------------------------------------------------------
print("   [audio] inspeccionando el código de SimpleX...")

cifile_f, cifile_t, _ = find_def(r"^(?:data\s+)?class\s+CIFile\b", "la clase CIFile")
PKG_MODEL = package_of(cifile_t)
cls_start = cifile_t.find("class CIFile")
cls_open = cifile_t.find("{", cls_start)
cls_body = cifile_t[cls_start: bracket_pairs(cifile_t).get(cls_open, cls_start + 4000)]
for needed in (r"\bfileName\b", r"\bfileSize\b", r"\bfileSource\b", r"\bloaded\b"):
    if not re.search(needed, cls_body):
        die(f"CIFile no tiene la propiedad esperada {needed}", cls_body[:1500])

_, t_cfa, _ = find_def(r"^(?:data\s+)?class\s+CryptoFileArgs\b", "CryptoFileArgs")
PKG_CFA = package_of(t_cfa)
f_cf, t_cf, _ = find_def(r"^(?:data\s+)?class\s+CryptoFile\b", "CryptoFile")
if "cryptoArgs" not in t_cf:
    die("CryptoFile no tiene 'cryptoArgs'")

# getAppFilePath puede tener varias versiones (String / URI) en paquetes distintos: se importan todas
_rx_gap = re.compile(r"^(?:expect\s+|actual\s+)?fun\s+getAppFilePath\s*\(", re.M)
GAP_PKGS = []
for _f in kt_files:
    _t = rd(_f)
    if _rx_gap.search(_t) and package_of(_t) not in GAP_PKGS:
        GAP_PKGS.append(package_of(_t))
if not GAP_PKGS:
    die("no encuentro getAppFilePath")
print(f"   [audio] getAppFilePath definido en: {GAP_PKGS}")

_, t_ctx, _ = find_def(r"^(?:lateinit\s+)?(?:var|val)\s+androidAppContext\b", "androidAppContext")
PKG_CTX = package_of(t_ctx)

# descifrado: preferimos decryptCryptoFile(origen, args, destino); si no, readCryptoFile
decrypt_code = None
imports_extra = []
rx_dec = re.compile(r"^(?:expect\s+|actual\s+|external\s+)?fun\s+decryptCryptoFile\s*\(([^)]*)\)", re.M)
rx_read = re.compile(r"^(?:expect\s+|actual\s+|external\s+)?fun\s+readCryptoFile\s*\(([^)]*)\)", re.M)
for f in kt_files:
    t = rd(f)
    m = rx_dec.search(t)
    if m and decrypt_code is None:
        types = [re.sub(r"\s*=.*", "", p.split(":", 1)[1]).strip() if ":" in p else "" for p in split_params(m.group(1))]
        if len(types) == 3 and types[0] == "String" and types[1].startswith("CryptoFileArgs") and types[2] == "String":
            decrypt_code = "decryptCryptoFile(path, args, t.absolutePath)"
            imports_extra.append(f"import {package_of(t)}.decryptCryptoFile")
if decrypt_code is None:
    for f in kt_files:
        t = rd(f)
        m = rx_read.search(t)
        if m:
            types = [p.split(":", 1)[1].strip() if ":" in p else "" for p in split_params(m.group(1))]
            if len(types) == 2 and types[0] == "String" and types[1].startswith("CryptoFileArgs"):
                decrypt_code = "t.writeBytes(readCryptoFile(path, args))"
                imports_extra.append(f"import {package_of(t)}.readCryptoFile")
                break
if decrypt_code is None:
    die("no encuentro decryptCryptoFile(String, CryptoFileArgs, String) ni readCryptoFile(String, CryptoFileArgs)")
print(f"   [audio] descifrado: {decrypt_code}")

# ¿puede parar la nota de voz que esté sonando?
stop_voice = ""
for f in kt_files:
    t = rd(f)
    if re.search(r"\b(?:expect\s+)?object\s+AudioPlayer\b", t) or re.search(r"\bobject\s+AudioPlayer\s*:", t):
        itf = None
        for g in kt_files:
            tg = rd(g)
            if re.search(r"interface\s+AudioPlayerInterface", tg) and re.search(r"fun\s+stop\s*\(\s*\)", tg):
                itf = True
                break
        if itf:
            stop_voice = f"try {{ {package_of(t)}.AudioPlayer.stop() }} catch (e: Throwable) {{}}"
        break
print(f"   [audio] parar nota de voz al iniciar audio: {'sí' if stop_voice else 'no (se omite)'}")

# imports de recursos (MR, painterResource, stringResource)
def need_import(line, what):
    for f in kt_files:
        if line in rd(f):
            return line
    die(f"no encuentro '{line}' en el código ({what})")

imp_mr = need_import("import chat.simplex.res.MR", "MR")
imp_painter = need_import("import dev.icerock.moko.resources.compose.painterResource", "painterResource")
imp_string = need_import("import dev.icerock.moko.resources.compose.stringResource", "stringResource")

# ---------------------------------------------------------------------------
# 1. CIFileView: usar el reproductor para audios ya descargados
# ---------------------------------------------------------------------------
cif_f, cif_t, m = find_def(r"\bfun\s+CIFileView\s*\(", "CIFileView")
PKG_ITEM = package_of(cif_t)
if "androidx.compose.material3" in cif_t:
    die("este SimpleX usa Material 3; las plantillas están escritas para Material 2")
pairs = bracket_pairs(cif_t)
op = m.end() - 1
cl = pairs[op]
sig = cif_t[op + 1:cl]
m_file = re.search(r"\b(\w+)\s*:\s*CIFile\?", sig)
if not m_file:
    die("CIFileView no recibe un parámetro 'CIFile?'", cif_t[m.start():cl + 200])
file_p = m_file.group(1)
m_small = re.search(r"\bsmallView\s*:\s*Boolean", sig)
m_menu = re.search(r"\b(\w+)\s*:\s*MutableState<Boolean>", sig)
after = re.match(r"\s*\{", cif_t[cl + 1:])
if not after:
    die("CIFileView no tiene cuerpo en bloque { }", cif_t[m.start():cl + 200])
body_open = cl + 1 + after.end()
cond = ("!smallView && " if m_small else "") + f"zetariaAudioAvailable({file_p})"
menu_call = f"{{ {m_menu.group(1)}.value = true }}" if m_menu else "{}"
hook = (f"\n  // Zetaria: los archivos de audio se muestran con reproductor\n"
        f"  if ({cond}) {{\n"
        f"    ZetariaAudioFileView({file_p}!!, {menu_call})\n"
        f"    return\n"
        f"  }}")
if "zetariaAudioAvailable" in cif_t:
    die("CIFileView ya tiene el parche de audio")
texts[cif_f] = cif_t[:body_open] + hook + cif_t[body_open:]
print(f"   [audio] CIFileView parcheado ({cif_f.name}): if ({cond})")

# ---------------------------------------------------------------------------
# 2. Menú de adjuntos: botón Audio (copia del botón Archivo) + selector filtrado
#    No depende de nombres internos (enum, etc.): localiza el botón por su texto.
# ---------------------------------------------------------------------------
# Se localiza el archivo del menú por lo que contiene (los textos Cámara/Imagen/Vídeo/Archivo en
# cualquier idioma), no por su nombre. Solo se cambia el texto del botón Archivo con una cadena
# propia; si no se encuentra, el resto de la función de audio se aplica igual.
def _xml_vals(path):
    if not path.exists():
        return {}
    return dict(re.findall(r'<string name="([^"]+)"[^>]*>([^<]*)</string>', path.read_text(encoding="utf-8")))


res_dir = src / "commonMain/resources/MR"
vals_es, vals_en = _xml_vals(res_dir / "es/strings.xml"), _xml_vals(res_dir / "base/strings.xml")
WANT = {  # clase de botón -> textos posibles (minúsculas)
    "camera": {"cámara", "camara", "camera", "use camera"},
    "image": {"imagen", "image", "gallery", "from gallery"},
    "video": {"vídeo", "video"},
    "file": {"archivo", "archivos", "file", "choose file", "files"},
}


def _kind(key):
    for v in (vals_es.get(key, ""), vals_en.get(key, "")):
        for kind, opts in WANT.items():
            if v.strip().lower() in opts:
                return kind
    return None


menu_hits = []
for f in kt_files:
    tt = rd(f)
    kinds = {}
    for k in set(re.findall(r"MR\.strings\.(\w+)", tt)):
        kd = _kind(k)
        if kd and kd not in kinds:
            kinds[kd] = k
    if "file" in kinds and len(kinds) >= 3:
        menu_hits.append((f, kinds["file"], sorted(kinds)))
# si hay un archivo cuyo nombre es el del menú, solo ese (evita tocar textos de otras pantallas)
_named = [h for h in menu_hits if "ChooseAttachment" in h[0].name]
if _named:
    menu_hits = _named
print(f"   [audio] archivos del menú de adjuntos: {[(h[0].name, h[1]) for h in menu_hits]}")
renamed = 0
for f, fkey, _k in menu_hits:
    tt = texts.get(f) or rd(f)
    nt, n = re.subn(r"\bMR\.strings\." + re.escape(fkey) + r"\b", "MR.strings.zetaria_file_audio_button", tt)
    if n:
        texts[f] = nt
        renamed += n
        print(f"   [audio] botón Archivo renombrado a 'Archivo y audios' en {f.name} ({n} uso/s)")
if not renamed:
    cand = []
    for f in kt_files:
        tt = rd(f)
        if "ChooseAttachment" in f.name or "gallery_image_button" in tt or "use_camera_button" in tt:
            cand.append(f"{f.name}: {sorted(set(re.findall(r'MR[.]strings[.](\\w+)', tt)))[:12]}")
    print("   AVISO: no encontré el menú de adjuntos; el botón Archivo conserva su nombre. Candidatos:")
    for c_ in cand:
        print("     ", c_)

# ---------------------------------------------------------------------------
# 3. Vista previa del redactor: ícono de audio
# ---------------------------------------------------------------------------
try:
    cf_f, cf_t, m = find_def(r"\bfun\s+ComposeFileView\s*\(", "ComposeFileView")
    pr = bracket_pairs(cf_t)
    op = m.end() - 1
    cl = pr[op]
    fn = re.search(r"\b(\w+)\s*:\s*String\b", cf_t[op + 1:cl])
    bo = cf_t.find("{", cl)
    bc = pr[bo]
    body = cf_t[bo:bc]
    im = re.search(r"painterResource\(MR\.images\.(\w+)\)", body)
    if not fn or not im:
        raise RuntimeError("estructura distinta")
    repl = (f"painterResource(if ({PKG_ITEM}.zetariaIsAudioName({fn.group(1)})) MR.images.zet_audio_file "
            f"else MR.images.{im.group(1)})")
    body2 = body[:im.start()] + repl + body[im.end():]
    texts[cf_f] = cf_t[:bo] + body2 + cf_t[bc:]
    print(f"   [audio] ícono de audio en la vista previa ({cf_f.name})")
except SystemExit:
    raise
except Exception as ex:
    print(f"   AVISO: vista previa del redactor sin ícono de audio ({ex}); el resto sigue")

# ---------------------------------------------------------------------------
# 4. Archivos nuevos: Kotlin (common/android/desktop), íconos y textos
# ---------------------------------------------------------------------------
imports_common = f"import {PKG_MODEL}.CIFile"
imports_android = "\n".join(dict.fromkeys([
    f"import {PKG_MODEL}.CIFile",
    f"import {PKG_CFA}.CryptoFileArgs",
] + [f"import {p_}.getAppFilePath" for p_ in GAP_PKGS] + [
    f"import {PKG_CTX}.androidAppContext",
    imp_mr, imp_painter, imp_string,
] + imports_extra))
pkg_dir = "/".join(PKG_ITEM.split("."))


def fill(name):
    t = (tpl_dir / name).read_text(encoding="utf-8")
    return (t.replace("%%PKG%%", PKG_ITEM)
             .replace("%%IMPORTS_COMMON%%", imports_common)
             .replace("%%IMPORTS_ANDROID%%", imports_android)
             .replace("%%STOP_VOICE%%", stop_voice)
             .replace("%%DECRYPT%%", decrypt_code))


new_files[src / f"commonMain/kotlin/{pkg_dir}/ZetariaAudio.kt"] = fill("ZetariaAudio.common.kt")
new_files[src / f"androidMain/kotlin/{pkg_dir}/ZetariaAudio.android.kt"] = fill("ZetariaAudio.android.kt")
if (src / "desktopMain").exists():
    new_files[src / f"desktopMain/kotlin/{pkg_dir}/ZetariaAudio.desktop.kt"] = fill("ZetariaAudio.desktop.kt")

img = src / "commonMain/resources/MR/images"
if not img.exists():
    die(f"no existe {img}")
for svg in ("zet_audio_file.svg", "zet_play.svg", "zet_pause.svg"):
    new_files[img / svg] = (tpl_dir / svg).read_text(encoding="utf-8")

base = src / "commonMain/resources/MR/base/strings.xml"
bx = rd(base)
if "zetaria_file_audio_button" in bx:
    die("strings.xml ya tiene zetaria_file_audio_button")
add = ('  <string name="zetaria_file_audio_button">Archivo y audios</string>\n'
       '  <string name="zetaria_audio_error">No se puede reproducir este audio</string>\n')
texts[base] = bx.replace("</resources>", add + "</resources>")

# ---------------------------------------------------------------------------
# Escritura (solo si todo llegó hasta aquí)
# ---------------------------------------------------------------------------
changed = 0
for p, t in texts.items():
    if t != orig.get(p):
        p.write_text(t, encoding="utf-8")
        changed += 1
for p, t in new_files.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(t, encoding="utf-8")
print(f"   [audio] listo: {changed} archivos modificados, {len(new_files)} nuevos")
