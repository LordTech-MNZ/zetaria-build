#!/usr/bin/env bash
# Rebrand SimpleX Chat (Android/Desktop) -> Zetaria
# Uso: ./rebrand.sh /ruta/a/simplex-chat/apps/multiplatform
set -euo pipefail

APP_NAME="Zetaria"
APP_ID="app.zetaria.chat"
HERE="$(cd "$(dirname "$0")" && pwd)"
MP="${1:?Uso: ./rebrand.sh <ruta a apps/multiplatform>}"
[ -d "$MP/android" ] || { echo "No encuentro $MP/android"; exit 1; }

echo "==> Nombre de la app: $APP_NAME"
# Solo el atributo app_name (en todos los idiomas). El resto de textos
# ("SimpleX address", etc.) se deja: son términos del protocolo.
find "$MP/common/src/commonMain/resources/MR" -name strings.xml -print0 \
  | xargs -0 sed -i -E "s#(<string name=\"app_name\">)[^<]*(</string>)#\1${APP_NAME}\2#"

echo "==> applicationId -> $APP_ID"
G="$MP/android/build.gradle.kts"
# NO se toca 'namespace' ni los paquetes Kotlin (chat.simplex.*): el puente JNI los necesita.
sed -i "s#applicationId = \"chat.simplex.app\"#applicationId = \"${APP_ID}\"#" "$G"
sed -i "s#\"chat.simplex.app.provider\"#\"${APP_ID}.provider\"#" "$G"
sed -i "s#\"chat.simplex.app\${rootProject#\"${APP_ID}\${rootProject#" "$G"

echo "==> Desktop: nombre de paquete"
D="$MP/desktop/build.gradle.kts"
sed -i "s#packageName = \"SimpleX\"#packageName = \"${APP_NAME}\"#; s#packageName = \"simplex\"#packageName = \"zetaria\"#" "$D"

echo "==> Íconos"
python3 "$HERE/gen_icons.py" "$HERE/branding/zetaria_icon.png" "$MP"

echo "==> Textos y logos de la interfaz"
python3 "$HERE/rebrand_ui.py" "$MP"

echo "Listo. Cambios aplicados en $MP"
