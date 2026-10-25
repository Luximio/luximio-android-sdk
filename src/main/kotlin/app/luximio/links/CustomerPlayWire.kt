package app.luximio.links

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/** The signed domain contains only objects, strings, safe integers and null. */
internal object CustomerPlayCanonical {
    const val MAXIMUM_INTEGER = 9_007_199_254_740_991L

    fun encode(value: JsonElement): ByteArray = canonical(value).toByteArray(Charsets.UTF_8)
    fun hash(bytes: ByteArray): String = digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun requestHash(clientData: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(clientData))

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun canonical(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonical(value.getValue(it)) }
        is JsonPrimitive -> if (value.isString) quote(value.content) else {
            requirePlay(Regex("-?(?:0|[1-9][0-9]*)").matches(value.content), PlayFailureReason.INVALID_DATA)
            val integer = value.longOrNull ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
            requirePlay(integer in -MAXIMUM_INTEGER..MAXIMUM_INTEGER, PlayFailureReason.INVALID_DATA)
            integer.toString()
        }
        else -> throw PlayFailure(PlayFailureReason.INVALID_DATA)
    }

    /** JSON.stringify escapes unpaired UTF-16 surrogates but preserves valid pairs. */
    private fun quote(value: String): String = buildString {
        append('"')
        var offset = 0
        while (offset < value.length) {
            val char = value[offset++]
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> when {
                    char.code < 0x20 -> appendEscape(char)
                    char.isHighSurrogate() -> {
                        if (offset < value.length && value[offset].isLowSurrogate()) append(char).append(value[offset++])
                        else appendEscape(char)
                    }
                    char.isLowSurrogate() -> appendEscape(char)
                    else -> append(char)
                }
            }
        }
        append('"')
    }

    private fun StringBuilder.appendEscape(value: Char) {
        append("\\u").append(value.code.toString(16).padStart(4, '0'))
    }
}

internal class CustomerPlayChallenge(
    val id: String,
    val clientData: ByteArray,
    val expiresAt: Long,
) {
    val requestHash: String get() = CustomerPlayCanonical.requestHash(clientData)
    override fun toString() = "CustomerPlayChallenge(context=<redacted>)"
}

/** Customer protocol codec. Prototype scope/receipt shapes never enter this wire format. */
internal class CustomerPlayWire(private val configuration: CustomerPlayConfiguration) {
    fun resolvePayload(expected: PlayReceipt?): ByteArray = payload(buildJsonObject {
        put("version", 1); put("scope", scope())
        expected?.let { put("receiptID", it.id); put("deliveryID", it.deliveryID) }
    })

    fun acknowledgementPayload(acknowledgement: PlayAcknowledgement): ByteArray {
        listOf(acknowledgement.receiptID, acknowledgement.deliveryID, acknowledgement.operationID).forEach(::validatePlayUUID)
        return payload(buildJsonObject {
            put("version", 1); put("scope", scope())
            put("receiptID", acknowledgement.receiptID); put("deliveryID", acknowledgement.deliveryID)
            put("operationID", acknowledgement.operationID); put("outcome", acknowledgement.outcome.name)
        })
    }

    fun challengePayload(purpose: String, operation: ByteArray): ByteArray {
        operationPath(purpose)
        return payload(buildJsonObject {
            put("version", 1); put("scope", scope()); put("purpose", purpose)
            put("payloadHash", CustomerPlayCanonical.hash(operation))
        })
    }

    fun envelope(operation: ByteArray, proof: JsonElement): ByteArray {
        val parsed = StrictPlayJson.objectValue(operation, MAXIMUM_BYTES)
        requirePlay(CustomerPlayCanonical.encode(parsed).contentEquals(operation), PlayFailureReason.INVALID_DATA)
        val bytes = CustomerPlayCanonical.encode(buildJsonObject { put("payload", parsed); put("proof", proof) })
        requirePlay(bytes.size <= MAXIMUM_BYTES, PlayFailureReason.INVALID_DATA)
        return bytes
    }

