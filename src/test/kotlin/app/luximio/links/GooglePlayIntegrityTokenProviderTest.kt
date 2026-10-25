package app.luximio.links

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class GooglePlayIntegrityTokenProviderTest {
    @Test fun `canceling one waiter keeps the single warm-up available to other callers`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<PreparedPlayIntegrityProvider>()
        val warmups = AtomicInteger()
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine { number ->
            assertEquals(123L, number)
            warmups.incrementAndGet()
            started.complete(Unit)
            release.await()
        }, 123)
        try {
            val first = async { provider.prepare() }
            withTimeout(2_000) { started.await() }
            val second = async { provider.requestToken(HASH) }
            yield()
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            assertEquals(1, warmups.get())
            release.complete(PreparedPlayIntegrityProvider { hash -> assertEquals(HASH, hash); "native-proof" })
            assertEquals("native-proof", withTimeout(2_000) { second.await() })
            provider.prepare()
            assertEquals(1, warmups.get())
        } finally { provider.close() }
    }

    @Test fun `a late invalid-provider error cannot discard the replacement provider`() = runBlocking {
        val oldRequests = AtomicInteger()
        val warmups = AtomicInteger()
        val newRequests = AtomicInteger()
        val bothOldRequests = CompletableDeferred<Unit>()
        val failFirst = CompletableDeferred<Unit>()
        val failSecond = CompletableDeferred<Unit>()
        val replacementUsed = CompletableDeferred<Unit>()
        val old = PreparedPlayIntegrityProvider {
            val index = oldRequests.incrementAndGet()
            if (index == 2) bothOldRequests.complete(Unit)
            if (index == 1) failFirst.await() else failSecond.await()
            throw InvalidPlayIntegrityProvider()
        }
        val replacement = PreparedPlayIntegrityProvider {
            newRequests.incrementAndGet()
            replacementUsed.complete(Unit)
            "fresh-native-proof"
        }
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine {
            if (warmups.incrementAndGet() == 1) old else replacement
        }, 123)
        try {
            provider.prepare()
            val first = async { provider.requestToken(HASH) }
            val second = async { provider.requestToken(HASH) }
            withTimeout(2_000) { bothOldRequests.await() }
            failFirst.complete(Unit)
            withTimeout(2_000) { replacementUsed.await() }
            failSecond.complete(Unit)
            assertEquals("fresh-native-proof", withTimeout(2_000) { first.await() })
            assertEquals("fresh-native-proof", withTimeout(2_000) { second.await() })
            assertEquals(2, warmups.get())
            assertEquals(2, newRequests.get())
        } finally { provider.close() }
    }

    @Test fun `a second invalid-provider result stops without an unbounded refresh loop`() = runBlocking {
        val warmups = AtomicInteger()
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine {
            warmups.incrementAndGet()
            PreparedPlayIntegrityProvider { throw InvalidPlayIntegrityProvider() }
        }, 123)
        try {
            expectFailure(PlayFailureReason.NETWORK) { provider.requestToken(HASH) }
            assertEquals(2, warmups.get())
        } finally { provider.close() }
    }

    @Test fun `ordinary native failures are redacted and do not refresh the provider`() = runBlocking {
        val warmups = AtomicInteger()
        val requests = AtomicInteger()
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine {
            warmups.incrementAndGet()
            PreparedPlayIntegrityProvider {
                requests.incrementAndGet()
                throw IllegalStateException("sensitive device and request details")
            }
        }, 123)
        try {
            val failure = expectFailure(PlayFailureReason.NETWORK) { provider.requestToken(HASH) }
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("sensitive"))
            assertEquals(1, warmups.get())
            assertEquals(1, requests.get())
        } finally { provider.close() }
    }

    @Test fun `warm-up deadline releases its operation and permits a fresh later attempt`() = runBlocking {
        val warmups = AtomicInteger()
        val canceled = CompletableDeferred<Unit>()
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine {
            if (warmups.incrementAndGet() == 1) {
                try { awaitCancellation() } finally { canceled.complete(Unit) }
            }
            PreparedPlayIntegrityProvider { "native-proof" }
        }, 123, prepareTimeoutMillis = 50)
        try {
            withTimeout(2_000) { expectFailure(PlayFailureReason.NETWORK) { provider.prepare() } }
            withTimeout(2_000) { canceled.await() }
            assertEquals("native-proof", withTimeout(2_000) { provider.requestToken(HASH) })
            assertEquals(2, warmups.get())
        } finally { provider.close() }
    }

    @Test fun `token request deadline fails closed while retaining the valid warmed provider`() = runBlocking {
        val warmups = AtomicInteger()
        val requests = AtomicInteger()
        val canceled = CompletableDeferred<Unit>()
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine {
            warmups.incrementAndGet()
            PreparedPlayIntegrityProvider {
                if (requests.incrementAndGet() == 1) {
                    try { awaitCancellation() } finally { canceled.complete(Unit) }
                }
                "native-proof"
            }
        }, 123, requestTimeoutMillis = 50)
        try {
            withTimeout(2_000) { expectFailure(PlayFailureReason.NETWORK) { provider.requestToken(HASH) } }
            withTimeout(2_000) { canceled.await() }
            assertEquals("native-proof", provider.requestToken(HASH))
            assertEquals(1, warmups.get())
        } finally { provider.close() }
    }

    @Test fun `closing the provider cancels owned preparation and rejects subsequent work`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val provider = GooglePlayIntegrityTokenProvider(PlayIntegrityEngine {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }, 123)
        val waiting = async { provider.prepare() }
        withTimeout(2_000) { started.await() }
        provider.close()
        withTimeout(2_000) { stopped.await(); waiting.join() }
        assertTrue(waiting.isCancelled)
        expectFailure(PlayFailureReason.UNAVAILABLE) { provider.requestToken(HASH) }
        provider.close()
    }

    private suspend fun expectFailure(reason: PlayFailureReason, action: suspend () -> Unit): PlayFailure {
        try { action(); fail("Native proof failure was accepted") }
        catch (error: PlayFailure) { assertEquals(reason, error.reason); return error }
        error("Expected proof failure")
    }

    private companion object { val HASH = "A".repeat(43) }
}
