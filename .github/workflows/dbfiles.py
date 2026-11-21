#!/usr/bin/env python3
"""Zetaria: explorador de archivos en «Datos del chat» (base de datos).

En Ajustes > Datos del chat > «Archivos y multimedia» aparece un botón nuevo que abre la lista de archivos que guarda
la app, del más pesado al más ligero (con miniaturas, filtros por tipo y orden por tamaño o fecha). La persona marca
solo los que quiere y los elimina, sin necesidad de usar el botón que borra TODA la multimedia.

TRANSACCIONAL (todo o nada): si algún anclaje del código de SimpleX no coincide, no se modifica nada.
Uso: dbfiles.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "files"
src = mp / "common/src"
K = "kotlin/chat/simplex/common"
edits, orig = {}, {}


def die(msg, ctx=None):
    print(f"\n::warning::Explorador de archivos NO aplicado: {msg}")
    print(f"ERROR (dbfiles): {msg}")
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
    print(f"   [dbfiles] {what}")


p = read(f"commonMain/{K}/views/database/DatabaseView.kt")
once(p, "import SectionView\n",
     "import SectionView\nimport chat.simplex.common.views.zetaria.ZetariaFilesView\n",
     "import de ZetariaFilesView")

anchor = "      val deleteFilesDisabled = operationsDisabled || appFilesCountAndSize.value.first == 0\n"
button = (
    "      SettingsActionItem(\n"
    "        painterResource(MR.images.ic_delete),\n"
    "        if (java.util.Locale.getDefault().language == \"es\") \"Ver archivos y elegir cuáles eliminar\" else \"Browse files and choose which to delete\",\n"
    "        click = { ModalManager.start.showModal(cardScreen = true) { ZetariaFilesView(appFilesCountAndSize) } },\n"
    "        disabled = deleteFilesDisabled\n"
    "      )\n"
)
once(p, anchor, anchor + button, "botón «Ver archivos» en Archivos y multimedia")

d = f"{K}/views/zetaria"
new_files = {
    src / f"commonMain/{d}/ZetariaFiles.kt": (tpl / "ZetariaFiles.kt").read_text(encoding="utf-8"),
    src / f"androidMain/{d}/ZetariaFiles.android.kt": (tpl / "ZetariaFiles.android.kt").read_text(encoding="utf-8"),
}
if (src / "desktopMain").exists():
    new_files[src / f"desktopMain/{d}/ZetariaFiles.desktop.kt"] = (tpl / "ZetariaFiles.desktop.kt").read_text(encoding="utf-8")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
print(f"   [dbfiles] listo: {sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, {len(new_files)} nuevos")
