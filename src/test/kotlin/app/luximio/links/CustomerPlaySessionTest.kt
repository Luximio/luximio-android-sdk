package app.luximio.links

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CustomerPlaySessionTest {
    private val configuration = CustomerPlayConfiguration("project_customer", "https://links.example.com",
        "example.customer.app", "example.customer.app", 123456789012L)

    private fun receipt() = testReceipt().let { it.copy(invocation = it.invocation.copy(
        scope = configuration.scope, originalURL = configuration.origin + "/events/aurora?future_context=survives")) }

    private class Journal(var state: PlayInstallState) : PlayInstallJournal {
        override fun read() = state
        override fun write(state: PlayInstallState) { this.state = state }
    }
    private fun journal() = Journal(PlayInstallState(scope = configuration.scope,
        installationID = "60000000-0000-4000-8000-000000000001"))

    @Test fun holdsRecoverAcrossNewSessionAndCompletedActionDoesNotReplay() = runBlocking {
        val journal = journal()
        var referrerCalls = 0
        var ready = false
        var routes = 0
        var acknowledgements = 0
        val source = PlayReferrerSource { referrerCalls++; PlayReferrerResult.Available(testReferrer) }
        val backend = object : PlayBackend {
            override suspend fun resolve(token: String, expected: PlayReceipt?) = receipt()
            override suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement) { acknowledgements++ }
        }
        fun newSession() = CustomerPlaySession.forTesting(configuration, journal, source, backend, { testNow },
            route = { if (ready) { routes++; PlayDisposition.APPLIED } else PlayDisposition.HOLD })
        val initial = newSession()
        initial.coordinator.activate()
        assertEquals(PlayPhase.HELD, journal.state.delivery?.phase)
        initial.close()
        val restored = newSession()
        restored.coordinator.activate()
        assertEquals(0, routes)
        ready = true
        restored.coordinator.readinessChanged()
        assertEquals(1, routes)
        assertEquals(1, acknowledgements)
        assertNull(journal.state.delivery?.acknowledgement)
        restored.close()
        newSession().coordinator.activate()
        assertEquals(1, routes)
        assertEquals(1, referrerCalls)
    }

    @Test fun closingDuringResponsePreventsRoutingAndRetainsSameInstallationWork() = runBlocking {
        val journal = journal()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<PlayReceipt>()
        var routes = 0
        var sourceCalls = 0
        val source = PlayReferrerSource { sourceCalls++; PlayReferrerResult.Available(testReferrer) }
        val backend = object : PlayBackend {
            override suspend fun resolve(token: String, expected: PlayReceipt?): PlayReceipt {
                started.complete(Unit)
                return response.await()
            }
            override suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement) = Unit
        }
        val old = CustomerPlaySession.forTesting(configuration, journal, source, backend, { testNow },
            route = { routes++; PlayDisposition.APPLIED })
        val work = async { old.coordinator.activate() }
        started.await()
        val deadline = journal.state.delivery!!.deadline
        old.close()
        response.complete(receipt())
        try { work.await(); fail("Closed session routed") } catch (_: CancellationException) { }
        assertEquals(0, routes)
        assertEquals(PlayPhase.RETRIEVED, journal.state.delivery?.phase)
        assertEquals(deadline, journal.state.delivery?.deadline)
        val current = CustomerPlaySession.forTesting(configuration, journal, source, backend, { testNow },
            route = { routes++; PlayDisposition.APPLIED })
        current.coordinator.activate()
        assertEquals(1, routes)
        assertEquals(1, sourceCalls)
        assertEquals(deadline, journal.state.delivery?.deadline)
    }
}
