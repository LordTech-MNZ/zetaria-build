#!/usr/bin/env python3
"""Zetaria: códigos QR morados con el logo MNZ en el centro (en vez de azul + símbolo de SimpleX).

Todos los QR de la app (dirección/perfil, enlace de un solo uso, enlace de grupo, etc.) pasan por el mismo
composable QRCode, así que basta con:
  1) cambiar el color por defecto de las celdas (azul 0xff062d56 -> morado oscuro 0xff4c1d95; tiene que ser
     oscuro para que las cámaras lo lean bien sobre el fondo blanco),
  2) dibujar en el centro el logo MNZ (recurso zet_qr_logo) en vez del icono de SimpleX.

Uso: qrstyle.py <ruta a apps/multiplatform>
"""
import sys, shutil, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
PURPLE = "0xff4c1d95"
changes = {}


def fail(msg):
    sys.exit(f"ERROR [qrstyle]: {msg}")


def patch(path, pairs):
    if not path.exists():
        fail(f"no existe {path.relative_to(mp)}")
    t = path.read_text(encoding="utf-8")
    for old, new, expected in pairs:
        n = t.count(old)
        if n != expected:
            fail(f"{path.name}: «{old[:60]}…» aparece {n} veces (debían ser {expected})")
        t = t.replace(old, new)
    changes[path] = t


# 1) Color de las celdas
patch(mp / "common/src/commonMain/kotlin/chat/simplex/common/views/newchat/QRCode.kt",
      [("Color(0xff062d56)", f"Color({PURPLE})", 3)])

# 2) Logo central (Android)
patch(mp / "common/src/androidMain/kotlin/chat/simplex/common/platform/Images.android.kt", [
    ("androidAppContext.resources.getDrawable(R.drawable.icon_foreground_android_common, null).toBitmap()",
     "androidAppContext.resources.getDrawable(R.drawable.zet_qr_logo, null).toBitmap()", 1),
    # el logo MNZ ocupa casi todo el círculo blanco (el icono de SimpleX llevaba margen interno)
    ("val logoSize = (width * size * 1.5).toInt()", "val logoSize = (width * size * 0.94).toInt()", 1),
    ("drawBitmap(logo, null, android.graphics.Rect(0, 0, logoSize, logoSize), null)",
     "drawBitmap(logo, null, android.graphics.Rect(0, 0, logoSize, logoSize), "
     "android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))", 1),
])

# 3) Recurso del logo: en la misma carpeta res que el icono original (así R lo resuelve igual)
res_root = None
cands = sorted(mp.rglob("icon_foreground_android_common.*"),
               key=lambda f: (0 if f.relative_to(mp).parts[0] == "common" else 1, str(f)))
for f in cands:
    parts = f.parts
    if "res" in parts:
        i = len(parts) - 1 - parts[::-1].index("res")
        res_root = pathlib.Path(*parts[:i + 1])
        break
if res_root is None:
    fail("no encuentro la carpeta res donde vive icon_foreground_android_common")
logo_src = here / "templates/qr/zet_qr_logo.png"
if not logo_src.exists():
    fail("falta templates/qr/zet_qr_logo.png")
dst = res_root / "drawable-nodpi" / "zet_qr_logo.png"

for p, content in changes.items():
    p.write_text(content, encoding="utf-8")
dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(logo_src, dst)
print(f"   [qrstyle] QR morados ({PURPLE}) con logo MNZ; recurso en {dst.relative_to(mp)}")
