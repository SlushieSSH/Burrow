package com.bigslushie.burrow

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.Flow
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

// Bound to the encrypted profile on disk: it is both the file name and the associated data, so changing it fails decryption.
private const val profileFileName = "profile.pb"
// Bound to the encrypted profile on disk: the JSON layout readFrom expects, bump it only together with a migration.
private const val profileFormatVersion = 1

data class Profile(val name: String = "", val description: String = "")

private object ProfileSerializer : Serializer<Profile> {
    override val defaultValue = Profile()

    override suspend fun readFrom(input: InputStream): Profile {
        try {
            val document = JSONObject(input.readBytes().decodeToString())
            if (document.getInt("version") != profileFormatVersion) throw CorruptionException("Unknown profile format")
            return Profile(document.getString("name"), document.getString("description"))
        } catch (e: JSONException) {
            throw CorruptionException("Profile file is not valid", e)
        }
    }

    override suspend fun writeTo(t: Profile, output: OutputStream) {
        val document = JSONObject().put("version", profileFormatVersion).put("name", t.name).put("description", t.description)
        output.write(document.toString().encodeToByteArray())
    }
}

class ProfileRepository(context: Context, aead: () -> Aead, health: StorageHealth) {
    val storage = EncryptedStore(context, aead, profileFileName, ProfileSerializer, StoredData.Profile, health)

    val profile: Flow<Profile> = storage.data

    suspend fun save(profile: Profile) {
        storage.update { profile }
    }
}
