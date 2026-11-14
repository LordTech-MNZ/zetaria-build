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
import chat.simplex.common.model.CIDirection
import chat.simplex.common.model.ChatInfo
import chat.simplex.common.model.ChatItem
import chat.simplex.common.model.ChatType
import chat.simplex.common.model.ComposedMessage
import chat.simplex.common.model.GroupInfo
import chat.simplex.common.model.GroupMember
import chat.simplex.common.model.GroupMemberRole
import chat.simplex.common.model.MsgContent
import chat.simplex.common.platform.ColumnWithScrollBar
import chat.simplex.common.platform.chatModel
import chat.simplex.common.platform.settings
import chat.simplex.common.views.chat.group.MemberRow
import chat.simplex.common.views.chat.group.updateMemberRoleDialog
import chat.simplex.common.views.chat.group.updateMembersRole
import chat.simplex.common.views.helpers.AppBarTitle
import chat.simplex.common.views.helpers.ModalView
import chat.simplex.common.views.helpers.withBGApi
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Roles de miembros de Zetaria.
 *
 * Los roles de un grupo (observador, miembro, moderador, administrador, propietario) los define el núcleo de
 * SimpleX y viajan en el protocolo: no se pueden crear roles nuevos desde la app. Lo que sí se puede es
 *   - asignar a cada miembro cualquiera de los roles que tu propio rol te permite (igual que en su ficha),
 *   - ponerle a cada rol un NOMBRE PROPIO en ese grupo (p. ej. «Moderador» -> «Conde»), y
 *   - (opcional) ponerle un APODO DE ROL a UN miembro concreto.
 *
 * Los nombres y apodos son POR GRUPO y los ve todo el grupo (los que usan Zetaria): cada cambio se publica en el
 * grupo como un aviso (mensaje de tipo propio «zetaria_roles», con un texto legible para quien no use Zetaria), y
 * cada app que lo recibe lo aplica SOLO si quien lo envió es moderador, administrador o propietario en ese momento
 * (y, para un apodo individual, si su rol es igual o mayor al rol del miembro). Un apodo individual solo se muestra
 * mientras el miembro conserve el rol para el que se puso. Así la jerarquía la comprueba cada dispositivo por su
 * cuenta. Si hay dos cambios sobre lo mismo, gana el más reciente.
 */
object ZetariaRoles {
  private const val SEP = "\u001F"
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

  fun cleanName(name: String): String = name.replace(SEP, "").trim().take(24)

  // ---- almacenamiento (en este dispositivo, por grupo). Se guarda también la hora del cambio, incluso cuando el
  // nombre queda vacío, para que un cambio antiguo no pueda deshacer uno más nuevo.
  private class Stored(val ts: Long, val role: String, val name: String)

  private fun roleKey(groupId: Long, role: GroupMemberRole): String =
    "zetaria_g" + groupId.toString() + "_role_" + role.memberRole

  private fun memberKey(groupId: Long, memberId: String): String =
    "zetaria_g" + groupId.toString() + "_mem_" + memberId

  private fun readRole(groupId: Long, role: GroupMemberRole): Stored? {
    val raw = settings.getString(roleKey(groupId, role), "")
    if (raw.isEmpty()) return null
    val p = raw.split(SEP)
    val ts = p.getOrNull(0)?.toLongOrNull() ?: return null
    return Stored(ts, role.memberRole, p.getOrNull(1) ?: "")
  }

  private fun readMember(groupId: Long, memberId: String): Stored? {
    val raw = settings.getString(memberKey(groupId, memberId), "")
    if (raw.isEmpty()) return null
    val p = raw.split(SEP)
    val ts = p.getOrNull(0)?.toLongOrNull() ?: return null
    return Stored(ts, p.getOrNull(1) ?: "", p.getOrNull(2) ?: "")
  }

  private fun writeRole(groupId: Long, role: GroupMemberRole, ts: Long, name: String) {
    settings.putString(roleKey(groupId, role), ts.toString() + SEP + name)
    version += 1
  }

  private fun writeMember(groupId: Long, memberId: String, role: GroupMemberRole, ts: Long, name: String) {
    settings.putString(memberKey(groupId, memberId), ts.toString() + SEP + role.memberRole + SEP + name)
    version += 1
  }

  private fun nextTs(previous: Long?): Long {
    val now = Clock.System.now().toEpochMilliseconds()
    return if (previous != null && previous >= now) previous + 1 else now
  }

  // ---- lectura (lee `version` para que las pantallas se actualicen solas)
  fun groupRoleAlias(groupId: Long, role: GroupMemberRole): String? {
    if (version < 0) return null
    val s = readRole(groupId, role) ?: return null
    return if (s.name.isEmpty()) null else s.name
  }

  fun roleName(groupId: Long, role: GroupMemberRole, isChannel: Boolean): String =
    groupRoleAlias(groupId, role) ?: role.text(isChannel)

