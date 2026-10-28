package com.bigslushie.burrow

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

// Bound to the encrypted chats on disk: it is both the file name and the associated data, so changing it fails decryption.
private const val chatsFileName = "chats.pb"
// Bound to the encrypted chats on disk: the JSON layout readFrom expects, bump it only together with a migration.
private const val chatsFormatVersion = 1

enum class Speaker { User, Character }

data class ChatMessage(
    val id: String,
    val speaker: Speaker,
    val versions: List<String>,
    val shownVersion: Int = versions.lastIndex,
) {
    val text: String get() = versions[shownVersion]
}

data class Chat(
    val id: String,
    val characterId: String,
    val messages: List<ChatMessage>,
    val updatedAtMillis: Long,
)

data class ChatArchive(val chats: List<Chat>)

private object ChatArchiveSerializer : Serializer<ChatArchive> {
    override val defaultValue = ChatArchive(emptyList())

    override suspend fun readFrom(input: InputStream): ChatArchive {
        try {
            val document = JSONObject(input.readBytes().decodeToString())
            if (document.getInt("version") != chatsFormatVersion) throw CorruptionException("Unknown chats format")
            val chats = document.getJSONArray("chats")
            return ChatArchive((0 until chats.length()).map { index -> readChat(chats.getJSONObject(index)) })
        } catch (e: JSONException) {
            throw CorruptionException("Chats file is not valid", e)
        }
    }

    override suspend fun writeTo(t: ChatArchive, output: OutputStream) {
        val chats = JSONArray()
        t.chats.forEach { chat -> chats.put(writeChat(chat)) }
        val document = JSONObject().put("version", chatsFormatVersion).put("chats", chats)
        output.write(document.toString().encodeToByteArray())
    }

    private fun readChat(source: JSONObject): Chat {
        val messages = source.getJSONArray("messages")
        return Chat(
            id = source.getString("id"),
            characterId = source.getString("character"),
            messages = (0 until messages.length()).map { index -> readMessage(messages.getJSONObject(index)) },
            updatedAtMillis = source.getLong("updated"),
        )
    }

    private fun readMessage(source: JSONObject): ChatMessage {
        val versions = source.getJSONArray("versions")
        val texts = (0 until versions.length()).map { index -> versions.getString(index) }
        if (texts.isEmpty()) throw CorruptionException("Message has no text")
        return ChatMessage(
            id = source.getString("id"),
            speaker = Speaker.valueOf(source.getString("speaker")),
            versions = texts,
            shownVersion = source.getInt("shown").coerceIn(texts.indices),
        )
    }

    private fun writeChat(chat: Chat): JSONObject {
        val messages = JSONArray()
        chat.messages.forEach { message ->
            messages.put(
                JSONObject()
                    .put("id", message.id)
                    .put("speaker", message.speaker.name)
                    .put("versions", JSONArray(message.versions))
                    .put("shown", message.shownVersion),
            )
        }
        return JSONObject()
            .put("id", chat.id)
            .put("character", chat.characterId)
            .put("messages", messages)
            .put("updated", chat.updatedAtMillis)
    }
}

class ChatRepository(private val context: Context, private val aead: () -> Aead) {
    private val store: DataStore<ChatArchive> by lazy {
        encryptedStore(context, aead(), chatsFileName, ChatArchiveSerializer)
    }

    val chats: Flow<List<Chat>> = flow { emitAll(store.data) }
        .map { archive -> archive.chats }
        .flowOn(Dispatchers.IO)

    suspend fun update(transform: (List<Chat>) -> List<Chat>) {
        withContext(Dispatchers.IO) { store.updateData { archive -> ChatArchive(transform(archive.chats)) } }
    }
}
