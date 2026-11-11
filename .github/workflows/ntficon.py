#!/usr/bin/env python3
"""Zetaria: el logo MNZ como ícono grande de las notificaciones (en vez del símbolo de SimpleX).

Cuando un chat no tiene foto (p. ej. «Nueva solicitud de contacto» de alguien sin avatar) o las vistas previas
están ocultas, SimpleX pone un ícono propio como imagen grande de la notificación. Aquí se localiza el código
de Android que arma la notificación (el que llama a setLargeIcon), se cambia el recurso de ese ícono por
zet_ntf_large (logo MNZ) y se copia el recurso a la carpeta res del mismo módulo (así R lo resuelve igual).

No se toca el ícono pequeño de la barra de estado (setSmallIcon): Android lo exige monocromo (blanco sobre
transparente) y un logo a color saldría como un cuadrado blanco. Las notificaciones de chats con foto siguen
mostrando la foto del contacto.

Todo o nada: si no encuentra el código esperado imprime un diagnóstico y sale con error SIN modificar nada
(el paso del workflow tiene continue-on-error, así que el APK se compila igual, con el ícono original).

Uso: ntficon.py <ruta a apps/multiplatform>
"""
import sys, re, shutil, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
NEW = "zet_ntf_large"


def fail(msg):
    sys.exit(f"ERROR [ntficon]: {msg}")


def read(f):
    return f.read_text(encoding="utf-8", errors="ignore")


# 1) El archivo que construye la notificación (llama a setLargeIcon)
roots = [mp / "android", mp / "common/src/androidMain"]
files = [f for r in roots if r.exists() for f in r.rglob("*.kt") if "setLargeIcon" in read(f)]
if not files:
    fail("no encuentro ningún .kt de Android que llame a setLargeIcon (¿cambió el código de notificaciones?)")

# 2) Dentro de él, los R.drawable.X que alimentan el ícono grande (no el pequeño, no los ya cambiados)
rx = re.compile(r"R\.drawable\.(\w+)")
plan = []  # (archivo, texto nuevo, nombres cambiados)
for f in files:
    t = read(f)
    names, out, last = [], [], 0
    for m in rx.finditer(t):
        name = m.group(1)
        before = t[max(0, m.start() - 300):m.start()]
        near_large = "largeicon" in before.lower()
        is_small = "setsmallicon" in before[-80:].lower() or name.startswith("ntf_")
        if name == NEW or not near_large or is_small:
            continue
        out.append(t[last:m.start()] + f"R.drawable.{NEW}")
        last = m.end()
        names.append(name)
    if names:
        plan.append((f, "".join(out) + t[last:], names))

if not plan:
    print("   [ntficon] líneas relacionadas, para ajustar el script:")
    for f in files:
        for i, line in enumerate(read(f).splitlines(), 1):
            if re.search(r"largeicon|decodeResource|R\.drawable", line, re.I):
                print(f"     {f.relative_to(mp)}:{i}: {line.strip()[:160]}")
    fail("encontré setLargeIcon pero ningún R.drawable.* que lo alimente (¿usa otra forma de cargar el ícono?)")

# 3) Recurso: en la carpeta res del módulo del archivo patcheado
f0 = plan[0][0]
res_root = next((q / "res" for q in f0.parents if q.is_relative_to(mp) and (q / "res").is_dir()), None)
if res_root is None:
    res_root = mp / "android/src/main/res"
src = here / "templates/ntf/zet_ntf_large.png"
if not src.exists():
    fail("falta templates/ntf/zet_ntf_large.png")

# Escribir (ya validado todo)
for f, new_text, _ in plan:
    f.write_text(new_text, encoding="utf-8")
dst = res_root / "drawable-nodpi" / f"{NEW}.png"
dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(src, dst)
cambiados = ", ".join(sorted({n for _, _, ns in plan for n in ns}))
print(f"   [ntficon] ícono grande de notificaciones -> logo MNZ (antes: {cambiados}); "
      f"{plan[0][0].name}; recurso en {dst.relative_to(mp)}")
