package com.bigslushie.burrow

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.datastore.dataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.UnknownServiceException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.minutes

private const val canary = "CANARY-7731-ZEBRA"
private const val hostPort = 47123

@RunWith(AndroidJUnit4::class)
class SecurityTest {
    private val hostAddress = requireNotNull(InstrumentationRegistry.getArguments().getString("hostAddress")) { "Pass -Pandroid.testInstrumentationRunnerArguments.hostAddress=<PC Tailscale address>" }
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BurrowApplication
    private val aead: Aead by lazy { createAead(application) }

    @Test
    fun storedDataNeverContainsPlaintext() = runTest(timeout = 2.minutes) {
        val character = Character("$canary-id", "$canary name", "$canary greeting", "$canary personality", System.currentTimeMillis())
        val message = ChatMessage("$canary-message", Speaker.User, listOf("$canary message"))
        val chat = Chat("$canary-chat", character.id, listOf(message), System.currentTimeMillis())
        val pairing = PairingRecord(hostAddress, "$canary certificate".encodeToByteArray(), "$canary-token")

        application.characterRepository.update { listOf(character) }
        application.chatRepository.update { listOf(chat) }
        application.profileRepository.save(Profile("$canary you", "$canary about"))
        application.pairingRepository.save(pairing)
        assertTrue(application.pictureStore.save(character.id, solidPicture()))

        assertEquals(character, application.characterRepository.characters.first().single())
        assertEquals(chat, application.chatRepository.chats.first().single())
        assertEquals("$canary you", application.profileRepository.profile.first().name)
        assertEquals("$canary-token", application.pairingRepository.current().bearerToken)
        assertNotNull(application.pictureStore.load(character.id))

        val stored = storedFiles()
        assertTrue(stored.any { it.name == "chats.pb" } && stored.any { it.name == "${character.id}.bin" })
        stored.forEach { file ->
            val bytes = file.readBytes()
            assertFalse("${file.name} holds plaintext", bytes.containsSequence(canary.encodeToByteArray()))
            assertFalse("${file.name} holds a raw JPEG", bytes.containsSequence(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        }
    }

    @Test
    fun tamperedOrSwappedCiphertextIsRejected() = runTest(timeout = 2.minutes) {
        val ciphertext = aead.encrypt("$canary chat".encodeToByteArray(), "chats.pb".encodeToByteArray())
        assertArrayEquals("$canary chat".encodeToByteArray(), aead.decrypt(ciphertext, "chats.pb".encodeToByteArray()))

        val flipped = ciphertext.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { aead.decrypt(flipped, "chats.pb".encodeToByteArray()) }
        assertThrows(GeneralSecurityException::class.java) { aead.decrypt(ciphertext, "characters.pb".encodeToByteArray()) }

        assertTrue(application.pictureStore.save("tamper", solidPicture()))
        val pictureFile = File(application.filesDir, "pictures/tamper.bin")
        pictureFile.writeBytes(pictureFile.readBytes().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() })
        assertNull(application.pictureStore.load("tamper"))
        application.pictureStore.delete("tamper")
    }

    @Test
    fun keysetIsWrappedByHardwareKey() {
        aead
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val masterKey = keyStore.getKey("pairing_master_key", null) as SecretKey
        val keyInfo = SecretKeyFactory.getInstance(masterKey.algorithm, "AndroidKeyStore").getKeySpec(masterKey, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX)
        }
        val keysetPrefs = File(application.dataDir, "shared_prefs/pairing_keyset_prefs.xml").readText()
        assertFalse(keysetPrefs.contains("type.googleapis.com/google.crypto.tink.AesGcmKey"))
    }

    @Test
    fun impostorCertificateIsRefused() = runTest(timeout = 2.minutes) {
        val impostor = HeldCertificate.Builder().addSubjectAlternativeName(hostAddress).build()
        val status = HostClient().status(PairingRecord(hostAddress, impostor.certificate.encoded, "$canary-token"))
        assertEquals(HostStatus.Offline, status)
    }

    @Test
    fun pinnedHostRefusesUnknownToken() = runTest(timeout = 2.minutes) {
        val status = HostClient().status(PairingRecord(hostAddress, presentedHostCertificate().encoded, "$canary-token"))
        assertEquals(HostStatus.Unpaired, status)
    }

    @Test
    fun cleartextIsBlocked() {
        val request = Request.Builder().url("http://$hostAddress:$hostPort/status").build()
        assertThrows(UnknownServiceException::class.java) { OkHttpClient().newCall(request).execute().close() }
    }

    private fun storedFiles(): List<File> =
        listOf(application.filesDir, File(application.dataDir, "shared_prefs"), application.dataStoreFile("x").parentFile!!)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .distinct()

    private fun solidPicture(): Uri {
        val file = File(application.cacheDir, "picture.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        return Uri.fromFile(file)
    }

    private fun presentedHostCertificate(): X509Certificate {
        var presented: X509Certificate? = null
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                presented = chain.first()
                throw CertificateException("recorded")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(hostAddress, hostPort), 10_000)
            try {
                socket.startHandshake()
            } catch (expected: SSLException) {
                assertNotNull(presented)
            }
        }
        return checkNotNull(presented)
    }
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
