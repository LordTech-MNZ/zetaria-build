#!/usr/bin/env python3
"""Zetaria: Esteganografía y códigos — botón "Esteganografía" en el menú de adjuntos.

13 herramientas: imagen<->audio en color y sin pérdida (códec ZIC2), imagen<->audio por
espectrograma (compatible con los audios antiguos), ocultar/descubrir archivos en audio y
mensajes en imágenes (AES-256-GCM + LSB), César, Morse (con audio guardable y transcripción
desde un audio de Morse), QR y códigos de barras (texto, imágenes, archivos y videos, con
cámara o cargando imágenes del teléfono).

Requiere que ya estén aplicados los GIFs (gifs.py), que a su vez necesitan stickers y encuestas.
Añade la dependencia ZXing mediante un script de inicio de Gradle (zetaria-zxing.init.gradle) que
el workflow pasa con -I; no se modifica ningún build.gradle.
TRANSACCIONAL: si algún anclaje no coincide, no se modifica nada.

Uso: stego.py <ruta a apps/multiplatform>
"""
import re, sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "stego"
src = mp / "common/src"
K = "kotlin/chat/simplex/common"
edits, orig = {}, {}


def die(msg, ctx=None):
    print(f"\n::warning::Esteganografía NO aplicada: {msg}")
    print(f"ERROR (stego): {msg}")
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
    print(f"   [stego] {what}")


# 0) símbolos que usa el código nuevo
kt = [f for f in src.rglob("*.kt") if "/build/" not in str(f).replace("\\", "/")]
texts = {f: f.read_text(encoding="utf-8") for f in kt}
alltext = "".join(texts.values())


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
need(r"^fun generateNewFileName\(", f"{P}.views.helpers", "generateNewFileName")
need(r"^(?:expect )?fun resizeImageToStrSize\(", f"{P}.platform", "resizeImageToStrSize")
need(r"^fun getAppFilePath\(fileName: String\)", f"{P}.platform", "getAppFilePath(String)")
need(r"^val chatController\b", f"{P}.platform", "chatController")
need(r"^(?:lateinit )?va[lr] chatModel\b", f"{P}.platform", "chatModel")
need(r"^(?:lateinit )?var androidAppContext\b", f"{P}.platform", "androidAppContext")
need(r"^fun writeCryptoFile\(", f"{P}.model", "writeCryptoFile")
need(r"^(?:expect )?fun QRCodeScanner\(", f"{P}.views.newchat", "QRCodeScanner")
for needed in ("import com.google.accompanist.permissions.rememberPermissionState",
               "import androidx.camera.lifecycle.ProcessCameraProvider",
               "import androidx.camera.view.PreviewView",
               "import chat.simplex.res.MR",
               "import dev.icerock.moko.resources.compose.painterResource"):
    if needed not in alltext:
        die(f"no encuentro «{needed}» en el código: falta una librería que este parche necesita")
print("   [stego] símbolos y librerías verificados")

# 1) nueva opción en el menú (detrás de Gifs)
p = read(f"commonMain/{K}/views/helpers/ChooseAttachmentView.kt")
once(p, "  object Gifs: AttachmentOption()\n",
     "  object Gifs: AttachmentOption()\n  object Stego: AttachmentOption()\n",
     "AttachmentOption.Stego añadido")

# 2) botón "Esteganografía" (cuarto hueco de la segunda fila: 1.0 de lo que queda = cuarto cuarto)
p = read(f"androidMain/{K}/views/helpers/ChooseAttachmentView.android.kt")
anchor = ("        attachmentOption.value = AttachmentOption.Gifs\n"
          "        hide()\n"
          "      }\n")
button = ("      ActionButton(Modifier.fillMaxWidth(1f), null, \"Estegano-\\ngrafía\", icon = painterResource(MR.images.zet_stego)) {\n"
          "        attachmentOption.value = AttachmentOption.Stego\n"
          "        hide()\n"
          "      }\n")
once(p, anchor, anchor + button, "botón Esteganografía añadido al menú")

# 3) panel dentro del compositor
p = read(f"commonMain/{K}/views/chat/ComposeView.kt")
once(p, "  ZetariaGifHost(chatsCtx, chat, attachmentOption)\n",
     "  ZetariaGifHost(chatsCtx, chat, attachmentOption)\n  ZetariaStegoHost(chatsCtx, chat, attachmentOption)\n",
     "ZetariaStegoHost añadido a ComposeView")
once(p, "import chat.simplex.common.views.chat.item.ZetariaGifHost\n",
     "import chat.simplex.common.views.chat.item.ZetariaGifHost\nimport chat.simplex.common.views.chat.item.ZetariaStegoHost\n",
     "import de ZetariaStegoHost")

# 4) archivos nuevos
img = src / "commonMain/resources/MR/images"
if not img.exists():
    die("no existe la carpeta de imágenes MR/images")
d = f"{K}/views/chat/item"
new_files = {
    src / f"commonMain/{d}/ZetariaStegoCore.kt": (tpl / "ZetariaStegoCore.kt").read_text(encoding="utf-8"),
    src / f"commonMain/{d}/ZetariaStego.kt": (tpl / "ZetariaStego.common.kt").read_text(encoding="utf-8"),
    src / f"androidMain/{d}/ZetariaStego.android.kt": (tpl / "ZetariaStego.android.kt").read_text(encoding="utf-8"),
    src / f"androidMain/{d}/ZetariaStegoCodes.android.kt": (tpl / "ZetariaStegoCodes.android.kt").read_text(encoding="utf-8"),
}
if (src / "desktopMain").exists():
    new_files[src / f"desktopMain/{d}/ZetariaStego.desktop.kt"] = (tpl / "ZetariaStego.desktop.kt").read_text(encoding="utf-8")
for svg in sorted(tpl.glob("*.svg")):
    new_files[img / svg.name] = svg.read_text(encoding="utf-8")

# 5) script de inicio de Gradle con ZXing (el workflow lo pasa con -I si existe)
init_path = mp.parents[2] / "zetaria-zxing.init.gradle"
new_files[init_path] = (tpl / "zetaria-zxing.init.gradle").read_text(encoding="utf-8")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
print(f"   [stego] listo: {sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, {len(new_files)} nuevos")
print(f"   [stego] ZXing se añadirá con {init_path.name}")
