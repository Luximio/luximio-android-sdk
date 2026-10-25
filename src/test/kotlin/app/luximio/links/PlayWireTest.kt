package app.luximio.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

internal val testScope = PlayScope("project_ticketdesk_android_v1", "app.luximio.ticketdesk.playprototype")
internal val testConfiguration = PlayConfiguration(testScope, "https://sdk-ticketdesk-android.luximio.app", testScope.androidPackageName)
internal const val testNow = 1_792_231_200_000L
internal const val testToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
internal const val testReferrer = "lx_v=1&lx_token=$testToken"
internal fun fixture(): JsonObject {
    val stream = requireNotNull(PlayWireTest::class.java.getResourceAsStream("/play-continuation-v1.json")) {
        "Bundled Play wire fixture not found"
    }
    return stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
}
internal fun testReceipt(): PlayReceipt = PlayWire(testConfiguration).preparedResponse(
    fixture().getValue("preparedResponse").toString().toByteArray(), null, testNow)
internal fun failure(reason: PlayFailureReason, block: () -> Unit) {
    try { block(); fail("Expected $reason") }
    catch (error: PlayFailure) { assertEquals(reason, error.reason) }
}

class PlayWireTest {
    @Test fun sharedFixtureRequestsAndResponsesAgree() {
        val wire = PlayWire(testConfiguration)
        val fixture = fixture()
        val receipt = testReceipt()
        assertEquals(fixture["resolveRequest"], Json.parseToJsonElement(wire.resolveRequest().decodeToString()))
        assertEquals(fixture["pinnedResolveRequest"], Json.parseToJsonElement(wire.resolveRequest(receipt).decodeToString()))
        val acknowledgement = PlayAcknowledgement(receipt.id, receipt.deliveryID,
            "50000000-0000-4000-8000-000000000001", PlayOutcome.applied)
        assertEquals(fixture["acknowledgeRequest"], Json.parseToJsonElement(wire.acknowledgeRequest(acknowledgement).decodeToString()))
        wire.acknowledgedResponse(fixture.getValue("acknowledgedResponse").toString().toByteArray(), acknowledgement)
        assertEquals(receipt, wire.preparedResponse(fixture.getValue("preparedResponse").toString().toByteArray(), receipt, testNow))
        assertFalse(wire.resolveRequest().decodeToString().contains(testToken))
        assertFalse(wire.acknowledgeRequest(acknowledgement).decodeToString().contains(testToken))
    }

    @Test fun onlyCanonicalOpaqueEnvelopeIsAccepted() {
        assertEquals(testToken, PlayEnvelope.parse(testReferrer).token)
        listOf("", "lx_v=2&lx_token=$testToken", "$testReferrer&lx_v=1", "$testReferrer&campaign=1",
            "lx_token=$testToken&lx_v=1", "lx_v%3D1%26lx_token%3D$testToken", "lx_v=1&lx_token=${testToken}%3D",
            "lx_v=1&lx_token=${testToken.dropLast(1)}B", "lx_v=1&lx_token=${"A".repeat(513)}",
            "lx_v=1&lx_token=$testToken\n").forEach { value ->
            failure(PlayFailureReason.INVALID_DATA) { PlayEnvelope.parse(value) }
        }
        assertFalse(PlayEnvelope.parse(testReferrer).toString().contains(testToken))
    }

    @Test fun configurationRequiresRuntimePackageAndFixedOrigin() {
        failure(PlayFailureReason.CONFIGURATION) {
            PlayConfiguration(testScope, testConfiguration.origin, "different.customer.app")
        }
        listOf("http://sdk-ticketdesk-android.luximio.app", "https://sdk-ticketdesk-android.luximio.app.evil.test",
            "https://sdk-ticketdesk-android.luximio.app/", "https://go.luximio.app").forEach {
            failure(PlayFailureReason.CONFIGURATION) { PlayConfiguration(testScope, it, testScope.androidPackageName) }
        }
    }

