package chat.simplex.common.views.chat.item

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.simplex.common.model.*
import chat.simplex.common.platform.chatModel
import chat.simplex.common.views.helpers.AttachmentOption
import kotlinx.coroutines.*
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Encuestas de Zetaria.
// Sin cambios de protocolo: todo viaja como mensajes de texto normales.
//   Encuesta : "📊 Pregunta" + una línea por opción ("○ " una respuesta, "□ " varias)
//   Voto     : respuesta a la encuesta  "🗳 1,3 · Opción A, Opción C"  ("🗳 0" = voto retirado)
//   Cierre   : respuesta del autor      "🔒 Encuesta cerrada"
// Un SimpleX normal las ve como texto y respuestas; Zetaria las dibuja como tarjeta.
// ---------------------------------------------------------------------------
private const val ZP_POLL = "📊 "
private const val ZP_VOTE = "🗳 "
private const val ZP_CLOSE = "🔒"

class ZetariaPoll(val question: String, val options: List<String>, val multi: Boolean)

fun zetariaParsePollText(text: String): ZetariaPoll? {
  if (!text.startsWith(ZP_POLL)) return null
  val lines = text.split("\n")
  val q = lines[0].substring(ZP_POLL.length).trim()
  val opts = ArrayList<String>()
  var multi = false
  for (l in lines.drop(1)) {
    if (l.startsWith("○ ")) {
      opts.add(l.substring(2).trim())
    } else if (l.startsWith("□ ")) {
      multi = true
      opts.add(l.substring(2).trim())
    }
  }
  if (q.isEmpty() || opts.size < 2) return null
  return ZetariaPoll(q, opts, multi)
}

fun zetariaIsPoll(ci: ChatItem): Boolean =
  ci.content.msgContent is MsgContent.MCText && ci.meta.itemDeleted == null && zetariaParsePollText(ci.content.text) != null

sealed class ZetariaPollEvent {
  class Vote(val sel: Set<Int>): ZetariaPollEvent()
  object Close: ZetariaPollEvent()
}

fun zetariaPollEvent(ci: ChatItem): ZetariaPollEvent? {
  val q = ci.quotedItem ?: return null
  if (q.itemId == null) return null
  if (ci.meta.itemDeleted != null || ci.content.msgContent !is MsgContent.MCText) return null
  if (!q.content.text.startsWith(ZP_POLL)) return null
  val t = ci.content.text
  if (t.startsWith(ZP_CLOSE)) return ZetariaPollEvent.Close
  if (t.startsWith(ZP_VOTE)) {
    val head = t.substring(ZP_VOTE.length).substringBefore(" ·").trim()
    val nums = head.split(",").map { it.trim().toIntOrNull() }
    if (nums.isEmpty() || nums.any { it == null }) return null
    return ZetariaPollEvent.Vote(nums.filterNotNull().filter { it > 0 }.toSet())
  }
  return null
}

fun zetariaIsPollEvent(ci: ChatItem): Boolean = zetariaPollEvent(ci) != null

private fun zpVoterKey(d: CIDirection): String = when (d) {
  is CIDirection.DirectSnd, is CIDirection.GroupSnd, is CIDirection.LocalSnd -> "me"
  is CIDirection.GroupRcv -> "m" + d.groupMember.groupMemberId
  else -> "o"
}

private fun zpVoterName(d: CIDirection, chat: Chat): String = when (d) {
  is CIDirection.DirectSnd, is CIDirection.GroupSnd, is CIDirection.LocalSnd -> "Tú"
  is CIDirection.GroupRcv -> d.groupMember.displayName
  else -> chat.chatInfo.displayName
}

// ---------------------------------------------------------------------------
// Recuento. Se vuelve a calcular con los mensajes cargados del chat; lo ya visto se recuerda
// durante la sesión por si al desplazarse la lista deja de tener cargados los votos.
// ---------------------------------------------------------------------------
private class ZpRec(val itemId: Long, val name: String, val sel: Set<Int>)

private val zpVotesCache = HashMap<String, HashMap<String, ZpRec>>()
private val zpClosedCache = HashSet<String>()

