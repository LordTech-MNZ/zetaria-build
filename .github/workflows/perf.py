#!/usr/bin/env python3
"""Zetaria: carga de chats más rápida (cambios pequeños y conservadores).

1) Paginación (SimpleXAPI.kt): se leen menos mensajes al abrir un chat (75 -> 50 en Android) y por cada tramo al
   desplazarse (100 -> 75; umbral de precarga 50 -> 30). El primer dibujo del chat es más rápido; al subir se siguen
   cargando mensajes de forma continua.
2) Feed (ZetariaFeed.kt, si está aplicado): la lectura de TODOS los chats para el globito de «Feed» ya no compite con
   la apertura de tus chats al arrancar (espera 8 s), y las lecturas que corrigen el «último mensaje» de la lista de
   chats se hacen de 2 en 2 en vez de todas a la vez.
Transaccional (todo o nada). Uso: perf.py <apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
base = mp / "common/src/commonMain/kotlin/chat/simplex/common"
orig, edits = {}, {}


def die(m):
    sys.exit(f"ERROR [perf] {m}. No se modificó ningún archivo.")


def load(p):
    if not p.exists():
        die(f"no existe {p.name}")
    orig[p] = p.read_text(encoding="utf-8")
    edits[p] = orig[p]
    return p


def once(p, old, new, what):
    n = edits[p].count(old)
    if n != 1:
        die(f"{what}: el texto esperado aparece {n} veces (debía ser 1) en {p.name}")
    edits[p] = edits[p].replace(old, new, 1)
    print(f"   [perf] {what}")


p = load(base / "model/SimpleXAPI.kt")
once(p,
     "    val INITIAL_COUNT = if (appPlatform.isDesktop) 100 else 75\n"
     "    const val PRELOAD_COUNT = 100\n"
     "    const val UNTIL_PRELOAD_COUNT = 50\n",
     "    val INITIAL_COUNT = if (appPlatform.isDesktop) 100 else 50\n"
     "    const val PRELOAD_COUNT = 75\n"
     "    const val UNTIL_PRELOAD_COUNT = 30\n",
     "paginación de chats más ligera")

feed = base / "views/chat/item/ZetariaFeed.kt"
if feed.exists():
    p = load(feed)
    once(p,
         "private suspend fun zfLastRealItem(chat: Chat): ChatItem? {\n"
         "  val res = zfGetChatQuiet(chat.remoteHostId, chat.chatInfo.apiId, ChatPagination.Last(100), \"\")\n"
         "    ?: throw IllegalStateException(\"chat not readable\")\n"
         "  return res.first.chatItems.lastOrNull { !zetariaIsFeedItem(it) }\n"
         "}\n",
         "private val zfReadGate = Semaphore(2)\n\n"
         "private suspend fun zfLastRealItem(chat: Chat): ChatItem? {\n"
         "  zfReadGate.acquire()\n"
         "  try {\n"
         "    val res = zfGetChatQuiet(chat.remoteHostId, chat.chatInfo.apiId, ChatPagination.Last(100), \"\")\n"
         "      ?: throw IllegalStateException(\"chat not readable\")\n"
         "    return res.first.chatItems.lastOrNull { !zetariaIsFeedItem(it) }\n"
         "  } finally {\n"
         "    zfReadGate.release()\n"
         "  }\n"
         "}\n",
         "lecturas del último mensaje real: máx. 2 a la vez")
    once(p,
         "      withContext(Dispatchers.IO) {\n        try {\n          zetariaCleanGalleryDuplicates()\n",
         "      withContext(Dispatchers.IO) {\n        delay(8000)\n        try {\n          zetariaCleanGalleryDuplicates()\n",
         "el feed espera 8 s al arrancar antes de leer todos los chats")
else:
    print("   [perf] aviso: no hay Feed aplicado; solo se ajustó la paginación")

for q, txt in edits.items():
    if txt != orig[q]:
        q.write_text(txt, encoding="utf-8")
print("   [perf] listo")
