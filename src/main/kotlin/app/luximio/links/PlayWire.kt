package app.luximio.links

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.longOrNull

/** Strict bounded codec shared by native HTTPS and fixture tests. Bearers are never serialized here. */
class PlayWire(private val configuration: PlayConfiguration) {
    fun resolveRequest(expected: PlayReceipt? = null): ByteArray = StrictPlayJson.encode(
        ResolveRequest(scope = configuration.scope, receiptID = expected?.id, deliveryID = expected?.deliveryID))

    fun acknowledgeRequest(acknowledgement: PlayAcknowledgement): ByteArray = StrictPlayJson.encode(
        AcknowledgeRequest(scope = configuration.scope, receiptID = acknowledgement.receiptID,
            deliveryID = acknowledgement.deliveryID, operationID = acknowledgement.operationID,
            outcome = acknowledgement.outcome))

    fun preparedResponse(bytes: ByteArray, expected: PlayReceipt?, nowMillis: Long): PlayReceipt {
        val root = StrictPlayJson.objectValue(bytes, 16_384)
        exactKeys(root, setOf("version", "result", "receipt"))
        requirePlay(numberField(root, "version") == 1L && stringField(root, "result") == "prepared", PlayFailureReason.INVALID_DATA)
        val receiptObject = root["receipt"] as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
        validateReceiptFields(receiptObject)
        val response = StrictPlayJson.decode<PreparedResponse>(bytes, 16_384)
        requirePlay(response.version == 1 && response.result == "prepared", PlayFailureReason.INVALID_DATA)
        response.receipt.validate(configuration, nowMillis)
        requirePlay(expected == null || response.receipt == expected, PlayFailureReason.CHANGED_DESTINATION)
        return response.receipt
    }

    fun acknowledgedResponse(bytes: ByteArray, expected: PlayAcknowledgement) {
        val root = StrictPlayJson.objectValue(bytes, 16_384)
        requirePlay(numberField(root, "version") == 1L, PlayFailureReason.INVALID_DATA)
        listOf("result", "receiptID", "deliveryID", "operationID", "outcome").forEach { stringField(root, it) }
        val response = StrictPlayJson.decode<AcknowledgedResponse>(bytes, 16_384)
        requirePlay(response.version == 1 && response.result == "acknowledged" &&
            response.receiptID == expected.receiptID && response.deliveryID == expected.deliveryID &&
            response.operationID == expected.operationID && response.outcome == expected.outcome,
            PlayFailureReason.INVALID_DATA)
    }

    fun rejectedResponse(bytes: ByteArray): PlayFailure {
        val root = StrictPlayJson.objectValue(bytes, 16_384)
        requirePlay(numberField(root, "version") == 1L, PlayFailureReason.INVALID_DATA)
        stringField(root, "result")
        stringField(root, "reason")
        val response = StrictPlayJson.decode<RejectedResponse>(bytes, 16_384)
        requirePlay(response.version == 1 && response.result == "rejected", PlayFailureReason.INVALID_DATA)
        val reason = when (response.reason) {
            "network" -> PlayFailureReason.NETWORK
            "busy" -> PlayFailureReason.BUSY
            "expired" -> PlayFailureReason.EXPIRED
            "wrongScope" -> PlayFailureReason.WRONG_SCOPE
            "changedDestination" -> PlayFailureReason.CHANGED_DESTINATION
            "unavailable", "unverifiedContext" -> PlayFailureReason.UNAVAILABLE
            "completed" -> PlayFailureReason.COMPLETED
            "invalidData", "conflict" -> PlayFailureReason.INVALID_DATA
            else -> throw PlayFailure(PlayFailureReason.INVALID_DATA)
        }
        return PlayFailure(reason)
    }

