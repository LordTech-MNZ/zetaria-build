package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatModel
import chat.simplex.common.views.helpers.AttachmentOption

// Escritorio: sin buscador de GIFs por ahora.
@Composable
actual fun ZetariaGifHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {}