  // Apodo individual del rol de este miembro. Está atado al rol en que se puso: si el miembro cambia de rol, deja de verse.
  fun memberAlias(member: GroupMember): String? {
    if (version < 0) return null
    val s = readMember(member.groupId, member.memberId) ?: return null
    if (s.role != member.memberRole.memberRole || s.name.isEmpty()) return null
    return s.name
  }

  // Lo que se muestra como rol de un miembro: su apodo individual, o el nombre del rol en ese grupo
  fun roleLabel(member: GroupMember, isChannel: Boolean): String =
    memberAlias(member) ?: roleName(member.groupId, member.memberRole, isChannel)

  // ¿Este miembro tiene algún apodo (individual o del rol en este grupo)? Sirve para mostrar la etiqueta de rol
  // también a los miembros normales, que SimpleX oculta por defecto.
  fun hasLabel(member: GroupMember): Boolean =
    memberAlias(member) != null || groupRoleAlias(member.groupId, member.memberRole) != null

  // ---- cambios hechos por ti (se guardan y se avisan al grupo). Nombre vacío = volver al original / quitar apodo
  fun setGroupRoleAlias(rhId: Long?, groupInfo: GroupInfo, role: GroupMemberRole, name: String) {
    val clean = cleanName(name)
    val ts = nextTs(readRole(groupInfo.groupId, role)?.ts)
    writeRole(groupInfo.groupId, role, ts, clean)
    ZetariaRolesSync.announceRole(rhId, groupInfo, role, clean, ts)
  }

  fun setMemberAlias(rhId: Long?, groupInfo: GroupInfo, member: GroupMember, name: String) {
    val clean = cleanName(name)
    val ts = nextTs(readMember(groupInfo.groupId, member.memberId)?.ts)
    writeMember(groupInfo.groupId, member.memberId, member.memberRole, ts, clean)
    ZetariaRolesSync.announceMember(rhId, groupInfo, member, clean, ts)
  }

  // ---- cambios recibidos del grupo (ya verificados): solo se aplican si son más nuevos que lo guardado
  fun applyRemoteRole(groupId: Long, role: GroupMemberRole, name: String, ts: Long) {
    val current = readRole(groupId, role)
    if (current != null && current.ts >= ts) return
    writeRole(groupId, role, ts, name)
  }

  fun applyRemoteMember(groupId: Long, memberId: String, role: GroupMemberRole, name: String, ts: Long) {
    val current = readMember(groupId, memberId)
    if (current != null && current.ts >= ts) return
    writeMember(groupId, memberId, role, ts, name)
  }
}

// Envío y recepción de los avisos de apodos por el propio grupo
object ZetariaRolesSync {
  const val TYPE = "zetaria_roles"

  fun announceRole(rhId: Long?, groupInfo: GroupInfo, role: GroupMemberRole, name: String, ts: Long) {
    val original = role.text(groupInfo.isChannel)
    val text = if (name.isEmpty()) "🏷️ El rol «" + original + "» vuelve a su nombre original"
      else "🏷️ El rol «" + original + "» ahora se llama «" + name + "»"
    post(rhId, groupInfo, text, "role", role, null, name, ts)
  }

  fun announceMember(rhId: Long?, groupInfo: GroupInfo, member: GroupMember, name: String, ts: Long) {
    val text = if (name.isEmpty()) "🎖️ Se quitó el apodo de rol de " + member.chatViewName
      else "🎖️ Apodo de rol de " + member.chatViewName + ": «" + name + "»"
    post(rhId, groupInfo, text, "member", member.memberRole, member.memberId, name, ts)
  }

  private fun post(rhId: Long?, groupInfo: GroupInfo, text: String, op: String, role: GroupMemberRole, memberId: String?, name: String, ts: Long) {
    if (groupInfo.useRelays) return // en canales los nombres solo se guardan en este dispositivo
    val json = buildJsonObject {
      put("type", TYPE)
      put("text", text)
      put("v", 1)
      put("op", op)
      put("role", role.memberRole)
      if (memberId != null) put("memberId", memberId)
      put("name", name)
      put("ts", ts)
    }
    withBGApi {
      try {
        val items = chatModel.controller.apiSendMessages(
          rh = rhId,
          type = ChatType.Group,
          id = groupInfo.apiId,
          scope = null,
          composedMessages = listOf(
            ComposedMessage(null, null, MsgContent.MCUnknown(type = TYPE, text = text, json = json), emptyMap())
          )
        )
        if (!items.isNullOrEmpty()) {
          items.forEach { aChatItem ->
            withContext(Dispatchers.Main) {
              chatModel.chatsContext.addChatItem(rhId, aChatItem.chatInfo, aChatItem.chatItem)
            }
          }
        }
      } catch (e: Exception) {
        // si el aviso no sale, el cambio queda guardado en este dispositivo
      }
    }
  }