    @Serializable private data class ResolveRequest(val version: Int = 1, val scope: PlayScope,
        val receiptID: String? = null, val deliveryID: String? = null)
    @Serializable private data class AcknowledgeRequest(val version: Int = 1, val scope: PlayScope,
        val receiptID: String, val deliveryID: String, val operationID: String, val outcome: PlayOutcome)
    @Serializable private data class PreparedResponse(val version: Int, val result: String, val receipt: PlayReceipt)
    @Serializable private data class AcknowledgedResponse(val version: Int, val result: String,
        val receiptID: String, val deliveryID: String, val operationID: String, val outcome: PlayOutcome)
    @Serializable private data class RejectedResponse(val version: Int, val result: String, val reason: String)
}

/** A malformed existing snapshot is an error, never a fresh install. */
object PlayJournalCodec {
    const val MAXIMUM_BYTES = 32_768
    fun encode(state: PlayInstallState, configuration: PlayRecoveryConfiguration, nowMillis: Long = System.currentTimeMillis()): ByteArray {
        state.validate(configuration, nowMillis)
        val bytes = StrictPlayJson.encode(state)
        requirePlay(bytes.size <= MAXIMUM_BYTES, PlayFailureReason.STORAGE)
        return bytes
    }
    fun decode(bytes: ByteArray, configuration: PlayRecoveryConfiguration, nowMillis: Long = System.currentTimeMillis()): PlayInstallState {
        val root = StrictPlayJson.objectValue(bytes, MAXIMUM_BYTES)
        val required = setOf("schemaVersion", "scope", "installationID", "acquisition")
        requirePlay(root.keys.containsAll(required) && (root.keys - required).all { it == "delivery" },
            PlayFailureReason.INVALID_DATA)
        requirePlay(numberField(root, "schemaVersion") == 1L, PlayFailureReason.INVALID_DATA)
        stringField(root, "installationID")
        stringField(root, "acquisition")
        val scope = root["scope"] as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
        validateScopeFields(scope)
        root["delivery"]?.takeUnless { it == JsonNull }?.let { encoded ->
            val delivery = encoded as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
            val requiredDelivery = setOf("token", "retrievedAt", "unresolvedDeadline", "phase")
            requirePlay(delivery.keys.containsAll(requiredDelivery) &&
                (delivery.keys - requiredDelivery).all { it == "receipt" || it == "acknowledgement" },
                PlayFailureReason.INVALID_DATA)
            stringField(delivery, "token")
            stringField(delivery, "phase")
            numberField(delivery, "retrievedAt")
            numberField(delivery, "unresolvedDeadline")
            delivery["receipt"]?.takeUnless { it == JsonNull }?.let {
                validateReceiptFields(it as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA))
            }
            delivery["acknowledgement"]?.takeUnless { it == JsonNull }?.let {
                val acknowledgement = it as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
                exactKeys(acknowledgement, setOf("receiptID", "deliveryID", "operationID", "outcome"))
                acknowledgement.keys.forEach { key -> stringField(acknowledgement, key) }
            }
        }
        return StrictPlayJson.decode<PlayInstallState>(bytes, MAXIMUM_BYTES).also { it.validate(configuration, nowMillis) }
    }
}

private fun validateScopeFields(scope: JsonObject) {
    exactKeys(scope, setOf("channel", "projectID", "androidPackageName"))
    scope.keys.forEach { stringField(scope, it) }
}

private fun validateReceiptFields(receipt: JsonObject) {
    exactKeys(receipt, setOf("id", "deliveryID", "invocation", "canonicalLinkID", "destination", "expiresAt"))
    val invocation = receipt["invocation"] as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
    exactKeys(invocation, setOf("id", "operationID", "originalURL", "capturedAt", "expiresAt", "scope"))
    validateScopeFields(invocation["scope"] as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA))
    val destination = receipt["destination"] as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
    exactKeys(destination, setOf("route", "data"))
    listOf("id", "deliveryID", "canonicalLinkID").forEach { stringField(receipt, it) }
    listOf("id", "operationID", "originalURL").forEach { stringField(invocation, it) }
    numberField(receipt, "expiresAt")
    numberField(invocation, "capturedAt")
    numberField(invocation, "expiresAt")
    stringField(destination, "route")
    requirePlay(destination["data"] is JsonObject, PlayFailureReason.INVALID_DATA)
}

private fun exactKeys(value: JsonObject, keys: Set<String>) {
    requirePlay(value.keys == keys, PlayFailureReason.INVALID_DATA)
}

