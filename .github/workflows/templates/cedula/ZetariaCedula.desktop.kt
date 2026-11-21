package chat.simplex.common.views.zetaria

import androidx.compose.ui.graphics.ImageBitmap

// Escritorio: sin imagen de cédula por ahora; se comparte solo el código QR.
actual fun zetariaCedulaImage(name: String, photo: ImageBitmap?, qr: ImageBitmap, id: String, dateLine: String): ImageBitmap = qr
