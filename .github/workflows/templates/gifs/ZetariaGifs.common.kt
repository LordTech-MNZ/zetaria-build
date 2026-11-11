package chat.simplex.common.views.chat.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatModel
import chat.simplex.common.views.helpers.AttachmentOption

// Buscador de GIFs (KLIPY). Se abre desde el botón "GIFs" del menú de adjuntos.
@Composable
expect fun ZetariaGifHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>)
