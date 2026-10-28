package com.bigslushie.burrow

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import java.io.InputStream
import java.io.OutputStream
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

// Bound to the encrypted characters on disk: it is both the file name and the associated data, so changing it fails decryption.
private const val charactersFileName = "characters.pb"
// Bound to the encrypted characters on disk: the JSON layout readFrom expects, bump it only together with a migration.
private const val charactersFormatVersion = 1

enum class ReplyLength(val maxTokens: Int, val instruction: String, val reminder: String) {
    Short(240, "Keep every reply under 60 words: one short action and a line or two of speech, finished cleanly.", "(Reply in under 60 words.)"),
    Medium(520, "Keep every reply under 180 words, finished cleanly.", "(Reply in under 180 words, shorter if this message is short.)"),
    Long(1100, "Replies can run up to 400 words when the moment calls for detail, finished cleanly.", "(Reply in up to 400 words, shorter if this message is short.)"),
}

data class Character(
    val id: String,
    val name: String,
    val greeting: String,
    val personality: String,
    val createdAtMillis: Long,
    val scenario: String = "",
    val exampleDialogue: String = "",
    val replyLength: ReplyLength = ReplyLength.Medium,
    val creativity: Float = defaultCreativity,
    val systemPrompt: String = "",
)

const val defaultCreativity = 0.8f

private object CharacterListSerializer : Serializer<List<Character>> {
    override val defaultValue: List<Character> = emptyList()

    override suspend fun readFrom(input: InputStream): List<Character> {
        try {
            val document = JSONObject(input.readBytes().decodeToString())
            if (document.getInt("version") != charactersFormatVersion) throw CorruptionException("Unknown characters format")
            val entries = document.getJSONArray("characters")
            return (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                Character(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    greeting = entry.getString("greeting"),
                    personality = entry.getString("personality"),
                    createdAtMillis = entry.getLong("created"),
                    scenario = entry.optString("scenario"),
                    exampleDialogue = entry.optString("examples"),
                    replyLength = ReplyLength.entries.firstOrNull { it.name == entry.optString("length") } ?: ReplyLength.Medium,
                    creativity = entry.optDouble("creativity", defaultCreativity.toDouble()).toFloat(),
                    systemPrompt = entry.optString("system"),
                )
            }
        } catch (e: JSONException) {
            throw CorruptionException("Characters file is not valid", e)
        }
    }

    override suspend fun writeTo(t: List<Character>, output: OutputStream) {
        val entries = JSONArray()
        t.forEach { character ->
            entries.put(
                JSONObject()
                    .put("id", character.id)
                    .put("name", character.name)
                    .put("greeting", character.greeting)
                    .put("personality", character.personality)
                    .put("created", character.createdAtMillis)
                    .put("scenario", character.scenario)
                    .put("examples", character.exampleDialogue)
                    .put("length", character.replyLength.name)
                    .put("creativity", character.creativity.toDouble())
                    .put("system", character.systemPrompt),
            )
        }
        output.write(JSONObject().put("version", charactersFormatVersion).put("characters", entries).toString().encodeToByteArray())
    }
}

class CharacterRepository(private val context: Context, private val aead: () -> Aead) {
    private val store: DataStore<List<Character>> by lazy {
        encryptedStore(context, aead(), charactersFileName, CharacterListSerializer)
    }

    val characters: Flow<List<Character>> = flow { emitAll(store.data) }
        .map { characters -> characters.sortedByDescending { it.createdAtMillis } }
        .flowOn(Dispatchers.IO)

    suspend fun update(transform: (List<Character>) -> List<Character>) {
        withContext(Dispatchers.IO) { store.updateData(transform) }
    }
}
