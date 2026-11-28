#!/usr/bin/env python3
"""Zetaria · rebrand de la app de iPhone (SimpleX apps/ios -> Zetaria).

Uso:
    python3 ios_rebrand.py <ruta a simplex-chat/apps/ios> <ícono.png>

Qué hace (solo configuración, nada de lógica de la app):
  1. Bundle IDs:  chat.simplex.app*  ->  app.zetaria.chat*   (app, extensiones y App Group)
  2. Nombre visible (CFBundleDisplayName): SimpleX -> Zetaria
  3. Íconos: regenera cada imagen de AppIcon.appiconset con el mismo nombre y tamaño
  4. (Opcional) DEVELOPMENT_TEAM si defines la variable ZETARIA_APPLE_TEAM

Seguridad:
  * No toca nada fuera de la carpeta que se le pasa.
  * Nunca falla en silencio: imprime un informe y sale con código 1 si no pudo
    aplicar algo crítico (bundle id, nombre o íconos).
  * Escribe zetaria-ios-report.txt junto a la carpeta con el resumen.

Este archivo se llama a propósito 'ios_rebrand.py': build-apk.yml busca sus scripts
con 'find overlay -name <nombre>', y un nombre distinto evita cualquier cruce con Android.
"""
import json
import os
import re
import sys
from pathlib import Path

from PIL import Image

APP_NAME = "Zetaria"
OLD_ID = "chat.simplex.app"
NEW_ID = "app.zetaria.chat"
ICON_BG = (40, 12, 60)  # mismo morado oscuro que el ícono adaptativo de Android

# chat.simplex.app, chat.simplex.app.SimpleX-NSE, group.chat.simplex.app ...
ID_RE = re.compile(r"(?<![A-Za-z0-9_-])" + re.escape(OLD_ID) + r"(?![A-Za-z0-9_])")

CONFIG_SUFFIXES = {".pbxproj", ".entitlements", ".plist", ".xcconfig"}
SKIP_DIRS = {".git", "build", "DerivedData", "Pods", "node_modules"}

report = {"ids": {}, "swift_ids": {}, "display_name": {}, "icons": [], "team": {}, "warnings": []}


