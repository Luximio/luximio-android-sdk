package app.luximio.links

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CustomerPlayBackendTest {
    @Test fun nativeResolveAndCompletionUseFreshValidatedChallengesAndHeaderOnlyBearers() = runBlocking {
        val server = CustomerPlayTestServer()
        val backend = server.backend()
        val receipt = backend.resolve(customerPlayToken)
        assertEquals(customerPlayReceipt(), receipt)
        backend.resolve(customerPlayToken, receipt)
        backend.acknowledge(customerPlayToken, customerPlayAcknowledgement())
        assertEquals(6, server.requests.size)
        assertEquals(3, server.hashes.size)
        assertEquals(3, server.hashes.toSet().size)
        for (request in server.requests) {
            assertEquals(customerPlayToken, request.bearer)
            assertFalse(request.body.decodeToString().contains(customerPlayToken))
            assertTrue(request.url.startsWith(customerPlayConfiguration().origin + "/api/v1/recovery/"))
            assertFalse(request.url.contains('?'))
            assertFalse(request.toString().contains(customerPlayToken))
        }
        val operations = server.requests.filterNot { it.url.endsWith("/challenges") }
        assertEquals(customerPlayFixture().getValue("resolveUnpinned"), server.payload(operations[0]))
        assertEquals(customerPlayFixture().getValue("resolve"), server.payload(operations[1]))
        assertEquals(customerPlayFixture().getValue("acknowledge"), server.payload(operations[2]))
    }

    @Test fun lostCompletionResponseRetriesSameOperationWithNewProofAndNoNewNavigation() = runBlocking {
        val server = CustomerPlayTestServer()
        var loseResponse = true
        server.operationFault = { request ->
            if (request.url.endsWith("/acknowledge") && loseResponse) {
                loseResponse = false
                throw PlayFailure(PlayFailureReason.NETWORK)
            }
            null
        }
        val backend = server.backend()
        val ack = customerPlayAcknowledgement()
        try { backend.acknowledge(customerPlayToken, ack); fail("Expected lost response") }
        catch (failure: PlayFailure) { assertEquals(PlayFailureReason.NETWORK, failure.reason) }
        backend.acknowledge(customerPlayToken, ack)
        val attempts = server.requests.filter { it.url.endsWith("/acknowledge") }
        assertEquals(2, attempts.size)
        assertEquals(server.payload(attempts[0]), server.payload(attempts[1]))
        val proofs = attempts.map { StrictPlayJson.objectValue(it.body, 65_536).getValue("proof").jsonObject }
        assertNotEquals(proofs[0].getValue("challengeID"), proofs[1].getValue("challengeID"))
        assertEquals(2, server.hashes.toSet().size)
    }

    @Test fun malformedExpectedReceiptAndAcknowledgementFailBeforeChallengeOrProvider() = runBlocking {
        val server = CustomerPlayTestServer()
        val backend = server.backend()
        for (receipt in listOf(customerPlayReceipt().copy(invocation = customerPlayReceipt().invocation.copy(
            scope = PlayScope("wrong", "app.example.customer"))),
            customerPlayReceipt().copy(invocation = customerPlayReceipt().invocation.copy(
                originalURL = "https://another.example/catalog/item")),
            customerPlayReceipt().copy(id = "not-a-uuid"))) {
            try { backend.resolve(customerPlayToken, receipt); fail("Expected local rejection") }
            catch (_: PlayFailure) { }
        }
        try { backend.acknowledge(customerPlayToken, customerPlayAcknowledgement().copy(operationID = "invalid")); fail("Expected invalid ACK") }
        catch (failure: PlayFailure) { assertEquals(PlayFailureReason.INVALID_DATA, failure.reason) }
        assertTrue(server.requests.isEmpty())
        assertTrue(server.hashes.isEmpty())
    }

    @Test fun challengeBindingFailureNeverRequestsIntegrityOrSendsNativeOperation() = runBlocking {
        val server = CustomerPlayTestServer()
        server.challengeFault = { response ->
            val binding = StrictPlayJson.objectValue(Base64.getDecoder().decode(response.getValue("clientData").jsonPrimitive.content), 8_192)
            JsonObject(response + ("clientData" to JsonPrimitive(Base64.getEncoder().encodeToString(
                CustomerPlayCanonical.encode(JsonObject(binding + ("origin" to JsonPrimitive("https://evil.example"))))))))
        }
        try { server.backend().resolve(customerPlayToken); fail("Expected invalid challenge") }
        catch (failure: PlayFailure) { assertEquals(PlayFailureReason.INVALID_DATA, failure.reason) }
        assertEquals(1, server.requests.size)
        assertTrue(server.hashes.isEmpty())
    }

    @Test fun malformedResponseMetadataStatusesAndAcknowledgementCannotBecomeSuccess() = runBlocking {
        val variants: List<(CustomerPlayHttpRequest) -> CustomerPlayHttpResponse> = listOf(
            { request -> CustomerPlayHttpResponse(200, request.url + "/", "application/json", customerPlayBytes("prepared")) },
            { request -> CustomerPlayHttpResponse(200, request.url, "text/html", customerPlayBytes("prepared")) },
            { request -> CustomerPlayHttpResponse(201, request.url, "application/json", customerPlayBytes("prepared")) },
            { request -> CustomerPlayHttpResponse(307, request.url, "application/json", customerPlayBytes("prepared")) },
            { request -> CustomerPlayHttpResponse(200, request.url, "application/json", ByteArray(65_537)) })
        for (variant in variants) {
            val server = CustomerPlayTestServer()
            server.operationFault = variant
            try { server.backend().resolve(customerPlayToken); fail("Expected invalid response") }
            catch (failure: PlayFailure) { assertEquals(PlayFailureReason.INVALID_DATA, failure.reason) }
        }
        val server = CustomerPlayTestServer()
        server.operationFault = { request -> CustomerPlayHttpResponse(200, request.url, "application/json",
            customerPlayBytes("acknowledged").decodeToString().replace("\"outcome\":\"applied\"", "\"outcome\":\"discarded\"").toByteArray()) }
        try { server.backend().acknowledge(customerPlayToken, customerPlayAcknowledgement()); fail("Expected wrong ACK") }
        catch (failure: PlayFailure) { assertEquals(PlayFailureReason.INVALID_DATA, failure.reason) }
    }

    @Test fun unavailableProofAndChallengeExpiryCannotFallbackToUnverifiedRecovery() = runBlocking {
        val server = CustomerPlayTestServer()
        val backend = CustomerPlayBackend(customerPlayConfiguration(), server, PlayIntegrityTokenProvider {
            throw PlayFailure(PlayFailureReason.UNVERIFIED)
        }) { customerPlayNow }
        try { backend.resolve(customerPlayToken); fail("Expected proof rejection") }
        catch (failure: PlayFailure) { assertEquals(PlayFailureReason.UNVERIFIED, failure.reason) }
        assertEquals(1, server.requests.size)
        var now = customerPlayNow
        val expiring = CustomerPlayBackend(customerPlayConfiguration(), server, PlayIntegrityTokenProvider {
            now += 300_000
            "opaque-token"
        }) { now }
        try { expiring.resolve(customerPlayToken); fail("Expected expired challenge") }
        catch (failure: PlayFailure) { assertEquals(PlayFailureReason.NETWORK, failure.reason) }
        assertEquals(2, server.requests.size)
    }

    @Test fun expiringNativeChallengeKeepsHeldReceiptForFreshProofOnExplicitRetry() = runBlocking {
        val server = CustomerPlayTestServer()
        var expireNextProof = false
        val backend = CustomerPlayBackend(customerPlayConfiguration(), server, PlayIntegrityTokenProvider { hash ->
            if (expireNextProof) { expireNextProof = false; server.now += 300_000 }
            "integrity:$hash"
        }) { server.now }
        val configuration = customerPlayConfiguration()
        val journal = object : PlayInstallJournal {
            var state = PlayInstallState(scope = configuration.scope,
                installationID = "60000000-0000-4000-8000-000000000001")
            override fun read() = state
            override fun write(state: PlayInstallState) { this.state = state }
        }
        var ready = false
        var routes = 0
        var status: PlayStatus = PlayStatus.Idle
        val coordinator = PlayDeferredCoordinator(configuration, journal,
            PlayReferrerSource { PlayReferrerResult.Available("lx_v=1&lx_token=$customerPlayToken") }, backend,
            { server.now }, route = { if (ready) { routes++; PlayDisposition.APPLIED } else PlayDisposition.HOLD },
            onStatus = { status = it })
        coordinator.activate()
        val held = journal.state.delivery!!
        assertEquals(PlayPhase.HELD, held.phase)
        ready = true
        expireNextProof = true
        coordinator.readinessChanged()
        assertEquals(PlayStatus.RetryAvailable(PlayFailureReason.NETWORK), status)
        assertEquals(held, journal.state.delivery)
        assertEquals(0, routes)
        coordinator.retry()
        assertEquals(1, routes)
        assertEquals(PlayPhase.APPLIED, journal.state.delivery?.phase)
        assertEquals(held.receipt, journal.state.delivery?.receipt)
        assertEquals(held.deadline, journal.state.delivery?.deadline)
        assertNull(journal.state.delivery?.acknowledgement)
    }

    @Test fun rejectedNativeProofKeepsCustomerPinsUntilExplicitFreshProofRetry() = runBlocking {
        val server = CustomerPlayTestServer()
        var rejectProof = false
        server.operationFault = { request -> if (rejectProof) CustomerPlayHttpResponse(401, request.url,
            "application/json", "{\"version\":1,\"result\":\"rejected\",\"reason\":\"unverified\"}".toByteArray()) else null }
        val configuration = customerPlayConfiguration()
        val journal = object : PlayInstallJournal {
            var state = PlayInstallState(scope = configuration.scope,
                installationID = "60000000-0000-4000-8000-000000000001")
            override fun read() = state
            override fun write(state: PlayInstallState) { this.state = state }
        }
        var ready = false
        var routes = 0
        var status: PlayStatus = PlayStatus.Idle
        val coordinator = PlayDeferredCoordinator(configuration, journal,
            PlayReferrerSource { PlayReferrerResult.Available("lx_v=1&lx_token=$customerPlayToken") }, server.backend(),
            { server.now }, route = { if (ready) { routes++; PlayDisposition.APPLIED } else PlayDisposition.HOLD },
            onStatus = { status = it })
        coordinator.activate()
        val held = journal.state.delivery!!
        ready = true
        rejectProof = true
        coordinator.readinessChanged()
        assertEquals(PlayStatus.RetryAvailable(PlayFailureReason.UNVERIFIED), status)
        assertEquals(held, journal.state.delivery)
        assertEquals(0, routes)
        assertTrue(server.requests.none { it.url.endsWith("/acknowledge") })
        val attempts = server.requests.size
        coordinator.activate()
        coordinator.readinessChanged()
        assertEquals(attempts, server.requests.size) // No implicit proof retry or navigation.
        rejectProof = false
        coordinator.retry()
        assertEquals(1, routes)
        assertEquals(held.receipt, journal.state.delivery?.receipt)
        assertEquals(held.token, journal.state.delivery?.token)
        assertEquals(held.deadline, journal.state.delivery?.deadline)
        assertEquals(PlayPhase.APPLIED, journal.state.delivery?.phase)
        assertNull(journal.state.delivery?.acknowledgement)
        assertEquals(server.hashes.size, server.hashes.toSet().size) // Every attempt used fresh proof.
    }

    @Test fun providerCancellationIsPreservedAndStopsTheOperation() = runBlocking {
        val server = CustomerPlayTestServer()
        val backend = CustomerPlayBackend(customerPlayConfiguration(), server, PlayIntegrityTokenProvider {
            throw CancellationException("cancelled")
        }) { customerPlayNow }
        try { backend.resolve(customerPlayToken); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, server.requests.size)
    }

    @Test fun strictServerRejectionsKeepTheirMeaningAcrossNativeOperations() = runBlocking {
        for ((status, reason, expected) in listOf(Triple(401, "unverified", PlayFailureReason.UNVERIFIED),
            Triple(409, "conflict", PlayFailureReason.CONFLICT), Triple(429, "busy", PlayFailureReason.BUSY),
            Triple(503, "network", PlayFailureReason.NETWORK), Triple(409, "changedDestination", PlayFailureReason.CHANGED_DESTINATION))) {
            val server = CustomerPlayTestServer()
            server.operationFault = { request -> CustomerPlayHttpResponse(status, request.url, "application/json",
                "{\"version\":1,\"result\":\"rejected\",\"reason\":\"$reason\"}".toByteArray()) }
            try { server.backend().resolve(customerPlayToken); fail("Expected $reason") }
            catch (failure: PlayFailure) { assertEquals(expected, failure.reason) }
        }
    }

    @Test fun edgeFailuresCannotDiscardAValidInstallBeforeAnExplicitRetry() = runBlocking {
        val server = CustomerPlayTestServer()
        var unavailable = true
        server.operationFault = { request -> if (unavailable) CustomerPlayHttpResponse(
            503, request.url, "text/html", "<html>Temporary edge failure</html>".toByteArray()) else null }
        val configuration = customerPlayConfiguration()
        val journal = object : PlayInstallJournal {
            var state = PlayInstallState(scope = configuration.scope,
                installationID = "60000000-0000-4000-8000-000000000001")
            override fun read() = state
            override fun write(state: PlayInstallState) { this.state = state }
        }
        var routes = 0
        var fetches = 0
        val coordinator = PlayDeferredCoordinator(configuration, journal,
            PlayReferrerSource { fetches++; PlayReferrerResult.Available("lx_v=1&lx_token=$customerPlayToken") },
            server.backend(), { customerPlayNow }, route = { routes++; PlayDisposition.APPLIED })
        coordinator.activate()
        assertEquals(PlayPhase.RETRIEVED, journal.state.delivery?.phase)
        assertEquals(0, routes)
        val before = journal.state.delivery!!
        unavailable = false
        coordinator.retry()
        assertEquals(1, routes)
        assertEquals(1, fetches)
        assertEquals(before.token, journal.state.delivery?.token)
        assertEquals(before.deadline, journal.state.delivery?.deadline)
        assertEquals(PlayPhase.APPLIED, journal.state.delivery?.phase)
        assertNull(journal.state.delivery?.acknowledgement)
    }

    @Test fun nonProtocolErrorsRemainRetryableWithoutAcceptingThemAsSuccess() = runBlocking {
        for ((status, contentType, body) in listOf(
            Triple(429, "text/html", "slow down"), Triple(408, "application/json", "{}"),
            Triple(503, "application/json", "{\"version\":1,\"result\":\"rejected\",\"reason\":\"wrongScope\"}"),
            Triple(403, "text/html", "edge policy"), Triple(502, "application/json", "not-json"))) {
            val server = CustomerPlayTestServer()
            server.operationFault = { request -> CustomerPlayHttpResponse(status, request.url, contentType, body.toByteArray()) }
            try { server.backend().resolve(customerPlayToken); fail("Error response became success") }
            catch (failure: PlayFailure) { assertEquals(if (status == 429) PlayFailureReason.BUSY else PlayFailureReason.NETWORK, failure.reason) }
        }
    }
}