    fun challengeResponse(bytes: ByteArray, purpose: String, operation: ByteArray, bearer: String, nowMillis: Long): CustomerPlayChallenge {
        val root = objectValue(bytes)
        exact(root, "version", "result", "challengeID", "challenge", "clientData", "expiresAt")
        requirePlay(integer(root, "version") == 1L && string(root, "result") == "challenge", PlayFailureReason.INVALID_DATA)
        val id = string(root, "challengeID")
        validatePlayUUID(id)
        val nonce = string(root, "challenge")
        decodeBase64URL(nonce, 32)
        val raw = decodeBase64(string(root, "clientData"), 8_192)
        val binding = StrictPlayJson.objectValue(raw, 8_192)
        exact(binding, "version", "challengeID", "challenge", "projectID", "hostname", "channel", "identityHash", "origin",
            "method", "path", "purpose", "payloadHash", "tokenHash", "createdAt", "expiresAt")
        val createdAt = integer(binding, "createdAt")
        val expiresAt = integer(binding, "expiresAt")
        requirePlay(integer(binding, "version") == 1L && string(binding, "challengeID") == id && string(binding, "challenge") == nonce &&
            string(binding, "projectID") == configuration.projectID && string(binding, "hostname") == configuration.hostname &&
            string(binding, "channel") == CHANNEL && string(binding, "identityHash").matches(HASH) &&
            string(binding, "origin") == configuration.origin && string(binding, "method") == "POST" &&
            string(binding, "path") == operationPath(purpose) && string(binding, "purpose") == purpose &&
            string(binding, "payloadHash") == CustomerPlayCanonical.hash(operation) &&
            string(binding, "tokenHash") == CustomerPlayCanonical.hash(bearer.toByteArray(Charsets.UTF_8)) &&
            nowMillis in 0..CustomerPlayCanonical.MAXIMUM_INTEGER && createdAt >= 0 &&
            createdAt <= CustomerPlayCanonical.MAXIMUM_INTEGER - CHALLENGE_MILLIS &&
            expiresAt == createdAt + CHALLENGE_MILLIS &&
            createdAt <= nowMillis + minOf(CHALLENGE_MILLIS, CustomerPlayCanonical.MAXIMUM_INTEGER - nowMillis) &&
            integer(root, "expiresAt") == expiresAt &&
            CustomerPlayCanonical.encode(binding).contentEquals(raw), PlayFailureReason.INVALID_DATA)
        // A delayed response may outlive its challenge while the receipt remains valid.
        requirePlay(expiresAt > nowMillis, PlayFailureReason.NETWORK)
        return CustomerPlayChallenge(id, raw, expiresAt)
    }

    fun preparedResponse(bytes: ByteArray, expected: PlayReceipt?, nowMillis: Long): PlayReceipt {
        val root = objectValue(bytes)
        exact(root, "version", "result", "receipt")
        requirePlay(integer(root, "version") == 1L && string(root, "result") == "prepared", PlayFailureReason.INVALID_DATA)
        val receipt = child(root, "receipt")
        exact(receipt, "id", "deliveryID", "scope", "invocation", "canonicalLinkID", "destination", "expiresAt")
        val receiptScope = child(receipt, "scope")
        exact(receiptScope, "projectID", "channel")
        requirePlay(string(receiptScope, "projectID") == configuration.projectID && string(receiptScope, "channel") == CHANNEL,
            PlayFailureReason.WRONG_SCOPE)
        val invocation = child(receipt, "invocation")
        exact(invocation, "id", "operationID", "originalURL", "capturedAt", "expiresAt")
        val destination = child(receipt, "destination")
        exact(destination, "route", "data")
        val bridged = PlayReceipt(id = string(receipt, "id"), deliveryID = string(receipt, "deliveryID"),
            invocation = PlayInvocation(id = string(invocation, "id"), operationID = string(invocation, "operationID"),
                originalURL = string(invocation, "originalURL"), capturedAt = integer(invocation, "capturedAt"),
                expiresAt = integer(invocation, "expiresAt"), scope = configuration.coordinatorConfiguration.scope),
            canonicalLinkID = string(receipt, "canonicalLinkID"),
            destination = PlayDestination(string(destination, "route"), child(destination, "data")),
            expiresAt = integer(receipt, "expiresAt"))
        validateReceipt(bridged, nowMillis)
        requirePlay(expected == null || expected == bridged, PlayFailureReason.CHANGED_DESTINATION)
        return bridged
    }

    fun validateReceipt(receipt: PlayReceipt, nowMillis: Long) {
        receipt.validate(configuration.coordinatorConfiguration, nowMillis)
    }

