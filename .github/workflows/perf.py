#!/usr/bin/env python3
"""Zetaria: carga de chats más rápida.

Un solo cambio, seguro y acotado: al abrir un chat se leen menos mensajes de golpe
(SimpleXAPI.kt): 75 -> 50 en el teléfono para el primer tramo, 100 -> 75 por cada tramo
siguiente al desplazarse hacia arriba, y el umbral que dispara la precarga del tramo
siguiente baja de 50 a 30. El chat se dibuja antes; al subir se sigue cargando todo con
normalidad.

(Antes esta misma optimización tocaba también ZetariaFeed.kt -un semáforo mal puesto y
una espera que no frenaba lo que debía- y eso causaba filas vacías y orden inestable en
la lista de Chats. Ya se corrigió directamente en el Feed (templates/feed/ZetariaFeed.kt):
el barrido que arma el Feed ahora lee hasta 4 chats a la vez en vez de uno por uno, sin
tocar la lista de Chats para nada; y la fila nunca vuelve a quedar vacía. Por eso este
script ya no modifica el Feed.)

Transaccional (todo o nada). Uso: perf.py <apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
p = mp / "common/src/commonMain/kotlin/chat/simplex/common/model/SimpleXAPI.kt"
if not p.exists():
    sys.exit("ERROR [perf] no existe SimpleXAPI.kt")
t = p.read_text(encoding="utf-8")

old = (
    "    val INITIAL_COUNT = if (appPlatform.isDesktop) 100 else 75\n"
    "    const val PRELOAD_COUNT = 100\n"
    "    const val UNTIL_PRELOAD_COUNT = 50\n")
n = t.count(old)
if n != 1:
    sys.exit(f"ERROR [perf] el texto esperado aparece {n} veces (debía ser 1). No se modificó nada.")

t = t.replace(
    old,
    "    val INITIAL_COUNT = if (appPlatform.isDesktop) 100 else 50\n"
    "    const val PRELOAD_COUNT = 75\n"
    "    const val UNTIL_PRELOAD_COUNT = 30\n",
    1)
p.write_text(t, encoding="utf-8")
print("   [perf] listo: paginación de chats más ligera")
