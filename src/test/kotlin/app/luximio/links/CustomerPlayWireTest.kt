package app.luximio.links

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun customerPlayFixture(): JsonObject = CustomerPlayWireTest::class.java
    .getResourceAsStream("/customer-play-recovery-v1.json")!!.use {
        StrictPlayJson.objectValue(it.readBytes(), 65_536)
    }

internal val customerPlayNow = 1_800_000_000_123L
internal val customerPlayToken = "ERERERERERERERERERERERERERERERERERERERERERE"
internal fun customerPlayConfiguration() = CustomerPlayConfiguration(
    projectID = "project_customer_play_wire_v1", origin = "https://customer-play-wire.links.example.com",
    androidPackageName = "app.example.customer", actualPackageName = "app.example.customer",
    cloudProjectNumber = 123_456_789L)

internal fun customerPlayBytes(key: String): ByteArray = customerPlayFixture().getValue(key).toString().toByteArray()
internal fun customerPlayReceipt(): PlayReceipt = CustomerPlayWire(customerPlayConfiguration())
    .preparedResponse(customerPlayBytes("prepared"), null, customerPlayNow)
internal fun customerPlayAcknowledgement() = PlayAcknowledgement(
    "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444",
    "55555555-5555-4555-8555-555555555555", PlayOutcome.applied)

class CustomerPlayWireTest {
    private val wire = CustomerPlayWire(customerPlayConfiguration())

    @Test fun canonicalCodecMatchesActualWorkerVectorsIncludingUtf16AndLoneSurrogates() {
        for (item in customerPlayFixture().getValue("canonicalCases").jsonArray) {
            val value = item.jsonObject
            val bytes = CustomerPlayCanonical.encode(value.getValue("payload"))
            assertEquals(value.getValue("name").jsonPrimitive.content,
                value.getValue("canonical").jsonPrimitive.content, bytes.decodeToString())
            assertEquals(value.getValue("sha256").jsonPrimitive.content, CustomerPlayCanonical.hash(bytes))
        }
        for (text in listOf("true", "1.5", "9007199254740992", "-9007199254740992", "[1]")) {
            failure(PlayFailureReason.INVALID_DATA) { CustomerPlayCanonical.encode(Json.parseToJsonElement(text)) }
        }
    }

    @Test fun sharedCustomerShapesBridgeScopeOnlyAfterWireValidation() {
        val receipt = customerPlayReceipt()
        assertEquals(customerPlayConfiguration().coordinatorConfiguration.scope, receipt.invocation.scope)
        assertEquals("/catalog/item.v2", receipt.destination.route)
        assertEquals("link:customer.play/v1", receipt.canonicalLinkID)
        assertEquals(customerPlayFixture().getValue("resolveUnpinned"),
            Json.parseToJsonElement(wire.resolvePayload(null).decodeToString()))
        assertEquals(customerPlayFixture().getValue("resolve"),
            Json.parseToJsonElement(wire.resolvePayload(receipt).decodeToString()))
        assertEquals(customerPlayFixture().getValue("acknowledge"),
            Json.parseToJsonElement(wire.acknowledgementPayload(customerPlayAcknowledgement()).decodeToString()))
        wire.acknowledgedResponse(customerPlayBytes("acknowledged"), customerPlayAcknowledgement())
        assertEquals(receipt, wire.preparedResponse(customerPlayBytes("prepared"), receipt, customerPlayNow))
        assertFalse(wire.envelope(wire.resolvePayload(receipt), JsonNull).decodeToString().contains(customerPlayToken))
    }

    @Test fun challengeKeepsOriginalCanonicalBytesAndUsesBase64urlHashOfDecodedBytes() {
        val payload = wire.resolvePayload(null)
        val challenge = wire.challengeResponse(customerPlayBytes("challenge"), "resolve", payload,
            customerPlayToken, customerPlayNow)
        val encoded = customerPlayFixture().getValue("challenge").jsonObject.getValue("clientData").jsonPrimitive.content
        assertArrayEquals(Base64.getDecoder().decode(encoded), challenge.clientData)
        assertEquals(customerPlayFixture().getValue("requestHash").jsonPrimitive.content, challenge.requestHash)
        assertFalse(challenge.requestHash == CustomerPlayCanonical.hash(challenge.clientData))
        assertFalse(challenge.toString().contains(encoded))
    }

    @Test fun everyChallengeBindingFieldAndOuterEchoAreCheckedBeforeNativeProof() {
        val original = customerPlayFixture().getValue("challenge").jsonObject
        val binding = StrictPlayJson.objectValue(Base64.getDecoder().decode(original.getValue("clientData").jsonPrimitive.content), 8_192)
        for (field in binding.keys + "extra") {
            val changed = JsonObject(binding + (field to when (field) {
                "version" -> JsonPrimitive(2)
                "createdAt" -> JsonPrimitive(1)
                "expiresAt" -> JsonPrimitive(customerPlayNow - 1)
                else -> JsonPrimitive("wrong")
            }))
            val response = JsonObject(original + ("clientData" to JsonPrimitive(
                Base64.getEncoder().encodeToString(CustomerPlayCanonical.encode(changed)))))
            failure(PlayFailureReason.INVALID_DATA) { wire.challengeResponse(response.toString().toByteArray(),
                "resolve", wire.resolvePayload(null), customerPlayToken, customerPlayNow) }
        }
        for (field in listOf("version", "result", "challengeID", "challenge", "expiresAt", "extra")) {
            val response = JsonObject(original + (field to if (field == "expiresAt")
                JsonPrimitive(customerPlayNow + 300_001) else JsonPrimitive("wrong")))
            failure(PlayFailureReason.INVALID_DATA) { wire.challengeResponse(response.toString().toByteArray(),
                "resolve", wire.resolvePayload(null), customerPlayToken, customerPlayNow) }
        }
    }