def walk(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            yield Path(dirpath) / f


def read(p: Path):
    try:
        return p.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def rel(p: Path, root: Path) -> str:
    return str(p.relative_to(root))


# ---------------------------------------------------------------- 1. bundle IDs
def step_bundle_ids(root: Path):
    for p in walk(root):
        suf = p.suffix
        if suf in CONFIG_SUFFIXES:
            bucket = "ids"
        elif suf == ".swift":
            bucket = "swift_ids"
        else:
            continue
        text = read(p)
        if text is None or OLD_ID not in text:
            continue
        new, n = ID_RE.subn(NEW_ID, text)
        if n:
            p.write_text(new, encoding="utf-8")
            report[bucket][rel(p, root)] = n


# ---------------------------------------------------------------- 2. nombre visible
PBX_NAME_RE = re.compile(r'(INFOPLIST_KEY_CFBundleDisplayName\s*=\s*)("?)([^";\n]*)("?)(;)')
PLIST_NAME_RE = re.compile(
    r"(<key>CFBundle(?:DisplayName|Name)</key>\s*<string>)([^<]*)(</string>)"
)
STRINGS_NAME_RE = re.compile(r'(CFBundle(?:DisplayName|Name)"?\s*=\s*")([^"]*)(")')


def rename_value(v: str) -> str:
    # "SimpleX" -> "Zetaria"; "SimpleX NSE" -> "Zetaria NSE"; no toca "$(PRODUCT_NAME)"
    return v.replace("SimpleX", APP_NAME) if "SimpleX" in v else v


def step_display_name(root: Path):
    for p in walk(root):
        name = p.name
        if p.suffix == ".pbxproj":
            rx, fn = PBX_NAME_RE, lambda m: m.group(1) + m.group(2) + rename_value(m.group(3)) + m.group(4) + m.group(5)
        elif name == "Info.plist":
            rx, fn = PLIST_NAME_RE, lambda m: m.group(1) + rename_value(m.group(2)) + m.group(3)
        elif name == "InfoPlist.strings":
            rx, fn = STRINGS_NAME_RE, lambda m: m.group(1) + rename_value(m.group(2)) + m.group(3)
        else:
            continue
        text = read(p)
        if text is None:
            continue
        changed = 0

        def sub(m):
            nonlocal changed
            out = fn(m)
            if out != m.group(0):
                changed += 1
            return out

        new = rx.sub(sub, text)
        if changed:
            p.write_text(new, encoding="utf-8")
            report["display_name"][rel(p, root)] = changed


# ---------------------------------------------------------------- 3. íconos
def flatten(src: Image.Image, px: int, tinted: bool) -> Image.Image:
    img = src.resize((px, px), Image.LANCZOS)
    bg = Image.new("RGBA", (px, px), ICON_BG + (255,))
    bg.alpha_composite(img)          # iOS no admite transparencia en el ícono
    out = bg.convert("RGB")
    if tinted:                       # variante "tinted" de iOS 18: escala de grises
        out = out.convert("L").convert("RGB")
    return out


def step_icons(root: Path, icon_path: Path):
    src = Image.open(icon_path).convert("RGBA")
    for d in sorted(root.rglob("*.appiconset")):
        if not d.name.startswith("AppIcon"):
            continue
        cj = d / "Contents.json"
        if not cj.exists():
            report["warnings"].append(f"{rel(d, root)}: sin Contents.json, se omite")
            continue
        data = json.loads(cj.read_text(encoding="utf-8"))
        for entry in data.get("images", []):
            fname = entry.get("filename")
            if not fname:
                continue
            m = re.match(r"^(\d+(?:\.\d+)?)x", entry.get("size", ""))
            if not m:
                report["warnings"].append(f"{rel(d, root)}/{fname}: tamaño ilegible, se omite")
                continue
            scale = int(entry.get("scale", "1x").rstrip("x"))
            px = int(round(float(m.group(1)) * scale))
            appearances = json.dumps(entry.get("appearances", []))
            tinted = "tinted" in appearances
            flatten(src, px, tinted).save(d / fname, "PNG")
            report["icons"].append(f"{rel(d, root)}/{fname} ({px}x{px}{' tinted' if tinted else ''})")


# ---------------------------------------------------------------- 4. equipo de Apple (opcional)
def step_team(root: Path):
    team = os.environ.get("ZETARIA_APPLE_TEAM", "").strip()
    if not team:
        return
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        report["warnings"].append("ZETARIA_APPLE_TEAM no parece un Team ID (10 caracteres A-Z/0-9); se ignora")
        return
    for p in walk(root):
        if p.suffix != ".pbxproj":
            continue
        text = read(p)
        if text is None:
            continue
        new, n = re.subn(r"(DEVELOPMENT_TEAM\s*=\s*)[A-Z0-9]{10}(;)", r"\g<1>" + team + r"\2", text)
        if n:
            p.write_text(new, encoding="utf-8")
            report["team"][rel(p, root)] = n


# ---------------------------------------------------------------- informe
def residual(root: Path):
    left = {}
    for p in walk(root):
        if p.suffix in CONFIG_SUFFIXES | {".swift", ".strings", ".json"}:
            text = read(p)
            if text and ID_RE.search(text):
                left[rel(p, root)] = len(ID_RE.findall(text))
    return left


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    root, icon = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    if not root.is_dir() or not (list(root.glob("*.xcodeproj")) or list(root.rglob("*.pbxproj"))):
        print(f"ERROR: {root} no parece la carpeta apps/ios de SimpleX (no hay .xcodeproj).")
        sys.exit(1)
    if not icon.is_file():
        print(f"ERROR: no encuentro el ícono {icon}")
        sys.exit(1)

    step_bundle_ids(root)
    step_display_name(root)
    step_icons(root, icon)
    step_team(root)
    left = residual(root)

    lines = ["== Zetaria iOS: informe de rebrand ==",
             f"Bundle IDs en config ({OLD_ID} -> {NEW_ID}): {sum(report['ids'].values())} cambios en {len(report['ids'])} archivos"]
    lines += [f"   {k}: {v}" for k, v in sorted(report["ids"].items())]
    lines.append(f"Bundle IDs en código Swift: {sum(report['swift_ids'].values())} cambios en {len(report['swift_ids'])} archivos")
    lines += [f"   {k}: {v}" for k, v in sorted(report["swift_ids"].items())]
    lines.append(f"Nombre visible -> {APP_NAME}: {sum(report['display_name'].values())} cambios en {len(report['display_name'])} archivos")
    lines += [f"   {k}: {v}" for k, v in sorted(report["display_name"].items())]
    lines.append(f"Íconos regenerados: {len(report['icons'])}")
    lines += [f"   {i}" for i in report["icons"]]
    if report["team"]:
        lines.append(f"DEVELOPMENT_TEAM actualizado en: {', '.join(report['team'])}")
    if left:
        lines.append("OJO, quedan referencias a chat.simplex.app (revisar a mano):")
        lines += [f"   {k}: {v}" for k, v in sorted(left.items())]
    lines += [f"AVISO: {w}" for w in report["warnings"]]

    critical = []
    if not report["ids"]:
        critical.append("no se cambió ningún bundle id (¿cambió la estructura del proyecto?)")
    if not report["display_name"]:
        critical.append("no se encontró CFBundleDisplayName para renombrar")
    if not report["icons"]:
        critical.append("no se regeneró ningún ícono (AppIcon.appiconset)")
    lines += [f"ERROR CRÍTICO: {c}" for c in critical]
    lines.append("RESULTADO: " + ("FALLÓ" if critical else "OK"))

    text = "\n".join(lines)
    print(text)
    (root.parent / "zetaria-ios-report.txt").write_text(text + "\n", encoding="utf-8")
    sys.exit(1 if critical else 0)


if __name__ == "__main__":
    main()
