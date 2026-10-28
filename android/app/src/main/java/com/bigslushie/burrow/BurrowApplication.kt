package com.bigslushie.burrow

import android.app.Application
import android.os.Build
import android.provider.Settings

class BurrowApplication : Application() {
    private val aead by lazy { createAead(this) }
    val pairingRepository by lazy { PairingRepository(this) { aead } }
    val chatRepository by lazy { ChatRepository(this) { aead } }
    val characterRepository by lazy { CharacterRepository(this) { aead } }
    val profileRepository by lazy { ProfileRepository(this) { aead } }
    val pictureStore by lazy { PictureStore(this) { aead } }
    val hostClient by lazy { HostClient() }

    val deviceName: String
        get() = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
}