    @Test fun rejectsWrongScopeOriginDeadlineAndChangedDestination() {
        val receipt = testReceipt()
        failure(PlayFailureReason.WRONG_SCOPE) { receipt.copy(invocation = receipt.invocation.copy(
            scope = testScope.copy(projectID = "another_project"))).validate(testConfiguration, testNow) }
        failure(PlayFailureReason.WRONG_SCOPE) { receipt.copy(invocation = receipt.invocation.copy(
            originalURL = "https://another.example/events/aurora")).validate(testConfiguration, testNow) }
        failure(PlayFailureReason.EXPIRED) { receipt.validate(testConfiguration, receipt.expiresAt) }
        failure(PlayFailureReason.INVALID_DATA) { receipt.copy(expiresAt = receipt.invocation.expiresAt + 1)
            .validate(testConfiguration, testNow) }
        failure(PlayFailureReason.INVALID_DATA) { receipt.copy(id = "1-1-1-1-1").validate(testConfiguration, testNow) }
        failure(PlayFailureReason.INVALID_DATA) { receipt.copy(id = "30000000-0000-0000-8000-000000000001")
            .validate(testConfiguration, testNow) }
        failure(PlayFailureReason.WRONG_SCOPE) { receipt.copy(invocation = receipt.invocation.copy(
            scope = testScope.copy(androidPackageName = "another.customer.app"))).validate(testConfiguration, testNow) }
        failure(PlayFailureReason.CHANGED_DESTINATION) { PlayWire(testConfiguration).preparedResponse(
            fixture().getValue("preparedResponse").toString().toByteArray(), receipt.copy(canonicalLinkID = "changed"), testNow) }
    }

    @Test fun duplicateEscapedDuplicateAndUnknownResponseFieldsFailClosed() {
        val original = fixture().getValue("preparedResponse").toString()
        listOf(original.replaceFirst("\"version\":1", "\"version\":1,\"version\":1"),
            original.replaceFirst("\"version\":1", "\"version\":1,\"v\\u0065rsion\":1"),
            original.dropLast(1) + ",\"unknown\":true}",
            original.replace("\"channel\":\"googlePlay\",", ""),
            original.replace("\"projectID\":\"project_ticketdesk_android_v1\"", "\"projectID\":\"other\"")
        ).forEach { body ->
            try { PlayWire(testConfiguration).preparedResponse(body.toByteArray(), null, testNow); fail("Malformed response accepted") }
            catch (_: PlayFailure) { }
        }
    }

    @Test fun boundedUtf8AndDepthAreCheckedBeforeDecoding() {
        val wire = PlayWire(testConfiguration)
        listOf(ByteArray(16_385) { ' '.code.toByte() }, byteArrayOf(0xc3.toByte(), 0x28),
            ("[".repeat(33) + "0" + "]".repeat(33)).toByteArray()).forEach {
            failure(PlayFailureReason.INVALID_DATA) { wire.preparedResponse(it, null, testNow) }
        }
    }

    @Test fun numericStringsAndCoercibleStringFieldsAreRejected() {
        val original = fixture().getValue("preparedResponse").toString()
        listOf(original.replaceFirst("\"version\":1", "\"version\":\"1\""),
            original.replace("\"capturedAt\":$testNow", "\"capturedAt\":\"$testNow\""),
            original.replace("\"canonicalLinkID\":\"ticketdesk_android_aurora_schedule\"", "\"canonicalLinkID\":123")
        ).forEach { body ->
            failure(PlayFailureReason.INVALID_DATA) {
                PlayWire(testConfiguration).preparedResponse(body.toByteArray(), null, testNow)
            }
        }
    }

