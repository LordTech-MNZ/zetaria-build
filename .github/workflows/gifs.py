#!/usr/bin/env python3
"""Zetaria: buscador de GIFs (KLIPY) — botón "GIFs" en el menú de adjuntos.

Requiere que ya estén aplicados los stickers (stickers.py): usa su fila de botones.
La clave de KLIPY puede ir incrustada con el secreto KLIPY_API_KEY de GitHub (opcional);
si no, el usuario la pega dentro de la app.
TRANSACCIONAL: si algún anclaje no coincide, no se modifica nada.

Uso: gifs.py <ruta a apps/multiplatform>
"""
import os, re, sys, pathlib

mp = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "gifs"
src = mp / "common/src"
K = "kotlin/chat/simplex/common"
edits, orig = {}, {}


def die(msg, ctx=None):
    print(f"\n::warning::GIFs NO aplicados: {msg}")
    print(f"ERROR (gifs): {msg}")
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
    print(f"   [gifs] {what}")


# 0) símbolos que usa el código nuevo
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
need(r"^const val MAX_IMAGE_SIZE_AUTO_RCV\b", f"{P}.views.helpers", "MAX_IMAGE_SIZE_AUTO_RCV")
need(r"^fun generateNewFileName\(", f"{P}.views.helpers", "generateNewFileName")
need(r"^(?:expect )?fun resizeImageToStrSize\(", f"{P}.platform", "resizeImageToStrSize")
need(r"^fun getAppFilePath\(fileName: String\)", f"{P}.platform", "getAppFilePath(String)")
need(r"^val chatController\b", f"{P}.platform", "chatController")
need(r"^(?:lateinit )?va[lr] chatModel\b", f"{P}.platform", "chatModel")
need(r"^(?:lateinit )?var androidAppContext\b", f"{P}.platform", "androidAppContext")
need(r"^fun writeCryptoFile\(", f"{P}.model", "writeCryptoFile")
if "coil.decode.ImageDecoderDecoder" not in "".join(texts.values()):
    die("este SimpleX no usa Coil con ImageDecoderDecoder")
print("   [gifs] símbolos de SimpleX verificados")

# 1) nueva opción en el menú (detrás de Stickers)
p = read(f"commonMain/{K}/views/helpers/ChooseAttachmentView.kt")
once(p, "  object Stickers: AttachmentOption()\n",
     "  object Stickers: AttachmentOption()\n  object Gifs: AttachmentOption()\n",
     "AttachmentOption.Gifs añadido")

# 2) botón "GIFs" (tercer hueco de la segunda fila: 0.5 de lo que queda = tercer cuarto)
p = read(f"androidMain/{K}/views/helpers/ChooseAttachmentView.android.kt")
anchor = ("        attachmentOption.value = AttachmentOption.Stickers\n"
          "        hide()\n"
          "      }\n")
button = ("      ActionButton(Modifier.fillMaxWidth(0.5f), null, \"GIFs\", icon = painterResource(MR.images.zet_gif)) {\n"
          "        attachmentOption.value = AttachmentOption.Gifs\n"
          "        hide()\n"
          "      }\n")
once(p, anchor, anchor + button, "botón GIFs añadido al menú")

# 3) panel dentro del compositor
p = read(f"commonMain/{K}/views/chat/ComposeView.kt")
once(p, "  ZetariaStickerHost(chatsCtx, chat, attachmentOption)\n",
     "  ZetariaStickerHost(chatsCtx, chat, attachmentOption)\n  ZetariaGifHost(chatsCtx, chat, attachmentOption)\n",
     "ZetariaGifHost añadido a ComposeView")
once(p, "import chat.simplex.common.views.chat.item.ZetariaStickerHost\n",
     "import chat.simplex.common.views.chat.item.ZetariaStickerHost\nimport chat.simplex.common.views.chat.item.ZetariaGifHost\n",
     "import de ZetariaGifHost")

# 4) archivos nuevos (la clave de la compilación se sanea: solo letras, números, _ y -)
key = os.environ.get("KLIPY_API_KEY", "").strip()
if key and not re.fullmatch(r"[A-Za-z0-9_\-]{8,200}", key):
    print("   AVISO: KLIPY_API_KEY tiene caracteres no válidos; se ignora (el usuario podrá pegarla en la app)")
    key = ""
print(f"   [gifs] clave incrustada en la app: {'sí' if key else 'no (se pedirá dentro de la app)'}")

img = src / "commonMain/resources/MR/images"
if not img.exists():
    die("no existe la carpeta de imágenes MR/images")
d = f"{K}/views/chat/item"
android_txt = (tpl / "ZetariaGifs.android.kt").read_text(encoding="utf-8").replace("%%KLIPY_KEY%%", key)
new_files = {
    src / f"commonMain/{d}/ZetariaGifs.kt": (tpl / "ZetariaGifs.common.kt").read_text(encoding="utf-8"),
    src / f"androidMain/{d}/ZetariaGifs.android.kt": android_txt,
    img / "zet_gif.svg": (tpl / "zet_gif.svg").read_text(encoding="utf-8"),
}
if (src / "desktopMain").exists():
    new_files[src / f"desktopMain/{d}/ZetariaGifs.desktop.kt"] = (tpl / "ZetariaGifs.desktop.kt").read_text(encoding="utf-8")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
print(f"   [gifs] listo: {sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, {len(new_files)} nuevos")
