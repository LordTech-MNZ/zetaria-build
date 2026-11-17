#!/usr/bin/env python3
"""Zetaria: el soporte deja de ser «Ask SimpleX Team» y pasa a ser tu perfil de Zetaria.

 1) «Nuevo chat»: se oculta la tarjeta «Ask SimpleX Team» (la crea el núcleo de SimpleX) y en su lugar aparece
    «Soporte de Zetaria», que conecta con la dirección de tu perfil.
 2) Ajustes > Ayuda («Enviar preguntas e ideas») y la ayuda de bienvenida apuntan a la misma dirección.
 3) Textos de esas dos entradas: ya no hablan de «desarrolladores de SimpleX».

Para que cada persona que escribe quede en un GRUPO contigo (y no en un chat directo), la dirección debe ser
«de empresa»: en el perfil de Zetaria, Ajustes > Dirección > activar «Dirección de empresa».

La dirección se cambia aquí abajo (SUPPORT_LINK) o con la variable ZETARIA_SUPPORT_LINK.
Acepta el formato de Zetaria (<BASE>/#h~host/a~CLAVE), https://host/a#CLAVE o simplex:/a#CLAVE?h=host.

Transaccional (todo o nada). Uso: support.py <ruta a apps/multiplatform>
"""
import os, re, sys, pathlib

SUPPORT_LINK = "https://lordtech-mnz.github.io/zetaria-build/#h~smp18.simplex.im/a~_NcwLy5Ef83sR57HYWGBbW4PAqbr3p-gycS97xzUHl8"

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
K = mp / "common/src/commonMain/kotlin/chat/simplex/common"
RES = mp / "common/src/commonMain/resources/MR"


def fail(msg):
    sys.exit(f"ERROR [support]: {msg}")


def to_simplex(link):
    """Cualquier formato aceptado -> simplex:/a#CLAVE?h=host (el mismo formato que usa SimpleX para su equipo)."""
    link = link.strip()
    m = re.search(r"#h(?:~|%7E)([A-Za-z0-9.-]+)/([a-z])(?:~|%7E|#)([A-Za-z0-9_-]+)\s*$", link, re.I)
    if not m:
        m = re.fullmatch(r"https://([A-Za-z0-9.-]+)/([a-z])#([A-Za-z0-9_-]+)", link)
    if m:
        host, kind, key = m.groups()
        return f"simplex:/{kind}#{key}?h={host}"
    if re.fullmatch(r"simplex:/[a-z]#[A-Za-z0-9_-]+\?h=[A-Za-z0-9.-]+", link):
        return link
    fail(f"no entiendo el enlace de soporte: {link!r}")


link = to_simplex(os.environ.get("ZETARIA_SUPPORT_LINK", "").strip() or SUPPORT_LINK)

changes = {}


def text_of(p):
    return changes.get(p, p.read_text(encoding="utf-8"))


def sub(rel, old, new, what, count=1):
    p = K / rel
    if not p.exists():
        fail(f"no existe {rel}")
    t = text_of(p)
    n = t.count(old)
    if n != count:
        fail(f"{what}: el texto esperado aparece {n} veces en {rel} (debía ser {count})")
    changes[p] = t.replace(old, new)


# 1) Nuevo chat ---------------------------------------------------------------------------------------------
NC = "views/newchat/NewChatSheet.kt"
# (import normal: dentro de los lambdas la variable «chat» taparía el paquete chat.simplex...)
sub(NC,
    "import chat.simplex.common.model.ChatController.appPrefs\n",
    "import chat.simplex.common.model.ChatController.appPrefs\n"
    "import chat.simplex.common.views.zetaria.ZetariaSupport\n"
    "import chat.simplex.common.views.zetaria.ZetariaSupportRow\n",
    "imports")
sub(NC,
    "  return c.filter { chat -> contactTypes.contains(chatContactType(chat)) }\n",
    "  return c.filter { chat -> contactTypes.contains(chatContactType(chat)) && !ZetariaSupport.isSimplexTeamCard(chat) }\n",
    "ocultar la tarjeta de SimpleX Team")
sub(NC,
    "if (filteredContactChats.isNotEmpty() && searchText.value.text.isEmpty()) {",
    "if (searchText.value.text.isEmpty()) {",
    "cabecera «Tus contactos» siempre visible", count=2)
sub(NC,
    "\n      itemsIndexed(filteredContactChats) { index, chat ->",
    "\n      item {\n"
    "        if (searchText.value.text.isEmpty()) {\n"
    "          ZetariaSupportRow(rh?.remoteHostId)\n"
    "        }\n"
    "      }\n"
    "      itemsIndexed(filteredContactChats) { index, chat ->",
    "fila de soporte de Zetaria", count=2)

# 2) Ajustes > Ayuda y ayuda de bienvenida --------------------------------------------------------------------
sub("views/usersettings/SettingsView.kt",
    'val simplexTeamUri =\n  "simplex:/a#lrdvu2d8A1GumSmoKb2krQmtKhWXq-tyGpHuM7aMwsw?h=smp6.simplex.im"',
    f'val simplexTeamUri =\n  "{link}"',
    "dirección del soporte")

# 3) Textos ---------------------------------------------------------------------------------------------------
TXT = {
    "chat_with_the_founder": ("Zetaria support", "Soporte de Zetaria"),
    "you_can_connect_to_simplex_chat_founder": (
        '<![CDATA[You can <font color="#0088ff">contact Zetaria support to ask any questions and to receive updates</font>.]]>',
        '<![CDATA[Puedes <font color="#0088ff">escribir al soporte de Zetaria para hacer preguntas y recibir novedades</font>.]]>'),
}
nstr = 0
for f in sorted(RES.glob("*/strings.xml")):
    es = f.parent.name.startswith("es")
    t = f.read_text(encoding="utf-8")
    new = t
    for key, (en, esp) in TXT.items():
        pat = re.compile(r'(<string\b[^>]*\bname="' + key + r'"[^>]*>)(.*?)(</string>)', re.S)
        new = pat.sub(lambda m: m.group(1) + (esp if es else en) + m.group(3), new)
    if new != t:
        changes[f] = new
        nstr += 1
if nstr == 0:
    fail("no encuentro los textos del soporte en strings.xml")

# 4) Archivo nuevo --------------------------------------------------------------------------------------------
tpl = here / "templates/support/ZetariaSupport.kt"
if not tpl.exists():
    fail(f"falta la plantilla {tpl}")
changes[K / "views/zetaria/ZetariaSupport.kt"] = tpl.read_text(encoding="utf-8").replace("__ZETARIA_SUPPORT_LINK__", link)

for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
print(f"   [support] soporte de Zetaria listo ({link}); textos en {nstr} idiomas; {len(changes)} archivos")