    fun acknowledgedResponse(bytes: ByteArray, expected: PlayAcknowledgement) {
        val root = objectValue(bytes)
        exact(root, "version", "result", "receiptID", "deliveryID", "operationID", "outcome")
        requirePlay(integer(root, "version") == 1L && string(root, "result") == "acknowledged", PlayFailureReason.INVALID_DATA)
        listOf("receiptID", "deliveryID", "operationID").forEach { validatePlayUUID(string(root, it)) }
        requirePlay(string(root, "receiptID") == expected.receiptID && string(root, "deliveryID") == expected.deliveryID &&
            string(root, "operationID") == expected.operationID && string(root, "outcome") == expected.outcome.name,
            PlayFailureReason.INVALID_DATA)
    }

    fun rejectedResponse(bytes: ByteArray, status: Int): PlayFailure {
        val root = objectValue(bytes)
        exact(root, "version", "result", "reason")
        requirePlay(integer(root, "version") == 1L && string(root, "result") == "rejected", PlayFailureReason.INVALID_DATA)
        val reason = when (string(root, "reason") to status) {
            "invalidData" to 400, "invalidData" to 405 -> PlayFailureReason.INVALID_DATA
            "unavailable" to 404 -> PlayFailureReason.UNAVAILABLE
            "wrongScope" to 403 -> PlayFailureReason.WRONG_SCOPE
            "expired" to 410 -> PlayFailureReason.EXPIRED
            "conflict" to 409 -> PlayFailureReason.CONFLICT
            "completed" to 409 -> PlayFailureReason.COMPLETED
            "changedDestination" to 409 -> PlayFailureReason.CHANGED_DESTINATION
            "busy" to 429 -> PlayFailureReason.BUSY
            "network" to 503 -> PlayFailureReason.NETWORK
            "unverified" to 401 -> PlayFailureReason.UNVERIFIED
            else -> throw PlayFailure(PlayFailureReason.INVALID_DATA)
        }
        return PlayFailure(reason)
    }

    private fun scope() = buildJsonObject { put("projectID", configuration.projectID); put("channel", CHANNEL) }
    private fun payload(value: JsonObject): ByteArray = CustomerPlayCanonical.encode(value)
    private fun objectValue(bytes: ByteArray) = StrictPlayJson.objectValue(bytes, MAXIMUM_BYTES)
    private fun exact(value: JsonObject, vararg fields: String) = requirePlay(value.keys == fields.toSet(), PlayFailureReason.INVALID_DATA)
    private fun child(value: JsonObject, key: String): JsonObject = value[key] as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
    private fun string(value: JsonObject, key: String): String {
        val primitive = value[key] as? JsonPrimitive ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
        requirePlay(primitive.isString, PlayFailureReason.INVALID_DATA)
        return primitive.content
    }
    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
        requirePlay(!primitive.isString && Regex("-?(?:0|[1-9][0-9]*)").matches(primitive.content), PlayFailureReason.INVALID_DATA)
        val result = primitive.longOrNull ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
        requirePlay(result in 0..CustomerPlayCanonical.MAXIMUM_INTEGER, PlayFailureReason.INVALID_DATA)
        return result
    }

    private fun decodeBase64(value: String, maximum: Int): ByteArray {
        requirePlay(value.isNotEmpty() && value.length <= ((maximum + 2) / 3) * 4 &&
            BASE64.matches(value), PlayFailureReason.INVALID_DATA)
        val result = try { Base64.getDecoder().decode(value) }
        catch (_: IllegalArgumentException) { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
        requirePlay(result.size <= maximum && Base64.getEncoder().encodeToString(result) == value, PlayFailureReason.INVALID_DATA)
        return result
    }
    private fun decodeBase64URL(value: String, exactBytes: Int): ByteArray {
        requirePlay(value.matches(Regex("[A-Za-z0-9_-]+")), PlayFailureReason.INVALID_DATA)
        val result = try { Base64.getUrlDecoder().decode(value) }
        catch (_: IllegalArgumentException) { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
        requirePlay(result.size == exactBytes && Base64.getUrlEncoder().withoutPadding().encodeToString(result) == value,
            PlayFailureReason.INVALID_DATA)
        return result
    }

    private fun operationPath(purpose: String): String {
        requirePlay(purpose == "resolve" || purpose == "acknowledge", PlayFailureReason.INVALID_DATA)
        return "$ROOT/$purpose"
    }

    companion object {
        const val ROOT = "/api/v1/recovery"
        const val MAXIMUM_BYTES = 65_536
        private const val CHANNEL = "play_install_referrer"
        private const val CHALLENGE_MILLIS = 300_000L
        private val HASH = Regex("[0-9a-f]{64}")
        private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
    }
}
