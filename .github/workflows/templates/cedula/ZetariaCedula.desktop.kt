package chat.simplex.common.views.zetaria

import androidx.compose.ui.graphics.ImageBitmap

// Escritorio: sin imagen de cédula por ahora; se muestra solo el código QR.
actual fun zetariaCedulaImage(card: ZetariaCedulaCard): ImageBitmap = card.qr ?: ImageBitmap(1, 1)

actual fun zetariaFlagImage(): ImageBitmap? = null
