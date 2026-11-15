#!/usr/bin/env python3
"""Zetaria: llamadas grupales (Jitsi) con botón en la barra del grupo.

  1) Botón de llamada en la barra superior de los grupos normales (Android; no canales ni chats de negocio) que
     abre la pantalla «Llamada grupal»: servidor configurable (por defecto el público de Jitsi, para pruebas),
     crea una sala con nombre aleatorio y publica el enlace en el grupo.
  2) Al tocar en un mensaje un enlace de llamada de Zetaria («https://servidor/Zetaria-xxxxxxxx...») se entra a la
     llamada dentro de la app (WebView con permisos de micrófono y cámara solo para ese servidor).
  3) Se copian ZetariaCall.{common,android,desktop}.kt.

Todo o nada: si algo no encaja termina con error SIN modificar nada (el paso del workflow tiene continue-on-error).
Uso: call.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
K = mp / "common/src/commonMain/kotlin/chat/simplex/common"
tpl = here / "templates" / "call"
changes = {}
IMPORT = "import chat.simplex.common.views.zetaria.ZetariaGroupCall\n"


def fail(msg):
    sys.exit(f"ERROR [call]: {msg}")


def sub_once(rel, old, new, what):
    p = K / rel
    if not p.exists():
        fail(f"no existe {rel}")
    t = changes.get(p, p.read_text(encoding="utf-8"))
    n = t.count(old)
    if n != 1:
        fail(f"{what}: el texto esperado aparece {n} veces en {rel} (debía ser 1)")
    changes[p] = t.replace(old, new, 1)


def add_import(rel):
    # (no se puede escribir «chat.simplex...» dentro de esas pantallas: variables locales como `chat` taparían al paquete)
    p = K / rel
    t = changes.get(p, p.read_text(encoding="utf-8"))
    if IMPORT in t:
        return
    if "\nimport " not in t:
        fail(f"{rel}: no encuentro la zona de imports")
    changes[p] = t.replace("\nimport ", "\n" + IMPORT + "import ", 1)


# 1) Botón en la barra superior del grupo
sub_once("views/chat/ChatView.kt",
         "  // Content filter button: always in bar on desktop and for groups; on Android for direct chats it\n",
         "  // Zetaria: llamada grupal (grupos normales; no canales ni chats de negocio)\n"
         "  if (appPlatform.isAndroid && chatInfo is ChatInfo.Group && !chatInfo.groupInfo.useRelays &&\n"
         "    chatInfo.groupInfo.businessChat == null && chatInfo.groupInfo.membership.memberRole >= GroupMemberRole.Member) {\n"
         "    barButtons.add {\n"
         "      IconButton({ ZetariaGroupCall.start(chatInfo.groupInfo) }) {\n"
         "        Icon(painterResource(MR.images.ic_call_500), \"Llamada grupal\", tint = MaterialTheme.colors.primary)\n"
         "      }\n"
         "    }\n"
         "  }\n\n"
         "  // Content filter button: always in bar on desktop and for groups; on Android for direct chats it\n",
         "botón de llamada grupal")
add_import("views/chat/ChatView.kt")

# 2) Tocar un enlace de llamada en un mensaje = entrar a la llamada
sub_once("views/chat/item/TextItemView.kt",
         "fun openBrowserAlert(uri: String, uriHandler: UriHandler) {\n",
         "fun openBrowserAlert(uri: String, uriHandler: UriHandler) {\n"
         "  if (ZetariaGroupCall.isCallLink(uri)) { ZetariaGroupCall.join(uri); return }\n",
         "enlace de llamada en mensajes")
add_import("views/chat/item/TextItemView.kt")

# 3) Archivos Kotlin
targets = {
    "ZetariaCall.common.kt": mp / "common/src/commonMain/kotlin/chat/simplex/common/views/zetaria/ZetariaCall.common.kt",
    "ZetariaCall.android.kt": mp / "common/src/androidMain/kotlin/chat/simplex/common/views/zetaria/ZetariaCall.android.kt",
    "ZetariaCall.desktop.kt": mp / "common/src/desktopMain/kotlin/chat/simplex/common/views/zetaria/ZetariaCall.desktop.kt",
}
for name, dest in targets.items():
    src = tpl / name
    if not src.exists():
        fail(f"falta templates/call/{name}")
    changes[dest] = src.read_text(encoding="utf-8")

for p, content in changes.items():
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
print(f"   [call] llamadas grupales: botón en el grupo + entrada por enlace ({len(changes)} archivos)")
