package %%PKG%%

import androidx.compose.runtime.Composable
%%IMPORTS_COMMON%%

// Escritorio: sin reproductor propio; se usa la vista de archivo normal.
actual fun zetariaAudioAvailable(file: CIFile?): Boolean = false

@Composable
actual fun ZetariaAudioFileView(file: CIFile, onLongClick: () -> Unit) {}
