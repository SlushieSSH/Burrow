package com.bigslushie.burrow

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CharacterDraft(
    val name: String,
    val greeting: String,
    val personality: String,
    val scenario: String,
    val exampleDialogue: String,
    val replyLength: ReplyLength,
    val creativity: Float,
    val systemPrompt: String,
    val picture: Uri?,
)

private fun Character.withDraft(draft: CharacterDraft) = copy(
    name = draft.name.trim(),
    greeting = draft.greeting.trim(),
    personality = draft.personality.trim(),
    scenario = draft.scenario.trim(),
    exampleDialogue = draft.exampleDialogue.trim(),
    replyLength = draft.replyLength,
    creativity = draft.creativity.coerceIn(0f, 1f),
    systemPrompt = draft.systemPrompt.trim(),
)

class CharactersViewModel(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val pictureStore: PictureStore,
) : ViewModel() {
    val characters: StateFlow<List<Character>?> = characterRepository.characters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pictures = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val pictures: StateFlow<Map<String, ImageBitmap>> = _pictures.asStateFlow()

    init {
        viewModelScope.launch {
            characterRepository.characters.collect { characters -> loadMissingPictures(characters) }
        }
    }

    suspend fun create(draft: CharacterDraft): Character {
        val character = Character(
            id = UUID.randomUUID().toString(),
            name = "",
            greeting = "",
            personality = "",
            createdAtMillis = System.currentTimeMillis(),
        ).withDraft(draft)
        draft.picture?.let { picture -> storePicture(character.id, picture) }
        characterRepository.update { characters -> characters + character }
        return character
    }

    suspend fun edit(characterId: String, draft: CharacterDraft) {
        draft.picture?.let { picture -> storePicture(characterId, picture) }
        characterRepository.update { characters ->
            characters.map { character -> if (character.id == characterId) character.withDraft(draft) else character }
        }
    }

    fun delete(character: Character) {
        viewModelScope.launch {
            characterRepository.update { characters -> characters.filterNot { it.id == character.id } }
            chatRepository.update { chats -> chats.filterNot { it.characterId == character.id } }
            pictureStore.delete(character.id)
            _pictures.update { pictures -> pictures - character.id }
        }
    }

    private suspend fun storePicture(characterId: String, picture: Uri) {
        if (!pictureStore.save(characterId, picture)) return
        val loaded = pictureStore.load(characterId) ?: return
        _pictures.update { pictures -> pictures + (characterId to loaded) }
    }

    private suspend fun loadMissingPictures(characters: List<Character>) {
        characters.filter { it.id !in _pictures.value }.forEach { character ->
            val loaded = pictureStore.load(character.id) ?: return@forEach
            _pictures.update { pictures -> pictures + (character.id to loaded) }
        }
    }

    companion object {
        val factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as BurrowApplication
                CharactersViewModel(application.characterRepository, application.chatRepository, application.pictureStore)
            }
        }
    }
}
