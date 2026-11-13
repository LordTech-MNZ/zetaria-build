#!/usr/bin/env python3
"""Zetaria: botón «Roles de miembros» en la configuración de los grupos.

  1) GroupMemberRole.text() pasa a buscar primero un nombre propio guardado en el dispositivo (lápiz) y, si no
     hay, usa el nombre original (que queda en defaultText()). Así el nombre propio se ve en toda la app.
  2) En Información del grupo (sección «Editar perfil / Mensaje de bienvenida / Preferencias») aparece el botón
     «Roles de miembros», que abre ZetariaRolesView: renombrar roles con el lápiz y asignar roles a miembros.
  3) Se copia ZetariaRoles.kt.
  4) Apodo de rol INDIVIDUAL (opcional, desde el lápiz): se muestra en la lista de miembros, en la burbuja de cada
     mensaje del grupo, en la ficha del miembro y en las listas de canal/soporte.
  Jerarquía: solo propietario, administrador y moderador pueden cambiar nombres y apodos (ver ZetariaRoles.kt).

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

# 2b) Apodo individual: dónde se muestra el rol de un miembro --------------------------------------------------
IMPORT = "import chat.simplex.common.views.zetaria.ZetariaRoles\n"


def add_import(rel):
    # (no se puede escribir «chat.simplex...» dentro de esas pantallas: variables locales como `chat` taparían al paquete)
    p = K / rel
    t = changes.get(p, p.read_text(encoding="utf-8"))
    if IMPORT in t:
        return
    if "\nimport " not in t:
        fail(f"{rel}: no encuentro la zona de imports")
    changes[p] = t.replace("\nimport ", "\n" + IMPORT + "import ", 1)


# lista de miembros (y todas las pantallas que usan MemberRow)
sub_once("views/chat/group/GroupChatInfoView.kt",
         "      if (role in listOf(GroupMemberRole.Owner, GroupMemberRole.Admin, GroupMemberRole.Moderator, GroupMemberRole.Observer)) {\n"
         "        Text(role.text(isChannel = isChannel), color = MaterialTheme.colors.secondary)\n",
         "      if (role in listOf(GroupMemberRole.Owner, GroupMemberRole.Admin, GroupMemberRole.Moderator, GroupMemberRole.Observer) ||\n"
         "        ZetariaRoles.memberAlias(member) != null) {\n"
         "        Text(ZetariaRoles.roleLabel(member, isChannel), color = MaterialTheme.colors.secondary)\n",
         "rol en MemberRow")
add_import("views/chat/group/GroupChatInfoView.kt")

# etiqueta del rol sobre los mensajes del grupo
sub_once("views/chat/ChatView.kt",
         "if (memCount == 1 && member.memberRole > GroupMemberRole.Member) {",
         "if (memCount == 1 && (member.memberRole > GroupMemberRole.Member || ZetariaRoles.memberAlias(member) != null)) {",
         "condición de la etiqueta de rol en mensajes")
sub_once("views/chat/ChatView.kt",
         "member.memberRole.text(isChannel = chatInfo.isChannel),",
         "ZetariaRoles.roleLabel(member, chatInfo.isChannel),",
         "etiqueta de rol en mensajes")
add_import("views/chat/ChatView.kt")

# ficha del miembro (cuando no se puede cambiar su rol)
sub_once("views/chat/group/GroupMemberInfoView.kt",
         "member.memberRole.text(isChannel = groupInfo.isChannel))",
         "ZetariaRoles.roleLabel(member, groupInfo.isChannel))",
         "rol en la ficha del miembro")
add_import("views/chat/group/GroupMemberInfoView.kt")

# listas de canal y de soporte
sub_once("views/chat/group/ChannelMembersView.kt",
         "        member.memberRole.text(isChannel = isChannel),\n",
         "        ZetariaRoles.roleLabel(member, isChannel),\n",
         "rol en ChannelMembersView")
add_import("views/chat/group/ChannelMembersView.kt")
sub_once("views/chat/group/MemberSupportView.kt",
         "      member.memberRole.text(isChannel = isChannel)\n",
         "      ZetariaRoles.roleLabel(member, isChannel)\n",
         "rol en MemberSupportView")
add_import("views/chat/group/MemberSupportView.kt")

# 3) Pantalla
src = here / "templates/roles/ZetariaRoles.kt"
if not src.exists():
    fail("falta templates/roles/ZetariaRoles.kt")
dest = K / "views/zetaria/ZetariaRoles.kt"
changes[dest] = src.read_text(encoding="utf-8")

for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
print(f"   [roles] botón «Roles de miembros» + apodos individuales ({len(changes)} archivos)")
