package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatModel
import chat.simplex.common.views.helpers.AttachmentOption

// Escritorio: sin herramientas de esteganografía por ahora.
@Composable
actual fun ZetariaStegoHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {}
