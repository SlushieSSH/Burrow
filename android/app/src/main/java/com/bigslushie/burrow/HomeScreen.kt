package com.bigslushie.burrow

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HomeTab { Characters, Create, Chats, Profile }

val LocalCharacterPictures = compositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }

val LocalUserPicture = compositionLocalOf<ImageBitmap?> { null }

class HomeActions(
    val onTabChange: (HomeTab) -> Unit,
    val onStartCreating: () -> Unit,
    val onOpenCharacter: (Character) -> Unit,
    val onEditCharacter: (Character) -> Unit,
    val onDeleteCharacter: (Character) -> Unit,
    val onOpenChat: (Chat) -> Unit,
    val onDeleteChat: (Chat) -> Unit,
    val onSaveProfile: (String, String) -> Unit,
    val onProfilePicture: (Uri) -> Unit,
    val onUnpair: () -> Unit,
)

@Composable
fun HomeScreen(host: HostStatus, tab: HomeTab, characters: List<Character>, chats: List<Chat>, profile: Profile, actions: HomeActions) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                HomeTab.Characters -> CharacterGrid(host, characters, actions)
                HomeTab.Create -> CreateIntro(actions.onStartCreating)
                HomeTab.Chats -> Inbox(host, chats, characters, actions)
                HomeTab.Profile -> ProfileTab(host, profile, actions.onSaveProfile, actions.onProfilePicture, actions.onUnpair)
            }
        }
        IconNavigation(tab, actions.onTabChange)
    }
}

@Composable
fun CenteredHeader(title: String, host: HostStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 18.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        HostStatusLine(host, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun HostStatusLine(host: HostStatus, modifier: Modifier = Modifier, opensModels: Boolean = true) {
    val ready = host is HostStatus.Ready
    val label = when (host) {
        is HostStatus.Ready -> listOfNotNull(
            stringResource(R.string.status_ready),
            host.modelName,
            host.contextLength.takeIf { it > 0 }?.let { stringResource(R.string.context_size, it / 1_024) },
        ).joinToString("  ")
        is HostStatus.Loading -> stringResource(R.string.status_loading, host.modelName)
        HostStatus.Unloading -> stringResource(R.string.status_unloading)
        HostStatus.NoModel -> stringResource(R.string.status_no_model)
        HostStatus.Offline, HostStatus.Unpaired -> stringResource(R.string.status_offline)
    }
    val openModels = LocalOpenModels.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = opensModels, onClick = openModels)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ready) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (opensModels) {
            Icon(
                painter = painterResource(R.drawable.ic_next),
                contentDescription = stringResource(R.string.models_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp)
                    .rotate(90f),
            )
        }
    }
}

@Composable
private fun IconNavigation(tab: HomeTab, onTabChange: (HomeTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavigationIcon(R.drawable.ic_grid, R.string.tab_characters, tab == HomeTab.Characters) { onTabChange(HomeTab.Characters) }
        NavigationIcon(R.drawable.ic_create, R.string.tab_create, tab == HomeTab.Create) { onTabChange(HomeTab.Create) }
        NavigationIcon(R.drawable.ic_chats, R.string.tab_chats, tab == HomeTab.Chats) { onTabChange(HomeTab.Chats) }
        NavigationIcon(R.drawable.ic_profile, R.string.tab_profile, tab == HomeTab.Profile) { onTabChange(HomeTab.Profile) }
    }
}

@Composable
private fun NavigationIcon(icon: Int, label: Int, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                painter = painterResource(icon),
                contentDescription = stringResource(label),
                tint = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent),
        )
    }
}

@Composable
fun CharacterPicture(character: Character, modifier: Modifier, shape: Shape, initialSize: Dp) {
    PictureOrInitial(LocalCharacterPictures.current[character.id], character.name, modifier, shape, initialSize)
}

@Composable
fun CharacterAvatar(character: Character, size: Dp) {
    CharacterPicture(character, Modifier.size(size), CircleShape, size)
}

@Composable
fun UserAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val picture = LocalUserPicture.current
    if (picture == null && profile.name.isBlank()) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_profile), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
        return
    }
    PictureOrInitial(picture, profile.name, modifier.size(size), CircleShape, size)
}

@Composable
private fun PictureOrInitial(picture: ImageBitmap?, name: String, modifier: Modifier, shape: Shape, initialSize: Dp) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(bitmap = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = name.take(1).uppercase(),
                fontSize = (initialSize.value * 0.45f).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun EmptyState(icon: Int, message: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CharacterGrid(host: HostStatus, characters: List<Character>, actions: HomeActions) {
    Column(Modifier.fillMaxSize()) {
        CenteredHeader(stringResource(R.string.tab_characters), host)
        if (characters.isEmpty()) {
            EmptyState(R.drawable.ic_grid, R.string.characters_empty)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(characters, key = { it.id }) { character -> CharacterTile(character, actions) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterTile(character: Character, actions: HomeActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = { actions.onOpenCharacter(character) }, onLongClick = { menuOpen = true }),
    ) {
        CharacterPicture(character, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 160.dp)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chats),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(20.dp),
        )
        Text(
            text = character.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                menuOpen = false
                actions.onEditCharacter(character)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                menuOpen = false
                confirmingDelete = true
            })
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.delete_character_title, character.name)) },
            text = { Text(stringResource(R.string.delete_character_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    actions.onDeleteCharacter(character)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CreateIntro(onStartCreating: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(140.dp))
        Text(
            text = stringResource(R.string.create_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        PillButton(text = stringResource(R.string.continue_button), enabled = true, onClick = onStartCreating)
    }
}

@Composable
fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Inbox(host: HostStatus, chats: List<Chat>, characters: List<Character>, actions: HomeActions) {
    Column(Modifier.fillMaxSize()) {
        CenteredHeader(stringResource(R.string.tab_chats), host)
        val rows = chats.mapNotNull { chat -> characters.firstOrNull { it.id == chat.characterId }?.let { chat to it } }
        if (rows.isEmpty()) {
            EmptyState(R.drawable.ic_inbox, R.string.chats_empty)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { (chat, _) -> chat.id }) { (chat, character) ->
                ChatRow(chat, character, onOpen = { actions.onOpenChat(chat) }, onDelete = { actions.onDeleteChat(chat) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: Chat, character: Character, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterAvatar(character, 54.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = relativeTime(chat.updatedAtMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = chat.messages.lastOrNull()?.text.orEmpty().replace('\n', ' ').replace("*", ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_chat)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun relativeTime(timestampMillis: Long): String {
    val minutes = (System.currentTimeMillis() - timestampMillis) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.time_now)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(R.string.time_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.time_days, (minutes / (60 * 24)).toInt())
    }
}
