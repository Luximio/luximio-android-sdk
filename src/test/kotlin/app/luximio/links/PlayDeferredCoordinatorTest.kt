package app.luximio.links

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlayDeferredCoordinatorTest {
    @Test fun prototypeKeepsItsExistingTerminalUnverifiedPolicy() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { PlayDisposition.HOLD }
        val client = fixture.client()
        client.activate()
        fixture.backend.resolveFailure = PlayFailureReason.UNVERIFIED
        client.readinessChanged()
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery?.phase)
        assertEquals(PlayStatus.Rejected(PlayFailureReason.UNVERIFIED), fixture.statuses.last())
        val resolves = fixture.backend.resolves
        client.retry()
        assertEquals(resolves, fixture.backend.resolves)
        assertTrue(fixture.applied.isEmpty())
    }

    @Test fun tokenIsDurableBeforeFirstResolveAndPinBeforeCustomerRouter() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.backend.beforeResolve = {
            assertEquals(testToken, fixture.journal.state.delivery!!.token)
            assertEquals(PlayAcquisition.COMPLETE, fixture.journal.state.acquisition)
        }
        fixture.router = { assertEquals(PlayPhase.APPLYING, fixture.journal.state.delivery!!.phase); PlayDisposition.APPLIED }
        fixture.client().activate()
        assertEquals(PlayPhase.APPLIED, fixture.journal.state.delivery!!.phase)
        assertNull(fixture.journal.state.delivery!!.acknowledgement)
        assertEquals(1, fixture.routes)
    }

    @Test fun lostResolveResponseSurvivesProcessRestartWithoutRequeryingPlay() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.backend.resolveFailure = PlayFailureReason.NETWORK
        fixture.client().activate()
        val retrieved = fixture.journal.state.delivery!!
        assertEquals(PlayPhase.RETRIEVED, retrieved.phase)
        fixture.time += 60_000
        fixture.backend.resolveFailure = null
        fixture.client().activate()
        assertEquals(1, fixture.fetches)
        assertEquals(retrieved.retrievedAt, fixture.journal.state.delivery!!.retrievedAt)
        assertEquals(retrieved.unresolvedDeadline, fixture.journal.state.delivery!!.unresolvedDeadline)
        assertEquals(1, fixture.routes)
    }

    @Test fun heldEntrySurvivesRestartAndReadinessRevalidatesCurrentDestination() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { PlayDisposition.HOLD }
        fixture.client().activate()
        assertEquals(PlayPhase.HELD, fixture.journal.state.delivery!!.phase)
        val newClient = fixture.client()
        newClient.activate()
        assertEquals(1, fixture.backend.resolves)
        fixture.router = { PlayDisposition.APPLIED }
        newClient.readinessChanged()
        assertEquals(2, fixture.backend.resolves)
        assertEquals(fixture.backend.receipt, fixture.backend.expectedReceipts.last())
        assertEquals(PlayPhase.APPLIED, fixture.journal.state.delivery!!.phase)
    }

    @Test fun duplicateActivationAndTerminalReferrerNeverReplayEvenAfterErasure() = runBlocking {
        val fixture = CoordinatorFixture()
        val client = fixture.client()
        client.activate(); client.activate(); client.retry(); fixture.client().activate()
        assertEquals(1, fixture.routes)
        assertEquals(1, fixture.fetches)
        fixture.time = fixture.backend.receipt.expiresAt + 1
        fixture.client().activate()
        assertNull(fixture.journal.state.delivery)
        assertEquals(PlayAcquisition.COMPLETE, fixture.journal.state.acquisition)
        fixture.client().retry()
        assertEquals(1, fixture.fetches)
    }

    @Test fun missingUnsupportedAndInvalidReferrerAreTerminalWithoutResolverCalls() = runBlocking {
        listOf(PlayReferrerResult.Missing, PlayReferrerResult.Unsupported,
            PlayReferrerResult.Rejected(PlayReferrerRejection.PERMISSION_DENIED),
            PlayReferrerResult.Available("utm_source=ordinary"), PlayReferrerResult.Available("$testReferrer&x=1")).forEach { result ->
            val fixture = CoordinatorFixture()
            fixture.sourceResult = result
            fixture.client().activate(); fixture.client().retry()
            assertEquals(PlayAcquisition.COMPLETE, fixture.journal.state.acquisition)
            assertEquals(0, fixture.backend.resolves)
            assertEquals(1, fixture.fetches)
            if (result == PlayReferrerResult.Available("utm_source=ordinary"))
                assertTrue(fixture.statuses.contains(PlayStatus.NoReferrer))
        }
    }

    @Test fun unavailablePlayIsRetryableButDoesNotHotLoopOnActivation() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.sourceResult = PlayReferrerResult.Retryable
        val client = fixture.client()
        client.activate(); client.activate(); client.readinessChanged()
        assertEquals(1, fixture.fetches)
        assertEquals(PlayAcquisition.FRESH, fixture.journal.state.acquisition)
        fixture.sourceResult = PlayReferrerResult.Available(testReferrer)
        client.retry()
        assertEquals(2, fixture.fetches)
        assertEquals(1, fixture.routes)
    }

    @Test fun offlineResolvePreservesHeldReceiptAndOriginalContext() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { PlayDisposition.HOLD }
        val client = fixture.client()
        client.activate()
        val held = fixture.journal.state.delivery
        fixture.router = { PlayDisposition.APPLIED }
        fixture.backend.resolveFailure = PlayFailureReason.NETWORK
        client.readinessChanged()
        assertEquals(held, fixture.journal.state.delivery)
        fixture.backend.resolveFailure = null
        client.retry()
        assertEquals(held!!.receipt!!.invocation.originalURL, fixture.applied.single().originalURL)
    }

    @Test fun changedDestinationAndRemovedTargetCannotReviveAfterServerRestoration() = runBlocking {
        listOf(PlayFailureReason.CHANGED_DESTINATION, PlayFailureReason.UNAVAILABLE).forEach { reason ->
            val fixture = CoordinatorFixture()
            fixture.router = { PlayDisposition.HOLD }
            val client = fixture.client(); client.activate()
            fixture.backend.resolveFailure = reason
            client.readinessChanged()
            assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
            fixture.backend.resolveFailure = null
            fixture.router = { PlayDisposition.APPLIED }
            client.retry(); fixture.client().activate()
            assertTrue(fixture.applied.isEmpty())
        }
    }

    @Test fun successfulServerResponseWithDifferentPinnedDestinationIsRejected() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { PlayDisposition.HOLD }
        val client = fixture.client(); client.activate()
        val original = fixture.backend.receipt
        fixture.backend.receipt = original.copy(destination = original.destination.copy(route = "events/other"))
        fixture.router = { PlayDisposition.APPLIED }
        client.readinessChanged()
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
        assertTrue(fixture.statuses.contains(PlayStatus.Rejected(PlayFailureReason.CHANGED_DESTINATION)))
        assertTrue(fixture.applied.isEmpty())
    }

    @Test fun initialStorageFailureMakesNoProviderOrNetworkRequest() = runBlocking {
        val fixture = CoordinatorFixture()
        val badJournal = object : PlayInstallJournal {
            override fun read(): PlayInstallState = throw PlayFailure(PlayFailureReason.STORAGE)
            override fun write(state: PlayInstallState) = error("Must not write")
        }
        val client = PlayDeferredCoordinator(testConfiguration, badJournal,
            PlayReferrerSource { fixture.fetches++; PlayReferrerResult.Available(testReferrer) },
            fixture.backend, { testNow }, route = { fail("Must not route"); PlayDisposition.DISCARDED })
        client.activate(); client.retry()
        assertEquals(0, fixture.fetches)
        assertEquals(0, fixture.backend.resolves)
    }

    @Test fun expiredUnresolvedTokenCannotStartNewAcquisition() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.backend.resolveFailure = PlayFailureReason.NETWORK
        fixture.client().activate()
        fixture.time += PLAY_CONTINUATION_LIFETIME_MILLIS + 1
        fixture.backend.resolveFailure = null
        fixture.client().activate()
        assertNull(fixture.journal.state.delivery)
        assertEquals(1, fixture.fetches)
        assertEquals(1, fixture.backend.resolves)
    }

    @Test fun manualActionWhileProviderCallbackIsPendingSupersedesItPermanently() = runBlocking {
        val fixture = CoordinatorFixture()
        val delivered = CompletableDeferred<PlayReferrerResult>()
        val source = PlayReferrerSource { fixture.fetches++; delivered.await() }
        val client = fixture.client(source)
        val query = launch { client.activate() }
        yield()
        client.cancelForManualNavigation()
        delivered.complete(PlayReferrerResult.Available(testReferrer)); query.join()
        fixture.client().activate()
        assertEquals(PlayAcquisition.SUPPRESSED, fixture.journal.state.acquisition)
        assertEquals(0, fixture.backend.resolves)
        assertEquals(0, fixture.routes)
    }

    @Test fun manualActionWhileResolveIsPendingPreventsLateNavigation() = runBlocking {
        val fixture = CoordinatorFixture()
        val server = CompletableDeferred<Unit>()
        fixture.backend.beforeResolve = { server.await() }
        val client = fixture.client()
        val query = launch { client.activate() }
        yield()
        client.cancelForManualNavigation()
        server.complete(Unit); query.join()
        client.retry(); fixture.client().activate()
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
        assertEquals(0, fixture.routes)
    }

    @Test fun concurrentActivationMakesOnlyOneProviderAndResolveCall() = runBlocking {
        val fixture = CoordinatorFixture()
        val server = CompletableDeferred<Unit>()
        fixture.backend.beforeResolve = { server.await() }
        val client = fixture.client()
        val first = launch { client.activate() }; yield()
        client.activate(); client.readinessChanged(); client.retry()
        server.complete(Unit); first.join()
        assertEquals(1, fixture.fetches)
        assertEquals(1, fixture.backend.resolves)
        assertEquals(1, fixture.routes)
    }

    @Test fun deniedCurrentAccountBecomesTerminalEvenAfterSwitchingAccount() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { PlayDisposition.HOLD }
        val client = fixture.client(); client.activate()
        fixture.router = { PlayDisposition.DISCARDED }
        client.readinessChanged()
        fixture.router = { PlayDisposition.APPLIED }
        client.readinessChanged(); client.retry()
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
        assertTrue(fixture.applied.isEmpty())
    }

    @Test fun applyingCrashRequiresExactExplicitConfirmationRatherThanActivationOrRetry() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { throw IllegalStateException("Synthetic crash after route commitment") }
        fixture.client().activate()
        assertEquals(PlayPhase.APPLYING, fixture.journal.state.delivery!!.phase)
        fixture.router = { PlayDisposition.APPLIED }
        val restarted = fixture.client()
        restarted.activate(); restarted.retry(); restarted.readinessChanged()
        restarted.confirmUncertain("40000000-0000-4000-8000-000000000099")
        assertEquals(1, fixture.routes)
        restarted.confirmUncertain(fixture.backend.receipt.deliveryID)
        assertEquals(2, fixture.routes)
        assertEquals(PlayPhase.APPLIED, fixture.journal.state.delivery!!.phase)
    }

    @Test fun cancellationOfUncertainApplicationPreventsConfirmationReplay() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { throw IllegalStateException("Synthetic uncertain navigation") }
        val client = fixture.client(); client.activate()
        client.cancelForManualNavigation()
        fixture.router = { PlayDisposition.APPLIED }
        client.confirmUncertain(fixture.backend.receipt.deliveryID)
        assertEquals(1, fixture.routes)
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
    }

    @Test fun lostCompletionResponseRetriesSameOperationWithoutReopening() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.backend.ackFailure = true
        fixture.client().activate()
        val first = fixture.journal.state.delivery!!.acknowledgement!!
        assertTrue(fixture.completionPending.last())
        fixture.backend.ackFailure = false
        fixture.client().retry()
        assertEquals(listOf(first, first), fixture.backend.acknowledgements)
        assertNull(fixture.journal.state.delivery!!.acknowledgement)
        assertEquals(1, fixture.routes)
        assertFalse(fixture.completionPending.last())
    }

    @Test fun failedTokenSaveMakesNoNetworkCall() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.journal.beforeWrite = { if (it.delivery != null) throw IllegalStateException("Disk full") }
        fixture.client().activate()
        assertEquals(0, fixture.backend.resolves)
        assertEquals(0, fixture.routes)
        assertNull(fixture.journal.state.delivery)
    }

    @Test fun failedApplyingSaveCannotNavigate() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.journal.beforeWrite = { if (it.delivery?.phase == PlayPhase.APPLYING) throw IllegalStateException("Disk full") }
        fixture.client().activate()
        assertEquals(0, fixture.routes)
        assertEquals(PlayPhase.PINNED, fixture.journal.state.delivery!!.phase)
    }

    @Test fun terminalSaveFailureIsUncertainAndCannotAutomaticallyRepeatRoute() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.journal.beforeWrite = { if (it.delivery?.phase == PlayPhase.APPLIED) throw IllegalStateException("Disk full") }
        val client = fixture.client(); client.activate()
        assertEquals(PlayPhase.APPLYING, fixture.journal.state.delivery!!.phase)
        assertTrue(fixture.statuses.last() is PlayStatus.UncertainApplication)
        fixture.journal.beforeWrite = {}
        client.activate(); client.retry()
        assertEquals(1, fixture.routes)
    }

    @Test fun failedCancellationInvalidatesInFlightResultAndReportsFailure() = runBlocking {
        val fixture = CoordinatorFixture()
        val response = CompletableDeferred<Unit>()
        fixture.backend.beforeResolve = { response.await() }
        val client = fixture.client()
        val query = launch { client.activate() }; yield()
        fixture.journal.beforeWrite = { throw IllegalStateException("Disk full") }
        try { client.cancelForManualNavigation(); fail("Cancellation must not promise persistence") }
        catch (error: PlayFailure) { assertEquals(PlayFailureReason.STORAGE, error.reason) }
        response.complete(Unit); query.join()
        assertEquals(0, fixture.routes)
        assertTrue(fixture.statuses.last() is PlayStatus.Suppressed)
        fixture.journal.beforeWrite = {}
        client.activate()
        assertEquals(0, fixture.routes)
        client.retry()
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
        client.activate(); client.retry(); fixture.client().activate()
        assertEquals(0, fixture.routes)
    }

    @Test fun expiryDuringPinOrApplyingWriteCannotDispatchOrAcknowledgeApplied() = runBlocking {
        listOf(PlayPhase.PINNED, PlayPhase.APPLYING).forEach { delayedPhase ->
            val fixture = CoordinatorFixture()
            fixture.journal.beforeWrite = { state ->
                if (state.delivery?.phase == delayedPhase) fixture.time = fixture.backend.receipt.expiresAt
            }
            fixture.client().activate()
            assertEquals(0, fixture.routes)
            assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
            assertTrue(fixture.backend.acknowledgements.none { it.outcome == PlayOutcome.applied })
        }
    }

    @Test fun differentInstallationSnapshotDuringRequestFailsClosed() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.backend.beforeResolve = { fixture.journal.state = fixture.journal.state.copy(
            installationID = "60000000-0000-4000-8000-000000000099") }
        fixture.client().activate()
        assertEquals(0, fixture.routes)
    }

    @Test fun missingCancelledPhaseAfterRestartCannotReviveAnUnpinnedBearer() = runBlocking {
        val fixture = CoordinatorFixture()
        val response = CompletableDeferred<Unit>()
        fixture.backend.beforeResolve = { response.await() }
        val client = fixture.client()
        val query = launch { client.activate() }; yield()
        client.cancelForManualNavigation()
        assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
        assertNull(fixture.journal.state.delivery!!.receipt)
        response.complete(Unit); query.join()
        val stored = Json.parseToJsonElement(PlayJournalCodec.encode(fixture.journal.state, testConfiguration,
            testNow).decodeToString()).jsonObject
        val corrupted = JsonObject(stored + ("delivery" to JsonObject(stored.getValue("delivery").jsonObject - "phase")))
            .toString().toByteArray()
        val imported = object : PlayInstallJournal {
            override fun read() = PlayJournalCodec.decode(corrupted, testConfiguration, testNow)
            override fun write(state: PlayInstallState) = error("Corruption must not be rewritten as fresh recovery")
        }
        val statuses = mutableListOf<PlayStatus>()
        val restarted = PlayDeferredCoordinator(testConfiguration, imported,
            PlayReferrerSource { fail("Must not query Play after corrupted cancellation"); PlayReferrerResult.Missing },
            fixture.backend, { testNow }, route = { fail("Cancelled bearer cannot route"); PlayDisposition.DISCARDED },
            onStatus = { statuses += it })
        restarted.activate(); restarted.retry()
        assertEquals(1, fixture.backend.resolves)
        assertEquals(0, fixture.routes)
        assertTrue(statuses.all { it is PlayStatus.Suppressed })
    }

    @Test fun durableCancellationReturnsBeforeIndependentSlowCompletionAndRetriesSameOperation() = runBlocking {
        val fixture = CoordinatorFixture()
        fixture.router = { PlayDisposition.HOLD }
        val client = fixture.client(); client.activate()
        val release = CompletableDeferred<Unit>()
        fixture.backend.beforeAcknowledge = { release.await() }
        fixture.backend.ackFailure = true
        val navigationAvailable = CompletableDeferred<Unit>()
        val cancellation = launch { client.cancelForManualNavigation(); navigationAvailable.complete(Unit) }
        yield()
        try {
            assertTrue("A saved cancellation must not wait for HTTP", navigationAvailable.isCompleted)
            assertEquals(PlayPhase.DISCARDED, fixture.journal.state.delivery!!.phase)
            assertTrue(fixture.backend.acknowledgements.isEmpty())
        } finally { if (!navigationAvailable.isCompleted) release.complete(Unit) }
        cancellation.join()
        val first = fixture.journal.state.delivery!!.acknowledgement!!
        val completion = launch { client.flushCompletion() }
        yield()
        assertFalse(completion.isCompleted)
        assertTrue(navigationAvailable.isCompleted)
        release.complete(Unit); completion.join()
        assertEquals(first, fixture.journal.state.delivery!!.acknowledgement)
        fixture.backend.ackFailure = false
        client.flushCompletion()
        assertEquals(listOf(first, first), fixture.backend.acknowledgements)
        assertNull(fixture.journal.state.delivery!!.acknowledgement)
        assertTrue(fixture.applied.isEmpty())
    }
}

