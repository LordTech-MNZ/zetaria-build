#!/usr/bin/env python3
"""Zetaria: texto de la pantalla «Tu red» de la bienvenida.

Reemplaza la frase corta («Los routers de la red no pueden saber quién se comunica con quién») por el aviso
de Zetaria: servidores de SimpleX por ahora, y las normas de uso (conductas inapropiadas, contenido ilegal, baneo).
El texto va dentro del código (no depende del idioma del teléfono): español, y en inglés si el teléfono no está
en español.

Los dos párrafos se dibujan como dos Textos separados por un espacio fijo (no como un único texto con una línea
en blanco en medio): así el espacio entre párrafos es siempre el mismo, sin el salto «disparejo» que deja una
línea en blanco dentro de un solo bloque de texto. El texto va justificado (no centrado), para que el margen
izquierdo y derecho queden parejos en vez del efecto de zigzag de un párrafo centrado con líneas de distinto
largo. El icono de arriba se limita a 150 dp de alto para dejar más sitio al texto. Si aun así el texto no
cabe en pantallas pequeñas, el bloque entero se desplaza dentro de su caja (máx. 260 dp) sin empujar el botón
«Continuar». No toca los botones «Configurar routers» ni «Configurar notificaciones».
Transaccional. Uso: network_notice.py <apps/multiplatform>
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

image_old = "                modifier = Modifier.padding(horizontal = DEFAULT_ONBOARDING_HORIZONTAL_PADDING).fillMaxWidth()\n"
n_img = t.count(image_old)
if n_img != 1:
    sys.exit(f"ERROR [network_notice] (icono) el texto esperado aparece {n_img} veces (debía ser 1). No se modificó nada.")
t = t.replace(
    image_old,
    "                modifier = Modifier.padding(horizontal = DEFAULT_ONBOARDING_HORIZONTAL_PADDING).fillMaxWidth().heightIn(max = 150.dp)\n",
    1)

t = t.replace(android_old, "              ZetariaNetworkNotice(Modifier.padding(top = 10.dp))\n", 1)
t = t.replace(desktop_old, "          ZetariaNetworkNotice(Modifier)\n", 1)

ES_P1 = ("Por los momentos aún no contamos con servidores propios, así que mientras tanto seguiremos usando los "
         "servidores originales de Simplex chat, para este Fork de Zetaria.")
ES_P2 = ("Recuerda, que haya verdadera libertad de expresión en esta red social de nuestro reino, no significa "
         "que no hayan límites morales y éticos, está totalmente prohibido usar esta app para conductas "
         "inapropiadas, o para fines ilícitos, como difundir contenido ilegal y relacionados. El Rey Supremo al "
         "enterarse, que usas la app para eso, serás baneado sin contemplación y tu base de datos será "
         "eliminada...")
EN_P1 = ("For now we do not have our own servers yet, so in the meantime we will keep using the original "
         "SimpleX Chat servers for this Zetaria fork.")
EN_P2 = ("Remember: real freedom of expression in our kingdom's social network does not mean there are no moral "
         "and ethical limits. Using this app for inappropriate conduct or for unlawful purposes, such as "
         "spreading illegal content and the like, is strictly forbidden. If the Supreme King learns that you use "
         "the app for that, you will be banned without mercy and your database will be deleted...")

t = t.rstrip("\n") + '''

@Composable
private fun ZetariaNetworkNotice(modifier: Modifier) {
  val es = java.util.Locale.getDefault().language == "es"
  val p1 = if (es) "''' + ES_P1 + '''" else "''' + EN_P1 + '''"
  val p2 = if (es) "''' + ES_P2 + '''" else "''' + EN_P2 + '''"
  Column(
    modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      p1,
      style = MaterialTheme.typography.body2,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colors.secondary,
      lineHeight = 18.sp,
      textAlign = TextAlign.Justify
    )
    Spacer(Modifier.height(10.dp))
    Text(
      p2,
      style = MaterialTheme.typography.body2,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colors.secondary,
      lineHeight = 18.sp,
      textAlign = TextAlign.Justify
    )
  }
}
'''
p.write_text(t, encoding="utf-8")
print("   [network_notice] listo: párrafos separados y justificados; icono a 150 dp")