private fun stringField(value: JsonObject, key: String): String {
    val field = value[key] as? JsonPrimitive ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
    requirePlay(field.isString, PlayFailureReason.INVALID_DATA)
    return field.content
}

private fun numberField(value: JsonObject, key: String): Long {
    val field = value[key] as? JsonPrimitive ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
    requirePlay(!field.isString, PlayFailureReason.INVALID_DATA)
    return field.longOrNull ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
}

internal object StrictPlayJson {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8)
    inline fun <reified T> decode(bytes: ByteArray, maximum: Int): T {
        val text = text(bytes, maximum)
        return try { json.decodeFromString<T>(text) }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
    }
    fun objectValue(bytes: ByteArray, maximum: Int): JsonObject =
        try { json.parseToJsonElement(text(bytes, maximum)) as? JsonObject ?: throw PlayFailure(PlayFailureReason.INVALID_DATA) }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.INVALID_DATA) }

    fun text(bytes: ByteArray, maximum: Int): String {
        requirePlay(bytes.isNotEmpty() && bytes.size <= maximum, PlayFailureReason.INVALID_DATA)
        val value = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
        DuplicateKeyCheck(value).check()
        return value
    }
}

/** kotlinx JSON accepts duplicate keys; reject them before decoding, including escaped aliases. */
private class DuplicateKeyCheck(private val text: String) {
    private var offset = 0
    fun check() { value(0); whitespace(); requirePlay(offset == text.length, PlayFailureReason.INVALID_DATA) }
    private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
    private fun take(char: Char): Boolean { whitespace(); return if (offset < text.length && text[offset] == char) { offset++; true } else false }
    private fun value(depth: Int) {
        requirePlay(depth <= 32, PlayFailureReason.INVALID_DATA)
        whitespace()
        requirePlay(offset < text.length, PlayFailureReason.INVALID_DATA)
        when (text[offset]) {
            '{' -> {
                offset++
                val keys = mutableSetOf<String>()
                if (take('}')) return
                do {
                    whitespace()
                    val encoded = string()
                    val key = try { (StrictPlayJson.json.parseToJsonElement(encoded) as JsonPrimitive).content }
                        catch (_: Exception) { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
                    requirePlay(keys.add(key) && take(':'), PlayFailureReason.INVALID_DATA)
                    value(depth + 1)
                    if (take('}')) return
                } while (take(','))
                throw PlayFailure(PlayFailureReason.INVALID_DATA)
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1); if (take(']')) return } while (take(','))
                throw PlayFailure(PlayFailureReason.INVALID_DATA)
            }
            '"' -> string()
            else -> {
                val start = offset
                while (offset < text.length && text[offset] !in " \t\r\n,}]") offset++
                requirePlay(start != offset, PlayFailureReason.INVALID_DATA)
                val literal = text.substring(start, offset)
                val valid = literal == "true" || literal == "false" || literal == "null" ||
                    (JSON_NUMBER.matches(literal) && literal.toDoubleOrNull()?.isFinite() == true)
                requirePlay(valid, PlayFailureReason.INVALID_DATA)
            }
        }
    }
    private fun string(): String {
        val start = offset
        requirePlay(offset < text.length && text[offset++] == '"', PlayFailureReason.INVALID_DATA)
        while (offset < text.length) {
            when (val char = text[offset++]) {
                '"' -> return text.substring(start, offset)
                '\\' -> {
                    requirePlay(offset < text.length, PlayFailureReason.INVALID_DATA)
                    when (text[offset++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            requirePlay(offset + 4 <= text.length && text.substring(offset, offset + 4)
                                .all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }, PlayFailureReason.INVALID_DATA)
                            offset += 4
                        }
                        else -> throw PlayFailure(PlayFailureReason.INVALID_DATA)
                    }
                }
                else -> requirePlay(char.code >= 0x20, PlayFailureReason.INVALID_DATA)
            }
        }
        throw PlayFailure(PlayFailureReason.INVALID_DATA)
    }

    private companion object {
        val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