    @Test fun challengeRejectsNoncanonicalBase64JsonAndDuplicateFields() {
        val original = customerPlayFixture().getValue("challenge").jsonObject
        val raw = Base64.getDecoder().decode(original.getValue("clientData").jsonPrimitive.content).decodeToString()
        for (clientData in listOf("????", original.getValue("clientData").jsonPrimitive.content + "\n",
            Base64.getEncoder().encodeToString((" " + raw).toByteArray()),
            Base64.getEncoder().encodeToString(("{\"version\":1," + raw.drop(1)).toByteArray()))) {
            val response = JsonObject(original + ("clientData" to JsonPrimitive(clientData)))
            failure(PlayFailureReason.INVALID_DATA) { wire.challengeResponse(response.toString().toByteArray(),
                "resolve", wire.resolvePayload(null), customerPlayToken, customerPlayNow) }
        }
    }

    @Test fun validChallengeExpiringWhileSuspendedIsRetryableAfterBindingValidation() {
        val delayedNow = customerPlayNow + 300_000
        failure(PlayFailureReason.NETWORK) { wire.challengeResponse(customerPlayBytes("challenge"),
            "resolve", wire.resolvePayload(null), customerPlayToken, delayedNow) }

        val original = customerPlayFixture().getValue("challenge").jsonObject
        val malformed = JsonObject(original + ("expiresAt" to JsonPrimitive(delayedNow + 1)))
        failure(PlayFailureReason.INVALID_DATA) { wire.challengeResponse(malformed.toString().toByteArray(),
            "resolve", wire.resolvePayload(null), customerPlayToken, delayedNow) }
    }

    @Test fun receiptRejectsOldScopeShapeUnknownFieldsWrongPinsAndUnsafeNumbers() {
        val original = customerPlayBytes("prepared").decodeToString()
        val root = customerPlayFixture().getValue("prepared").jsonObject
        val receipt = root.getValue("receipt").jsonObject
        val invocation = receipt.getValue("invocation").jsonObject
        val legacy = JsonObject(root + ("receipt" to JsonObject((receipt - "scope") +
            ("invocation" to JsonObject(invocation + ("scope" to receipt.getValue("scope")))))))
        for (bytes in listOf(legacy.toString().toByteArray(),
            (original.dropLast(1) + ",\"extra\":true}").toByteArray(),
            original.replaceFirst("\"version\":1", "\"version\":1,\"v\\u0065rsion\":1").toByteArray(),
            original.replace("\"capturedAt\":$customerPlayNow", "\"capturedAt\":9007199254740992").toByteArray(),
            original.replace("\"capturedAt\":$customerPlayNow", "\"capturedAt\":\"$customerPlayNow\"").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28))) {
            failure(PlayFailureReason.INVALID_DATA) { wire.preparedResponse(bytes, null, customerPlayNow) }
        }
        failure(PlayFailureReason.CHANGED_DESTINATION) { wire.preparedResponse(customerPlayBytes("prepared"),
            customerPlayReceipt().copy(canonicalLinkID = "changed"), customerPlayNow) }
        failure(PlayFailureReason.EXPIRED) { wire.preparedResponse(customerPlayBytes("prepared"), null,
            customerPlayReceipt().expiresAt) }
    }

    @Test fun onlyMatchingAcknowledgementsAndStatusReasonPairsAreAuthoritative() {
        failure(PlayFailureReason.INVALID_DATA) { wire.acknowledgedResponse(customerPlayBytes("acknowledged"),
            customerPlayAcknowledgement().copy(operationID = "77777777-7777-4777-8777-777777777777")) }
        val cases = listOf(Triple("invalidData", 400, PlayFailureReason.INVALID_DATA),
            Triple("invalidData", 405, PlayFailureReason.INVALID_DATA), Triple("unavailable", 404, PlayFailureReason.UNAVAILABLE),
            Triple("wrongScope", 403, PlayFailureReason.WRONG_SCOPE), Triple("expired", 410, PlayFailureReason.EXPIRED),
            Triple("conflict", 409, PlayFailureReason.CONFLICT), Triple("completed", 409, PlayFailureReason.COMPLETED),
            Triple("changedDestination", 409, PlayFailureReason.CHANGED_DESTINATION), Triple("busy", 429, PlayFailureReason.BUSY),
            Triple("network", 503, PlayFailureReason.NETWORK), Triple("unverified", 401, PlayFailureReason.UNVERIFIED))
        for ((reason, status, expected) in cases) {
            val body = "{\"version\":1,\"result\":\"rejected\",\"reason\":\"$reason\"}".toByteArray()
            assertEquals(expected, wire.rejectedResponse(body, status).reason)
            failure(PlayFailureReason.INVALID_DATA) { wire.rejectedResponse(body, 418) }
        }
    }

    @Test fun customerLimitIs64KiBWithoutChangingPrototypeLimit() {
        failure(PlayFailureReason.INVALID_DATA) { wire.preparedResponse(ByteArray(65_537) { 32 }, null, customerPlayNow) }
        failure(PlayFailureReason.INVALID_DATA) { wire.envelope(
            CustomerPlayCanonical.encode(buildJsonObject { put("large", "x".repeat(65_536)) }), JsonNull) }
    }
}