private class CustomerPlayTestServer : CustomerPlayTransport {
    var now = customerPlayNow
    val requests = mutableListOf<CustomerPlayHttpRequest>()
    val hashes = mutableListOf<String>()
    var challengeFault: ((JsonObject) -> JsonObject)? = null
    var operationFault: ((CustomerPlayHttpRequest) -> CustomerPlayHttpResponse?)? = null
    private val issued = mutableMapOf<String, Pair<JsonObject, String>>()
    private val configuration = customerPlayConfiguration()

    fun backend() = CustomerPlayBackend(configuration, this, PlayIntegrityTokenProvider { requestHash ->
        hashes += requestHash
        "integrity:$requestHash"
    }) { now }

    fun payload(request: CustomerPlayHttpRequest): JsonObject = StrictPlayJson.objectValue(request.body, 65_536).getValue("payload").jsonObject

    override suspend fun post(request: CustomerPlayHttpRequest): CustomerPlayHttpResponse {
        requests += request
        val envelope = StrictPlayJson.objectValue(request.body, 65_536)
        assertEquals(setOf("payload", "proof"), envelope.keys)
        val payload = payload(request)
        if (request.url.endsWith("/challenges")) {
            assertEquals(JsonNull, envelope.getValue("proof"))
            val purpose = payload.getValue("purpose").jsonPrimitive.content
            val id = UUID.randomUUID().toString()
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { issued.size.toByte() })
            val binding = buildJsonObject {
                put("version", 1); put("challengeID", id); put("challenge", nonce)
                put("projectID", configuration.projectID); put("hostname", configuration.hostname)
                put("channel", "play_install_referrer"); put("identityHash", "a".repeat(64))
                put("origin", configuration.origin); put("method", "POST")
                put("path", "/api/v1/recovery/$purpose"); put("purpose", purpose)
                put("payloadHash", payload.getValue("payloadHash")); put("tokenHash", CustomerPlayCanonical.hash(customerPlayToken.toByteArray()))
                put("createdAt", now); put("expiresAt", now + 300_000)
            }
            val clientData = CustomerPlayCanonical.encode(binding)
            issued[id] = payload to CustomerPlayCanonical.requestHash(clientData)
            val response = buildJsonObject {
                put("version", 1); put("result", "challenge"); put("challengeID", id); put("challenge", nonce)
                put("clientData", Base64.getEncoder().encodeToString(clientData)); put("expiresAt", now + 300_000)
            }
            return CustomerPlayHttpResponse(201, request.url, "application/json", (challengeFault?.invoke(response) ?: response).toString().toByteArray())
        }
        val proof = envelope.getValue("proof").jsonObject
        assertEquals(setOf("kind", "challengeID", "integrityToken"), proof.keys)
        assertEquals("play_integrity", proof.getValue("kind").jsonPrimitive.content)
        val (challenge, hash) = issued.remove(proof.getValue("challengeID").jsonPrimitive.content)!!
        assertEquals(challenge.getValue("payloadHash").jsonPrimitive.content, CustomerPlayCanonical.hash(CustomerPlayCanonical.encode(payload)))
        assertEquals("integrity:$hash", proof.getValue("integrityToken").jsonPrimitive.content)
        operationFault?.invoke(request)?.let { return it }
        val response = if (request.url.endsWith("/acknowledge")) buildJsonObject {
            put("version", 1); put("result", "acknowledged")
            for (key in listOf("receiptID", "deliveryID", "operationID", "outcome")) put(key, payload.getValue(key))
        }.toString().toByteArray() else customerPlayBytes("prepared")
        return CustomerPlayHttpResponse(200, request.url, "application/json",
            response)
    }
}
