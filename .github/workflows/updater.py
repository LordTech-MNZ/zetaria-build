#!/usr/bin/env python3
"""Zetaria: actualizaciones automáticas desde las Releases de GitHub.

Qué hace sobre el código de SimpleX:
  1) Sube versionCode / versionName según el número de compilación (Android solo deja instalar una
     actualización si el versionCode es mayor que el de la app instalada).
  2) Añade el permiso REQUEST_INSTALL_PACKAGES al AndroidManifest (necesario para instalar el APK).
  3) Copia ZetariaUpdate.{common,android,desktop}.kt (comprobación + diálogo + descarga + instalación),
     con el número de compilación y el repositorio incrustados.
  4) Engancha ZetariaUpdateHost() justo después de MainScreen() para que el aviso salga sobre la app.
  5) Añade el botón «Buscar actualizaciones» en Configuración > Configuración avanzada (solo Android), que
     comprueba al momento la última Release del repositorio y dice si hay versión nueva, si estás al día o el error.

Variables de entorno (las pone el workflow):
  ZETARIA_BUILD_NUMBER  número de compilación (github.run_number). Si es 0 no se toca la versión.
  ZETARIA_REPO          "usuario/repositorio" de GitHub donde se publican las Releases.

A diferencia de otros scripts, este NO se omite en silencio: si algo no encaja termina con error,
porque una compilación sin actualizador nunca podría actualizarse sola después.

Uso: updater.py <ruta a apps/multiplatform>
"""
import os, re, sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "updater"

build = os.environ.get("ZETARIA_BUILD_NUMBER", "0").strip() or "0"
repo = (os.environ.get("ZETARIA_REPO") or os.environ.get("GITHUB_REPOSITORY") or "lordtech-mnz/zetaria-build").strip()
if not re.fullmatch(r"\d+", build):
    sys.exit(f"ZETARIA_BUILD_NUMBER inválido: {build!r}")
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
    sys.exit(f"ZETARIA_REPO inválido: {repo!r}")
BUILD = int(build)

changes = {}  # ruta -> texto nuevo (todo se escribe al final, solo si todo encajó)


def fail(msg):
    sys.exit(f"ERROR [updater]: {msg}")


# 1) Versión ---------------------------------------------------------------------------------
if BUILD > 0:
    gp = mp / "gradle.properties"
    if not gp.exists():
        fail("no existe gradle.properties")
    t = gp.read_text(encoding="utf-8")
    m_code = re.search(r"^android\.version_code=(\d+)\s*$", t, re.M)
    m_name = re.search(r"^android\.version_name=(\S+)\s*$", t, re.M)
    if not m_code or not m_name:
        fail("gradle.properties no tiene android.version_code / android.version_name")
    new_code = 100_000 + BUILD
    if new_code <= int(m_code.group(1)):
        fail(f"versionCode nuevo ({new_code}) no supera al original ({m_code.group(1)})")
    base_name = re.sub(r"-z\d+$", "", m_name.group(1))
    t = t[:m_code.start()] + f"android.version_code={new_code}" + t[m_code.end():]
    m_name = re.search(r"^android\.version_name=(\S+)\s*$", t, re.M)
    t = t[:m_name.start()] + f"android.version_name={base_name}-z{BUILD}" + t[m_name.end():]
    changes[gp] = t
    print(f"   [updater] versionCode={new_code}, versionName={base_name}-z{BUILD}")
else:
    print("   [updater] ZETARIA_BUILD_NUMBER=0: no se modifica la versión (prueba local)")

# 2) Permiso para instalar APK ------------------------------------------------------------------
manifest = mp / "android/src/main/AndroidManifest.xml"
if not manifest.exists():
    fail("no existe android/src/main/AndroidManifest.xml")
t = manifest.read_text(encoding="utf-8")
if "REQUEST_INSTALL_PACKAGES" not in t:
    if t.count("<application") != 1:
        fail("no encuentro un único <application en el AndroidManifest")
    perm = '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n\n    '
    t = t.replace("<application", perm + "<application", 1)
    changes[manifest] = t
    print("   [updater] permiso REQUEST_INSTALL_PACKAGES añadido")

