package com.bigslushie.burrow

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

// Bound to the encrypted pairing on disk: it is both the file name and the associated data, so changing it fails decryption.
private const val pairingFileName = "pairing.pb"
// Bound to the encrypted pairing on disk: the layout readFrom expects, bump it only together with a migration.
private const val pairingFormatVersion = 2
private const val maxCertificateBytes = 16 * 1024

class PairingRecord(val hostAddress: String, val certificate: ByteArray, val bearerToken: String) {
    val isPaired: Boolean get() = bearerToken.isNotEmpty()

    companion object {
        val unpaired = PairingRecord("", ByteArray(0), "")
    }
}

private object PairingRecordSerializer : Serializer<PairingRecord> {
    override val defaultValue = PairingRecord.unpaired

    override suspend fun readFrom(input: InputStream): PairingRecord {
        val stream = DataInputStream(input)
        try {
            if (stream.readByte().toInt() != pairingFormatVersion) throw CorruptionException("Unknown pairing format")
            val hostAddress = stream.readUTF()
            val certificateSize = stream.readInt()
            if (certificateSize !in 0..maxCertificateBytes) throw CorruptionException("Pairing certificate size out of range")
            val certificate = ByteArray(certificateSize)
            stream.readFully(certificate)
            return PairingRecord(hostAddress, certificate, stream.readUTF())
        } catch (e: EOFException) {
            throw CorruptionException("Pairing file is truncated", e)
        }
    }

    override suspend fun writeTo(t: PairingRecord, output: OutputStream) {
        val stream = DataOutputStream(output)
        stream.writeByte(pairingFormatVersion)
        stream.writeUTF(t.hostAddress)
        stream.writeInt(t.certificate.size)
        stream.write(t.certificate)
        stream.writeUTF(t.bearerToken)
        stream.flush()
    }
}

class PairingRepository(private val context: Context, private val aead: () -> Aead) {
    private val store: DataStore<PairingRecord> by lazy {
        encryptedStore(context, aead(), pairingFileName, PairingRecordSerializer)
    }

    suspend fun current(): PairingRecord = withContext(Dispatchers.IO) { store.data.first() }

    suspend fun save(record: PairingRecord) {
        withContext(Dispatchers.IO) { store.updateData { record } }
    }

    suspend fun clear() = save(PairingRecord.unpaired)
}
