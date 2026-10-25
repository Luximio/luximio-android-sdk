package app.luximio.links

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

data class CustomerPlayHttpRequest(val url: String, val bearer: String, val body: ByteArray) {
    override fun toString() = "CustomerPlayHttpRequest(body=<redacted>, bearer=<redacted>)"
}

data class CustomerPlayHttpResponse(val status: Int, val url: String, val contentType: String?, val body: ByteArray) {
    override fun toString() = "CustomerPlayHttpResponse(status=$status, body=<redacted>)"
}

fun interface CustomerPlayTransport {
    suspend fun post(request: CustomerPlayHttpRequest): CustomerPlayHttpResponse
}

/** Fixed-origin, normal-TLS customer recovery. No redirect or HTTP retry can replay a proof. */
class HttpsCustomerPlayTransport internal constructor(
    private val origin: String,
    private val calls: Call.Factory,
    private val deadlineMillis: Long = 10_000,
) : CustomerPlayTransport {
    constructor(origin: String) : this(origin, customerPlayHttpClient())

    init {
        val parsed = runCatching { URI(origin) }.getOrNull()
        requirePlay(deadlineMillis > 0 && parsed != null && parsed.scheme == "https" && !parsed.host.isNullOrEmpty() &&
            parsed.rawAuthority == parsed.host && parsed.host == parsed.host.lowercase() &&
            parsed.userInfo == null && parsed.port == -1 && parsed.rawPath.isNullOrEmpty() &&
            parsed.rawQuery == null && parsed.rawFragment == null && origin == "https://${parsed.host}",
            PlayFailureReason.CONFIGURATION)
    }

    override suspend fun post(request: CustomerPlayHttpRequest): CustomerPlayHttpResponse {
        requirePlay(request.url in PATHS.map { origin + it }, PlayFailureReason.WRONG_SCOPE)
        PlayEnvelope.validateToken(request.bearer)
        requirePlay(request.body.isNotEmpty() && request.body.size <= MAXIMUM_BODY_BYTES, PlayFailureReason.INVALID_DATA)
        val nativeRequest = Request.Builder()
            .url(request.url)
            .header("Authorization", "Bearer ${request.bearer}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .post(CustomerPlayRequestBody(request.body))
            .build()
        val call = try { calls.newCall(nativeRequest) }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.NETWORK) }
        return try {
            withTimeout(deadlineMillis) { awaitResponse(call) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw PlayFailure(PlayFailureReason.NETWORK)
        }
    }

    private suspend fun awaitResponse(call: Call): CustomerPlayHttpResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(PlayFailure(PlayFailureReason.NETWORK))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                val body = it.body
                                // An edge/proxy error body is not an application rejection.
                                // Keep its validation failure retryable without relaxing the limit.
                                val invalidBodyReason = if (it.code in 200..299) PlayFailureReason.INVALID_DATA else PlayFailureReason.NETWORK
                                requirePlay(body.contentLength() <= MAXIMUM_BODY_BYTES, invalidBodyReason)
                                val bytes = body.byteStream().use { stream ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(2_048)
                                    while (true) {
                                        if (!continuation.isActive) return
                                        val read = stream.read(buffer)
                                        if (read == -1) break
                                        requirePlay(output.size() + read <= MAXIMUM_BODY_BYTES, invalidBodyReason)
                                        output.write(buffer, 0, read)
                                    }
                                    output.toByteArray()
                                }
                                CustomerPlayHttpResponse(it.code, it.request.url.toString(), it.header("Content-Type"), bytes)
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            val failure = error as? PlayFailure ?: PlayFailure(PlayFailureReason.NETWORK)
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                })
            } catch (_: Exception) {
                call.cancel()
                if (continuation.isActive) continuation.resumeWithException(PlayFailure(PlayFailureReason.NETWORK))
            }
        }

    private companion object {
        const val MAXIMUM_BODY_BYTES = 64 * 1_024
        val PATHS = setOf("/api/v1/recovery/challenges", "/api/v1/recovery/resolve", "/api/v1/recovery/acknowledge")
    }
}

private class CustomerPlayRequestBody(bytes: ByteArray) : RequestBody() {
    private val bytes = bytes.copyOf()
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength() = bytes.size.toLong()
    // This also prevents HTTP follow-ups, such as a 503 Retry-After: 0, replaying proof.
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
}

internal fun customerPlayHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(10, TimeUnit.SECONDS)
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .writeTimeout(8, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .cache(null)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .build()
