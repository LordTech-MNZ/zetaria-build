#!/usr/bin/env python3
"""Zetaria: botón «Roles de miembros» en la configuración de los grupos.

  1) GroupMemberRole.text() pasa a buscar primero un nombre propio guardado en el dispositivo (lápiz) y, si no
     hay, usa el nombre original (que queda en defaultText()). Así el nombre propio se ve en toda la app.
  2) En Información del grupo (sección «Editar perfil / Mensaje de bienvenida / Preferencias») aparece el botón
     «Roles de miembros», que abre ZetariaRolesView: renombrar roles con el lápiz y asignar roles a miembros.
  3) Se copia ZetariaRoles.kt.

Los roles en sí (observador, miembro, moderador, administrador, propietario) los fija el núcleo de SimpleX: no se
pueden crear roles nuevos sin recompilar el núcleo.

Todo o nada: si algo no encaja termina con error SIN modificar nada (el paso del workflow tiene continue-on-error).
Uso: roles.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
K = mp / "common/src/commonMain/kotlin/chat/simplex/common"
changes = {}


def fail(msg):
    sys.exit(f"ERROR [roles]: {msg}")


def sub_once(rel, old, new, what):
    p = K / rel
    if not p.exists():
        fail(f"no existe {rel}")
    t = changes.get(p, p.read_text(encoding="utf-8"))
    n = t.count(old)
    if n != 1:
        fail(f"{what}: el texto esperado aparece {n} veces en {rel} (debía ser 1)")
    changes[p] = t.replace(old, new, 1)


# 1) Nombre propio de los roles
sub_once("model/ChatModel.kt",
         "  fun text(isChannel: Boolean): String = when (this) {\n    Relay -> generalGetString(MR.strings.group_member_role_relay)\n",
         "  // Zetaria: nombre propio del rol (si lo hay) o el original\n"
         "  fun text(isChannel: Boolean): String =\n"
         "    chat.simplex.common.views.zetaria.ZetariaRoles.aliasFor(this) ?: defaultText(isChannel)\n\n"
         "  fun defaultText(isChannel: Boolean): String = when (this) {\n"
         "    Relay -> generalGetString(MR.strings.group_member_role_relay)\n",
         "GroupMemberRole.text")

# 2) Botón en la información del grupo
anchor = "        GroupPreferencesButton(prefsTitleId, openPreferences)\n"
button = (
    "        if (groupInfo.businessChat == null) {\n"
    "          // Zetaria: roles de miembros (asignar roles y ponerles nombre propio)\n"
    "          SettingsActionItem(\n"
    "            painterResource(MR.images.ic_manage_accounts),\n"
    "            \"Roles de miembros\",\n"
    "            {\n"
    "              ModalManager.end.showCustomModal { close ->\n"
    "                ZetariaRolesView(chat.remoteHostId, chat.id, close)\n"
    "              }\n"
    "            },\n"
    "            iconColor = MaterialTheme.colors.secondary\n"
    "          )\n"
    "        }\n"
)
sub_once("views/chat/group/GroupChatInfoView.kt", anchor, button + anchor, "botón de roles")
# Import (no se puede escribir «chat.simplex...» en esa pantalla: la variable local `chat` taparía al paquete)
sub_once("views/chat/group/GroupChatInfoView.kt", "import chat.simplex.res.MR\n",
         "import chat.simplex.res.MR\nimport chat.simplex.common.views.zetaria.ZetariaRolesView\n", "import de ZetariaRolesView")

# 3) Pantalla
src = here / "templates/roles/ZetariaRoles.kt"
if not src.exists():
    fail("falta templates/roles/ZetariaRoles.kt")
dest = K / "views/zetaria/ZetariaRoles.kt"
changes[dest] = src.read_text(encoding="utf-8")

for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
print(f"   [roles] botón «Roles de miembros» añadido ({len(changes)} archivos)")
