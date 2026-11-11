#!/usr/bin/env python3
"""Reemplaza los íconos de SimpleX por el de Zetaria conservando
los nombres y tamaños que ya existen en el repo."""
import sys, pathlib
from PIL import Image, ImageDraw

src_path, mp = sys.argv[1], pathlib.Path(sys.argv[2])
src = Image.open(src_path).convert("RGBA")
BG = (40, 12, 60, 255)  # morado oscuro para el fondo del ícono adaptativo

def fit(img, size):
    return img.resize((size, size), Image.LANCZOS)

def round_icon(size):
    base = fit(src, size)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out

def foreground(size):
    # Canvas adaptativo: el contenido seguro es el 66% central
    inner = int(size * 0.62)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(fit(src, inner), ((size - inner) // 2, (size - inner) // 2))
    return out

def legacy(size):
    return fit(src, size)

n = 0
res = mp / "android/src/main/res"
for p in sorted(res.glob("mipmap-*/*.png")):
    size = Image.open(p).size[0]
    name = p.stem
    if name.endswith("_foreground"):
        img = foreground(size)
    elif name.endswith("_round"):
        img = round_icon(size)
    else:
        img = legacy(size)
    img.save(p); n += 1

# Fondo del ícono adaptativo
bg = res / "values/icon_background.xml"
if bg.exists():
    bg.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                  '    <color name="icon_background">#280C3C</color>\n</resources>\n')

# Desktop
dist = mp / "desktop/src/jvmMain/resources/distribute"
if dist.exists():
    big = fit(src, 1024)
    big.save(dist / "simplex.png")
    big.save(dist / "simplex.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    big.save(dist / "simplex.icns")
    n += 3
# Ícono interno de la app (logo en pantallas) se deja tal cual.
print(f"   {n} íconos reemplazados")
