package com.bigslushie.burrow

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException

// Bound to every encrypted file on disk: renaming it orphans the Tink keyset and all stored data becomes unreadable.
private const val keysetName = "pairing_keyset"
// Bound to every encrypted file on disk: the keyset lives in this preferences file, renaming it loses the key.
private const val keysetPreferencesFile = "pairing_keyset_prefs"
// Bound to every encrypted file on disk: the Keystore key that wraps the keyset, a new alias cannot decrypt the old keyset.
private const val masterKeyUri = "android-keystore://pairing_master_key"
private const val keystoreUriPrefix = "android-keystore://"

class KeysetLocation(val name: String, val preferencesFile: String, val masterKeyUri: String) {
    companion object {
        val app = KeysetLocation(keysetName, keysetPreferencesFile, masterKeyUri)
    }
}

sealed interface KeyState {
    class Ready(val aead: Aead) : KeyState
    data object KeysetUnreadable : KeyState
    data object KeystoreUnavailable : KeyState
}

fun openKeys(context: Context, location: KeysetLocation = KeysetLocation.app): KeyState {
    val keysetManager = try {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, location.name, location.preferencesFile)
            .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
            .withMasterKeyUri(location.masterKeyUri)
            .build()
    } catch (e: GeneralSecurityException) {
        return KeyState.KeysetUnreadable
    } catch (e: IOException) {
        return KeyState.KeysetUnreadable
    } catch (e: ProviderException) {
        return KeyState.KeysetUnreadable
    }
    if (!keysetManager.isUsingKeystore) {
        context.getSharedPreferences(location.preferencesFile, Context.MODE_PRIVATE).edit().remove(location.name).commit()
        return KeyState.KeystoreUnavailable
    }
    return KeyState.Ready(keysetManager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
}

fun eraseKeys(context: Context, location: KeysetLocation = KeysetLocation.app) {
    context.deleteSharedPreferences(location.preferencesFile)
    try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(location.masterKeyUri.removePrefix(keystoreUriPrefix))
    } catch (e: GeneralSecurityException) {
        return
    } catch (e: IOException) {
        return
    }
}