class ZetariaTally(
  val counts: IntArray,
  val voters: List<List<String>>,
  val totalVoters: Int,
  val mine: Set<Int>,
  val closed: Boolean
)

fun zetariaTally(items: List<ChatItem>, poll: ChatItem, chat: Chat, n: Int): ZetariaTally {
  val ck = chat.id + ":" + poll.id
  val votes = zpVotesCache.getOrPut(ck) { HashMap() }
  val authorKey = zpVoterKey(poll.chatDir)
  for (ci in items) {
    if (ci.quotedItem?.itemId != poll.id) continue
    val ev = zetariaPollEvent(ci) ?: continue
    val key = zpVoterKey(ci.chatDir)
    when (ev) {
      is ZetariaPollEvent.Vote -> {
        val prev = votes[key]
        if (prev == null || ci.id > prev.itemId) votes[key] = ZpRec(ci.id, zpVoterName(ci.chatDir, chat), ev.sel)
      }
      is ZetariaPollEvent.Close -> {
        if (key == authorKey) zpClosedCache.add(ck)
      }
    }
  }
  val counts = IntArray(n)
  val voters = List(n) { ArrayList<String>() }
  var total = 0
  for (r in votes.values) {
    var any = false
    for (i in r.sel) {
      if (i in 1..n) {
        counts[i - 1]++
        voters[i - 1].add(r.name)
        any = true
      }
    }
    if (any) total++
  }
  val mine = votes["me"]?.sel?.filter { it in 1..n }?.toSet() ?: emptySet()
  return ZetariaTally(counts, voters, total, mine, zpClosedCache.contains(ck))
}

// ---------------------------------------------------------------------------
// Envío (igual que el compositor: mismo API y mismo alta en la lista del chat)
// ---------------------------------------------------------------------------
private suspend fun zpSend(chat: Chat, chatsCtx: ChatModel.ChatsContext, text: String, quotedItemId: Long?): Boolean {
  val cInfo = chat.chatInfo
  val mc = MsgContent.MCText(text)
  val sent = if (cInfo.chatType == ChatType.Local) {
    chatModel.controller.apiCreateChatItems(
      rh = chat.remoteHostId,
      noteFolderId = cInfo.apiId,
      composedMessages = listOf(ComposedMessage(null, null, mc, emptyMap()))
    )
  } else {
    chatModel.controller.apiSendMessages(
      rh = chat.remoteHostId,
      type = cInfo.chatType,
      id = cInfo.apiId,
      scope = cInfo.groupChatScope(),
      sendAsGroup = cInfo.sendAsGroup,
      live = false,
      ttl = null,
      sign = false,
      composedMessages = listOf(ComposedMessage(null, quotedItemId, mc, emptyMap()))
    )
  }
  if (sent.isNullOrEmpty()) return false
  for (a in sent) {
    withContext(Dispatchers.Main) {
      chatsCtx.addChatItem(chat.remoteHostId, a.chatInfo, a.chatItem)
    }
  }
  return true
}

// ---------------------------------------------------------------------------
// Tarjeta de la encuesta (dentro de la burbuja del mensaje)
// ---------------------------------------------------------------------------
@Composable
private fun ZpIndicator(selected: Boolean, square: Boolean, color: Color) {
  val shape = if (square) RoundedCornerShape(5.dp) else CircleShape
  Box(Modifier.size(20.dp).border(2.dp, color, shape), contentAlignment = Alignment.Center) {
    if (selected) Box(Modifier.size(10.dp).background(color, shape))
  }
}

