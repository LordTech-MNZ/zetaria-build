#!/usr/bin/env python3
"""Zetaria: Autoridad suprema (baneos, silencios, retirada de publicaciones y, opcionalmente, borrado de datos).

Cómo funciona (resumen; los detalles están en ZetariaAuthority.kt y en AUTORIDAD.md):
  - La app lleva SOLO la llave PÚBLICA de la Autoridad (variable de repositorio ZETARIA_AUTHORITY_PUBKEY).
  - La llave PRIVADA no está en el repositorio ni en el APK: vive cifrada con contraseña en el teléfono de la Autoridad.
  - Cada orden es un «edicto» firmado (ECDSA P-256). Cada teléfono comprueba la firma antes de obedecerlo.
  - Los edictos viajan por mensajes entre contactos y por authority/edicts.txt del repositorio (consulta cada 30 min).

Qué parcha en el código de SimpleX:
  1) SimpleXAPI.kt     -> apiSendMessages: bloquea los envíos de quien tiene una sanción vigente.
                       -> mensajes nuevos: los edictos que llegan se validan, se guardan y se reenvían.
  2) ZetariaFeed.kt    -> los edictos no se dibujan en el chat; el feed oculta publicaciones retiradas y las de autores
                          sancionados; botón ⚖ en cada publicación ajena (solo con la autoridad desbloqueada).
  3) SettingsView.kt   -> botón «Autoridad de Zetaria» en Configuración avanzada.
  4) SimplexApp.kt     -> arranque (carga edictos y consulta el repositorio). Opcional.
  4b) Cédula: el pie dice «MONARCA FUNDADOR…» para quien tenga un edicto TITLE firmado; «CIUDADANO…» para los demás.
  5) Copia ZetariaAuthority.kt con la llave pública y el repositorio incrustados.

Variables de entorno (las pone el workflow):
  ZETARIA_AUTHORITY_PUBKEY  llave pública (base64url, la que muestra la app al crear la llave). Vacía = sin autoridad.
  ZETARIA_ALLOW_WIPE        «on» para permitir edictos de borrado de datos (por defecto desactivado).
  ZETARIA_REPO              «usuario/repositorio» donde está authority/edicts.txt.

Necesita los pasos de Cédula (ID de Zetaria) y Feed. Transaccional (todo o nada).
Uso: authority.py <ruta a apps/multiplatform>
"""
import os, re, sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).resolve().parent
tpl = here / "templates" / "authority"
K = "kotlin/chat/simplex/common"
base = mp / "common/src/commonMain"
Z = "chat.simplex.common.views.zetaria.ZetariaAuthority"

pub = (os.environ.get("ZETARIA_AUTHORITY_PUBKEY") or "").strip()
wipe = "on" if (os.environ.get("ZETARIA_ALLOW_WIPE") or "").strip().lower() == "on" else "off"
repo = (os.environ.get("ZETARIA_REPO") or os.environ.get("GITHUB_REPOSITORY") or "lordtech-mnz/zetaria-build").strip()


def die(msg):
    sys.exit(f"ERROR [authority] {msg}")


pub = re.sub(r"[\s\"']", "", pub)  # espacios, saltos de línea y comillas pegadas por error
if pub:
    import base64
    try:
        raw = base64.urlsafe_b64decode(pub.replace("+", "-").replace("/", "_").rstrip("=") + "==")
    except Exception:
        raw = b""
    if len(raw) != 91 or raw[:2] != b"\x30\x59":
        print("::error::ZETARIA_AUTHORITY_PUBKEY no es una llave pública válida (debe decodificar a 91 bytes). "
              f"Recibí {len(pub)} caracteres; la correcta tiene 122. Cópiala completa desde la app.")
        die("ZETARIA_AUTHORITY_PUBKEY inválida")
else:
    print("::warning::ZETARIA_AUTHORITY_PUBKEY está VACÍA: este APK no podrá desbloquear la Autoridad. "
          "Créala en Settings > Secrets and variables > Actions > pestaña VARIABLES (no Secrets).")
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
    die(f"ZETARIA_REPO inválido: {repo!r}")

