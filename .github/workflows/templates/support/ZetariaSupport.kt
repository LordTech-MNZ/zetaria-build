package chat.simplex.common.views.zetaria

import SectionItemView
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.simplex.common.model.*
import chat.simplex.common.platform.chatModel
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chatlist.connectIfOpenedViaUri
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource

// Soporte de Zetaria: sustituye al contacto «Ask SimpleX Team» (tarjeta que crea el núcleo de SimpleX).
// Al tocarlo se conecta a la dirección del perfil de Zetaria; si esa dirección es «de empresa» (business address),
// SimpleX crea un grupo privado por cada persona que escribe, con el soporte y esa persona dentro.
object ZetariaSupport {
  const val LINK = "__ZETARIA_SUPPORT_LINK__"

  // La tarjeta que el núcleo crea para el equipo de SimpleX (se oculta en «Nuevo chat»)
  fun isSimplexTeamCard(chat: Chat): Boolean {
    val c = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: return false
    if (!c.isContactCard) return false
    val link = c.profile.contactLink ?: ""
    val name = c.profile.displayName
    return link.contains("lrdvu2d8A1GumSmoKb2krQmtKhWXq") || name.startsWith("Ask SimpleX") || name == "SimpleX Team"
  }

  fun title(): String =
    if (java.util.Locale.getDefault().language == "es") "Soporte de Zetaria" else "Zetaria support"
}

@Composable
fun ZetariaSupportRow(rhId: Long?) {
  SectionItemView(click = { connectIfOpenedViaUri(rhId, ZetariaSupport.LINK, chatModel) }) {
    ProfileImage(42.dp, if (isInDarkTheme()) MR.images.ic_simplex_light else MR.images.ic_simplex_dark)
    Spacer(Modifier.width(DEFAULT_PADDING_HALF))
    Text(
      ZetariaSupport.title(),
      Modifier.weight(1f),
      color = MaterialTheme.colors.primary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Icon(
      painterResource(MR.images.ic_mail),
      contentDescription = null,
      tint = MaterialTheme.colors.primary,
      modifier = Modifier.size(21.dp)
    )
  }
}
