#!/usr/bin/env python3
"""Zetaria: stickers (botón "Stickers" en el menú de adjuntos, panel, importación, envío y guardado).

Requiere que las encuestas (polls.py) ya estén aplicadas: usa su segunda fila de botones.
TRANSACCIONAL: si algún anclaje del código de SimpleX no coincide, no se modifica nada.

Uso: stickers.py <ruta a apps/multiplatform>
"""
import re, sys, pathlib

mp = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "stickers"
src = mp / "common/src"
K = "kotlin/chat/simplex/common"
edits, orig = {}, {}


def die(msg, ctx=None):
    print(f"\n::warning::Stickers NO aplicados: {msg}")
    print(f"ERROR (stickers): {msg}")
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
    n = edits[p].count(old)
    if n != 1:
        die(f"{what}: el texto esperado aparece {n} veces (debía ser 1) en {p.name}", old)
    edits[p] = edits[p].replace(old, new, 1)
    print(f"   [stickers] {what}")


# 0) los símbolos que usa el código nuevo deben existir en el paquete esperado
kt = [f for f in src.rglob("*.kt") if "/build/" not in str(f).replace("\\", "/")]
texts = {f: f.read_text(encoding="utf-8") for f in kt}


def pkg_of(t):
    m = re.search(r"^package\s+([\w.]+)", t, re.M)
    return m.group(1) if m else None


def need(regex, pkg, what):
    rx = re.compile(regex, re.M)
    for f, t in texts.items():
        if rx.search(t) and pkg_of(t) == pkg:
            return
    die(f"no encuentro {what} en el paquete {pkg}")


P = "chat.simplex.common"
need(r"^fun getAppFilePath\(fileName: String\)", f"{P}.platform", "getAppFilePath(String)")
need(r"^fun getLoadedFilePath\(", f"{P}.platform", "getLoadedFilePath")
need(r"^(?:expect )?fun resizeImageToStrSize\(", f"{P}.platform", "resizeImageToStrSize")
need(r"^val chatController\b", f"{P}.platform", "chatController")
need(r"^(?:lateinit )?va[lr] chatModel\b", f"{P}.platform", "chatModel")
need(r"^(?:lateinit )?var androidAppContext\b", f"{P}.platform", "androidAppContext")
need(r"^fun generateNewFileName\(", f"{P}.views.helpers", "generateNewFileName")
need(r"^(?:expect )?suspend fun getLoadedImage\(", f"{P}.views.helpers", "getLoadedImage")
need(r"^fun rememberGetMultipleContentsLauncher\(", f"{P}.views.helpers", "rememberGetMultipleContentsLauncher")
need(r"^fun writeCryptoFile\(", f"{P}.model", "writeCryptoFile")
need(r"^class ComposedMessage\b", f"{P}.model", "ComposedMessage")
print("   [stickers] símbolos de SimpleX verificados")

# 1) nueva opción en el menú de adjuntos (detrás de Poll)
p = read(f"commonMain/{K}/views/helpers/ChooseAttachmentView.kt")
once(p, "  object Poll: AttachmentOption()\n",
     "  object Poll: AttachmentOption()\n  object Stickers: AttachmentOption()\n",
     "AttachmentOption.Stickers añadido")

# 2) botón "Stickers" junto al de Encuesta (segunda fila; 0.3334 de lo que queda = segundo cuarto)
p = read(f"androidMain/{K}/views/helpers/ChooseAttachmentView.android.kt")
anchor = ("        attachmentOption.value = AttachmentOption.Poll\n"
          "        hide()\n"
          "      }\n")
button = ("      ActionButton(Modifier.fillMaxWidth(0.3334f), null, \"Stickers\", icon = painterResource(MR.images.zet_sticker)) {\n"
          "        attachmentOption.value = AttachmentOption.Stickers\n"
          "        hide()\n"
          "      }\n")
once(p, anchor, anchor + button, "botón Stickers añadido al menú")

# 3) panel de stickers dentro del compositor (tiene el chat actual)
p = read(f"commonMain/{K}/views/chat/ComposeView.kt")
once(p, "  ZetariaPollHost(chatsCtx, chat, attachmentOption)\n",
     "  ZetariaPollHost(chatsCtx, chat, attachmentOption)\n  ZetariaStickerHost(chatsCtx, chat, attachmentOption)\n",
     "ZetariaStickerHost añadido a ComposeView")
once(p, "import chat.simplex.common.views.chat.item.ZetariaPollHost\n",
     "import chat.simplex.common.views.chat.item.ZetariaPollHost\nimport chat.simplex.common.views.chat.item.ZetariaStickerHost\n",
     "import de ZetariaStickerHost")

# 4) el sticker se dibuja sin burbuja
p = read(f"commonMain/{K}/views/chat/item/ChatItemView.kt")
anchor = "if (cItem.quotedItem == null && cItem.meta.itemForwarded == null && cItem.meta.itemDeleted == null && !cItem.meta.isLive) {\n"
hook = ("if (zetariaStickerAvailable(cItem)) {\n"
        "                ZetariaStickerItem(chat, cItem) { showMenu.value = true }\n"
        "              } else ")
once(p, anchor, hook + anchor, "sticker sin burbuja en ChatItemView")

# 5) archivos nuevos
img = src / "commonMain/resources/MR/images"
if not img.exists():
    die("no existe la carpeta de imágenes MR/images")
d = f"{K}/views/chat/item"
new_files = {
    src / f"commonMain/{d}/ZetariaStickers.kt": (tpl / "ZetariaStickers.common.kt").read_text(encoding="utf-8"),
    src / f"androidMain/{d}/ZetariaStickers.android.kt": (tpl / "ZetariaStickers.android.kt").read_text(encoding="utf-8"),
    img / "zet_sticker.svg": (tpl / "zet_sticker.svg").read_text(encoding="utf-8"),
}
if (src / "desktopMain").exists():
    new_files[src / f"desktopMain/{d}/ZetariaStickers.desktop.kt"] = (tpl / "ZetariaStickers.desktop.kt").read_text(encoding="utf-8")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
print(f"   [stickers] listo: {sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, {len(new_files)} nuevos")