orig, edits = {}, {}


def load(path, label):
    if not path.exists():
        die(f"no existe {label}")
    if path not in orig:
        orig[path] = path.read_text(encoding="utf-8")
        edits[path] = orig[path]
    return path


def once(p, old, new, what):
    n = edits[p].count(old)
    if n != 1:
        die(f"{what}: el texto esperado aparece {n} veces (debía ser 1) en {p.name}")
    edits[p] = edits[p].replace(old, new, 1)
    print(f"   [authority] {what}")


# 0) Requisitos: Cédula y Feed ya aplicados
feed_p = base / K / "views/chat/item/ZetariaFeed.kt"
ced_p = base / K / "views/zetaria/ZetariaCedula.kt"
if not feed_p.exists():
    die("falta el Feed (ZetariaFeed.kt): este paso va DESPUÉS de feed.py")
if not ced_p.exists():
    die("falta la Cédula (ZetariaCedula.kt): este paso va DESPUÉS de cedula.py")

# 0b) Título en el pie de la cédula (solo si lo firmó la Autoridad): MONARCA FUNDADOR en vez de CIUDADANO
p = load(ced_p, "ZetariaCedula.kt")
once(p,
     "  val issued: String,\n  val flag: ImageBitmap?\n)\n",
     "  val issued: String,\n  val flag: ImageBitmap?,\n  val footer: String = \"\"\n)\n",
     "campo footer en la cédula")
once(p,
     "info.sex, issued, flag)\n",
     f"info.sex, issued, flag, {Z}.footerFor(info.id))\n",
     "la cédula pide su pie a la Autoridad")
ced_android = mp / "common/src/androidMain" / K / "views/zetaria/ZetariaCedula.android.kt"
p = load(ced_android, "ZetariaCedula.android.kt")
once(p,
     '  c.drawText("CIUDADANO DEL REINO SOBERANO DE ZETARIA", w / 2f, 739f, tp(25f, Color.WHITE, true, 0.1f, Paint.Align.CENTER))\n',
     '  val footerText = card.footer.ifEmpty { "CIUDADANO DEL REINO SOBERANO DE ZETARIA" }\n'
     '  val footerColor = if (card.footer.isEmpty()) Color.WHITE else 0xFFFFD54F.toInt()\n'
     '  c.drawText(footerText, w / 2f, 739f, tp(25f, footerColor, true, 0.1f, Paint.Align.CENTER))\n',
     "pie de la cédula: título o ciudadano")

# 1) Envíos y mensajes entrantes
p = load(base / K / "model/SimpleXAPI.kt", "SimpleXAPI.kt")
once(p,
     "    val cmd = CC.ApiSendMessages(type, id, scope, sendAsGroup, live, ttl, sign, composedMessages)\n"
     "    return processSendMessageCmd(rh, cmd)\n",
     f"    if ({Z}.blocksSend(type, composedMessages.map {{ it.msgContent.text }})) return null\n"
     "    val cmd = CC.ApiSendMessages(type, id, scope, sendAsGroup, live, ttl, sign, composedMessages)\n"
     "    return processSendMessageCmd(rh, cmd)\n",
     "bloqueo de envíos sancionados")
once(p,
     "          ntfManager.notifyMessageReceived(rhId, r.user, cInfo, cItem)\n",
     f"          {Z}.onNewItem(rhId, r.user, cInfo, cItem)\n"
     "          ntfManager.notifyMessageReceived(rhId, r.user, cInfo, cItem)\n",
     "recepción de edictos")

# 2) Feed
p = load(feed_p, "ZetariaFeed.kt")
once(p,
     "fun zetariaIsFeedItem(ci: ChatItem): Boolean = zfHeadOf(ci) != null\n",
     f"fun zetariaIsFeedItem(ci: ChatItem): Boolean = zfHeadOf(ci) != null || {Z}.isEdictItem(ci)\n",
     "los edictos se ocultan como mensajes del feed")