  // Llamado por la app cada vez que llega un mensaje nuevo de tipo desconocido
  fun onReceived(rhId: Long?, chatInfo: ChatInfo, item: ChatItem, content: MsgContent.MCUnknown) {
    if (content.type != TYPE) return
    val group = (chatInfo as? ChatInfo.Group)?.groupInfo ?: return
    val sender = (item.chatDir as? CIDirection.GroupRcv)?.groupMember ?: return
    val json = content.json as? JsonObject ?: return
    withBGApi {
      try {
        process(rhId, group, sender, json)
      } catch (e: Exception) {
        // aviso mal formado: se ignora
      }
    }
  }

  private suspend fun process(rhId: Long?, group: GroupInfo, sender: GroupMember, json: JsonObject) {
    // Jerarquía: solo moderador, administrador y propietario
    if (sender.memberRole < GroupMemberRole.Moderator) return
    val op = json["op"]?.jsonPrimitive?.contentOrNull ?: return
    val roleName = json["role"]?.jsonPrimitive?.contentOrNull ?: return
    val role = GroupMemberRole.values().firstOrNull { it.memberRole == roleName } ?: return
    val name = ZetariaRoles.cleanName(json["name"]?.jsonPrimitive?.contentOrNull ?: "")
    val ts = json["ts"]?.jsonPrimitive?.longOrNull ?: return
    if (op == "role") {
      if (role !in ZetariaRoles.editableRoles) return
      withContext(Dispatchers.Main) { ZetariaRoles.applyRemoteRole(group.groupId, role, name, ts) }
    } else if (op == "member") {
      val memberId = json["memberId"]?.jsonPrimitive?.contentOrNull ?: return
      // Jerarquía: el aviso dice qué rol tiene el miembro; el emisor no puede ponerle apodo a un rol mayor al suyo.
      // No se consulta la lista de miembros (esa consulta depende del perfil activo y no sirve para perfiles en segundo
      // plano). Si el aviso mintiera sobre el rol, no tendría efecto: el apodo solo se muestra cuando ese rol coincide
      // con el rol real del miembro (ver ZetariaRoles.memberAlias).
      if (role > sender.memberRole) return
      withContext(Dispatchers.Main) { ZetariaRoles.applyRemoteMember(group.groupId, memberId, role, name, ts) }
    }
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
              Text(ZetariaRoles.roleName(gInfo.groupId, role, gInfo.isChannel))
              if (ZetariaRoles.groupRoleAlias(gInfo.groupId, role) != null) {
                Text(
                  "Original: " + role.text(isChannel = gInfo.isChannel),
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
        if (canEdit) "Toca el lápiz para cambiar el nombre de un rol, o el apodo de un solo miembro. Cada cambio se avisa en el grupo y lo ven todos los que usen Zetaria."
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

  // Renombrar un rol (para todos los que lo tengan) o poner un apodo a un solo miembro
  val roleToRename = renaming
  if (roleToRename != null && canEdit) {
    var individual by remember(roleToRename) { mutableStateOf(false) }
    var target by remember(roleToRename) { mutableStateOf<GroupMember?>(null) }
    var text by remember(roleToRename) { mutableStateOf(ZetariaRoles.groupRoleAlias(gInfo.groupId, roleToRename) ?: "") }
    val candidates: List<GroupMember> = members.filter {
      it.memberRole == roleToRename && it.memberCurrent && ZetariaRoles.canEditMemberName(gInfo, it)
    }

    fun chooseAll() {
      individual = false
      target = null
      text = ZetariaRoles.groupRoleAlias(gInfo.groupId, roleToRename) ?: ""
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
            "Nombre original: " + roleToRename.text(isChannel = gInfo.isChannel),
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
          Text(
            if (gInfo.useRelays) "En canales el cambio solo se guarda en tu dispositivo."
            else "Se publicará un aviso en el grupo y lo verán todos los que usen Zetaria.",
            color = MaterialTheme.colors.secondary,
            fontSize = 12.sp
          )
        }
      },
      confirmButton = {
        TextButton({
          val t = target
          if (individual) {
            if (t != null) ZetariaRoles.setMemberAlias(rhId, gInfo, t, text)
          } else {
            ZetariaRoles.setGroupRoleAlias(rhId, gInfo, roleToRename, text)
          }
          renaming = null
        }, enabled = !individual || target != null) { Text("Guardar") }
      },
      dismissButton = {
        Row {
          TextButton({
            val t = target
            if (individual) {
              if (t != null) ZetariaRoles.setMemberAlias(rhId, gInfo, t, "")
            } else {
              ZetariaRoles.setGroupRoleAlias(rhId, gInfo, roleToRename, "")
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
              Text(ZetariaRoles.roleName(gInfo.groupId, role, gInfo.isChannel))
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = { TextButton({ assigning = null }) { Text("Cancelar") } }
    )
  }
}