@Composable
fun ZetariaPollCard(chatsCtx: ChatModel.ChatsContext, chat: Chat, ci: ChatItem) {
  val poll = zetariaParsePollText(ci.content.text) ?: return
  val items = chatsCtx.chatItems.value
  val size = items.size
  val t = remember(size, ci.id) { zetariaTally(items, ci, chat, poll.options.size) }
  var pending by remember(ci.id, t.mine) { mutableStateOf(t.mine) }
  var busy by remember { mutableStateOf(false) }
  var showVoters by remember { mutableStateOf(false) }
  var confirmClose by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val primary = MaterialTheme.colors.primary
  val secondary = MaterialTheme.colors.secondary
  val onBg = MaterialTheme.colors.onBackground
  val closed = t.closed

  fun send(text: String) {
    if (busy) return
    busy = true
    scope.launch(Dispatchers.IO) {
      try {
        zpSend(chat, chatsCtx, text, ci.id)
      } finally {
        withContext(Dispatchers.Main) { busy = false }
      }
    }
  }

  fun sendVote(sel: Set<Int>) {
    if (closed) return
    val s = sel.filter { it in 1..poll.options.size }.sorted()
    val text = if (s.isEmpty()) {
      ZP_VOTE + "0 · Voto retirado"
    } else {
      ZP_VOTE + s.joinToString(",") + " · " + s.joinToString(", ") { poll.options[it - 1] }
    }
    send(text)
  }

  Column(
    Modifier
      .width(270.dp)
      .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 22.dp)
  ) {
    Text(
      "📊  Encuesta" + (if (poll.multi) " · varias respuestas" else "") + (if (closed) " · cerrada" else ""),
      fontSize = 11.sp,
      color = secondary
    )
    Spacer(Modifier.height(4.dp))
    Text(poll.question, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onBg)
    Spacer(Modifier.height(6.dp))
    poll.options.forEachIndexed { i, opt ->
      val n = i + 1
      val frac = if (t.totalVoters > 0) t.counts[i].toFloat() / t.totalVoters else 0f
      val selected = if (poll.multi && !closed) pending.contains(n) else t.mine.contains(n)
      Box(
        Modifier
          .fillMaxWidth()
          .padding(vertical = 3.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(primary.copy(alpha = 0.10f))
          .clickable(enabled = !closed && !busy) {
            if (poll.multi) {
              pending = if (pending.contains(n)) pending - n else pending + n
            } else if (t.mine != setOf(n)) {
              sendVote(setOf(n))
            }
          }
      ) {
        Box(Modifier.matchParentSize()) {
          Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(primary.copy(alpha = 0.28f)))
        }
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          ZpIndicator(selected, poll.multi, if (closed && !selected) secondary else primary)
          Spacer(Modifier.width(8.dp))
          Text(opt, Modifier.weight(1f), fontSize = 14.sp, color = onBg)
          Spacer(Modifier.width(6.dp))
          Text("${(frac * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onBg)
        }
      }
      if (showVoters && t.voters[i].isNotEmpty()) {
        Text(
          t.voters[i].joinToString(", "),
          fontSize = 11.sp,
          color = secondary,
          modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "${t.totalVoters} " + (if (t.totalVoters == 1) "voto" else "votos"),
        Modifier.weight(1f),
        fontSize = 12.sp,
        color = secondary
      )
      if (t.totalVoters > 0) {
        Text(
          if (showVoters) "Ocultar votantes" else "Ver votantes",
          fontSize = 12.sp,
          color = primary,
          modifier = Modifier.clickable { showVoters = !showVoters }.padding(vertical = 4.dp)
        )
      }
    }
    if (!closed) {
      if (poll.multi) {
        Button(
          onClick = { sendVote(pending) },
          enabled = !busy && pending != t.mine,
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
          Text(if (pending.isEmpty() && t.mine.isNotEmpty()) "Retirar voto" else "Votar")
        }
      } else if (t.mine.isNotEmpty()) {
        Text(
          "Retirar voto",
          fontSize = 12.sp,
          color = primary,
          modifier = Modifier.clickable(enabled = !busy) { sendVote(emptySet()) }.padding(vertical = 4.dp)
        )
      }
      if (ci.chatDir.sent) {
        Text(
          "Cerrar encuesta",
          fontSize = 12.sp,
          color = MaterialTheme.colors.error,
          modifier = Modifier.clickable(enabled = !busy) { confirmClose = true }.padding(vertical = 4.dp)
        )
      }
    }
  }

  if (confirmClose) {
    AlertDialog(
      onDismissRequest = { confirmClose = false },
      title = { Text("¿Cerrar la encuesta?") },
      text = { Text("Ya no se podrá votar. Esto no se puede deshacer.") },
      confirmButton = {
        TextButton(onClick = {
          confirmClose = false
          send(ZP_CLOSE + " Encuesta cerrada")
        }) { Text("Cerrar") }
      },
      dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancelar") } }
    )
  }
}

