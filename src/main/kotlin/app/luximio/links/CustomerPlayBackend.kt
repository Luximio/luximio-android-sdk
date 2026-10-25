package app.luximio.links

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Attested customer recovery; no private prototype, token-profile or fingerprint fallback. */
class CustomerPlayBackend(
    private val configuration: CustomerPlayConfiguration,
    private val transport: CustomerPlayTransport,
    private val proofProvider: PlayIntegrityTokenProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlayBackend {
    private val wire = CustomerPlayWire(configuration)

    override suspend fun resolve(token: String, expected: PlayReceipt?): PlayReceipt {
        currentCoroutineContext().ensureActive()
        // A corrupted/mismatched durable pin must never request fresh provider proof.
        expected?.let { wire.validateReceipt(it, clock()) }
        val response = nativeOperation("resolve", token, wire.resolvePayload(expected))
        return wire.preparedResponse(response, expected, clock())
    }

    override suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement) {
        currentCoroutineContext().ensureActive()
        val payload = wire.acknowledgementPayload(acknowledgement)
        val response = nativeOperation("acknowledge", token, payload)
        wire.acknowledgedResponse(response, acknowledgement)
    }

    private suspend fun nativeOperation(purpose: String, bearer: String, payload: ByteArray): ByteArray {
        PlayEnvelope.validateToken(bearer)
        val challengeBody = wire.envelope(wire.challengePayload(purpose, payload), JsonNull)
        val challengeBytes = send(CustomerPlayWire.ROOT + "/challenges", bearer, challengeBody, 201)
        val challenge = wire.challengeResponse(challengeBytes, purpose, payload, bearer, clock())
        val integrityToken = try {
            currentCoroutineContext().ensureActive()
            proofProvider.requestToken(challenge.requestHash).also { currentCoroutineContext().ensureActive() }
        } catch (error: CancellationException) { throw error }
        catch (error: PlayFailure) { throw error }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.NETWORK) }
        requirePlay(integrityToken.isNotEmpty() && integrityToken.length <= 16_384, PlayFailureReason.UNVERIFIED)
        // A suspended app may outlive its short proof challenge while the
        // saved receipt remains valid. Retry with new proof; never discard it
        // or transmit the expired challenge.
        requirePlay(clock() in 0 until challenge.expiresAt, PlayFailureReason.NETWORK)
        val proof = buildJsonObject {
            put("kind", "play_integrity"); put("challengeID", challenge.id); put("integrityToken", integrityToken)
        }
        return send(CustomerPlayWire.ROOT + "/" + purpose, bearer, wire.envelope(payload, proof), 200)
    }

    private suspend fun send(path: String, bearer: String, body: ByteArray, expectedStatus: Int): ByteArray {
        try {
            currentCoroutineContext().ensureActive()
            val request = CustomerPlayHttpRequest(configuration.origin + path, bearer, body)
            val response = transport.post(request)
            currentCoroutineContext().ensureActive()
            requirePlay(response.url == request.url, PlayFailureReason.INVALID_DATA)
            val validJSONBody = response.body.isNotEmpty() && response.body.size <= CustomerPlayWire.MAXIMUM_BYTES &&
                response.contentType?.substringBefore(';')?.trim()?.lowercase() == "application/json"
            if (response.status == expectedStatus) {
                requirePlay(validJSONBody, PlayFailureReason.INVALID_DATA)
                return response.body
            }
            requirePlay(response.status in 400..599, PlayFailureReason.INVALID_DATA)
            // CDN, private network gates and provider failures may return HTML or
            // malformed JSON. Only a valid protocol rejection has terminal meaning.
            // Losing a temporary response must not durably discard a valid token.
            val retryable = if (response.status == 429) PlayFailureReason.BUSY else PlayFailureReason.NETWORK
            if (!validJSONBody) throw PlayFailure(retryable)
            val rejection = try { wire.rejectedResponse(response.body, response.status) }
            catch (_: PlayFailure) { throw PlayFailure(retryable) }
            throw rejection
        } catch (error: CancellationException) { throw error }
        catch (error: PlayFailure) { throw error }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.NETWORK) }
    }
}