once(p,
     "    if (!a.mine && deletedTheirs.contains(a.key)) continue\n",
     "    if (!a.mine && deletedTheirs.contains(a.key)) continue\n"
     f"    if (!a.mine && {Z}.hidesPost(a.ev.pid, a.ev.chatId, a.ev.tsMs)) continue\n",
     "el feed oculta publicaciones retiradas o de sancionados")
once(p,
     "  if ((e.body.isEmpty() && !zfIsMediaItem(e.ci)) || a.comments.containsKey(e.cid)) return\n",
     "  if ((e.body.isEmpty() && !zfIsMediaItem(e.ci)) || a.comments.containsKey(e.cid)) return\n"
     f"  if ({Z}.hidesComment(e.pid, e.cid)) return\n",
     "el feed oculta comentarios retirados")
once(p,
     '      Text("📤", fontSize = 18.sp, modifier = Modifier.clickable { showShare = true }.padding(8.dp))\n'
     "      if (p.mine) {\n",
     '      Text("📤", fontSize = 18.sp, modifier = Modifier.clickable { showShare = true }.padding(8.dp))\n'
     f"      if (!p.mine && {Z}.isAuthority()) {{\n"
     f'        Text("⚖️", fontSize = 18.sp, modifier = Modifier.clickable {{ {Z}.openSanctionFromFeed(p.chatId, p.pid) }}.padding(8.dp))\n'
     "      }\n"
     "      if (p.mine) {\n",
     "botón ⚖ de sanción en las publicaciones")

# 3) Botón en Configuración avanzada
p = load(base / K / "views/usersettings/SettingsView.kt", "SettingsView.kt")
once(p,
     "      AppShutdownItem()\n      AppVersionItem(showVersion)\n",
     "      SettingsActionItem(painterResource(MR.images.ic_shield), \"Autoridad de Zetaria\", "
     "showSettingsModal { chat.simplex.common.views.zetaria.ZetariaAuthorityView(it) })\n"
     "      AppShutdownItem()\n      AppVersionItem(showVersion)\n",
     "botón «Autoridad de Zetaria»")

# 4) Arranque (opcional)
hook_path = mp / "android/src/main/java/chat/simplex/app/SimplexApp.kt"
hook_old = "    deleteOldChatArchive()\n"
if hook_path.exists():
    ht = hook_path.read_text(encoding="utf-8")
    if ht.count(hook_old) == 1:
        orig[hook_path] = ht
        edits[hook_path] = ht.replace(hook_old, hook_old + f"    {Z}.start()\n", 1)
        print("   [authority] arranque de la comprobación de edictos")
    else:
        print("   [authority] aviso: no encontré el punto de arranque; los edictos solo se recibirán por mensajes")

# 5) Archivo nuevo, con la llave pública y el repositorio incrustados
kt = (tpl / "ZetariaAuthority.kt").read_text(encoding="utf-8")
for ph in ("__ZETARIA_AUTHORITY_PUBKEY__", "__ZETARIA_REPO__", "__ZETARIA_ALLOW_WIPE__"):
    if ph not in kt:
        die(f"la plantilla no contiene {ph}")
kt = kt.replace("__ZETARIA_AUTHORITY_PUBKEY__", pub or "__ZETARIA_AUTHORITY_PUBKEY__")
kt = kt.replace("__ZETARIA_REPO__", repo).replace("__ZETARIA_ALLOW_WIPE__", wipe)
dest = base / K / "views/zetaria/ZetariaAuthority.kt"
if dest.exists():
    die("ZetariaAuthority.kt ya existe")

for pth, txt in edits.items():
    if txt != orig[pth]:
        pth.write_text(txt, encoding="utf-8")
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(kt, encoding="utf-8")
state = "con llave pública" if pub else "SIN llave pública todavía (crea la llave en la app y pon ZETARIA_AUTHORITY_PUBKEY)"
print(f"   [authority] listo ({state}; borrado de datos: {wipe}): "
      f"{sum(1 for q in edits if edits[q] != orig[q])} archivos modificados, 1 nuevo")
