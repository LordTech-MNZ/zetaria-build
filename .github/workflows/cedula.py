#!/usr/bin/env python3
"""Zetaria: «Crear cédula del Reino Soberano de Zetaria» (sustituye a «Obtener nombre SimpleX (BETA)»).

En Ajustes > Dirección, el botón del nombre de SimpleX pasa a ser el de la cédula. Dentro:
  - ID único aleatorio (ZET-XXXX-XXXX-XXXX-XXXX-XXXX) que se genera una sola vez al instalar la app y no cambia.
    Vive en un archivo interno (zetaria_id) de la carpeta de la base de datos y en los ajustes de la app, así que
    viaja con la exportación/importación de la base de datos.
  - Cédula HORIZONTAL con bandera: nombre, foto de perfil, el mismo QR de la dirección Zetaria, ID y datos básicos
    (fecha de nacimiento/edad, estado civil, profesión, trabajo, ciudad) que se piden al crearla.
  - El ID se muestra como texto copiable bajo la cédula.
  - «Renovar cédula» vuelve a tomar nombre, foto, enlace y datos; el ID se queda igual.
  - «Compartir cédula» genera una imagen PNG de la cédula.
  - La cédula es POR PERFIL (cada perfil tiene la suya); el ID es el de la instalación.
  - El ID se publica SIEMPRE en cada perfil (aunque no tenga cédula) mediante una línea OCULTA en la descripción del
    perfil; si el perfil ya creó su cédula, la línea lleva también los datos. Los contactos ven el ID y, si hay cédula,
    el botón «Ver cédula» en su ficha (sin ID: «Sin ID de Zetaria»).
  - «Guardar en la galería» deja la cédula en Imágenes/Identificación Zetaria. Zetaria oculta esa línea en la descripción y en el
    editor de perfil, y no se puede borrar desde Zetaria.

Además: los archivos internos zetaria_* no cuentan ni se borran en «Archivos y multimedia» (para no perder el ID).

TRANSACCIONAL (todo o nada), salvo el gancho de arranque (opcional: si no encaja, el ID se crea al abrir la cédula).
Uso: cedula.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
tpl = here / "templates" / "cedula"
src = mp / "common/src"
K = "kotlin/chat/simplex/common"
edits, orig = {}, {}


def die(msg, ctx=None):
    print(f"\n::warning::Cédula NO aplicada: {msg}")
    print(f"ERROR (cedula): {msg}")
    if ctx:
        print("----- contexto -----")
        print(ctx)
        print("--------------------")
    print("No se modificó ningún archivo.")
    sys.exit(1)


def read(path, label):
    if not path.exists():
        die(f"no existe {label}")
    if path not in orig:
        orig[path] = path.read_text(encoding="utf-8")
        edits[path] = orig[path]
    return path


def once(p, old, new, what):
    n = edits[p].count(old)
    if n != 1:
        die(f"{what}: el texto esperado aparece {n} veces (debía ser 1) en {p.name}", old)
    edits[p] = edits[p].replace(old, new, 1)
    print(f"   [cedula] {what}")


# 1) Dirección: el botón del nombre de SimpleX pasa a ser el de la cédula
rel = f"commonMain/{K}/views/usersettings/UserAddressView.kt"
p = read(src / rel, rel)
once(p, "import SectionViewWithButton\n",
     "import SectionViewWithButton\n"
     "import chat.simplex.common.views.zetaria.ZetariaCedula\n"
     "import chat.simplex.common.views.zetaria.ZetariaCedulaView\n",
     "imports de la cédula")

t = edits[p]
start_marker = "          val domain = user?.profile?.contactDomain?.domain\n"
end_marker = "              iconColor = MaterialTheme.colors.secondary\n            )\n          }\n"
if t.count(start_marker) != 1:
    die(f"no encuentro el bloque del nombre de SimpleX ({t.count(start_marker)} coincidencias)", start_marker)
i = t.index(start_marker)
j = t.find(end_marker, i)
if j < 0 or "SetSimplexDomainView(" not in t[i:j] or "get_simplex_name_beta" not in t[i:j]:
    die("el bloque del nombre de SimpleX no tiene la forma esperada", t[i:i + 600])
j += len(end_marker)
block = (
    "          SectionView {\n"
    "            SettingsActionItem(\n"
    "              painterResource(MR.images.ic_qr_code),\n"
    "              ZetariaCedula.buttonLabel(user),\n"
    "              click = {\n"
    "                ModalManager.start.showModal(cardScreen = true) { ZetariaCedulaView(user, userAddress.connLinkContact) }\n"
    "              },\n"
    "              iconColor = MaterialTheme.colors.secondary\n"
    "            )\n"
    "          }\n"
)
edits[p] = t[:i] + block + t[j:]
print("   [cedula] botón de la cédula en vez de «Obtener nombre SimpleX»")

# 2) Los archivos internos zetaria_* no se cuentan ni se borran con «Eliminar archivos»
rel = f"commonMain/{K}/views/helpers/Utils.kt"
p = read(src / rel, rel)
once(p, "    File(dir).listFiles()?.forEach {\n      fileCount++\n",
     "    File(dir).listFiles()?.filter { !it.name.startsWith(\"zetaria_\") }?.forEach {\n      fileCount++\n",
     "contador de archivos ignora zetaria_*")
once(p, "    dir.list()?.forEach {\n      removeFile(it)\n    }\n",
     "    dir.list()?.forEach {\n      if (!it.startsWith(\"zetaria_\")) removeFile(it)\n    }\n",
     "«eliminar todo» respeta zetaria_*")

# 3) Gancho de arranque (opcional): el ID se crea al abrir la app por primera vez
hook_path = mp / "android/src/main/java/chat/simplex/app/SimplexApp.kt"
hook_old = "    deleteOldChatArchive()\n"
hook_done = False
if hook_path.exists():
    ht = hook_path.read_text(encoding="utf-8")
    if ht.count(hook_old) == 1:
        orig[hook_path] = ht
        edits[hook_path] = ht.replace(hook_old, hook_old + "    chat.simplex.common.views.zetaria.ZetariaCedula.ensureId()\n", 1)
        hook_done = True
        print("   [cedula] el ID se crea al abrir la app por primera vez")
if not hook_done:
    print("   [cedula] aviso: no encontré el punto de arranque; el ID se creará al abrir la cédula (sigue siendo único y permanente)")

# 3b) Ficha del contacto: ID visible y botón «Ver cédula»
rel = f"commonMain/{K}/views/chat/ChatInfoView.kt"
p = read(src / rel, rel)
once(p, "import chat.simplex.res.MR\n",
     "import chat.simplex.res.MR\nimport chat.simplex.common.views.zetaria.ZetariaContactSection\n",
     "import de ZetariaContactSection")
once(p, "    LocalAliasEditor(chat.id, localAlias, updateValue = onLocalAliasChanged)\n\n    SectionDividerSpaced()\n",
     "    LocalAliasEditor(chat.id, localAlias, updateValue = onLocalAliasChanged)\n\n    SectionDividerSpaced()\n\n    ZetariaContactSection(contact)\n",
     "sección de cédula en la ficha del contacto")

# 3a) La línea de cédula queda oculta: no se ve en la descripción ni en el editor de perfil, y el editor la conserva
Z = "chat.simplex.common.views.zetaria.ZetariaCedula"
p = read(src / f"commonMain/{K}/views/chat/ChatInfoView.kt", "ChatInfoView.kt")
once(p, "  val descr = description?.trim()?.ifEmpty { null }\n",
     f"  val descr = {Z}.stripLine(description).trim().ifEmpty {{ null }}\n",
     "descripción sin la línea de cédula")
p = read(src / f"commonMain/{K}/views/usersettings/UserProfileView.kt", "UserProfileView.kt")
once(p, "description = description.trim().ifEmpty { null }, image = image)",
     f"description = {Z}.withLine(description, profile.description), image = image)",
     "el editor conserva la línea de cédula al guardar")
once(p, '  val description = remember { mutableStateOf(profile.description ?: "") }\n',
     f'  val description = remember {{ mutableStateOf({Z}.stripLine(profile.description)) }}\n',
     "el editor no muestra la línea de cédula")
once(p, '            description.value.trim() == (profile.description ?: "") &&\n',
     f'            description.value.trim() == {Z}.stripLine(profile.description) &&\n',
     "comparación del editor sin la línea de cédula")

# 3d) El ID se publica en cada perfil SIEMPRE (aunque no haya cédula): al abrir la lista de chats con ese perfil activo
rel = f"commonMain/{K}/views/chatlist/ChatListView.kt"
p = read(src / rel, rel)
once(p, "  val oneHandUI = remember { appPrefs.oneHandUI.state }\n\n  LaunchedEffect(Unit) {\n    val showWhatsNew",
     "  val oneHandUI = remember { appPrefs.oneHandUI.state }\n\n"
     "  val zetariaUser = chatModel.currentUser.value\n"
     f"  LaunchedEffect(zetariaUser?.userId) {{ {Z}.publish(zetariaUser) }}\n\n"
     "  LaunchedEffect(Unit) {\n    val showWhatsNew",
     "el ID se publica en el perfil activo aunque no tenga cédula")

# 3c) Bandera del reino: drawable de Android (como el logo de los QR), junto al icono original
flag = here / "branding" / "zetaria_flag.png"
if not flag.exists():
    die(f"falta {flag}")
res_root = None
for f in sorted(mp.rglob("icon_foreground_android_common.*"),
                key=lambda f: (0 if f.relative_to(mp).parts[0] == "common" else 1, str(f))):
    parts = f.parts
    if "res" in parts:
        i = len(parts) - 1 - parts[::-1].index("res")
        res_root = pathlib.Path(*parts[:i + 1])
        break
if res_root is None:
    die("no encuentro la carpeta res donde vive icon_foreground_android_common")

# 4) Archivos nuevos
d = f"{K}/views/zetaria"
new_files = {
    src / f"commonMain/{d}/ZetariaCedula.kt": (tpl / "ZetariaCedula.kt").read_text(encoding="utf-8"),
    src / f"androidMain/{d}/ZetariaCedula.android.kt": (tpl / "ZetariaCedula.android.kt").read_text(encoding="utf-8"),
}
if (src / "desktopMain").exists():
    new_files[src / f"desktopMain/{d}/ZetariaCedula.desktop.kt"] = (tpl / "ZetariaCedula.desktop.kt").read_text(encoding="utf-8")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
(res_root / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
(res_root / "drawable-nodpi" / "zet_flag.png").write_bytes(flag.read_bytes())
print(f"   [cedula] listo: {sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, {len(new_files)} nuevos")
