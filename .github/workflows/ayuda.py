#!/usr/bin/env python3
"""Zetaria: Ayuda y asistencia sin rastros de SimpleX + «Sobre Zetaria» + donaciones + documento PDF.

Qué hace (todo o nada: si algo no encaja con el código de SimpleX, termina con error y no toca nada):
  1) Ayuda y asistencia
       - «Guía de uso» y «Novedades» muestran las funciones de Zetaria (ZetariaFeatures en ZetariaAyuda.kt)
       - «Seguir SimpleX Network News»  -> canal oficial «Fuerza Nacional Zetaria - FNZ»
       - «Contacta vía email»           -> movimientonacionalzetario369@gmail.com
       - «Contribuye» y «Califica en GitHub» -> repositorio de Zetaria
       - «Valora la aplicación» se quita (abría la ficha de SimpleX en Google Play)
       - «Sobre SimpleX Chat»           -> «Sobre Zetaria» (pantalla propia con la imagen de la Capitanía General)
  2) Configuración: sección «Zetaria» con
       - botón del documento PDF (texto fijado por la Autoridad, p. ej. «Leer el Manifiesto Zetario»).
         Solo si el paso de la Autoridad está aplicado; si no, se omite y lo demás sigue.
       - «Apoyar a Zetaria»: donaciones con el QR de Binance Pay.
  3) Copia las imágenes (Capitanía y QR) y ZetariaAyuda.kt.

Variables de entorno (opcionales):
  ZETARIA_REPO         «usuario/repositorio» (por defecto el del workflow)
  ZETARIA_NEWS_LINK    enlace del canal oficial (formato Zetaria, https://host/c#CLAVE o simplex:/c#CLAVE?h=host)
  ZETARIA_SUPPORT_EMAIL correo de soporte

Va DESPUÉS de authority.py, support.py y updater.py.
Uso: ayuda.py <ruta a apps/multiplatform>
"""
import os, re, sys, shutil, pathlib

NEWS_LINK = "https://lordtech-mnz.github.io/zetaria-build/#h~smp17.simplex.im/c~5-MwKz7RXTnkrq_pB0pDy9QPdlD9IkaKW2WvAH-wKj0"
EMAIL = "movimientonacionalzetario369@gmail.com"
NEWS_TITLE = ("Canal oficial: Fuerza Nacional Zetaria - FNZ", "Official channel: Fuerza Nacional Zetaria - FNZ")
ABOUT_TITLE = ("Sobre Zetaria", "About Zetaria")

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).resolve().parent
K = mp / "common/src/commonMain/kotlin/chat/simplex/common"
RES = mp / "common/src/commonMain/resources/MR"


def fail(msg):
    sys.exit(f"ERROR [ayuda]: {msg}")


repo = (os.environ.get("ZETARIA_REPO") or os.environ.get("GITHUB_REPOSITORY") or "LordTech-MNZ/zetaria-build").strip()
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
    fail(f"ZETARIA_REPO inválido: {repo!r}")