    @Test fun preparedDestinationRejectsBareAndNonFiniteNestedLiteralsButKeepsValidJsonValues() {
        val original = fixture().getValue("preparedResponse").toString()
        fun withLiteral(literal: String) = original.replace("\"section\":\"schedule\"",
            "\"section\":\"schedule\",\"nested\":{\"value\":$literal}").toByteArray()
        listOf("garbage", "NaN", "Infinity", "-Infinity", "+1", "01", "0x1", "1.", "1e", "True", "falsee", "1e309",
            "[NaN]", "\"raw\nnewline\"", "\"bad\\xescape\"", "\"bad\\u00XY\"")
            .forEach { literal ->
                failure(PlayFailureReason.INVALID_DATA) {
                    PlayWire(testConfiguration).preparedResponse(withLiteral(literal), null, testNow)
                }
            }
        listOf("true", "false", "null", "0", "-0", "-1", "1.5", "1e3", "1E+3", "1e-3",
            "\"escaped\\nnewline\"", "\"unicode\\u0041\"", "[true,null,1.5]").forEach { literal ->
            assertEquals("events/detail", PlayWire(testConfiguration).preparedResponse(withLiteral(literal), null, testNow)
                .destination.route)
        }
    }

    @Test fun journalDestinationRejectsBareAndNonFiniteNestedLiterals() {
        val state = PlayInstallState(scope = testScope, installationID = "60000000-0000-4000-8000-000000000001",
            acquisition = PlayAcquisition.COMPLETE, delivery = PlayDeliveryRecord(testToken, testNow,
                testNow + PLAY_CONTINUATION_LIFETIME_MILLIS, PlayPhase.HELD, testReceipt()))
        val original = PlayJournalCodec.encode(state, testConfiguration, testNow).decodeToString()
        listOf("garbage", "NaN", "Infinity", "-Infinity", "+1", "01", "0x1", "1.", "1e", "True", "falsee", "1e309",
            "[NaN]", "\"raw\nnewline\"", "\"bad\\xescape\"", "\"bad\\u00XY\"")
            .forEach { literal ->
                val encoded = original.replace("\"section\":\"schedule\"",
                    "\"section\":\"schedule\",\"nested\":{\"value\":$literal}").toByteArray()
                failure(PlayFailureReason.INVALID_DATA) {
                    PlayJournalCodec.decode(encoded, testConfiguration, testNow)
                }
            }
    }

    @Test fun everyRealPlayProviderResponseCodeHasAnExplicitClassification() {
        assertNull(PlayProviderResponses.failure(0))
        assertEquals(PlayReferrerResult.Retryable, PlayProviderResponses.failure(-1))
        assertEquals(PlayReferrerResult.Retryable, PlayProviderResponses.failure(1))
        assertEquals(PlayReferrerResult.Unsupported, PlayProviderResponses.failure(2))
        assertEquals(PlayReferrerResult.Rejected(PlayReferrerRejection.DEVELOPER_ERROR), PlayProviderResponses.failure(3))
        assertEquals(PlayReferrerResult.Rejected(PlayReferrerRejection.PERMISSION_DENIED), PlayProviderResponses.failure(4))
        assertEquals(PlayReferrerResult.Rejected(PlayReferrerRejection.UNKNOWN_RESPONSE), PlayProviderResponses.failure(999))
    }

    @Test fun acknowledgementMustEchoExactlyTheQueuedOperation() {
        val receipt = testReceipt()
        val acknowledgement = PlayAcknowledgement(receipt.id, receipt.deliveryID,
            "50000000-0000-4000-8000-000000000002", PlayOutcome.applied)
        failure(PlayFailureReason.INVALID_DATA) { PlayWire(testConfiguration).acknowledgedResponse(
            fixture().getValue("acknowledgedResponse").toString().toByteArray(), acknowledgement) }
    }

