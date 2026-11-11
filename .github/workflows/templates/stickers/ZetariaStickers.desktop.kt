package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatItem
import chat.simplex.common.model.ChatModel
import chat.simplex.common.views.helpers.AttachmentOption

// Escritorio: sin stickers por ahora (la imagen se ve como una imagen normal).
actual fun zetariaStickerAvailable(ci: ChatItem): Boolean = false

@Composable
actual fun ZetariaStickerItem(chat: Chat, ci: ChatItem, onLongClick: () -> Unit) {}

@Composable
actual fun ZetariaStickerHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {}
