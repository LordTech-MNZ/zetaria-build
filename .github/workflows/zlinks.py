#!/usr/bin/env python3
"""Zetaria: enlaces propios (en vez de simplex.chat) para contactos, direcciones y grupos.

Problema: la app genera enlaces https://simplex.chat/... y https://smpN.simplex.im/..., que al tocarlos fuera
de la app abren (o descargan) SimpleX original. Aquí se cambian por enlaces de Zetaria:

    <BASE>/contact#...            (BASE = tu página de GitHub Pages, p. ej. https://0rlanther.github.io/zetaria-build)

que abren una página de aterrizaje (docs/index.html) con el botón «Abrir en Zetaria» (esquema zetaria:/) y la
descarga del APK si aún no la tienen. El núcleo de SimpleX no se toca: solo se traduce en la frontera de la app.

Cambios (todos o ninguno; si algo no encaja termina con error, porque a medias dejaría enlaces inservibles):
  1) ZetariaLinks.kt (traductor) + sustitución de BASE.
  2) SALIDA: simplexChatLink() y simplexChatUri(short) devuelven enlaces de Zetaria (QR, copiar, compartir).
  3) ENTRADA: strIsSimplexLink, strConnectTarget, planAndConnect y connectIfOpenedViaUri traducen de vuelta
     antes de pasar el texto al núcleo; openExternalLink y openBrowserAlert abren los enlaces de Zetaria dentro
     de la app en vez de en el navegador.
  4) AndroidManifest: filtro de intents para el esquema zetaria:/ (para que la página abra la app).

Variables de entorno: ZETARIA_REPO ("usuario/repo"), ZETARIA_LINK_BASE (opcional, sin barra final).
Uso: zlinks.py <ruta a apps/multiplatform>
"""
import os, re, sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
K = mp / "common/src/commonMain/kotlin/chat/simplex/common"
Z = "chat.simplex.common.views.zetaria.ZetariaLinks"

repo = os.environ.get("ZETARIA_REPO", "LordTech-MNZ/zetaria-build").strip()
m = re.fullmatch(r"([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)", repo)
if not m:
    sys.exit(f"ERROR [zlinks]: ZETARIA_REPO inválido: {repo!r}")
base = os.environ.get("ZETARIA_LINK_BASE", "").strip() or f"https://{m.group(1).lower()}.github.io/{m.group(2)}"
if not re.fullmatch(r"https://[A-Za-z0-9.-]+(/[A-Za-z0-9._-]+)*", base):
    sys.exit(f"ERROR [zlinks]: ZETARIA_LINK_BASE inválida (https, sin barra final): {base!r}")

changes = {}


def fail(msg):
    sys.exit(f"ERROR [zlinks]: {msg}")


def text_of(rel):
    p = K / rel
    if not p.exists():
        fail(f"no existe {rel}")
    return p, changes.get(p, p.read_text(encoding="utf-8"))


def sub_once(rel, old, new, what):
    p, t = text_of(rel)
    n = t.count(old)
    if n != 1:
        fail(f"{what}: el texto esperado aparece {n} veces en {rel} (debía ser 1)")
    changes[p] = t.replace(old, new, 1)


# 2) SALIDA ---------------------------------------------------------------------------------------------
sub_once("model/SimpleXAPI.kt",
         '''fun simplexChatLink(uri: String): String =
  if (uri.startsWith("simplex:/")) uri.replace("simplex:/", "https://simplex.chat/")
  else uri
''',
         f'''// Zetaria: los enlaces que se muestran/comparten son los de Zetaria (ver ZetariaLinks.kt)
fun simplexChatLink(uri: String): String = {Z}.toZetaria(uri)
''', "simplexChatLink")
sub_once("model/SimpleXAPI.kt",
         "    if (short) connShortLink ?: simplexChatLink(connFullLink)\n",
         "    if (short) (connShortLink?.let { simplexChatLink(it) } ?: simplexChatLink(connFullLink))\n",
         "simplexChatUri (enlace corto)")

# 3) ENTRADA --------------------------------------------------------------------------------------------
sub_once("views/newchat/NewChatView.kt",
         "fun strIsSimplexLink(str: String): Boolean {\n  val parsedMd = parseToMarkdown(str)\n",
         f"fun strIsSimplexLink(str: String): Boolean {{\n  val parsedMd = parseToMarkdown({Z}.normalizeText(str))\n",
         "strIsSimplexLink")
