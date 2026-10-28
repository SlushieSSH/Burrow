package com.bigslushie.burrow

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class ChatActions(
    val onBack: () -> Unit,
    val onNewChat: () -> Unit,
    val onSend: (String) -> Unit,
    val onStop: () -> Unit,
    val onRegenerate: (ChatMessage) -> Unit,
    val onShowVersion: (ChatMessage, Int) -> Unit,
    val onEdit: (ChatMessage, String) -> Unit,
    val onDelete: (ChatMessage) -> Unit,
)

private sealed interface ChatRow {
    val key: String

    data class Saved(val message: ChatMessage, val isLatestReply: Boolean) : ChatRow {
        override val key = message.id
    }

    data class Pending(val reply: StreamingReply) : ChatRow {
        override val key = "pending"
    }
}

@Composable
fun ChatScreen(
    chat: Chat,
    character: Character,
    profile: Profile,
    host: HostStatus,
    streaming: StreamingReply?,
    notice: ChatNotice?,
    actions: ChatActions,
) {
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val rows = remember(chat.messages, streaming) { chatRows(chat, streaming) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        ChatTopBar(character, host, actions)
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        ) {
            itemsIndexed(rows.asReversed(), key = { _, row -> row.key }) { _, row ->
                when (row) {
                    is ChatRow.Pending -> CharacterMessage(character, row.reply.text, streaming = row.reply)
                    is ChatRow.Saved -> SavedMessage(
                        message = row.message,
                        character = character,
                        profile = profile,
                        streamingVersion = streaming?.takeIf { it.versionOf == row.message.id },
                        showReplyControls = row.isLatestReply && streaming == null,
                        actions = actions,
                        onEditRequest = { editing = row.message },
                    )
                }
            }
        }
        notice?.let { chatNotice ->
            Text(
                text = stringResource(chatNotice.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Composer(character, host, isStreaming = streaming != null, onSend = actions.onSend, onStop = actions.onStop)
    }

    editing?.let { message ->
        EditDialog(
            original = message.text,
            onSave = { text ->
                actions.onEdit(message, text)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun chatRows(chat: Chat, streaming: StreamingReply?): List<ChatRow> {
    val latestReplyIndex = chat.messages.indexOfLast { it.speaker == Speaker.Character }.takeIf { it == chat.messages.lastIndex && it > 0 }
    val saved = chat.messages.mapIndexed { index, message -> ChatRow.Saved(message, index == latestReplyIndex) }
    val pending = streaming?.takeIf { it.versionOf == null }?.let { ChatRow.Pending(it) }
    return saved + listOfNotNull(pending)
}

@Composable
private fun ChatTopBar(character: Character, host: HostStatus, actions: ChatActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
        }
        CharacterAvatar(character, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = character.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            HostStatusLine(host)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.chat_menu), tint = MaterialTheme.colorScheme.onBackground)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.new_chat)) },
                    onClick = {
                        menuOpen = false
                        actions.onNewChat()
                    },
                )
            }
        }
    }
}

@Composable
private fun SavedMessage(
    message: ChatMessage,
    character: Character,
    profile: Profile,
    streamingVersion: StreamingReply?,
    showReplyControls: Boolean,
    actions: ChatActions,
    onEditRequest: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        if (message.speaker == Speaker.User) {
            UserMessage(profile, message.text, onLongPress = { menuOpen = true })
        } else {
            CharacterMessage(
                character = character,
                text = streamingVersion?.text ?: message.text,
                streaming = streamingVersion,
                onLongPress = { menuOpen = true },
                footer = if (showReplyControls) {
                    { ReplyControls(message, actions) }
                } else {
                    null
                },
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = {
                menuOpen = false
                copyPrivately(context, message.text)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                menuOpen = false
                onEditRequest()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                menuOpen = false
                actions.onDelete(message)
            })
        }
    }
}

@Composable
private fun bubbleMaxWidth() = (LocalConfiguration.current.screenWidthDp * 0.8f).dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(profile: Profile, text: String, onLongPress: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
        Text(
            text = styledMessage(text, MaterialTheme.colorScheme.onSurfaceVariant),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth())
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Spacer(Modifier.width(8.dp))
        UserAvatar(profile, 30.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterMessage(
    character: Character,
    text: String,
    streaming: StreamingReply?,
    onLongPress: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        CharacterAvatar(character, 30.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = character.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth())
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                when {
                    streaming?.isThinking == true -> ThinkingPanel(streaming.thinking)
                    streaming != null && text.isEmpty() -> TypingDots()
                    else ->
                        Text(
                            text = styledMessage(text, MaterialTheme.colorScheme.onSurfaceVariant),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                }
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun ReplyControls(message: ChatMessage, actions: ChatActions) {
    val iconColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContentColor = MaterialTheme.colorScheme.outline,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.versions.size > 1) {
            IconButton(
                onClick = { actions.onShowVersion(message, message.shownVersion - 1) },
                enabled = message.shownVersion > 0,
                colors = iconColors,
            ) {
                Icon(painterResource(R.drawable.ic_previous), contentDescription = stringResource(R.string.previous_reply))
            }
            Text(
                text = stringResource(R.string.version_count, message.shownVersion + 1, message.versions.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { actions.onShowVersion(message, message.shownVersion + 1) },
                enabled = message.shownVersion < message.versions.lastIndex,
                colors = iconColors,
            ) {
                Icon(painterResource(R.drawable.ic_next), contentDescription = stringResource(R.string.next_reply))
            }
        }
        IconButton(onClick = { actions.onRegenerate(message) }, colors = iconColors) {
            Icon(painterResource(R.drawable.ic_regenerate), contentDescription = stringResource(R.string.regenerate))
        }
    }
}

@Composable
private fun ThinkingPanel(thinking: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val arrowTurn by animateFloatAsState(if (expanded) -90f else 90f, label = "thinkingArrow")
    LaunchedEffect(thinking, expanded) { if (expanded) scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.thinking),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            TypingDots()
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_next),
                contentDescription = stringResource(if (expanded) R.string.hide_thinking else R.string.show_thinking),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrowTurn),
            )
        }
        if (expanded) {
            Text(
                text = thinking.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(max = 260.dp)
                    .verticalScroll(scroll),
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { index ->
            val dotAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 500, delayMillis = index * 160), RepeatMode.Reverse),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun Composer(character: Character, host: HostStatus, isStreaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val canSend = host is HostStatus.Ready && draft.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.message_hint, character.name)) },
            maxLines = 6,
            visualTransformation = liveFormatting(),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainer,
                cursorColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (isStreaming) {
            ComposerButton(R.drawable.ic_stop, R.string.stop, enabled = true, onClick = onStop)
        } else {
            ComposerButton(R.drawable.ic_send, R.string.send, enabled = canSend, onClick = {
                onSend(draft)
                draft = ""
            })
        }
    }
}

@Composable
private fun ComposerButton(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.size(52.dp),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun EditDialog(original: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.edit_message)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 8, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
