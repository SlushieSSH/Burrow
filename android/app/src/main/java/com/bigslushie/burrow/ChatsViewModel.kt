package com.bigslushie.burrow

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private const val maxHistoryMessages = 100

data class StreamingReply(val chatId: String, val versionOf: String?, val thinking: String, val text: String) {
    val isThinking: Boolean get() = text.isEmpty() && thinking.isNotEmpty()
}

data class ChatNotice(val chatId: String, @StringRes val message: Int)

data class ChatsUiState(
    val chats: List<Chat> = emptyList(),
    val streaming: StreamingReply? = null,
    val notice: ChatNotice? = null,
)

private fun newId(): String = UUID.randomUUID().toString()

private fun ChatFailure.message(): Int = when (this) {
    ChatFailure.Unpaired -> R.string.chat_error_unpaired
    ChatFailure.NoModel -> R.string.chat_error_no_model
    ChatFailure.LmStudio -> R.string.chat_error_lm_studio
    ChatFailure.Unreachable -> R.string.chat_error_unreachable
}

class ChatsViewModel(
    private val chatRepository: ChatRepository,
    private val characterRepository: CharacterRepository,
    private val profileRepository: ProfileRepository,
    private val pairingRepository: PairingRepository,
    private val hostClient: HostClient,
) : ViewModel() {
    private val streaming = MutableStateFlow<StreamingReply?>(null)
    private val notice = MutableStateFlow<ChatNotice?>(null)
    private var replyJob: Job? = null

    val state: StateFlow<ChatsUiState> = combine(chatRepository.chats, streaming, notice) { chats, reply, chatNotice ->
        ChatsUiState(chats.sortedByDescending { it.updatedAtMillis }, reply, chatNotice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    suspend fun openChat(character: Character): String {
        val existing = chatRepository.chats.first().filter { it.characterId == character.id }.maxByOrNull { it.updatedAtMillis }
        return existing?.id ?: startChat(character)
    }

    suspend fun startChat(character: Character): String {
        val profile = profileRepository.profile.first()
        val greeting = ChatMessage(newId(), Speaker.Character, listOf(fillPlaceholders(character.greeting, character, profile)))
        val chat = Chat(newId(), character.id, listOf(greeting), System.currentTimeMillis())
        chatRepository.update { chats -> chats + chat }
        return chat.id
    }

    fun send(chatId: String, text: String) {
        val message = ChatMessage(newId(), Speaker.User, listOf(text.trim()))
        viewModelScope.launch {
            editChat(chatId) { chat -> chat.copy(messages = chat.messages + message) }
            reply(chatId, versionOf = null)
        }
    }

    fun regenerate(chatId: String, messageId: String) = reply(chatId, versionOf = messageId)

    fun showVersion(chatId: String, messageId: String, version: Int) {
        viewModelScope.launch {
            editMessages(chatId) { message ->
                if (message.id == messageId) message.copy(shownVersion = version.coerceIn(message.versions.indices)) else message
            }
        }
    }

    fun editMessage(chatId: String, messageId: String, text: String) {
        viewModelScope.launch {
            var resend = false
            editChat(chatId) { chat ->
                val index = chat.messages.indexOfFirst { it.id == messageId }
                if (index < 0) return@editChat chat
                val original = chat.messages[index]
                if (original.speaker == Speaker.User) {
                    resend = true
                    chat.copy(messages = chat.messages.take(index) + original.copy(versions = listOf(text.trim()), shownVersion = 0))
                } else {
                    val versions = original.versions.toMutableList().also { it[original.shownVersion] = text.trim() }
                    chat.copy(messages = chat.messages.toMutableList().also { it[index] = original.copy(versions = versions) })
                }
            }
            if (resend) reply(chatId, versionOf = null)
        }
    }

    fun deleteMessage(chatId: String, messageId: String) {
        viewModelScope.launch {
            editChat(chatId) { chat -> chat.copy(messages = chat.messages.filterNot { it.id == messageId }) }
        }
    }

    fun deleteChat(chatId: String) {
        if (streaming.value?.chatId == chatId) stopReply()
        viewModelScope.launch { chatRepository.update { chats -> chats.filterNot { it.id == chatId } } }
    }

    fun stopReply() {
        replyJob?.cancel()
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun reply(chatId: String, versionOf: String?) {
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            val chat = chatRepository.chats.first().firstOrNull { it.id == chatId } ?: return@launch
            val character = characterRepository.characters.first().firstOrNull { it.id == chat.characterId } ?: return@launch
            val profile = profileRepository.profile.first()
            val history = if (versionOf == null) chat.messages else chat.messages.takeWhile { it.id != versionOf }
            val recent = history.takeLast(maxHistoryMessages)
            val outgoing = listOf(OutgoingMessage("system", systemPrompt(character, profile))) + recent.mapIndexed { index, message ->
                when {
                    message.speaker != Speaker.User -> OutgoingMessage("assistant", message.text)
                    index == recent.lastIndex -> OutgoingMessage("user", withLengthReminder(message.text, character.replyLength))
                    else -> OutgoingMessage("user", message.text)
                }
            }
            val settings = ReplySettings(temperatureFor(character), character.replyLength.maxTokens)
            notice.value = null
            var thinking = ""
            var raw = ""
            var text = ""
            var cutOff = false
            streaming.value = StreamingReply(chatId, versionOf, thinking, text)
            try {
                hostClient.streamReply(pairingRepository.current(), outgoing, settings).collect { piece ->
                    when (piece) {
                        is ReplyPiece.Thinking -> thinking += piece.text
                        is ReplyPiece.Answer -> raw += piece.text
                        ReplyPiece.CutOff -> cutOff = true
                    }
                    val parts = splitReply(raw)
                    text = parts.answer
                    streaming.value = StreamingReply(chatId, versionOf, thinking + parts.thinking, text)
                }
                if (cutOff && text.isBlank()) notice.value = ChatNotice(chatId, R.string.chat_error_cut_off)
            } catch (e: ChatException) {
                notice.value = ChatNotice(chatId, e.failure.message())
            } catch (e: IOException) {
                notice.value = ChatNotice(chatId, ChatFailure.Unreachable.message())
            } finally {
                withContext(NonCancellable) {
                    val finished = if (cutOff) toLastSentence(text) else text
                    if (finished.isNotBlank()) saveReply(chatId, versionOf, finished.trim())
                    streaming.value = null
                }
            }
        }
    }

    private suspend fun saveReply(chatId: String, versionOf: String?, text: String) {
        if (versionOf == null) {
            editChat(chatId) { chat -> chat.copy(messages = chat.messages + ChatMessage(newId(), Speaker.Character, listOf(text))) }
            return
        }
        editMessages(chatId) { message ->
            if (message.id == versionOf) message.copy(versions = message.versions + text, shownVersion = message.versions.size) else message
        }
    }

    private suspend fun editMessages(chatId: String, transform: (ChatMessage) -> ChatMessage) {
        editChat(chatId) { chat -> chat.copy(messages = chat.messages.map(transform)) }
    }

    private suspend fun editChat(chatId: String, transform: (Chat) -> Chat) {
        chatRepository.update { chats ->
            chats.map { chat -> if (chat.id == chatId) transform(chat).copy(updatedAtMillis = System.currentTimeMillis()) else chat }
        }
    }

    companion object {
        val factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as BurrowApplication
                ChatsViewModel(
                    application.chatRepository,
                    application.characterRepository,
                    application.profileRepository,
                    application.pairingRepository,
                    application.hostClient,
                )
            }
        }
    }
}