# 3) Hook: ZetariaUpdateHost() después de MainScreen() -----------------------------------------------
common_kt = mp / "common/src/commonMain/kotlin/chat/simplex/common"
pat = re.compile(r"^([ \t]*)MainScreen\(\)[ \t]*$", re.M)
hooked = False
app_kt = common_kt / "App.kt"
cands = [app_kt] if app_kt.exists() else []
cands += [f for f in common_kt.rglob("*.kt") if f != app_kt]
for f in cands:
    t = f.read_text(encoding="utf-8")
    if "ZetariaUpdateHost()" in t:
        hooked = True  # ya estaba enganchado (reejecución)
        break
    ms = pat.findall(t)
    if len(ms) == 1:
        m = pat.search(t)
        ind = m.group(1)
        repl = (f"{ind}MainScreen()\n"
                f"{ind}// Zetaria: aviso de actualización disponible (no afecta al diseño)\n"
                f"{ind}chat.simplex.common.views.zetaria.ZetariaUpdateHost()")
        changes[f] = t[:m.start()] + repl + t[m.end():]
        print(f"   [updater] ZetariaUpdateHost() enganchado en {f.relative_to(mp)}")
        hooked = True
        break
if not hooked:
    fail("no encuentro una única llamada «MainScreen()» donde enganchar el aviso de actualización")

# 3b) Botón «Buscar actualizaciones» en Configuración avanzada (justo antes de «Salir») ----------------------------
settings_kt = mp / "common/src/commonMain/kotlin/chat/simplex/common/views/usersettings/SettingsView.kt"
if not settings_kt.exists():
    fail("no existe views/usersettings/SettingsView.kt")
t = settings_kt.read_text(encoding="utf-8")
anchor = "      AppShutdownItem()\n      AppVersionItem(showVersion)\n"
if t.count(anchor) != 1:
    fail(f"SettingsView.kt: no encuentro «AppShutdownItem() + AppVersionItem(showVersion)» (aparece {t.count(anchor)} veces)")
button = (
    "      if (appPlatform == AppPlatform.ANDROID) {\n"
    "        // Zetaria: comprobar actualizaciones a mano (el resultado lo muestra ZetariaUpdateHost)\n"
    "        SettingsActionItem(painterResource(MR.images.ic_upgrade), \"Buscar actualizaciones\", "
    "{ chat.simplex.common.views.zetaria.ZetariaUpdateBus.requestCheck() })\n"
    "      }\n"
)
changes[settings_kt] = t.replace(anchor, button + anchor, 1)
print("   [updater] botón «Buscar actualizaciones» añadido a Configuración")

# 4) Archivos Kotlin --------------------------------------------------------------------------------
common_src = tpl / "ZetariaUpdate.common.kt"
txt = common_src.read_text(encoding="utf-8")
txt = txt.replace("__ZETARIA_BUILD__", str(BUILD)).replace("__ZETARIA_REPO__", repo)
if "__ZETARIA_" in txt:
    fail("quedaron marcadores sin sustituir en ZetariaUpdate.common.kt")
dest = {
    mp / "common/src/commonMain/kotlin/chat/simplex/common/views/zetaria/ZetariaUpdate.common.kt": txt,
    mp / "common/src/androidMain/kotlin/chat/simplex/common/views/zetaria/ZetariaUpdate.android.kt":
        (tpl / "ZetariaUpdate.android.kt").read_text(encoding="utf-8"),
    mp / "common/src/desktopMain/kotlin/chat/simplex/common/views/zetaria/ZetariaUpdate.desktop.kt":
        (tpl / "ZetariaUpdate.desktop.kt").read_text(encoding="utf-8"),
}
for p, content in dest.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    changes[p] = content

for p, content in changes.items():
    p.write_text(content, encoding="utf-8")
print(f"   [updater] listo: compilación {BUILD}, repositorio {repo}, {len(changes)} archivos escritos")