// ---------------------------------------------------------------------------
// Votos y cierres: una línea discreta (sin burbuja) en lugar de una respuesta completa
// ---------------------------------------------------------------------------
@Composable
fun ZetariaPollEventLine(chat: Chat, ci: ChatItem) {
  val ev = zetariaPollEvent(ci) ?: return
  val sent = ci.chatDir.sent
  val name = zpVoterName(ci.chatDir, chat)
  val options = ci.quotedItem?.content?.text?.let { zetariaParsePollText(it)?.options } ?: emptyList()
  val label = when (ev) {
    is ZetariaPollEvent.Close ->
      if (sent) "🔒 Cerraste la encuesta" else "🔒 $name cerró la encuesta"
    is ZetariaPollEvent.Vote -> {
      val chosen = ev.sel.sorted().mapNotNull { options.getOrNull(it - 1) }
      when {
        chosen.isEmpty() -> if (sent) "🗳 Retiraste tu voto" else "🗳 $name retiró su voto"
        sent -> "🗳 Votaste: " + chosen.joinToString(", ")
        else -> "🗳 $name votó: " + chosen.joinToString(", ")
      }
    }
  }
  Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
    Text(
      label,
      fontSize = 12.sp,
      color = MaterialTheme.colors.secondary,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis
    )
  }
}

// ---------------------------------------------------------------------------
// Crear encuesta: se abre desde el botón "Encuesta" del menú de adjuntos
// ---------------------------------------------------------------------------
@Composable
fun ZetariaPollHost(chatsCtx: ChatModel.ChatsContext, chat: Chat, attachmentOption: MutableState<AttachmentOption?>) {
  var show by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(attachmentOption.value) {
    if (attachmentOption.value == AttachmentOption.Poll) {
      attachmentOption.value = null
      show = true
    }
  }
  if (show) {
    ZetariaPollCreateDialog(onDismiss = { show = false }) { question, options, multi ->
      show = false
      val sb = StringBuilder(ZP_POLL).append(question.replace("\n", " "))
      for (o in options) {
        sb.append("\n").append(if (multi) "□ " else "○ ").append(o.replace("\n", " "))
      }
      scope.launch(Dispatchers.IO) { zpSend(chat, chatsCtx, sb.toString(), null) }
    }
  }
}

@Composable
private fun ZetariaPollCreateDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean) -> Unit) {
  var question by remember { mutableStateOf("") }
  val options = remember { mutableStateListOf("", "") }
  var multi by remember { mutableStateOf(false) }
  val cleaned = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  val valid = question.isNotBlank() && cleaned.size >= 2
  val primary = MaterialTheme.colors.primary
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Nueva encuesta", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          question,
          { if (it.length <= 250) question = it },
          label = { Text("Pregunta") },
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text("Opciones (de 2 a 10)", fontSize = 13.sp, color = MaterialTheme.colors.secondary)
        Spacer(Modifier.height(4.dp))
        options.forEachIndexed { i, o ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
              o,
              { if (it.length <= 100) options[i] = it },
              label = { Text("Opción ${i + 1}") },
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
            if (options.size > 2) {
              Text(
                "✕",
                fontSize = 18.sp,
                modifier = Modifier.clickable { options.removeAt(i) }.padding(10.dp)
              )
            }
          }
          Spacer(Modifier.height(6.dp))
        }
        if (options.size < 10) {
          Text(
            "+ Añadir opción",
            color = primary,
            modifier = Modifier.clickable { options.add("") }.padding(vertical = 8.dp)
          )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Permitir varias respuestas", Modifier.weight(1f))
          Switch(multi, { multi = it })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cancelar") }
          TextButton(onClick = { onCreate(question.trim(), cleaned, multi) }, enabled = valid) { Text("Crear") }
        }
      }
    }
  }
}
