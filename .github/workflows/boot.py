#!/usr/bin/env python3
"""Zetaria: animación de arranque con fondo morado cósmico y el símbolo del movimiento en HD.

 - Pantalla superpuesta de arranque (Compose): fondo morado cósmico, estrellas que titilan, halo y ondas
   alrededor del símbolo MNZ (imagen de 1536 px).
 - LENTA (~4,8 s, con estrella fugaz) la primera vez que se abre la app tras instalarla; RÁPIDA (~1,1 s) en los
   arranques siguientes. Se muestra una vez por arranque del proceso (no al volver desde segundo plano).
 - Pantalla de inicio del sistema (Android 12+): fondo morado y sin el icono pequeño, para que el paso a la
   animación sea continuo. Si no se puede aplicar con seguridad, se omite y la animación funciona igual.

Transaccional (todo o nada) salvo la parte del sistema, que se omite sola si algo no encaja.
Uso: boot.py <ruta a apps/multiplatform>
"""
import re, sys, shutil, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
K = mp / "common/src/commonMain/kotlin/chat/simplex/common"
IMG = mp / "common/src/commonMain/resources/MR/images"
ANDROID = mp / "android"
BASE_COLOR = "#FF12062A"  # igual que BASE en ZetariaBoot.kt


def fail(msg):
    sys.exit(f"ERROR [boot]: {msg}")


changes = {}   # texto
copies = {}    # binarios: destino -> origen

# 1) Símbolo HD y animación ----------------------------------------------------------------------------------
sym = here / "branding/mnz_symbol.png"
tpl = here / "templates/boot/ZetariaBoot.kt"
for f in (sym, tpl):
    if not f.exists():
        fail(f"falta {f}")
if not IMG.is_dir():
    fail(f"no existe {IMG}")
copies[IMG / "zetaria_boot@4x.png"] = sym
changes[K / "views/zetaria/ZetariaBoot.kt"] = tpl.read_text(encoding="utf-8")

# 2) Conectar la animación en AppScreen ---------------------------------------------------------------------
app = K / "App.kt"
if not app.exists():
    fail("no existe App.kt")
t = app.read_text(encoding="utf-8")
# Otros parches (updater.py) añaden líneas justo debajo de MainScreen(): se aceptan tal cual.
anchor_rx = re.compile(
    r"^ {12}MainScreen\(\)\n"                       # MainScreen()
    r"(?: {12}//[^\n]*\n)?"                           # comentario de otro parche (opcional)
    r"(?: {12}[\w.]*ZetariaUpdateHost\(\)\n)?"        # aviso de actualización (opcional)
    r" {10}\}\n {8}\}\n",                            # cierre de los dos Box
    re.M)
hits = anchor_rx.findall(t)
if len(hits) != 1:
    fail(f"AppScreen: el bloque esperado aparece {len(hits)} veces en App.kt (debía ser 1)")
m = anchor_rx.search(t)
t = t[:m.end()] + "        ZetariaBootOverlay()\n" + t[m.end():]
imp = "import chat.simplex.res.MR\n"
if t.count(imp) != 1:
    fail(f"App.kt: import esperado aparece {t.count(imp)} veces (debía ser 1)")
t = t.replace(imp, imp + "import chat.simplex.common.views.zetaria.ZetariaBootOverlay\n", 1)
changes[app] = t

# 3) Pantalla de inicio del sistema (opcional, solo si es seguro) ---------------------------------------------
sys_note = "omitida"


def compile_sdk():
    for rel in ("build.gradle.kts", "build.gradle"):
        f = ANDROID / rel
        if f.exists():
            m = re.search(r"compileSdk(?:Version)?\s*[= ]\s*(\d+)", f.read_text(encoding="utf-8"))
            if m:
                return int(m.group(1))
    f = mp / "gradle.properties"
    if f.exists():
        m = re.search(r"android\.compileSdk\s*=\s*(\d+)", f.read_text(encoding="utf-8"))
        if m:
            return int(m.group(1))
    return None


def set_items(text, style, items):
    """Añade o reemplaza <item> dentro de <style name=style>. Devuelve (texto, ok)."""
    rx = re.compile(r'(<style\s+name="' + re.escape(style) + r'"[^>]*?>)(.*?)(</style>)', re.S)
    m = rx.search(text)
    if not m:
        return text, False
    head, body, tail = m.groups()
    for name, val in items:
        irx = re.compile(r'<item\s+name="' + re.escape(name) + r'"[^>]*>.*?</item>', re.S)
        new_item = f'<item name="{name}">{val}</item>'
        if irx.search(body):
            body = irx.sub(lambda _: new_item, body, count=1)
        else:
            body = f"\n        {new_item}" + body
    return text[:m.start()] + head + body + tail + text[m.end():], True


try:
    sdk = compile_sdk()
    manifest = ANDROID / "src/main/AndroidManifest.xml"
    res = ANDROID / "src/main/res"
    if sdk is None or sdk < 31:
        raise RuntimeError(f"compileSdk no es ≥ 31 (encontrado: {sdk})")
    if not manifest.exists():
        raise RuntimeError("no existe AndroidManifest.xml")
    mt = manifest.read_text(encoding="utf-8")
    themes = []
    for rx in (r"<application\b[^>]*>", r"<activity\b[^>]*?MainActivity[^>]*>"):
        for tag in re.findall(rx, mt, re.S):
            themes += re.findall(r'android:theme="@style/([\w.]+)"', tag)
    themes = list(dict.fromkeys(themes))
    if not themes:
        raise RuntimeError("no encuentro el tema de la app en el AndroidManifest")
    items = [
        ("android:windowSplashScreenBackground", "@color/zetaria_boot_bg"),
        ("android:windowSplashScreenAnimatedIcon", "@drawable/zetaria_splash_blank"),
        ("android:windowBackground", "@color/zetaria_boot_bg"),
    ]
    sys_changes = {}
    patched = 0
    for folder in ("values", "values-night"):
        f = res / folder / "themes.xml"
        if not f.exists():
            continue
        txt = f.read_text(encoding="utf-8")
        for th in themes:
            txt, ok = set_items(txt, th, items)
            patched += ok
        sys_changes[f] = txt
    if patched == 0:
        raise RuntimeError(f"no encuentro el estilo {themes} en themes.xml")
    sys_changes[res / "values/zetaria_boot_colors.xml"] = (
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="zetaria_boot_bg">{BASE_COLOR}</color>\n</resources>\n')
    sys_changes[res / "drawable/zetaria_splash_blank.xml"] = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        '    <size android:width="1dp" android:height="1dp" />\n'
        '    <solid android:color="#00000000" />\n</shape>\n')
    changes.update(sys_changes)
    sys_note = f"aplicada ({patched} estilos, compileSdk {sdk})"
except RuntimeError as e:
    sys_note = f"omitida ({e})"

# Escritura (todo junto, al final)
for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
for dst, src in copies.items():
    shutil.copy(src, dst)
print(f"   [boot] animación de arranque lista ({len(changes)} archivos + símbolo HD); pantalla de inicio del sistema: {sys_note}")
