package com.bigslushie.burrow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.tls.HandshakeCertificates
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val hostPort = 47123
// The PC host computes the same proofs from these labels; changing either one breaks pairing with it.
private const val clientProofLabel = "burrow-pair-client"
private const val serverProofLabel = "burrow-pair-server"
private val connectTimeout: Duration = Duration.ofSeconds(10)
private val readTimeout: Duration = Duration.ofSeconds(15)
private val streamReadTimeout: Duration = Duration.ofMinutes(5)
private val jsonType = "application/json".toMediaType()

private fun baseUrl(hostAddress: String) = "https://$hostAddress:$hostPort"

fun isHostAddress(text: String): Boolean {
    val parts = text.split('.')
    return parts.size == 4 && parts.all { part -> part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() <= 255 }
}

sealed interface PairingOutcome {
    class Paired(val record: PairingRecord) : PairingOutcome
    data object Rejected : PairingOutcome
    data object Unreachable : PairingOutcome
}

sealed interface HostStatus {
    data class Ready(val modelName: String, val contextLength: Int) : HostStatus
    data class Loading(val modelName: String) : HostStatus
    data object Unloading : HostStatus
    data object NoModel : HostStatus
    data object Unpaired : HostStatus
    data object Offline : HostStatus
}

enum class ChatFailure { Unpaired, NoModel, LmStudio, Unreachable }

class ChatException(val failure: ChatFailure) : IOException(failure.name)

data class OutgoingMessage(val role: String, val content: String)

data class ReplySettings(val temperature: Float, val maxTokens: Int)

sealed interface ReplyPiece {
    data class Thinking(val text: String) : ReplyPiece
    data class Answer(val text: String) : ReplyPiece
    data object CutOff : ReplyPiece
}

data class ModelOption(val key: String, val name: String, val maxContextLength: Int)

sealed interface ModelCatalog {
    data class Available(val models: List<ModelOption>) : ModelCatalog
    data object LmStudioClosed : ModelCatalog
    data object PortTaken : ModelCatalog
    data object LmStudioOpenToNetwork : ModelCatalog
    data object Offline : ModelCatalog
}

enum class ModelCommand { Started, Busy, LmStudioClosed, Failed }

private class CertificateRecorder : X509TrustManager {
    var presented: X509Certificate? = null

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        presented = chain.first()
        // Rejects every certificate on purpose: it only reads the PC's certificate for the pairing proof to check, and accepting here would trust anyone.
        throw CertificateException("Certificate recorded for pairing")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, closing, _ -> closing.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}

class HostClient {
    suspend fun pair(hostAddress: String, code: String, deviceName: String): PairingOutcome = withContext(Dispatchers.IO) { requestPairing(hostAddress, code, deviceName) }

    suspend fun status(record: PairingRecord): HostStatus = withContext(Dispatchers.IO) { requestStatus(record) }

    suspend fun models(record: PairingRecord): ModelCatalog = withContext(Dispatchers.IO) { requestModels(record) }

    suspend fun load(record: PairingRecord, model: ModelOption, contextLength: Int): ModelCommand = withContext(Dispatchers.IO) {
        sendModelCommand(record, "load", JSONObject().put("model", model.key).put("context_length", contextLength))
    }

    suspend fun unload(record: PairingRecord): ModelCommand = withContext(Dispatchers.IO) { sendModelCommand(record, "unload", JSONObject()) }