    @Test fun journalRoundTripDoesNotRenewCaptureOrAcceptCorruption() {
        val entry = PlayDeliveryRecord(testToken, testNow, testNow + PLAY_CONTINUATION_LIFETIME_MILLIS,
            PlayPhase.HELD, testReceipt())
        val state = PlayInstallState(scope = testScope, installationID = "60000000-0000-4000-8000-000000000001",
            acquisition = PlayAcquisition.COMPLETE, delivery = entry)
        val bytes = PlayJournalCodec.encode(state, testConfiguration, testNow)
        assertEquals(state, PlayJournalCodec.decode(bytes, testConfiguration, testNow + 100_000))
        assertFalse(state.toString().contains(testToken))
        failure(PlayFailureReason.INVALID_DATA) { PlayJournalCodec.decode(
            bytes.decodeToString().replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
                .toByteArray(), testConfiguration, testNow) }
        failure(PlayFailureReason.WRONG_SCOPE) { state.copy(scope = testScope.copy(projectID = "wrong"))
            .validate(testConfiguration, testNow) }
        failure(PlayFailureReason.INVALID_DATA) { state.copy(delivery = entry.copy(phase = PlayPhase.RETRIEVED))
            .validate(testConfiguration, testNow) }
        failure(PlayFailureReason.INVALID_DATA) { PlayJournalCodec.decode(
            bytes.decodeToString().replace("\"acquisition\":\"COMPLETE\",", "").toByteArray(), testConfiguration, testNow) }
    }

    @Test fun pinnedReceiptCannotExtendOriginalInstallRetrievalDeadline() {
        val receipt = testReceipt()
        val later = receipt.copy(invocation = receipt.invocation.copy(capturedAt = testNow + 60_000,
            expiresAt = receipt.expiresAt + 60_000), expiresAt = receipt.expiresAt + 60_000)
        later.validate(testConfiguration, testNow)
        val entry = PlayDeliveryRecord(testToken, testNow, testNow + PLAY_CONTINUATION_LIFETIME_MILLIS,
            PlayPhase.PINNED, later)
        assertEquals(testNow + PLAY_CONTINUATION_LIFETIME_MILLIS, entry.deadline)
    }

    @Test fun persistedDeliveryRequiresEveryNonOptionalMarkerField() {
        val state = PlayInstallState(scope = testScope, installationID = "60000000-0000-4000-8000-000000000001",
            acquisition = PlayAcquisition.COMPLETE, delivery = PlayDeliveryRecord(testToken, testNow,
                testNow + PLAY_CONTINUATION_LIFETIME_MILLIS, PlayPhase.DISCARDED))
        val root = Json.parseToJsonElement(PlayJournalCodec.encode(state, testConfiguration, testNow).decodeToString()).jsonObject
        val delivery = root.getValue("delivery").jsonObject
        listOf("token", "retrievedAt", "unresolvedDeadline", "phase").forEach { key ->
            val incomplete = JsonObject(root + ("delivery" to JsonObject(delivery - key)))
            failure(PlayFailureReason.INVALID_DATA) {
                PlayJournalCodec.decode(incomplete.toString().toByteArray(), testConfiguration, testNow)
            }
        }
    }

    @Test fun persistedNestedReceiptCannotReconstructOmittedScopeChannelFromDefaults() {
        val state = PlayInstallState(scope = testScope, installationID = "60000000-0000-4000-8000-000000000001",
            acquisition = PlayAcquisition.COMPLETE, delivery = PlayDeliveryRecord(testToken, testNow,
                testNow + PLAY_CONTINUATION_LIFETIME_MILLIS, PlayPhase.HELD, testReceipt()))
        val root = Json.parseToJsonElement(PlayJournalCodec.encode(state, testConfiguration, testNow).decodeToString()).jsonObject
        val delivery = root.getValue("delivery").jsonObject
        val receipt = delivery.getValue("receipt").jsonObject
        val invocation = receipt.getValue("invocation").jsonObject
        val scope = invocation.getValue("scope").jsonObject
        val incompleteInvocation = JsonObject(invocation + ("scope" to JsonObject(scope - "channel")))
        val incompleteReceipt = JsonObject(receipt + ("invocation" to incompleteInvocation))
        val incompleteDelivery = JsonObject(delivery + ("receipt" to incompleteReceipt))
        val incomplete = JsonObject(root + ("delivery" to incompleteDelivery))
        failure(PlayFailureReason.INVALID_DATA) {
            PlayJournalCodec.decode(incomplete.toString().toByteArray(), testConfiguration, testNow)
        }
    }
}
