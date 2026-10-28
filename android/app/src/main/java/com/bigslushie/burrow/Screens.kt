package com.bigslushie.burrow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private const val codeGroupSize = 5

private object CodeGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length <= codeGroupSize) return TransformedText(text, OffsetMapping.Identity)
        val grouped = text.text.substring(0, codeGroupSize) + "-" + text.text.substring(codeGroupSize)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset <= codeGroupSize) offset else offset + 1
            override fun transformedToOriginal(offset: Int) = if (offset <= codeGroupSize) offset else offset - 1
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}

@Composable
fun AppScreen(
    appViewModel: AppViewModel = viewModel(factory = AppViewModel.factory),
    chatsViewModel: ChatsViewModel = viewModel(factory = ChatsViewModel.factory),
    charactersViewModel: CharactersViewModel = viewModel(factory = CharactersViewModel.factory),
    profileViewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.factory),
) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, appViewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { appViewModel.watchHost() }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.screen) {
            Screen.Loading -> Unit
            Screen.Pairing -> PairingScreen(state, onHostAddressChange = appViewModel::onHostAddressChange, onCodeChange = appViewModel::onCodeChange, onPair = appViewModel::pair)
            Screen.Paired -> MainScreen(state, appViewModel, chatsViewModel, charactersViewModel, profileViewModel)
        }
    }
}

@Composable
private fun MainScreen(
    appState: AppUiState,
    appViewModel: AppViewModel,
    chatsViewModel: ChatsViewModel,
    charactersViewModel: CharactersViewModel,
    profileViewModel: ProfileViewModel,
) {
    val host = appState.host
    val chatsState by chatsViewModel.state.collectAsStateWithLifecycle()
    val loadedCharacters by charactersViewModel.characters.collectAsStateWithLifecycle()
    val loadedProfile by profileViewModel.profile.collectAsStateWithLifecycle()
    val pictures by charactersViewModel.pictures.collectAsStateWithLifecycle()
    val userPicture by profileViewModel.picture.collectAsStateWithLifecycle()
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var openChatId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Characters) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val characters = loadedCharacters ?: return
    val profile = loadedProfile ?: return
    if (choosingModel) {
        ModelSheet(
            host = host,
            catalog = appState.models,
            error = appState.modelError,
            actions = ModelSheetActions(
                onRefresh = appViewModel::fetchModels,
                onLoad = appViewModel::loadModel,
                onUnload = appViewModel::unloadModel,
                onDismiss = { choosingModel = false },
            ),
        )
    }
    CompositionLocalProvider(
        LocalCharacterPictures provides pictures,
        LocalUserPicture provides userPicture,
        LocalOpenModels provides { choosingModel = true },
    ) {
        val editing = editingId?.let { id -> characters.firstOrNull { it.id == id } }
        if (creating || editing != null) {
            CharacterEditor(
                existing = editing,
                onSave = { draft ->
                    if (editing == null) {
                        val created = charactersViewModel.create(draft)
                        openChatId = chatsViewModel.startChat(created)
                    } else {
                        charactersViewModel.edit(editing.id, draft)
                    }
                    creating = false
                    editingId = null
                    tab = HomeTab.Characters
                },
                onClose = {
                    creating = false
                    editingId = null
                },
            )
            return@CompositionLocalProvider
        }

        val openChat = openChatId?.let { id -> chatsState.chats.firstOrNull { it.id == id } }
        val openCharacter = openChat?.let { chat -> characters.firstOrNull { it.id == chat.characterId } }
        if (openChat == null || openCharacter == null) {
            HomeScreen(
                host = host,
                tab = tab,
                characters = characters,
                chats = chatsState.chats,
                profile = profile,
                actions = HomeActions(
                    onTabChange = { tab = it },
                    onStartCreating = { creating = true },
                    onOpenCharacter = { character -> scope.launch { openChatId = chatsViewModel.openChat(character) } },
                    onEditCharacter = { character -> editingId = character.id },
                    onDeleteCharacter = charactersViewModel::delete,
                    onOpenChat = { chat -> openChatId = chat.id },
                    onDeleteChat = { chat -> chatsViewModel.deleteChat(chat.id) },
                    onSaveProfile = profileViewModel::save,
                    onProfilePicture = profileViewModel::setPicture,
                    onUnpair = appViewModel::unpair,
                ),
            )
            return@CompositionLocalProvider
        }

        OpenChat(openChat, openCharacter, profile, host, chatsState, chatsViewModel, onClose = { openChatId = null }, onOpened = { openChatId = it })
    }
}

@Composable
private fun OpenChat(
    openChat: Chat,
    openCharacter: Character,
    profile: Profile,
    host: HostStatus,
    chatsState: ChatsUiState,
    chatsViewModel: ChatsViewModel,
    onClose: () -> Unit,
    onOpened: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)
    ChatScreen(
        chat = openChat,
        character = openCharacter,
        profile = profile,
        host = host,
        streaming = chatsState.streaming?.takeIf { it.chatId == openChat.id },
        notice = chatsState.notice?.takeIf { it.chatId == openChat.id },
        actions = ChatActions(
            onBack = onClose,
            onNewChat = { scope.launch { onOpened(chatsViewModel.startChat(openCharacter)) } },
            onSend = { text -> chatsViewModel.send(openChat.id, text) },
            onStop = chatsViewModel::stopReply,
            onRegenerate = { message -> chatsViewModel.regenerate(openChat.id, message.id) },
            onShowVersion = { message, version -> chatsViewModel.showVersion(openChat.id, message.id, version) },
            onEdit = { message, text -> chatsViewModel.editMessage(openChat.id, message.id, text) },
            onDelete = { message -> chatsViewModel.deleteMessage(openChat.id, message.id) },
        ),
    )
}

@Composable
private fun PairingScreen(state: AppUiState, onHostAddressChange: (String) -> Unit, onCodeChange: (String) -> Unit, onPair: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.pair_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = state.hostAddress,
            onValueChange = onHostAddressChange,
            label = { Text(stringResource(R.string.pair_address_label)) },
            singleLine = true,
            enabled = !state.isPairing,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.pair_code_label)) },
            singleLine = true,
            enabled = !state.isPairing,
            visualTransformation = CodeGrouping,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onPair() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.error?.let { stringResource(it) }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPair,
            enabled = state.code.length == pairingCodeLength && isHostAddress(state.hostAddress) && !state.isPairing,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(if (state.isPairing) R.string.pair_button_busy else R.string.pair_button))
        }
    }
}
