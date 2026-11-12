package chat.simplex.common.views.zetaria

import SectionBottomSpacer
import SectionDividerSpaced
import SectionItemView
import SectionItemViewSpaceBetween
import SectionTextFooter
import SectionView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.ChatInfo
import chat.simplex.common.model.GroupInfo
import chat.simplex.common.model.GroupMember
import chat.simplex.common.model.GroupMemberRole
import chat.simplex.common.platform.ColumnWithScrollBar
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.settings
import chat.simplex.common.views.chat.group.MemberRow
import chat.simplex.common.views.chat.group.updateMemberRoleDialog
import chat.simplex.common.views.chat.group.updateMembersRole
import chat.simplex.common.views.helpers.AppBarTitle
import chat.simplex.common.views.helpers.ModalView
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource

/*
 * Roles de miembros de Zetaria.
 *
 * Los roles de un grupo (observador, miembro, moderador, administrador, propietario) los define el núcleo de
 * SimpleX y viajan en el protocolo: no se pueden crear roles nuevos desde la app. Lo que sí se puede es
 *   - asignar a cada miembro cualquiera de los roles que tu propio rol te permite (igual que en su ficha), y
 *   - ponerle a cada rol un NOMBRE PROPIO (p. ej. «Moderador» -> «Capitán»).
 * Los nombres propios se guardan en este dispositivo y se aplican en toda la app (listas de miembros, selectores,
 * avisos...). Los demás miembros ven los nombres originales o los que ellos mismos hayan puesto.
 */
object ZetariaRoles {
  private const val PREFIX = "zetaria_role_alias_"
  private var version by mutableStateOf(0)

  val editableRoles: List<GroupMemberRole> = listOf(
    GroupMemberRole.Observer,
    GroupMemberRole.Member,
    GroupMemberRole.Moderator,
    GroupMemberRole.Admin,
    GroupMemberRole.Owner
  )

  // Nombre propio del rol, o null si no tiene (entonces se usa el original). Lee `version` para que las
  // pantallas se actualicen solas al renombrar.
  fun aliasFor(role: GroupMemberRole): String? {
    if (version < 0) return null
    if (role !in editableRoles) return null
    val saved = settings.getString(PREFIX + role.memberRole, "").trim()
    return if (saved.isEmpty()) null else saved
  }

  // Nombre vacío = restablecer el original
  fun setAlias(role: GroupMemberRole, name: String) {
    val clean = name.trim().take(24)
    if (clean.isEmpty()) settings.remove(PREFIX + role.memberRole)
    else settings.putString(PREFIX + role.memberRole, clean)
    version += 1
  }
}

@Composable
fun ZetariaRolesView(rhId: Long?, chatId: String, close: () -> Unit) {
  val groupInfo = remember { derivedStateOf {
    val ch = chatModel.getChat(chatId)
    val g = (ch?.chatInfo as? ChatInfo.Group)?.groupInfo
    if (g == null || ch.remoteHostId != rhId) null else g
  }}
  val gInfo: GroupInfo = groupInfo.value ?: return
  val members: List<GroupMember> = remember { chatModel.groupMembers }.value
  var renaming by remember { mutableStateOf<GroupMemberRole?>(null) }
  var assigning by remember { mutableStateOf<GroupMember?>(null) }

  ModalView(close = close, cardScreen = true) {
    ColumnWithScrollBar {
      AppBarTitle("Roles de miembros")

      SectionView("Nombres de los roles") {
        for (role in ZetariaRoles.editableRoles) {
          SectionItemViewSpaceBetween({ renaming = role }) {
            Column(Modifier.weight(1f)) {
              Text(role.text(isChannel = gInfo.isChannel))
              if (ZetariaRoles.aliasFor(role) != null) {
                Text(
                  "Original: " + role.defaultText(isChannel = gInfo.isChannel),
                  color = MaterialTheme.colors.secondary,
                  fontSize = 12.sp
                )
              }
            }
            IconButton({ renaming = role }) {
              Icon(
                painterResource(MR.images.ic_edit),
                "Editar nombre",
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(22.dp)
              )
            }
          }
        }
      }
      SectionTextFooter("Toca el lápiz para cambiar el nombre de un rol. Los nombres se guardan en este dispositivo.")
      SectionDividerSpaced()

      SectionView("Miembros") {
        val shown = members.filter { it.memberRole != GroupMemberRole.Relay && it.memberCurrent }
        for (m in shown) {
          val isUser = m.groupMemberId == gInfo.membership.groupMemberId
          val canAssign = !isUser && m.canChangeRoleTo(gInfo) != null
          val onClick: (() -> Unit)? = if (canAssign) {
            { assigning = m }
          } else null
          SectionItemView(onClick, minHeight = 54.dp) {
            MemberRow(m, user = isUser, isChannel = gInfo.isChannel)
          }
        }
      }
      SectionTextFooter("Toca un miembro para asignarle un rol. Solo puedes asignar roles iguales o menores al tuyo.")
      SectionBottomSpacer()
    }
  }

  // Renombrar un rol
  val roleToRename = renaming
  if (roleToRename != null) {
    var text by remember(roleToRename) { mutableStateOf(ZetariaRoles.aliasFor(roleToRename) ?: "") }
    AlertDialog(
      onDismissRequest = { renaming = null },
      title = { Text("Renombrar rol") },
      text = {
        Column {
          Text(
            "Nombre original: " + roleToRename.defaultText(isChannel = gInfo.isChannel),
            color = MaterialTheme.colors.secondary
          )
          OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(24) },
            singleLine = true,
            label = { Text("Nuevo nombre") }
          )
        }
      },
      confirmButton = {
        TextButton({
          ZetariaRoles.setAlias(roleToRename, text)
          renaming = null
        }) { Text("Guardar") }
      },
      dismissButton = {
        Row {
          TextButton({
            ZetariaRoles.setAlias(roleToRename, "")
            renaming = null
          }) { Text("Restablecer") }
          TextButton({ renaming = null }) { Text("Cancelar") }
        }
      }
    )
  }

  // Asignar un rol a un miembro
  val memberToAssign = assigning
  if (memberToAssign != null) {
    val options: List<GroupMemberRole> = memberToAssign.canChangeRoleTo(gInfo) ?: emptyList()
    fun pick(role: GroupMemberRole) {
      assigning = null
      if (role == memberToAssign.memberRole) return
      updateMemberRoleDialog(role, gInfo, memberToAssign.memberCurrent, onDismiss = {}, onConfirm = {
        updateMembersRole(role, rhId, gInfo, listOf(memberToAssign.groupMemberId))
      })
    }
    AlertDialog(
      onDismissRequest = { assigning = null },
      title = { Text("Rol de " + memberToAssign.chatViewName) },
      text = {
        Column {
          for (role in options) {
            Row(
              Modifier.fillMaxWidth().clickable { pick(role) },
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(selected = memberToAssign.memberRole == role, onClick = { pick(role) })
              Text(role.text(isChannel = gInfo.isChannel))
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = { TextButton({ assigning = null }) { Text("Cancelar") } }
    )
  }
}
