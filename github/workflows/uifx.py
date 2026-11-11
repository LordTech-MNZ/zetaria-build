#!/usr/bin/env python3
"""Zetaria: interfaz más suave y fluida, con transiciones al estilo Telegram.

Solo toca animaciones y movimiento: NO mueve ningún botón ni menú de sitio.
  1) Transiciones entre pantallas (ajustes, perfiles, información...): la pantalla nueva se desliza desde la
     derecha por encima, la anterior se desplaza un poco y se atenúa; al volver, al revés.
  2) Lista de chats <-> chat abierto: efecto de profundidad (la lista se desplaza menos y se atenúa).
  3) Curva de movimiento más natural (frenada suave) en los paneles que se deslizan.
  4) La lista de chats se reordena con animación cuando llega un mensaje.
  5) Los mensajes nuevos entran con un rebote suave.
  6) El botón de enviar aparece con rebote al sustituir al micrófono.

Cada mejora es independiente y transaccional: si alguna no encaja con el código de SimpleX, se omite con un
aviso y las demás se aplican igual.

Uso: uifx.py <ruta a apps/multiplatform>
"""
import re, sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
src = mp / "common/src"
K = "commonMain/kotlin/chat/simplex/common"

applied, skipped = [], []
need_fx_file = False


def read(rel):
    p = src / rel
    if not p.exists():
        raise RuntimeError(f"no existe {rel}")
    return p, p.read_text(encoding="utf-8")


def sub_once(text, old, new, what):
    n = text.count(old)
    if n != 1:
        raise RuntimeError(f"{what}: el texto esperado aparece {n} veces (debía ser 1)")
    return text.replace(old, new, 1)


def feature(name, fn):
    """Ejecuta fn() que devuelve {ruta: texto nuevo}; solo escribe si todo encajó."""
    try:
        changes = fn()
        for p, t in changes.items():
            p.write_text(t, encoding="utf-8")
        applied.append(name)
        print(f"   [uifx] OK: {name}")
        return True
    except Exception as e:
        skipped.append(name)
        print(f"   AVISO [uifx]: «{name}» no se aplicó ({e})")
        return False


# 1) Transiciones entre pantallas
def f_transitions():
    p, t = read(f"{K}/views/helpers/ModalView.kt")
    a = t.index("    @OptIn(ExperimentalAnimationApi::class)\n    fun fromStartToEndTransition() =")
    marker = "    private fun <T> animationSpec() = tween<T>(durationMillis = 250, easing = FastOutSlowInEasing)\n"
    b = t.index(marker, a) + len(marker)
    if t.count("fun fromStartToEndTransition") != 1 or t.count("fun fromEndToStartTransition") != 1:
        raise RuntimeError("las funciones de transición no son únicas")
    new = '''    // Zetaria: transición al estilo Telegram. La pantalla nueva entra por encima desde la derecha; la anterior se
    // desplaza un poco hacia la izquierda y se atenúa. Al volver, la pantalla de arriba sale hacia la derecha.
    @OptIn(ExperimentalAnimationApi::class)
    fun fromStartToEndTransition(): ContentTransform =
      ContentTransform(
        targetContentEnter = slideInHorizontally(
          initialOffsetX = { fullWidth -> -fullWidth / 4 },
          animationSpec = animationSpec()
        ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing), initialAlpha = 0.6f),
        initialContentExit = slideOutHorizontally(
          targetOffsetX = { fullWidth -> fullWidth },
          animationSpec = animationSpec()
        ),
        targetContentZIndex = -1f
      )

    @OptIn(ExperimentalAnimationApi::class)
    fun fromEndToStartTransition(): ContentTransform =
      ContentTransform(
        targetContentEnter = slideInHorizontally(
          initialOffsetX = { fullWidth -> fullWidth },
          animationSpec = animationSpec()
        ),
        initialContentExit = slideOutHorizontally(
          targetOffsetX = { fullWidth -> -fullWidth / 4 },
          animationSpec = animationSpec()
        ) + fadeOut(animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing), targetAlpha = 0.6f),
        targetContentZIndex = 1f
      )

    // Curva con arranque rápido y frenada larga y suave (decelerada)
    private fun <T> animationSpec() = tween<T>(durationMillis = 300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
'''
    return {p: t[:a] + new + t[b:]}