sub_once("views/newchat/NewChatView.kt",
         "fun strConnectTarget(str: String): ConnectTarget? {\n  val parsedMd = parseToMarkdown(str) ?: return null\n",
         f"fun strConnectTarget(str: String): ConnectTarget? {{\n  val parsedMd = parseToMarkdown({Z}.normalizeText(str)) ?: return null\n",
         "strConnectTarget")
sub_once("views/newchat/ConnectPlan.kt",
         "): CompletableDeferred<Boolean> {\n  when (val target = strConnectTarget(shortOrFullLink.trim())) {\n",
         f"): CompletableDeferred<Boolean> {{\n  val shortOrFullLink = {Z}.normalizeText(shortOrFullLink)\n"
         "  when (val target = strConnectTarget(shortOrFullLink.trim())) {\n",
         "planAndConnect")
sub_once("views/chatlist/ChatListView.kt",
         'fun connectIfOpenedViaUri(rhId: Long?, uri: String, chatModel: ChatModel) {\n  Log.d(TAG, "connectIfOpenedViaUri: opened via link")\n',
         'fun connectIfOpenedViaUri(rhId: Long?, uri: String, chatModel: ChatModel) {\n  Log.d(TAG, "connectIfOpenedViaUri: opened via link")\n'
         f"  val uri = {Z}.normalizeText(uri)\n",
         "connectIfOpenedViaUri")
sub_once("views/helpers/Utils.kt",
         '  if (uri.startsWith("https://simplex.chat/contact#") || (uri.startsWith("https://smp") && ".simplex.im/a#" in uri)) {\n',
         '  if (uri.startsWith("https://simplex.chat/contact#") || (uri.startsWith("https://smp") && ".simplex.im/a#" in uri)\n'
         f'    || {Z}.isZetariaLink(uri)) {{\n',
         "openExternalLink")
sub_once("views/chat/item/TextItemView.kt",
         "fun openBrowserAlert(uri: String, uriHandler: UriHandler) {\n",
         "fun openBrowserAlert(uri: String, uriHandler: UriHandler) {\n"
         f"  if ({Z}.isZetariaLink(uri)) {{ uriHandler.openVerifiedSimplexUri(uri); return }}\n",
         "openBrowserAlert")

# 1) Traductor -------------------------------------------------------------------------------------------
kt = (here / "templates/zlinks/ZetariaLinks.kt").read_text(encoding="utf-8").replace("__ZETARIA_LINK_BASE__", base)
if "__ZETARIA_" in kt:
    fail("marcadores sin sustituir en ZetariaLinks.kt")
changes[K / "views/zetaria/ZetariaLinks.kt"] = kt
(K / "views/zetaria").mkdir(parents=True, exist_ok=True)

# 4) Esquema zetaria:/ en el AndroidManifest ---------------------------------------------------------------
manifest = mp / "android/src/main/AndroidManifest.xml"
if not manifest.exists():
    fail("no existe android/src/main/AndroidManifest.xml")
t = manifest.read_text(encoding="utf-8")
if 'android:scheme="zetaria"' not in t:
    block = None
    for mm in re.finditer(r"<intent-filter\b.*?</intent-filter>", t, re.S):
        if 'android:scheme="simplex"' in mm.group(0):
            block = mm
            break
    new_filter = ('<intent-filter>\n'
                  '    <action android:name="android.intent.action.VIEW" />\n'
                  '    <category android:name="android.intent.category.DEFAULT" />\n'
                  '    <category android:name="android.intent.category.BROWSABLE" />\n'
                  '    <data android:scheme="zetaria" />\n'
                  '</intent-filter>')
    if block:
        line_start = t.rfind("\n", 0, block.start()) + 1
        indent = t[line_start:block.start()]
        indent = indent if indent.strip() == "" else ""
        ins = "\n" + indent + new_filter.replace("\n", "\n" + indent)
        t = t[:block.end()] + ins + t[block.end():]
    else:
        am = re.search(r"<activity\b[^>]*MainActivity[^>]*>", t, re.S)
        if not am:
            fail("no encuentro dónde añadir el filtro de intents en el AndroidManifest")
        t = t[:am.end()] + "\n        " + new_filter.replace("\n", "\n        ") + t[am.end():]
    changes[manifest] = t
    print("   [zlinks] filtro de intents zetaria:/ añadido al AndroidManifest")

for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
print(f"   [zlinks] listo: enlaces de Zetaria en {base}/... ({len(changes)} archivos)")
