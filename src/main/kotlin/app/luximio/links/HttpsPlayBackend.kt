package app.luximio.links

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed-origin, normal-TLS private prototype transport. Bearers only enter Authorization headers. */
class HttpsPlayBackend(
    private val configuration: PlayConfiguration,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlayBackend {
    private val wire = PlayWire(configuration)

    override suspend fun resolve(token: String, expected: PlayReceipt?): PlayReceipt {
        val response = post("/api/private/android-continuations/resolve", token, wire.resolveRequest(expected))
        return wire.preparedResponse(response, expected, clock())
    }

    override suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement) {
        val response = post("/api/private/android-continuations/acknowledge", token,
            wire.acknowledgeRequest(acknowledgement))
        wire.acknowledgedResponse(response, acknowledgement)
    }

    private suspend fun post(path: String, token: String, body: ByteArray): ByteArray = withContext(ioDispatcher) {
        PlayEnvelope.validateToken(token)
        val connection = (URI(configuration.origin + path).toURL().openConnection() as HttpsURLConnection)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setFixedLengthStreamingMode(body.size)
            ensureActive()
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            ensureActive()
            if (status in 300..399) throw PlayFailure(PlayFailureReason.INVALID_DATA)
            if (status == HttpURLConnection.HTTP_CLIENT_TIMEOUT || status == 429 || status >= 500) {
                throw PlayFailure(PlayFailureReason.NETWORK)
            }
            if (connection.contentLengthLong > MAXIMUM_RESPONSE_BYTES) {
                throw PlayFailure(PlayFailureReason.INVALID_DATA)
            }
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (contentType != "application/json") {
                // A CDN/private gate can return HTML without the application seeing this request.
                // Only a verified JSON rejection can terminally invalidate the saved bearer.
                throw PlayFailure(if (status in 200..299) PlayFailureReason.INVALID_DATA else PlayFailureReason.NETWORK)
            }
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2_048)
                while (true) {
                    ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAXIMUM_RESPONSE_BYTES) {
                        throw PlayFailure(PlayFailureReason.INVALID_DATA)
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
            if (status !in 200..299) {
                val rejection = try { wire.rejectedResponse(bytes) }
                catch (_: PlayFailure) { throw PlayFailure(PlayFailureReason.NETWORK) }
                throw rejection
            }
            bytes
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlayFailure) {
            throw error
        } catch (_: java.io.IOException) {
            throw PlayFailure(PlayFailureReason.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAXIMUM_RESPONSE_BYTES = 16_384 }
}
