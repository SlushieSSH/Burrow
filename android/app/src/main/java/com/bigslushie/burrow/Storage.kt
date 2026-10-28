package com.bigslushie.burrow

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class StoredData(@StringRes val label: Int, @StringRes val resetQuestion: Int, @StringRes val resetDetail: Int) {
    Chats(R.string.stored_chats, R.string.reset_chats_question, R.string.reset_gone_for_good),
    Characters(R.string.stored_characters, R.string.reset_characters_question, R.string.reset_gone_for_good),
    Profile(R.string.stored_profile, R.string.reset_profile_question, R.string.reset_gone_for_good),
    Pairing(R.string.stored_pairing, R.string.reset_pairing_question, R.string.reset_pairing_detail),
}

// AeadSerializer returns the default for an empty file, so without this a file wiped to zero bytes would pass as a fresh install.
private class RejectEmptyFile<T>(private val inner: Serializer<T>) : Serializer<T> {
    override val defaultValue = inner.defaultValue

    override suspend fun readFrom(input: InputStream): T {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) throw CorruptionException("Stored file is empty")
        return inner.readFrom(bytes.inputStream())
    }

    override suspend fun writeTo(t: T, output: OutputStream) = inner.writeTo(t, output)
}

class StorageHealth {
    private val unreadable = MutableStateFlow<Set<StoredData>>(emptySet())
    val problems: StateFlow<Set<StoredData>> = unreadable.asStateFlow()

    fun report(data: StoredData) = unreadable.update { it + data }

    fun recovered(data: StoredData) {
        if (data in unreadable.value) unreadable.update { it - data }
    }

    suspend fun awaitRecovery(data: StoredData) {
        unreadable.first { data !in it }
    }
}

class EncryptedStore<T>(
    private val context: Context,
    aead: () -> Aead,
    private val fileName: String,
    serializer: Serializer<T>,
    private val kind: StoredData,
    private val health: StorageHealth,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val store: DataStore<T> by lazy {
        DataStoreFactory.create(
            serializer = RejectEmptyFile(AeadSerializer(aead = aead(), wrappedSerializer = serializer, associatedData = fileName.encodeToByteArray())),
            scope = scope,
            produceFile = { context.dataStoreFile(fileName) },
        )
    }

    val data: Flow<T> = flow { emitAll(store.data) }
        .onEach { health.recovered(kind) }
        .retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            health.report(kind)
            health.awaitRecovery(kind)
            true
        }
        .flowOn(Dispatchers.IO)

    suspend fun update(transform: (T) -> T): Boolean = withContext(Dispatchers.IO) {
        try {
            store.updateData(transform)
            health.recovered(kind)
            true
        } catch (e: CorruptionException) {
            health.report(kind)
            false
        } catch (e: IOException) {
            false
        }
    }

    suspend fun checkAgain() = withContext(Dispatchers.IO) {
        try {
            store.data.first()
            health.recovered(kind)
        } catch (e: IOException) {
            health.report(kind)
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        context.dataStoreFile(fileName).delete()
        checkAgain()
    }
}