private class MemoryJournal : PlayInstallJournal {
    var state = PlayInstallState(scope = testScope, installationID = "60000000-0000-4000-8000-000000000001")
    var beforeWrite: (PlayInstallState) -> Unit = {}
    override fun read() = state
    override fun write(state: PlayInstallState) { beforeWrite(state); this.state = state }
}

private class FakeBackend : PlayBackend {
    var receipt = testReceipt()
    var resolves = 0
    var resolveFailure: PlayFailureReason? = null
    var ackFailure = false
    var beforeResolve: suspend () -> Unit = {}
    var beforeAcknowledge: suspend () -> Unit = {}
    val expectedReceipts = mutableListOf<PlayReceipt?>()
    val acknowledgements = mutableListOf<PlayAcknowledgement>()
    override suspend fun resolve(token: String, expected: PlayReceipt?): PlayReceipt {
        assertEquals(testToken, token)
        resolves++; expectedReceipts += expected
        beforeResolve()
        resolveFailure?.let { throw PlayFailure(it) }
        return receipt
    }
    override suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement) {
        assertEquals(testToken, token)
        acknowledgements += acknowledgement
        beforeAcknowledge()
        if (ackFailure) throw PlayFailure(PlayFailureReason.NETWORK)
    }
}

private class CoordinatorFixture {
    val journal = MemoryJournal()
    val backend = FakeBackend()
    var time = testNow
    var fetches = 0
    var routes = 0
    var sourceResult: PlayReferrerResult = PlayReferrerResult.Available(testReferrer)
    var router: (PlayLinkDelivery) -> PlayDisposition = { PlayDisposition.APPLIED }
    val applied = mutableListOf<PlayLinkDelivery>()
    val statuses = mutableListOf<PlayStatus>()
    val completionPending = mutableListOf<Boolean>()
    fun client(source: PlayReferrerSource = PlayReferrerSource { fetches++; sourceResult }) = PlayDeferredCoordinator(
        testConfiguration, journal, source, backend, { time }, route = {
            routes++
            val result = router(it)
            if (result == PlayDisposition.APPLIED) applied += it
            result
        }, onStatus = { statuses += it }, onCompletionPending = { completionPending += it })
}
