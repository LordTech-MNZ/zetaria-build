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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 *   - asignar a cada miembro cualquiera de los roles que tu propio rol te permite (igual que en su ficha),
 *   - ponerle a cada rol un NOMBRE PROPIO (p. ej. «Moderador» -> «Capitán») que vale para todos los que tengan ese rol, y
 *   - (opcional) ponerle un APODO DE ROL a UN miembro concreto, que solo cambia cómo ves a esa persona.
 * Todos los nombres y apodos se guardan en este dispositivo; los demás miembros ven los originales o los suyos.
 *
 * Jerarquía: solo propietarios, administradores y moderadores del grupo pueden cambiar nombres y apodos, y un apodo
 * individual solo se puede poner a miembros con un rol igual o menor al tuyo (la misma regla que para asignar roles).
 */
object ZetariaRoles {
  private const val PREFIX = "zetaria_role_alias_"
  private const val MEMBER_PREFIX = "zetaria_member_alias_"
  private var version by mutableStateOf(0)

  val editableRoles: List<GroupMemberRole> = listOf(
    GroupMemberRole.Observer,
    GroupMemberRole.Member,
    GroupMemberRole.Moderator,
    GroupMemberRole.Admin,
    GroupMemberRole.Owner
  )

  // Quién puede cambiar nombres y apodos en este grupo: propietario, administrador y moderador
  fun canEditNames(groupInfo: GroupInfo): Boolean =
    groupInfo.membership.memberRole >= GroupMemberRole.Moderator

  // Apodo individual: además, solo a miembros con rol igual o menor al tuyo
  fun canEditMemberName(groupInfo: GroupInfo, member: GroupMember): Boolean =
    canEditNames(groupInfo) && member.memberRole <= groupInfo.membership.memberRole

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

  private fun memberKey(member: GroupMember): String =
    MEMBER_PREFIX + member.groupId.toString() + "_" + member.groupMemberId.toString()

  // Apodo individual del rol de este miembro. Se guarda junto al rol al que se puso: si el miembro cambia de rol,
  // el apodo deja de aplicarse.
  fun memberAlias(member: GroupMember): String? {
    if (version < 0) return null
    val saved = settings.getString(memberKey(member), "")
    val sep = saved.indexOf(':')
    if (sep <= 0) return null
    if (saved.substring(0, sep) != member.memberRole.memberRole) return null
    val name = saved.substring(sep + 1).trim()
    return if (name.isEmpty()) null else name
  }

  // Nombre vacío = quitar el apodo individual
  fun setMemberAlias(member: GroupMember, name: String) {
    val clean = name.trim().take(24)
    if (clean.isEmpty()) settings.remove(memberKey(member))
    else settings.putString(memberKey(member), member.memberRole.memberRole + ":" + clean)
    version += 1
  }

  // Lo que se muestra como rol de un miembro: su apodo individual, o el nombre del rol (propio u original)
  fun roleLabel(member: GroupMember, isChannel: Boolean): String =
    memberAlias(member) ?: member.memberRole.text(isChannel)
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
  val canEdit = ZetariaRoles.canEditNames(gInfo)
  var renaming by remember { mutableStateOf<GroupMemberRole?>(null) }
  var assigning by remember { mutableStateOf<GroupMember?>(null) }

  ModalView(close = close, cardScreen = true) {
    ColumnWithScrollBar {
      AppBarTitle("Roles de miembros")

      SectionView("Nombres de los roles") {
        for (role in ZetariaRoles.editableRoles) {
          val onClick: (() -> Unit)? = if (canEdit) {
            { renaming = role }
          } else null
          SectionItemViewSpaceBetween(onClick) {
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
            if (canEdit) {
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
      }
      SectionTextFooter(
        if (canEdit) "Toca el lápiz para cambiar el nombre de un rol, o el apodo de un solo miembro. Los nombres se guardan en este dispositivo."
        else "Solo los propietarios, administradores y moderadores pueden cambiar los nombres de los roles."
      )
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

  // Renombrar un rol (para todos) o poner un apodo a un solo miembro
  val roleToRename = renaming
  if (roleToRename != null && canEdit) {
    var individual by remember(roleToRename) { mutableStateOf(false) }
    var target by remember(roleToRename) { mutableStateOf<GroupMember?>(null) }
    var text by remember(roleToRename) { mutableStateOf(ZetariaRoles.aliasFor(roleToRename) ?: "") }
    val candidates: List<GroupMember> = members.filter {
      it.memberRole == roleToRename && it.memberCurrent && ZetariaRoles.canEditMemberName(gInfo, it)
    }

    fun chooseAll() {
      individual = false
      target = null
      text = ZetariaRoles.aliasFor(roleToRename) ?: ""
    }
    fun chooseMember(m: GroupMember) {
      individual = true
      target = m
      text = ZetariaRoles.memberAlias(m) ?: ""
    }

    AlertDialog(
      onDismissRequest = { renaming = null },
      title = { Text("Renombrar rol") },
      text = {
        Column {
          Text(
            "Nombre original: " + roleToRename.defaultText(isChannel = gInfo.isChannel),
            color = MaterialTheme.colors.secondary
          )
          Row(
            Modifier.fillMaxWidth().clickable { chooseAll() },
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(selected = !individual, onClick = { chooseAll() })
            Text("Todos los que tengan este rol")
          }
          Row(
            Modifier.fillMaxWidth().clickable(enabled = candidates.isNotEmpty()) { chooseMember(candidates[0]) },
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(
              selected = individual,
              onClick = { chooseMember(candidates[0]) },
              enabled = candidates.isNotEmpty()
            )
            Text("Solo un miembro (opcional)")
          }
          if (candidates.isEmpty()) {
            Text(
              "Nadie tiene este rol ahora mismo (o su rol es mayor que el tuyo).",
              color = MaterialTheme.colors.secondary,
              fontSize = 12.sp
            )
          }
          if (individual) {
            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
              for (m in candidates) {
                Row(
                  Modifier.fillMaxWidth().clickable { chooseMember(m) },
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  RadioButton(selected = target?.groupMemberId == m.groupMemberId, onClick = { chooseMember(m) })
                  Text(m.chatViewName, maxLines = 1)
                }
              }
            }
          }
          OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(24) },
            singleLine = true,
            label = { Text(if (individual) "Apodo para este miembro" else "Nuevo nombre del rol") }
          )
        }
      },
      confirmButton = {
        TextButton({
          val t = target
          if (individual) {
            if (t != null) ZetariaRoles.setMemberAlias(t, text)
          } else {
            ZetariaRoles.setAlias(roleToRename, text)
          }
          renaming = null
        }, enabled = !individual || target != null) { Text("Guardar") }
      },
      dismissButton = {
        Row {
          TextButton({
            val t = target
            if (individual) {
              if (t != null) ZetariaRoles.setMemberAlias(t, "")
            } else {
              ZetariaRoles.setAlias(roleToRename, "")
            }
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
