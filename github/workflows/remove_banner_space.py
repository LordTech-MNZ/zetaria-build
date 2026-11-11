#!/usr/bin/env python3
"""Zetaria: quita del todo el banner de crowdfunding y el hueco que dejaba.

El rebranding original solo borraba el texto del banner; la tarjeta y sus contenedores
(Box / item / if) seguían en la lista de chats y en las tarjetas de bienvenida, y por eso
quedaba el espacio en blanco. Aquí se eliminan las llamadas a GetStakeBanner(...) y, mientras
el contenedor que las envolvía quede vacío, también ese contenedor.
Además se borran las constantes sueltas con enlaces de Wefunder que ya nadie usa.

Uso: remove_banner_space.py <ruta a apps/multiplatform>
"""
import re, sys, pathlib

mp = pathlib.Path(sys.argv[1])
src = mp / "common/src"
kt = [f for f in src.rglob("*.kt") if "/build/" not in str(f).replace("\\", "/")]


def bracket_pairs(t):
    pairs, stack, i, n = {}, [], 0, len(t)
    while i < n:
        c = t[i]
        if t.startswith('"""', i):
            j = t.find('"""', i + 3); i = n if j < 0 else j + 3; continue
        if c == '"':
            i += 1
            while i < n and t[i] != '"':
                if t[i] == "\\": i += 1
                elif t[i] == "\n": break
                i += 1
            i += 1; continue
        if c == "'":
            j = i + 1
            j += 2 if (j < n and t[j] == "\\") else 1
            i = j + 1 if (j < n and t[j] == "'") else i + 1
            continue
        if t.startswith("//", i):
            j = t.find("\n", i); i = n if j < 0 else j; continue
        if t.startswith("/*", i):
            j = t.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if c in "({[":
            stack.append(i)
        elif c in ")}]" and stack:
            pairs[stack.pop()] = i
        i += 1
    return pairs


def line_start(t, i):
    return t.rfind("\n", 0, i) + 1


def line_end(t, i):
    j = t.find("\n", i)
    return len(t) if j < 0 else j + 1


CONTAINERS = ("if", "item", "items", "Box", "Column", "Row", "AnimatedVisibility", "else")
total_calls = 0
total_wrappers = 0
changed_files = []

for f in kt:
    t = f.read_text(encoding="utf-8")
    if "GetStakeBanner(" not in t:
        continue
    # definición de la función: se deja (sin usos no molesta); solo se quitan las llamadas
    n_calls = 0
    while True:
        pairs = bracket_pairs(t)
        found = None
        for m in re.finditer(r"\bGetStakeBanner\s*\(", t):
            ls = line_start(t, m.start())
            if re.match(r"\s*(@Composable\s+)?fun\s", t[ls:m.start()] + "fun ") and "fun" in t[ls:m.start()]:
                continue  # es la definición
            if t[ls:m.start()].strip() != "":
                continue  # no empieza su propia línea
            o = t.find("(", m.start())
            c = pairs.get(o)
            if c is None:
                continue
            found = (ls, line_end(t, c))
            break
        if not found:
            break
        s, e = found
        t = t[:s] + t[e:]
        n_calls += 1
        # colapsar contenedores que se quedaron vacíos
        pos = s
        while True:
            pairs = bracket_pairs(t)
            enclosing = None
            for o, c in pairs.items():
                if t[o] == "{" and o < pos <= c:
                    if enclosing is None or o > enclosing[0]:
                        enclosing = (o, c)
            if not enclosing:
                break
            o, c = enclosing
            if t[o + 1:c].strip() != "":
                break
            hs = line_start(t, o)
            header = t[hs:o]
            first = header.strip().split("(")[0].split(" ")[0].split("{")[0]
            if first not in CONTAINERS or "\n" in header.strip():
                break
            t = t[:hs] + t[line_end(t, c):]
            pos = hs
            total_wrappers += 1
    if n_calls:
        total_calls += n_calls
        changed_files.append(f.name)
        f.write_text(t, encoding="utf-8")
        print(f"   banner quitado de {f.name}: {n_calls} llamada(s)")

# constantes con enlaces de Wefunder que quedaron sin usar
all_text = {f: f.read_text(encoding="utf-8") for f in kt}
for f, t in all_text.items():
    for m in list(re.finditer(r'^[ \t]*(?:private |internal )?const val (\w+)\s*=\s*"[^"\n]*wefunder[^"\n]*"[ \t]*\n', t, re.M | re.I)):
        name = m.group(1)
        uses = sum(len(re.findall(r"\b" + re.escape(name) + r"\b", tt)) for tt in all_text.values())
        if uses == 1:
            t = t.replace(m.group(0), "", 1)
            print(f"   constante sin uso eliminada: {name} ({f.name})")
    if t != all_text[f]:
        f.write_text(t, encoding="utf-8")

print(f"   listo: {total_calls} llamada(s) al banner quitadas, {total_wrappers} contenedor(es) vacío(s) eliminados")
if total_calls == 0:
    print("   AVISO: no encontré llamadas a GetStakeBanner (¿ya estaba quitado?)")
