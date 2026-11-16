#!/usr/bin/env python3
"""Zetaria: el nombre del grupo/chat ya no queda tapado por los botones de la barra superior.

Causa: SimpleX centra el título en la pantalla (CenteredRowLayout). Con varios botones a la derecha (filtro de
multimedia, llamada grupal de Zetaria, menú ⋮) el título centrado se mete DEBAJO de ellos.
Arreglo: el título sigue centrado si cabe; si no cabe, se desplaza a la izquierda lo justo para no pisar los
botones, y si aun así es largo termina en «…».

Transaccional (todo o nada). Uso: chatbar.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
p = mp / "common/src/commonMain/kotlin/chat/simplex/common/views/chat/item/FramedItemView.kt"
if not p.exists():
    sys.exit(f"ERROR [chatbar]: no existe {p}")

t = p.read_text(encoding="utf-8")
old = "      second.place((constraints.maxWidth - second.measuredWidth) / 2, "
new = ("      // Zetaria: el título centrado no puede pisar los botones de la derecha ni el botón de volver\n"
       "      val zetMinX = first.measuredWidth\n"
       "      val zetMaxX = (constraints.maxWidth - third.measuredWidth - second.measuredWidth).coerceAtLeast(zetMinX)\n"
       "      second.place(((constraints.maxWidth - second.measuredWidth) / 2).coerceIn(zetMinX, zetMaxX), ")
n = t.count(old)
if n != 1:
    sys.exit(f"ERROR [chatbar]: el texto esperado aparece {n} veces en FramedItemView.kt (debía ser 1)")
p.write_text(t.replace(old, new, 1), encoding="utf-8")
print("   [chatbar] título de la barra del chat: ya no queda debajo de los botones")
