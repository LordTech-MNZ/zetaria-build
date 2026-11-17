#!/usr/bin/env python3
"""Zetaria: Feed de publicaciones (pestaña «Feed» al lado de «Chats»).

- Publicaciones con texto, imágenes y videos de la gente que tienes agregada, de más nueva a más antigua.
- Imágenes y videos se pueden guardar en la galería, en la carpeta «Zetaria».
- Aviso (globito con número) en la pestaña «Feed» cuando hay publicaciones nuevas.
- Reacciones (👍 ❤️ 😂 😮 😢 🙏) y comentarios, visibles para todos los que recibieron la publicación
  (el autor reenvía lo que recibe al resto de sus contactos).
- Sin cambios de protocolo: todo son mensajes normales con una cabecera (ver ZetariaFeed.kt).
- Los mensajes del feed no se ven en el chat, no mueven ni cambian la fila de la lista de chats, no cuentan como
  «sin leer» y no generan notificaciones.

Transaccional (todo o nada). Uso: feed.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).resolve().parent
tpl = here / "templates" / "feed"
K = "kotlin/chat/simplex/common"
base = mp / "common/src/commonMain"


def die(msg):
    sys.exit(f"ERROR [feed] {msg}")


orig, edits = {}, {}


def load(rel):
    p = base / rel
    if not p.exists():
        die(f"no existe {p}")
    if p not in orig:
        orig[p] = p.read_text(encoding="utf-8")
        edits[p] = orig[p]
    return p


def once(p, old, new, what):
    n = edits[p].count(old)
    if n != 1:
        die(f"{what}: el texto esperado aparece {n} veces (debía ser 1)")
    edits[p] = edits[p].replace(old, new, 1)


def add_import(p, line):
    t = edits[p]
    pkg = line.rsplit(".", 1)[0] + ".*"
    if line in t or pkg in t:
        return
    m = __import__("re").search(r"^import .*$", t, __import__("re").M)
    if not m:
        die(f"{p.name} no tiene imports")
    edits[p] = t[:m.start()] + line + "\n" + t[m.start():]


IMP_FN = "import chat.simplex.common.views.chat.item."

# 1) Lista de chats: pestañas «Chats | Feed», pantalla del feed y botón de chat nuevo oculto en el feed
p = load(f"{K}/views/chatlist/ChatListView.kt")
once(p,
     "          Text(\n"
     "            stringResource(MR.strings.your_chats),\n"
     "            color = MaterialTheme.colors.onBackground,\n"
     "            fontWeight = FontWeight.SemiBold,\n"
     "          )\n",
     "          ZetariaFeedTabs(stringResource(MR.strings.your_chats))\n",
     "título «Chats» de la barra")
old = "      ChatListWithLoadingScreen(searchText, listState)\n"
if edits[p].count(old) != 2:
    die(f"ChatListWithLoadingScreen aparece {edits[p].count(old)} veces (debían ser 2)")
edits[p] = edits[p].replace(
    old,
    "      if (zetariaFeedOpen.value) {\n"
    "        ZetariaFeedScreen()\n"
    "      } else {\n"
    "        ChatListWithLoadingScreen(searchText, listState)\n"
    "      }\n")
once(p,
     "      if (searchText.value.text.isEmpty() && !chatModel.desktopNoUserNoRemote && chatModel.chatRunning.value == true) {\n"
     "        NewChatSheetFloatingButton(oneHandUI, stopped)\n",
     "      if (searchText.value.text.isEmpty() && !zetariaFeedOpen.value && !chatModel.desktopNoUserNoRemote && chatModel.chatRunning.value == true) {\n"
     "        NewChatSheetFloatingButton(oneHandUI, stopped)\n",
     "botón de chat nuevo")
add_import(p, IMP_FN + "*")

# 2) Lista de chats: la fila muestra el último mensaje real, no la publicación enviada
p = load(f"{K}/views/chatlist/ChatPreviewView.kt")
once(p,
     "  defaultClickAction: () -> Unit\n) {\n  val cInfo = chat.chatInfo\n",
     "  defaultClickAction: () -> Unit\n) {\n  val chat = zetariaPreviewChat(chat)\n  val cInfo = chat.chatInfo\n",
     "inicio de ChatPreviewView")
add_import(p, IMP_FN + "*")

# 2b) Los mensajes del feed no entran al modelo de la lista (ni suben el chat, ni cuentan como sin leer)
p = load(f"{K}/model/ChatModel.kt")
once(p,
     "    suspend fun addChatItem(rhId: Long?, chatInfo: ChatInfo, cItem: ChatItem) {\n",
     "    suspend fun addChatItem(rhId: Long?, chatInfo: ChatInfo, cItem: ChatItem) {\n"
     "      if (chatInfo is ChatInfo.Direct && zetariaIsFeedItem(cItem)) return\n",
     "addChatItem")
once(p,
     "    suspend fun upsertChatItem(rhId: Long?, cInfo: ChatInfo, cItem: ChatItem): Boolean {\n",
     "    suspend fun upsertChatItem(rhId: Long?, cInfo: ChatInfo, cItem: ChatItem): Boolean {\n"
     "      if (cInfo is ChatInfo.Direct && zetariaIsFeedItem(cItem)) return false\n",
     "upsertChatItem")
add_import(p, IMP_FN + "zetariaIsFeedItem")

# 3) Dentro del chat los mensajes del feed no se dibujan
p = load(f"{K}/views/chat/ChatView.kt")
once(p,
     "      if (itemSeparation.date != null) {\n"
     "        DateSeparator(itemSeparation.date)\n"
     "      }\n"
     "      ChatItemView(cItem, range, itemSeparation, previousItemSeparationLargeGap)\n",
     "      if (!zetariaIsFeedItem(cItem)) {\n"
     "        if (itemSeparation.date != null) {\n"
     "          DateSeparator(itemSeparation.date)\n"
     "        }\n"
     "        ChatItemView(cItem, range, itemSeparation, previousItemSeparationLargeGap)\n"
     "      }\n",
     "dibujo de cada mensaje en ChatView")
add_import(p, IMP_FN + "*")

# 4) Sin notificaciones por mensajes del feed
p = load(f"{K}/platform/NtfManager.kt")
once(p,
     "      cItem.showNotification &&\n",
     "      cItem.showNotification &&\n"
     "      !zetariaIsFeedItem(cItem) &&\n",
     "condición de notificación")
add_import(p, IMP_FN + "zetariaIsFeedItem")

# 5) Mensajes que llegan: leídos al instante, al feed y reenvío del autor
p = load(f"{K}/model/SimpleXAPI.kt")
once(p,
     "          ntfManager.notifyMessageReceived(rhId, r.user, cInfo, cItem)\n",
     "          zetariaFeedOnNewItem(rhId, r.user, cInfo, cItem)\n"
     "          ntfManager.notifyMessageReceived(rhId, r.user, cInfo, cItem)\n",
     "llegada de mensajes nuevos")
add_import(p, IMP_FN + "zetariaFeedOnNewItem")

# 6) Archivos nuevos (común, Android y, si existe, escritorio)
d = f"{K}/views/chat/item"
new_files = {
    base / d / "ZetariaFeed.kt": (tpl / "ZetariaFeed.kt").read_text(encoding="utf-8"),
    mp / "common/src/androidMain" / d / "ZetariaFeed.android.kt": (tpl / "ZetariaFeed.android.kt").read_text(encoding="utf-8"),
}
if (mp / "common/src/desktopMain").exists():
    new_files[mp / "common/src/desktopMain" / d / "ZetariaFeed.desktop.kt"] = (tpl / "ZetariaFeed.desktop.kt").read_text(encoding="utf-8")
for f in new_files:
    if f.exists():
        die(f"{f.name} ya existe")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
for pth, txt in new_files.items():
    pth.parent.mkdir(parents=True, exist_ok=True)
    pth.write_text(txt, encoding="utf-8")
print(f"   [feed] listo: {sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, {len(new_files)} nuevos")