# 2) Lista de chats <-> chat abierto: profundidad
def f_depth():
    p, t = read(f"{K}/App.kt")
    old = "          translationX = -minOf(offset.value.dp, maxWidth).toPx()\n        }\n"
    new = ("          // Zetaria: la lista se desplaza menos que el chat y se atenúa (efecto de profundidad)\n"
           "          val zProgress = (minOf(offset.value.dp, maxWidth).value / maxOf(maxWidth.value, 1f)).coerceIn(0f, 1f)\n"
           "          // al terminar, la lista sale del todo de la pantalla (como antes): así no puede recibir toques por debajo del chat\n"
           "          translationX = if (zProgress >= 0.999f) -maxWidth.toPx() else -minOf(offset.value.dp, maxWidth).toPx() * 0.28f\n"
           "          alpha = if (zProgress >= 0.999f) 0f else 1f - 0.35f * zProgress\n"
           "        }\n")
    return {p: sub_once(t, old, new, "desplazamiento de la lista de chats")}


# 3) Curvas de movimiento
def f_easing():
    p, t = read(f"{K}/views/helpers/AnimationUtils.kt")
    if "androidx.compose.animation.core.*" not in t:
        raise RuntimeError("AnimationUtils no importa animation.core.*")
    ease = "CubicBezierEasing(0.2f, 0f, 0f, 1f)"
    t = sub_once(t, "tween<T>(durationMillis = 350, easing = FastOutSlowInEasing)",
                 f"tween<T>(durationMillis = 320, easing = {ease})", "chatListAnimationSpec")
    n = t.count("tween<T>(350, 0, FastOutSlowInEasing)")
    if n < 1:
        raise RuntimeError("no encuentro las animaciones de los paneles")
    t = t.replace("tween<T>(350, 0, FastOutSlowInEasing)", f"tween<T>(300, 0, {ease})")
    return {p: t}


# 4) Lista de chats con reordenado animado
def f_list_items():
    p, t = read(f"{K}/views/chatlist/ChatListView.kt")
    old = "      ChatListNavLinkView(chat, nextChatSelected)\n"
    new = ("      Box(Modifier.animateItem()) {\n"
           "        ChatListNavLinkView(chat, nextChatSelected)\n"
           "      }\n")
    return {p: sub_once(t, old, new, "elemento de la lista de chats")}


# 5) Mensajes nuevos con rebote
def f_msg_pop():
    global need_fx_file
    p, t = read(f"{K}/views/chat/ChatView.kt")
    old = "            modifier = modifier.padding(\n              bottom = if (itemSeparation.largeGap) {"
    new = "            modifier = modifier.zetariaPopIn(cItem).padding(\n              bottom = if (itemSeparation.largeGap) {"
    out = {p: sub_once(t, old, new, "contenedor de cada mensaje")}
    need_fx_file = True
    return out


# 6) Botón de enviar con rebote
def f_send_pop():
    global need_fx_file
    p, t = read(f"{K}/views/chat/SendMsgView.kt")
    old = ("    modifier = Modifier.requiredSize(36.dp)\n"
           "      .combinedClickable(\n"
           "        onClick = {\n"
           "          if (sendToConnect != null) {")
    new = ("    modifier = Modifier.requiredSize(36.dp)\n"
           "      .zetariaPop()\n"
           "      .combinedClickable(\n"
           "        onClick = {\n"
           "          if (sendToConnect != null) {")
    out = {p: sub_once(t, old, new, "botón de enviar")}
    need_fx_file = True
    return out


feature("Transiciones entre pantallas (estilo Telegram)", f_transitions)
feature("Efecto de profundidad lista de chats ↔ chat", f_depth)
feature("Curvas de movimiento más suaves en paneles", f_easing)
feature("Lista de chats con reordenado animado", f_list_items)
ok5 = feature("Mensajes nuevos con rebote", f_msg_pop)
ok6 = feature("Botón de enviar con rebote", f_send_pop)

if ok5 or ok6:
    dst = src / f"{K}/views/chat/ZetariaUiFx.kt"
    dst.write_text((here / "templates/uifx/ZetariaUiFx.kt").read_text(encoding="utf-8"), encoding="utf-8")
    print("   [uifx] ZetariaUiFx.kt creado")

print(f"   [uifx] listo: {len(applied)} mejoras aplicadas, {len(skipped)} omitidas")
if not applied:
    sys.exit(1)
