#!/usr/bin/env python3
"""Zetaria: encuestas (botón "Encuesta" en el menú de adjuntos + tarjeta con votos en el chat).

Sin cambios de protocolo: la encuesta es un mensaje de texto y los votos son respuestas.
TRANSACCIONAL: si algún punto de anclaje del código de SimpleX no coincide exactamente,
no se modifica nada, se explica cuál y se sale con error (el build sigue sin encuestas).

Uso: polls.py <ruta a apps/multiplatform>
"""
import re, sys, pathlib

mp = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "polls"
src = mp / "common/src"
K = "kotlin/chat/simplex/common"

edits = {}   # ruta -> texto nuevo
orig = {}


def die(msg, ctx=None):
    print(f"\n::warning::Encuestas NO aplicadas: {msg}")
    print(f"ERROR (encuestas): {msg}")
    if ctx:
        print("----- contexto -----")
        print(ctx)
        print("--------------------")
    print("No se modificó ningún archivo.")
    sys.exit(1)


def read(rel):
    p = src / rel
    if not p.exists():
        die(f"no existe {rel}")
    if p not in orig:
        orig[p] = p.read_text(encoding="utf-8")
        edits[p] = orig[p]
    return p


def once(p, old, new, what):
    t = edits[p]
    n = t.count(old)
    if n != 1:
        die(f"{what}: el texto esperado aparece {n} veces (debía ser 1) en {p.name}", old)
    edits[p] = t.replace(old, new, 1)
    print(f"   [encuestas] {what}")


# 1) nueva opción en el menú de adjuntos
p = read(f"commonMain/{K}/views/helpers/ChooseAttachmentView.kt")
once(p, "  object File: AttachmentOption()\n",
     "  object File: AttachmentOption()\n  object Poll: AttachmentOption()\n",
     "AttachmentOption.Poll añadido")

# 2) botón "Encuesta" (segunda fila, bajo Cámara) sin tocar los 4 botones originales
p = read(f"androidMain/{K}/views/helpers/ChooseAttachmentView.android.kt")
t = edits[p]
m = re.search(r"actual fun ChooseAttachmentButtons\(.*\)\s*\{[ \t]*\n", t)
if not m:
    die("no encuentro ChooseAttachmentButtons en la versión Android", t)
depth, i = 1, m.end()
while i < len(t) and depth:
    depth += (t[i] == "{") - (t[i] == "}")
    i += 1
body_start, body_end = m.end(), i - 1
body = t[body_start:body_end]
if "ActionButton(" not in body:
    die("ChooseAttachmentButtons no contiene ActionButton", body)
new_body = (
    "\n  Column(Modifier.fillMaxWidth()) {\n"
    "    Row(Modifier.fillMaxWidth()) {" + body + "    }\n"
    "    Row(Modifier.fillMaxWidth()) {\n"
    "      ActionButton(Modifier.fillMaxWidth(0.25f), null, \"Encuesta\", icon = painterResource(MR.images.zet_poll)) {\n"
    "        attachmentOption.value = AttachmentOption.Poll\n"
    "        hide()\n"
    "      }\n"
    "    }\n"
    "  }\n"
)
edits[p] = t[:body_start] + new_body + t[body_end:]
print("   [encuestas] botón Encuesta añadido al menú de adjuntos")

# 3) anfitrión del diálogo "Nueva encuesta" dentro del compositor (tiene el chat actual)
p = read(f"commonMain/{K}/views/chat/ComposeView.kt")
t = edits[p]
mm = re.findall(r"^( *)AttachmentSelection\(composeState, attachmentOption, .*$", t, re.M)
if len(mm) != 1:
    die(f"AttachmentSelection(...) aparece {len(mm)} veces en ComposeView.kt (debía ser 1)")
line = re.search(r"^( *)AttachmentSelection\(composeState, attachmentOption, .*$", t, re.M)
# (no se puede usar el nombre completo del paquete: en ComposeView "chat" es una variable)
t2 = t[:line.end()] + f"\n{line.group(1)}ZetariaPollHost(chatsCtx, chat, attachmentOption)" + t[line.end():]
first_import = re.search(r"^import .*$", t2, re.M)
if not first_import:
    die("ComposeView.kt no tiene imports")
if "import chat.simplex.common.views.chat.item.ZetariaPollHost" in t2:
    die("ComposeView.kt ya importa ZetariaPollHost")
edits[p] = (t2[:first_import.start()] + "import chat.simplex.common.views.chat.item.ZetariaPollHost\n" + t2[first_import.start():])
print("   [encuestas] ZetariaPollHost añadido a ComposeView")

# 4) la encuesta se dibuja como tarjeta dentro de la burbuja
p = read(f"commonMain/{K}/views/chat/item/FramedItemView.kt")
old = "              else -> CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp)\n"
new = ("              else -> if (zetariaIsPoll(ci)) {\n"
       "                ZetariaPollCard(chatsCtx, chat, ci)\n"
       "              } else {\n"
       "                CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp)\n"
       "              }\n")
once(p, old, new, "tarjeta de encuesta en FramedItemView")

# 5) votos y cierres como línea discreta
p = read(f"commonMain/{K}/views/chat/item/ChatItemView.kt")
old = "              if (cItem.quotedItem == null && cItem.meta.itemForwarded == null && cItem.meta.itemDeleted == null && !cItem.meta.isLive) {\n"
new = ("              if (zetariaIsPollEvent(cItem)) {\n"
       "                ZetariaPollEventLine(chat, cItem)\n"
       "              } else " + old.lstrip())
once(p, old, new, "línea de votos en ChatItemView")

# 6) archivos nuevos
new_files = {
    src / f"commonMain/{K}/views/chat/item/ZetariaPoll.kt": (tpl / "ZetariaPoll.kt").read_text(encoding="utf-8"),
    src / "commonMain/resources/MR/images/zet_poll.svg": (tpl / "zet_poll.svg").read_text(encoding="utf-8"),
}
if not (src / "commonMain/resources/MR/images").exists():
    die("no existe la carpeta de imágenes MR/images")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
print(f"   [encuestas] listo: {sum(1 for p_ in edits if edits[p_] != orig[p_])} archivos modificados, {len(new_files)} nuevos")