    fun streamReply(record: PairingRecord, messages: List<OutgoingMessage>, settings: ReplySettings): Flow<ReplyPiece> = flow {
        val certificate = try {
            storedCertificate(record)
        } catch (e: CertificateException) {
            throw ChatException(ChatFailure.Unpaired)
        }
        val turns = JSONArray()
        messages.forEach { message -> turns.put(JSONObject().put("role", message.role).put("content", message.content)) }
        val request = Request.Builder()
            .url("${baseUrl(record.hostAddress)}/chat")
            .header("Authorization", "Bearer ${record.bearerToken}")
            .post(
                JSONObject()
                    .put("messages", turns)
                    .put("temperature", settings.temperature.toDouble())
                    .put("max_tokens", settings.maxTokens)
                    .toString()
                    .toRequestBody(jsonType),
            )
            .build()
        val call = pinnedClient(certificate, streamReadTimeout).newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.await().use { response ->
                when {
                    response.code == 401 -> throw ChatException(ChatFailure.Unpaired)
                    response.code == 409 -> throw ChatException(ChatFailure.NoModel)
                    !response.isSuccessful -> throw ChatException(ChatFailure.LmStudio)
                }
                val source = response.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val choice = try {
                        JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                    } catch (e: JSONException) {
                        throw ChatException(ChatFailure.LmStudio)
                    }
                    val delta = choice.optJSONObject("delta")
                    val thinking = delta?.optString("reasoning_content").orEmpty().ifEmpty { delta?.optString("reasoning").orEmpty() }
                    val answer = delta?.optString("content").orEmpty()
                    if (thinking.isNotEmpty()) emit(ReplyPiece.Thinking(thinking))
                    if (answer.isNotEmpty()) emit(ReplyPiece.Answer(answer))
                    if (choice.optString("finish_reason") == "length") emit(ReplyPiece.CutOff)
                }
            }
        } finally {
            cancellation.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun storedCertificate(record: PairingRecord): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(record.certificate.inputStream()) as X509Certificate

    private suspend fun requestPairing(hostAddress: String, code: String, deviceName: String): PairingOutcome {
        if (!isHostAddress(hostAddress)) return PairingOutcome.Unreachable
        val certificate = try {
            recordCertificate(hostAddress)
        } catch (e: IOException) {
            return PairingOutcome.Unreachable
        }
        val fingerprint = sha256(certificate.encoded)
        val codeKey = code.encodeToByteArray()
        val proof = hmacSha256(codeKey, clientProofLabel.encodeToByteArray() + fingerprint)
        val body = JSONObject()
            .put("proof", proof.toHexString())
            .put("device_name", deviceName)
            .toString()
        val request = Request.Builder().url("${baseUrl(hostAddress)}/pair").post(body.toRequestBody(jsonType)).build()

        return try {
            pinnedClient(certificate).newCall(request).await().use { response ->
                if (response.code == 400 || response.code == 403) return PairingOutcome.Rejected
                if (!response.isSuccessful) return PairingOutcome.Unreachable
                val reply = JSONObject(response.body.string())
                val bearerToken = reply.getString("token")
                val serverProof = reply.getString("server_proof").hexToByteArray()
                val expectedProof = hmacSha256(codeKey, serverProofLabel.encodeToByteArray() + fingerprint + bearerToken.encodeToByteArray())
                if (!MessageDigest.isEqual(expectedProof, serverProof)) return PairingOutcome.Rejected
                PairingOutcome.Paired(PairingRecord(hostAddress, certificate.encoded, bearerToken))
            }
        } catch (e: IOException) {
            PairingOutcome.Unreachable
        } catch (e: JSONException) {
            PairingOutcome.Rejected
        } catch (e: IllegalArgumentException) {
            PairingOutcome.Rejected
        }
    }

    private suspend fun requestStatus(record: PairingRecord): HostStatus {
        val certificate = try {
            storedCertificate(record)
        } catch (e: CertificateException) {
            return HostStatus.Offline
        }
        val request = Request.Builder()
            .url("${baseUrl(record.hostAddress)}/status")
            .header("Authorization", "Bearer ${record.bearerToken}")
            .build()
        return try {
            pinnedClient(certificate).newCall(request).await().use { response ->
                if (response.code == 401) return HostStatus.Unpaired
                if (!response.isSuccessful) return HostStatus.Offline
                val reply = JSONObject(response.body.string())
                when (reply.getString("state")) {
                    "ready" -> HostStatus.Ready(reply.getString("model"), reply.optInt("context"))
                    "loading" -> HostStatus.Loading(reply.getString("model"))
                    "unloading" -> HostStatus.Unloading
                    else -> HostStatus.NoModel
                }
            }
        } catch (e: IOException) {
            HostStatus.Offline
        } catch (e: JSONException) {
            HostStatus.Offline
        }
    }

    private suspend fun requestModels(record: PairingRecord): ModelCatalog {
        val certificate = try {
            storedCertificate(record)
        } catch (e: CertificateException) {
            return ModelCatalog.Offline
        }
        val request = Request.Builder()
            .url("${baseUrl(record.hostAddress)}/models")
            .header("Authorization", "Bearer ${record.bearerToken}")
            .build()
        return try {
            pinnedClient(certificate).newCall(request).await().use { response ->
                if (!response.isSuccessful) return ModelCatalog.Offline
                val reply = JSONObject(response.body.string())
                when (reply.getString("server")) {
                    "not_running" -> return ModelCatalog.LmStudioClosed
                    "not_lm_studio" -> return ModelCatalog.PortTaken
                    "open_to_network" -> return ModelCatalog.LmStudioOpenToNetwork
                }
                val entries = reply.getJSONArray("models")
                ModelCatalog.Available(
                    (0 until entries.length()).map { index ->
                        val entry = entries.getJSONObject(index)
                        ModelOption(entry.getString("key"), entry.getString("name"), entry.getInt("max_context"))
                    },
                )
            }
        } catch (e: IOException) {
            ModelCatalog.Offline
        } catch (e: JSONException) {
            ModelCatalog.Offline
        }
    }

    private suspend fun sendModelCommand(record: PairingRecord, path: String, body: JSONObject): ModelCommand {
        val certificate = try {
            storedCertificate(record)
        } catch (e: CertificateException) {
            return ModelCommand.Failed
        }
        val request = Request.Builder()
            .url("${baseUrl(record.hostAddress)}/$path")
            .header("Authorization", "Bearer ${record.bearerToken}")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return try {
            pinnedClient(certificate).newCall(request).await().use { response ->
                when (response.code) {
                    200 -> ModelCommand.Started
                    409 -> ModelCommand.Busy
                    503 -> ModelCommand.LmStudioClosed
                    else -> ModelCommand.Failed
                }
            }
        } catch (e: IOException) {
            ModelCommand.Failed
        }
    }

    private fun recordCertificate(hostAddress: String): X509Certificate {
        val recorder = CertificateRecorder()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(recorder), null)
        (sslContext.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = readTimeout.toMillis().toInt()
            socket.connect(InetSocketAddress(hostAddress, hostPort), connectTimeout.toMillis().toInt())
            try {
                socket.startHandshake()
            } catch (expected: SSLException) {
                if (recorder.presented == null) throw expected
            }
        }
        return recorder.presented ?: throw SSLPeerUnverifiedException("The PC sent no certificate")
    }

    private fun pinnedClient(certificate: X509Certificate, responseTimeout: Duration = readTimeout): OkHttpClient {
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        return OkHttpClient.Builder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(connectTimeout)
            .readTimeout(responseTimeout)
            .build()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}
