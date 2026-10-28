package com.bigslushie.burrow

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

private const val testPrefix = "recovery-test-"
private const val innerRejectMarker = "reject-inner-format"
private const val waitMillis = 10_000L

private object TextSerializer : Serializer<String> {
    override val defaultValue = ""

    override suspend fun readFrom(input: InputStream): String {
        val text = input.readBytes().decodeToString()
        if (text.startsWith(innerRejectMarker)) throw CorruptionException("Inner format rejected")
        return text
    }

    override suspend fun writeTo(t: String, output: OutputStream) = output.write(t.encodeToByteArray())
}

private object BuggySerializer : Serializer<String> {
    override val defaultValue = ""

    override suspend fun readFrom(input: InputStream): String = throw IllegalStateException("serializer bug")

    override suspend fun writeTo(t: String, output: OutputStream) = output.write(t.encodeToByteArray())
}

@RunWith(AndroidJUnit4::class)
class StorageRecoveryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val location = KeysetLocation("recovery_test_keyset", "recovery_test_keyset_prefs", "android-keystore://recovery_test_master_key")
    private val aead: Aead by lazy { (openKeys(context, location) as KeyState.Ready).aead }
    private val scopes = mutableListOf<CoroutineScope>()
    private val longText = "a reasonably long line of throwaway test text ".repeat(20)

    @After
    fun cleanUp() {
        runBlocking { scopes.forEach { scope -> scope.coroutineContext.job.cancelAndJoin() } }
        file("x").parentFile?.listFiles { candidate -> candidate.name.startsWith(testPrefix) }?.forEach(File::delete)
    }

    @Test
    fun garbageIsKeptReportedAndNeverOverwritten() = runBlocking {
        val name = "${testPrefix}garbage"
        val garbage = Random.nextBytes(512)
        place(name, garbage)
        val health = StorageHealth()
        val store = open(name, health)

        val reader = async(Dispatchers.IO) { store.data.first() }
        awaitProblem(health)

        assertFalse(store.update { "overwrite attempt" })
        assertArrayEquals(garbage, file(name).readBytes())
        assertTrue("a reader crashed instead of waiting", reader.isActive)
        reader.cancel()
    }

    @Test
    fun recoversWhileRunningWhenTheFileComesBack() = runBlocking {
        val name = "${testPrefix}recover"
        val good = writeValid(name, "hello")
        place(name, Random.nextBytes(good.size))
        val health = StorageHealth()
        val store = open(name, health)

        val reader = async(Dispatchers.IO) { store.data.first() }
        awaitProblem(health)
        place(name, good)
        store.checkAgain()

        assertEquals("hello", withTimeout(waitMillis) { reader.await() })
        assertTrue(health.problems.value.isEmpty())
    }

    @Test
    fun resetDeletesOnlyThatStoreAndStartsFresh() = runBlocking {
        val target = "${testPrefix}reset"
        val neighbour = "${testPrefix}neighbour"
        val neighbourBytes = writeValid(neighbour, "keep me")
        place(target, Random.nextBytes(300))
        val health = StorageHealth()
        val store = open(target, health)

        val reader = async(Dispatchers.IO) { store.data.first() }
        awaitProblem(health)
        store.reset()

        assertEquals("", withTimeout(waitMillis) { reader.await() })
        assertTrue(health.problems.value.isEmpty())
        assertTrue(store.update { "fresh" })
        assertEquals("fresh", store.data.first())
        assertArrayEquals(neighbourBytes, file(neighbour).readBytes())
    }

    @Test
    fun everyKindOfDamageIsCaughtAndLeftAlone() = runBlocking {
        val damages = mapOf<String, (ByteArray) -> ByteArray>(
            "truncated" to { bytes -> bytes.copyOf(bytes.size / 2) },
            "empty" to { ByteArray(0) },
            "flipped middle byte" to { bytes -> bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() } },
            "flipped first byte" to { bytes -> bytes.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() } },
            "extra bytes" to { bytes -> bytes + Random.nextBytes(64) },
        )
        for ((label, damage) in damages) {
            val name = "${testPrefix}damage-${label.replace(' ', '-')}"
            val damaged = damage(writeValid(name, longText))
            place(name, damaged)
            val health = StorageHealth()
            open(name, health).checkAgain()
            assertTrue("$label was not reported", StoredData.Chats in health.problems.value)
            assertArrayEquals("$label was modified", damaged, file(name).readBytes())
        }
    }

    @Test
    fun fileSwappedInFromAnotherStoreIsCaught() = runBlocking {
        val source = writeValid("${testPrefix}swap-source", longText)
        val name = "${testPrefix}swap-target"
        place(name, source)
        val health = StorageHealth()
        open(name, health).checkAgain()
        assertTrue(StoredData.Chats in health.problems.value)
        assertArrayEquals(source, file(name).readBytes())
    }

    @Test
    fun innerFormatErrorIsCaught() = runBlocking {
        val name = "${testPrefix}inner"
        val bytes = writeValid(name, "$innerRejectMarker and more")
        val health = StorageHealth()
        open(name, health).checkAgain()
        assertTrue(StoredData.Chats in health.problems.value)
        assertArrayEquals(bytes, file(name).readBytes())
    }

    @Test
    fun programmingBugsAreNotHiddenAsDamage() = runBlocking {
        val name = "${testPrefix}bug"
        writeValid(name, "fine")
        val health = StorageHealth()
        val store = open(name, health, BuggySerializer)
        try {
            store.data.first()
            fail("a serializer bug was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("serializer bug", e.message)
        }
        assertTrue(health.problems.value.isEmpty())
    }

    @Test
    fun hammeringTryAgainAndResetIsHarmless() = runBlocking {
        val name = "${testPrefix}hammer"
        val garbage = Random.nextBytes(256)
        place(name, garbage)
        val health = StorageHealth()
        val store = open(name, health)

        coroutineScope { repeat(200) { launch(Dispatchers.IO) { store.checkAgain() } } }
        assertTrue(StoredData.Chats in health.problems.value)
        assertArrayEquals(garbage, file(name).readBytes())

        coroutineScope { repeat(20) { launch(Dispatchers.IO) { store.reset() } } }
        assertTrue(health.problems.value.isEmpty())
        coroutineScope { repeat(50) { launch(Dispatchers.IO) { assertTrue(store.update { text -> text + "x" }) } } }
        assertEquals("x".repeat(50), store.data.first())
    }

    @Test
    fun healthyStoreStaysQuiet() = runBlocking {
        val name = "${testPrefix}healthy"
        writeValid(name, "all good")
        val health = StorageHealth()
        val store = open(name, health)
        assertEquals("all good", store.data.first())
        assertTrue(store.update { "still good" })
        store.checkAgain()
        assertEquals("still good", store.data.first())
        assertTrue(health.problems.value.isEmpty())
    }

    @Test
    fun missingFileIsAFreshStartNotDamage() = runBlocking {
        val name = "${testPrefix}missing"
        file(name).delete()
        val health = StorageHealth()
        val store = open(name, health)
        assertEquals("", store.data.first())
        store.checkAgain()
        assertTrue(health.problems.value.isEmpty())
        assertTrue(store.update { "first save" })
        assertEquals("first save", store.data.first())
    }

    @Test
    fun damagedKeysetIsReportedInsteadOfCrashing() {
        aead
        val preferences = context.getSharedPreferences(location.preferencesFile, Context.MODE_PRIVATE)
        for (garbage in listOf("not hex at all", "deadbeef", "00".repeat(64))) {
            preferences.edit().putString(location.name, garbage).commit()
            assertEquals("keyset '$garbage'", KeyState.KeysetUnreadable, openKeys(context, location))
        }
        eraseKeys(context, location)
        assertTrue(openKeys(context, location) is KeyState.Ready)
        eraseKeys(context, location)
    }

    private fun file(name: String): File = context.dataStoreFile(name)

    private fun place(name: String, bytes: ByteArray) {
        file(name).parentFile?.mkdirs()
        file(name).writeBytes(bytes)
    }

    private fun open(name: String, health: StorageHealth, serializer: Serializer<String> = TextSerializer): EncryptedStore<String> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return EncryptedStore(context, { aead }, name, serializer, StoredData.Chats, health, scope)
    }

    private suspend fun writeValid(name: String, text: String): ByteArray {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = EncryptedStore(context, { aead }, name, TextSerializer, StoredData.Chats, StorageHealth(), scope)
        assertTrue(store.update { text })
        scope.coroutineContext.job.cancelAndJoin()
        return file(name).readBytes()
    }

    private suspend fun awaitProblem(health: StorageHealth) {
        withTimeout(waitMillis) { health.problems.first { StoredData.Chats in it } }
    }
}
