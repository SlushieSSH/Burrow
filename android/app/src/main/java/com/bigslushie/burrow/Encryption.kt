package com.bigslushie.burrow

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager

// Bound to every encrypted file on disk: renaming it orphans the Tink keyset and all stored data becomes unreadable.
private const val keysetName = "pairing_keyset"
// Bound to every encrypted file on disk: the keyset lives in this preferences file, renaming it loses the key.
private const val keysetPreferencesFile = "pairing_keyset_prefs"
// Bound to every encrypted file on disk: the Keystore key that wraps the keyset, a new alias cannot decrypt the old keyset.
private const val masterKeyUri = "android-keystore://pairing_master_key"

fun createAead(context: Context): Aead {
    AeadConfig.register()
    val keysetManager = AndroidKeysetManager.Builder()
        .withSharedPref(context, keysetName, keysetPreferencesFile)
        .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
        .withMasterKeyUri(masterKeyUri)
        .build()
    check(keysetManager.isUsingKeystore) { "Android Keystore is unavailable, refusing to store the keyset unwrapped" }
    return keysetManager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

fun <T> encryptedStore(context: Context, aead: Aead, fileName: String, serializer: Serializer<T>): DataStore<T> =
    DataStoreFactory.create(
        serializer = AeadSerializer(
            aead = aead,
            wrappedSerializer = serializer,
            associatedData = fileName.encodeToByteArray(),
        ),
        corruptionHandler = ReplaceFileCorruptionHandler { serializer.defaultValue },
        produceFile = { context.dataStoreFile(fileName) },
    )
