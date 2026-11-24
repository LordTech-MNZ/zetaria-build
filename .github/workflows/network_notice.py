#!/usr/bin/env python3
"""Zetaria: texto de la pantalla «Tu red» de la bienvenida.

Reemplaza la frase corta («Los routers de la red no pueden saber quién se comunica con quién») por el aviso de Zetaria:
servidores de SimpleX por ahora, y las normas de uso (conductas inapropiadas, contenido ilegal, baneo).
El texto va dentro del código (no depende del idioma del teléfono): español, y en inglés si el teléfono no está en español.
Si el texto es más largo que la pantalla, se desplaza dentro de su caja (máx. 300 dp) y no empuja el botón «Continuar».
No toca los botones «Configurar routers» ni «Configurar notificaciones». Transaccional. Uso: network_notice.py <apps/multiplatform>
"""
import sys, pathlib

mp = pathlib.Path(sys.argv[1]).resolve()
p = mp / "common/src/commonMain/kotlin/chat/simplex/common/views/onboarding/YourNetwork.kt"
if not p.exists():
    sys.exit("ERROR [network_notice] no existe YourNetwork.kt")
t = p.read_text(encoding="utf-8")

android_old = (
    "              Text(\n"
    "                stringResource(MR.strings.onboarding_network_routers_cannot_know),\n"
    "                style = MaterialTheme.typography.h3,\n"
    "                fontWeight = FontWeight.Medium,\n"
    "                color = MaterialTheme.colors.secondary,\n"
    "                lineHeight = 25.sp,\n"
    "                textAlign = TextAlign.Center,\n"
    "                modifier = Modifier.padding(top = 14.dp)\n"
    "              )\n")
desktop_old = ("          Text(stringResource(MR.strings.onboarding_network_routers_cannot_know), style = MaterialTheme.typography.h3, "
               "fontWeight = FontWeight.Medium, color = MaterialTheme.colors.secondary, lineHeight = 25.sp, textAlign = TextAlign.Center)\n")
for name, old in (("Android", android_old), ("escritorio", desktop_old)):
    n = t.count(old)
    if n != 1:
        sys.exit(f"ERROR [network_notice] ({name}) el texto esperado aparece {n} veces (debía ser 1). No se modificó nada.")

t = t.replace(android_old, "              ZetariaNetworkNotice(Modifier.padding(top = 14.dp))\n", 1)
t = t.replace(desktop_old, "          ZetariaNetworkNotice(Modifier)\n", 1)

ES = ("Por los momentos aún no contamos con servidores propios, así que mientras tanto seguiremos usando los servidores "
      "originales de Simplex chat, para este Fork de Zetaria.\\n\\n"
      "Recuerda, que haya verdadera libertad de expresión en esta red social de nuestro reino, no significa que no hayan "
      "límites morales y éticos, está totalmente prohibido usar esta app para conductas inapropiadas, o para fines ilícitos, "
      "como difundir contenido ilegal y relacionados. El Rey Supremo al enterarse, que usas la app para eso, serás baneado "
      "sin contemplación y tu base de datos será eliminada...")
EN = ("For now we do not have our own servers yet, so in the meantime we will keep using the original SimpleX Chat servers "
      "for this Zetaria fork.\\n\\n"
      "Remember: real freedom of expression in our kingdom's social network does not mean there are no moral and ethical "
      "limits. Using this app for inappropriate conduct or for unlawful purposes, such as spreading illegal content and the "
      "like, is strictly forbidden. If the Supreme King learns that you use the app for that, you will be banned without "
      "mercy and your database will be deleted...")

t = t.rstrip("\n") + '''

@Composable
private fun ZetariaNetworkNotice(modifier: Modifier) {
  val es = java.util.Locale.getDefault().language == "es"
  val text = if (es) "''' + ES + '''" else "''' + EN + '''"
  Box(modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
    Text(
      text,
      style = MaterialTheme.typography.body2,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colors.secondary,
      lineHeight = 20.sp,
      textAlign = TextAlign.Center
    )
  }
}
'''
p.write_text(t, encoding="utf-8")
print("   [network_notice] listo: texto de «Tu red» reemplazado (Android y escritorio)")