repo_url = f"https://github.com/{repo}"
email = (os.environ.get("ZETARIA_SUPPORT_EMAIL") or EMAIL).strip()
if not re.fullmatch(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", email):
    fail(f"correo inválido: {email!r}")


def to_simplex(link):
    link = link.strip()
    m = re.search(r"#h(?:~|%7E)([A-Za-z0-9.-]+)/([a-z])(?:~|%7E|#)([A-Za-z0-9_-]+)\s*$", link, re.I)
    if not m:
        m = re.fullmatch(r"https://([A-Za-z0-9.-]+)/([a-z])#([A-Za-z0-9_-]+)", link)
    if m:
        host, kind, key = m.groups()
        return f"simplex:/{kind}#{key}?h={host}"
    if re.fullmatch(r"simplex:/[a-z]#[A-Za-z0-9_-]+\?h=[A-Za-z0-9.-]+", link):
        return link
    fail(f"no entiendo el enlace del canal: {link!r}")


news = to_simplex(os.environ.get("ZETARIA_NEWS_LINK", "").strip() or NEWS_LINK)

changes = {}


def text_of(p):
    return changes.get(p, p.read_text(encoding="utf-8"))


def sub(p, old, new, what, count=1):
    if not p.exists():
        fail(f"no existe {p}")
    t = text_of(p)
    n = t.count(old)
    if n != count:
        fail(f"{what}: el texto esperado aparece {n} veces en {p.name} (debía ser {count})")
    changes[p] = t.replace(old, new)
    print(f"   [ayuda] {what}")


SV = K / "views/usersettings/SettingsView.kt"

# 1) Ayuda y asistencia -------------------------------------------------------------------------------------
sub(SV,
    'val simplexNewsUri =\n  "simplex:/c#grcfG3ulVI4Sh6ow33qBsmSk7uEy3gRSl2KkJ5ER6tA?h=smp18.simplex.im"',
    f'val simplexNewsUri =\n  "{news}"',
    "canal oficial FNZ")
sub(SV, 'uriHandler.openUriCatching("mailto:chat@simplex.chat")',
    f'uriHandler.openUriCatching("mailto:{email}")', "correo de soporte del movimiento")
sub(SV, 'uriHandler.openExternalLink("https://github.com/simplex-chat/simplex-chat#contribute")',
    f'uriHandler.openExternalLink("{repo_url}")', "«Contribuye» -> repositorio de Zetaria")
sub(SV, 'uriHandler.openExternalLink("https://github.com/simplex-chat/simplex-chat")',
    f'uriHandler.openExternalLink("{repo_url}")', "«Califica en GitHub» -> repositorio de Zetaria", count=2)
sub(SV, "      if (appPlatform.isAndroid) {\n        RateAppItem(uriHandler)\n      }\n", "",
    "se quita «Valora la aplicación» (abría Google Play de SimpleX)")
sub(SV, "showModal { SimpleXInfo(it, onboarding = false) }",
    "showModal { chat.simplex.common.views.zetaria.ZetariaAboutView(it) }", "«Sobre Zetaria» con la imagen de la Capitanía")

sub(SV, "showModal { HelpView(userDisplayName) }",
    "showModal { chat.simplex.common.views.zetaria.ZetariaGuideView() }", "«Guía de uso» con las funciones de Zetaria")
sub(SV, "showCustomModal { _, close -> WhatsNewView(viaSettings = true, close = close) }",
    "showModal { chat.simplex.common.views.zetaria.ZetariaNewsView() }", "«Novedades» con las novedades de Zetaria")

# 2) Configuración: sección «Zetaria» ----------------------------------------------------------------------
have_authority = (K / "views/zetaria/ZetariaAuthority.kt").exists() and '"DOC"' in (K / "views/zetaria/ZetariaAuthority.kt").read_text(encoding="utf-8")
doc_item = "      chat.simplex.common.views.zetaria.ZetariaManifestoItem()\n" if have_authority else ""
if not have_authority:
    print("   [ayuda] aviso: no hay Autoridad con soporte de documentos; se omite el botón del PDF (el resto sigue)")
anchor = "    SectionDividerSpaced()\n\n    SectionView(stringResource(MR.strings.advanced_settings)) {\n"
section = (
    "    SectionDividerSpaced()\n\n"
    '    SectionView("Zetaria") {\n'
    + doc_item +
    '      SettingsActionItem(painterResource(MR.images.ic_redeem), "Apoyar a Zetaria (donar)", '
    "showSettingsModal { chat.simplex.common.views.zetaria.ZetariaDonateView() })\n"
    "    }\n"
    "    SectionDividerSpaced()\n\n"
    "    SectionView(stringResource(MR.strings.advanced_settings)) {\n"
)
sub(SV, anchor, section, "sección «Zetaria» en Configuración (PDF y donaciones)")

# 3) Textos (todos los idiomas: español en es*, inglés en el resto) ------------------------------------------
TXT = {
    "follow_simplex_network_news": NEWS_TITLE,
    "about_simplex_chat": ABOUT_TITLE,
}
nstr = 0
for f in sorted(RES.glob("*/strings.xml")):
    es = f.parent.name.startswith("es")
    t = text_of(f)
    new = t
    for key, (esp, en) in TXT.items():
        pat = re.compile(r'(<string\b[^>]*\bname="' + key + r'"[^>]*>)(.*?)(</string>)', re.S)
        new = pat.sub(lambda m: m.group(1) + (esp if es else en) + m.group(3), new)
    if es:
        for key, (esp, en) in TXT.items():
            if f'name="{key}"' not in new:  # el español de SimpleX no traduce esta clave: se añade
                new = new.replace("</resources>", f'  <string name="{key}">{esp}</string>\n</resources>')
    if new != t:
        changes[f] = new
        nstr += 1
if nstr == 0:
    fail("no encuentro los textos de ayuda en strings.xml")
print(f"   [ayuda] textos de ayuda cambiados en {nstr} idiomas")

# 4) Imágenes y archivo Kotlin -------------------------------------------------------------------------------
imgs = {
    "zetaria_capitania@4x.png": here / "branding/capitania_general.png",
    "zetaria_donate_qr@4x.png": here / "branding/donate_qr.png",
}
for name, src in imgs.items():
    if not src.exists():
        fail(f"falta la imagen {src}")
tpl = here / "templates/ayuda/ZetariaAyuda.kt"
if not tpl.exists():
    fail(f"falta la plantilla {tpl}")
kt = tpl.read_text(encoding="utf-8").replace("__ZETARIA_REPO_URL__", repo_url)
if not have_authority:
    # Sin Autoridad con soporte de documentos (plantilla vieja o paso fallido): se quita todo el código del PDF
    kt = re.sub(r"// ZETARIA_DOC_BEGIN.*?// ZETARIA_DOC_END\n", "", kt, flags=re.S)
    print("   [ayuda] código del PDF omitido (la Autoridad no soporta documentos)")
if "__ZETARIA_" in kt:
    fail("marcadores sin sustituir en ZetariaAyuda.kt")
changes[K / "views/zetaria/ZetariaAyuda.kt"] = kt

# Todo validado: escribir
for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
(RES / "images").mkdir(parents=True, exist_ok=True)
for name, src in imgs.items():
    shutil.copy(src, RES / "images" / name)
print(f"   [ayuda] listo: canal {news}, correo {email}, repositorio {repo_url}; {len(changes)} archivos e imágenes copiadas")
