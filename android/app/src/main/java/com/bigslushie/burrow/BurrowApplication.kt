package com.bigslushie.burrow

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BurrowApplication : Application() {
    private val keys = MutableStateFlow<KeyState?>(null)
    private val keysLock = Mutex()
    val keyState: StateFlow<KeyState?> = keys.asStateFlow()
    val storageHealth = StorageHealth()

    val pairingRepository by lazy { PairingRepository(this, ::readyAead, storageHealth) }
    val chatRepository by lazy { ChatRepository(this, ::readyAead, storageHealth) }
    val characterRepository by lazy { CharacterRepository(this, ::readyAead, storageHealth) }
    val profileRepository by lazy { ProfileRepository(this, ::readyAead, storageHealth) }
    val pictureStore by lazy { PictureStore(this, ::readyAead) }
    val hostClient by lazy { HostClient() }

    val deviceName: String
        get() = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    suspend fun openStorage() = keysLock.withLock {
        if (keys.value is KeyState.Ready) return@withLock
        val opened = withContext(Dispatchers.IO) { openKeys(this@BurrowApplication) }
        keys.value = opened
        if (opened is KeyState.Ready) StoredData.entries.forEach { kind -> storage(kind).checkAgain() }
    }

    suspend fun checkStorageAgain() {
        storageHealth.problems.value.forEach { kind -> storage(kind).checkAgain() }
    }

    suspend fun resetStorage(kind: StoredData) = storage(kind).reset()

    suspend fun eraseEverything() = keysLock.withLock {
        if (keys.value is KeyState.Ready) return@withLock
        withContext(Dispatchers.IO) {
            filesDir.listFiles()?.forEach { file -> file.deleteRecursively() }
            eraseKeys(this@BurrowApplication)
        }
        keys.value = null
    }

    private fun readyAead(): Aead {
        val state = keys.value
        check(state is KeyState.Ready) { "Storage was used before its keys were opened" }
        return state.aead
    }

    private fun storage(kind: StoredData): EncryptedStore<*> = when (kind) {
        StoredData.Chats -> chatRepository.storage
        StoredData.Characters -> characterRepository.storage
        StoredData.Profile -> profileRepository.storage
        StoredData.Pairing -> pairingRepository.storage
    }
}
