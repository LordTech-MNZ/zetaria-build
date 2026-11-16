#!/usr/bin/env python3
"""Zetaria: el nombre del grupo/chat se ve completo en la barra superior (hasta dos líneas).

1) Causa del solape: SimpleX centra el título en la pantalla (CenteredRowLayout). Con varios botones a la derecha
   (filtro de multimedia, llamada grupal de Zetaria, menú ⋮) el título centrado se metía DEBAJO de ellos.
   Ahora el título sigue centrado si cabe; si no, se desplaza a la izquierda lo justo para no pisar los botones.
2) El nombre ocupa hasta dos líneas y la letra se ajusta sola (de 16sp hasta un mínimo de 12sp) hasta que el
   nombre completo quepa; solo si aun así no cabe termina en «…». Si debajo del nombre hay otra línea (nombre
   completo o nº de suscriptores) se mantiene el comportamiento original de una línea.

Transaccional (todo o nada). Uso: chatbar.py <ruta a apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
base = mp / "common/src/commonMain/kotlin/chat/simplex/common/views/chat"


def sub_once(text, old, new, what):
    n = text.count(old)
    if n != 1:
        sys.exit(f"ERROR [chatbar] {what}: el texto esperado aparece {n} veces (debía ser 1)")
    return text.replace(old, new, 1)


files = {}
for name in ("item/FramedItemView.kt", "ChatView.kt"):
    p = base / name
    if not p.exists():
        sys.exit(f"ERROR [chatbar]: no existe {p}")
    files[p] = p.read_text(encoding="utf-8")
pf, pc = base / "item/FramedItemView.kt", base / "ChatView.kt"

files[pf] = sub_once(
    files[pf],
    "      second.place((constraints.maxWidth - second.measuredWidth) / 2, ",
    "      // Zetaria: el título centrado no puede pisar los botones de la derecha ni el botón de volver\n"
    "      val zetMinX = first.measuredWidth\n"
    "      val zetMaxX = (constraints.maxWidth - third.measuredWidth - second.measuredWidth).coerceAtLeast(zetMinX)\n"
    "      second.place(((constraints.maxWidth - second.measuredWidth) / 2).coerceIn(zetMinX, zetMaxX), ",
    "posición del título")

files[pc] = sub_once(
    files[pc],
    "        NameWithBadge(\n"
    "          cInfo.displayName, cInfo.nameBadge, fontWeight = FontWeight.SemiBold,\n"
    "          maxLines = 1, overflow = TextOverflow.Ellipsis\n"
    "        )\n",
    "        // Zetaria: el nombre ocupa hasta dos líneas y reduce la letra (mín. 12sp) hasta que quepa completo\n"
    "        val zetHasSecondary = (cInfo.fullName != \"\" && cInfo.fullName != cInfo.displayName && cInfo.localAlias.isEmpty()) ||\n"
    "          ((cInfo as? ChatInfo.Group)?.let { g -> g.groupInfo.useRelays && (g.groupInfo.groupSummary.publicMemberCount ?: 0L) > 0L } == true)\n"
    "        val zetBaseSize = LocalTextStyle.current.fontSize.let { if (it.isSpecified) it else 16.sp }\n"
    "        val zetSize = remember(cInfo.displayName) { mutableStateOf(zetBaseSize) }\n"
    "        NameWithBadge(\n"
    "          cInfo.displayName, cInfo.nameBadge, fontWeight = FontWeight.SemiBold,\n"
    "          fontSize = if (zetHasSecondary) TextUnit.Unspecified else zetSize.value,\n"
    "          maxLines = if (zetHasSecondary) 1 else 2, overflow = TextOverflow.Ellipsis,\n"
    "          style = LocalTextStyle.current.copy(lineHeight = if (zetHasSecondary) TextUnit.Unspecified else (zetSize.value.value * 1.15f).sp),\n"
    "          onTextLayout = { r ->\n"
    "            if (!zetHasSecondary && r.hasVisualOverflow && zetSize.value.value > 12f) {\n"
    "              zetSize.value = (zetSize.value.value - 1f).sp\n"
    "            }\n"
    "          }\n"
    "        )\n",
    "nombre en dos líneas")

# NameWithBadge acepta onTextLayout (parámetro opcional, no cambia a los demás usos)
pi = base.parent / "helpers/ChatInfoImage.kt"
if not pi.exists():
    sys.exit(f"ERROR [chatbar]: no existe {pi}")
files[pi] = pi.read_text(encoding="utf-8")
files[pi] = sub_once(
    files[pi],
    "  maxLines: Int = Int.MAX_VALUE,\n  style: TextStyle = LocalTextStyle.current\n) {\n  Row(modifier) {",
    "  maxLines: Int = Int.MAX_VALUE,\n  style: TextStyle = LocalTextStyle.current,\n"
    "  onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}\n) {\n  Row(modifier) {",
    "NameWithBadge (parámetro)")
files[pi] = sub_once(
    files[pi],
    "      maxLines = maxLines,\n      style = style\n    )\n    NameBadge(",
    "      maxLines = maxLines,\n      style = style,\n      onTextLayout = onTextLayout\n    )\n    NameBadge(",
    "NameWithBadge (uso)")

for p, t in files.items():
    p.write_text(t, encoding="utf-8")
print("   [chatbar] barra del chat: nombre completo en hasta dos líneas, sin quedar bajo los botones")
