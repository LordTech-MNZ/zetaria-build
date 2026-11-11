package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatItem
import chat.simplex.common.model.ChatModel
import chat.simplex.common.views.helpers.AttachmentOption

// Stickers de Zetaria: se envían como una imagen normal cuyo archivo se llama "sticker_...".
// Un SimpleX normal la ve como imagen; Zetaria la dibuja sin burbuja (y animada si es WebP animado).

fun zetariaIsStickerName(name: String?): Boolean = name != null && name.startsWith("sticker_")

/** true si el mensaje es un sticker ya descargado y esta plataforma sabe dibujarlo */
expect fun zetariaStickerAvailable(ci: ChatItem): Boolean

@Composable
expect fun ZetariaStickerItem(chat: Chat, ci: ChatItem, onLongClick: () -> Unit)

/** Panel de stickers (se abre desde el botón "Stickers" del menú de adjuntos) */
@Composable
expect fun ZetariaStickerHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>)
