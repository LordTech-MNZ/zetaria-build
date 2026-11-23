# Autoridad de Zetaria

Sistema para sancionar infracciones graves (acoso, contenido ilegal) con **órdenes firmadas**.

## Idea clave
Zetaria no tiene servidor central: cada teléfono hace cumplir lo que dice la Autoridad.
Por eso no se usa una contraseña compartida dentro de la app (cualquiera podría sacarla del APK),
sino **un par de llaves**:

| Llave | Dónde vive | Para qué |
|---|---|---|
| **Pública** | Variable de repositorio `ZETARIA_AUTHORITY_PUBKEY` → incrustada en el APK | Solo *comprueba* firmas |
| **Privada** | Solo en tu teléfono, cifrada con tu contraseña | *Firma* las órdenes |

Aunque alguien tenga el APK, el repositorio y el código, no puede dar órdenes sin tu llave privada.

## Puesta en marcha (una sola vez)
1. Sube estos archivos al repo y compila (la primera compilación queda sin llave: es normal).
2. En la app: **Configuración > Configuración avanzada > Autoridad de Zetaria**.
   Escribe una contraseña larga (mín. 10 caracteres) y pulsa **Crear llave de Autoridad en este teléfono**.
3. La app muestra la **llave pública**. Cópiala y guárdala en GitHub:
   *Settings > Secrets and variables > Actions > **Variables** > New variable* → `ZETARIA_AUTHORITY_PUBKEY`.
4. Compila de nuevo e instala la actualización. Entra otra vez a «Autoridad de Zetaria», escribe tu
   contraseña y **Desbloquear**. Solo ese teléfono (con esa llave) queda como Autoridad.
5. **Respaldo**: con la autoridad desbloqueada, «Respaldo cifrado de la llave» copia un texto cifrado
   con tu contraseña. Guárdalo en un gestor de contraseñas. Si pierdes el teléfono y el respaldo, la
   autoridad no se puede recuperar (habría que crear otra llave y recompilar).

## Qué puedes ordenar
- **Retirar publicación / comentario** del feed (botón ⚖ en cada publicación ajena, con la autoridad desbloqueada).
- **Restringir**: no puede publicar ni comentar en el feed.
- **Silenciar**: sin feed ni grupos (los mensajes directos siguen).
- **Banear**: no puede enviar nada. Sus publicaciones se ocultan en los demás teléfonos.
- **Levantar** cualquier sanción (desde el registro de edictos).
- **Borrar datos** (solo si activas `ZETARIA_ALLOW_WIPE = on`; ver abajo).

Todas pueden tener duración en días (0 = sin fin) y un motivo que ve el sancionado y el registro público.

## Cómo llegan las órdenes a los demás
1. Al emitirlas, se envían a tus contactos y **cada teléfono las reenvía una vez** a los suyos.
2. Para llegar a **todos**: en la app pulsa «Copiar todos los edictos» y pégalos en `authority/edicts.txt`
   del repositorio. Cada app lo consulta cada 30 minutos. (Ese archivo no dispara compilaciones.)
Un edicto falso o alterado se descarta: la firma no coincide con la llave pública.

## Borrado de datos (desactivado por defecto)
Es irreversible, y si tu llave se filtrara un atacante podría borrar los datos de todos. Por eso solo existe
si pones `ZETARIA_ALLOW_WIPE = on`. El teléfono sancionado ve un aviso con el motivo antes de borrar, y el
edicto queda en el registro público. Avisa de esta norma a la gente antes de activarlo. Para la mayoría de
infracciones, Banear ya cumple el objetivo.

## Límites (importante)
- **Es cumplimiento por cooperación**: lo hacen cumplir las apps Zetaria. Alguien baneado puede usar SimpleX
  oficial, una versión modificada o reinstalar (el ID de la cédula es por instalación) y seguirá pudiendo
  hablar con quien lo acepte. Lo que sí logra el sistema: los teléfonos Zetaria dejan de mostrar su contenido
  y su app oficial de Zetaria no puede publicar.
- Los mensajes directos de un baneado no se ocultan en el teléfono de quien los recibe (cada quien puede
  bloquear al contacto desde su chat). Para grupos, usa además los roles de SimpleX (propietario/moderador).
- Un edicto de borrado solo se ejecuta si el teléfono lo recibe (contacto o repositorio) y la app está abierta.
