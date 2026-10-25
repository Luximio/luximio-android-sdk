package app.luximio.links

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeAdaptersTest {
    @Test fun `hung Binder read times out closes on Main and blocks every extra client until it returns`() = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "referrer-main-test") }.asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { Thread(it, "referrer-io-test") }.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val creations = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        try {
            val source = BoundedPlayReferrerRead(main, io, 500)
            val first = async {
                source.fetch {
                    creations.incrementAndGet()
                    object : PlayReferrerConnection {
                        override suspend fun connect(): PlayReferrerResult? {
                            assertTrue(Thread.currentThread().name.startsWith("referrer-main-test"))
                            return null
                        }
                        override fun read(): PlayReferrerResult {
                            assertTrue(Thread.currentThread().name.startsWith("referrer-io-test"))
                            reads.incrementAndGet()
                            entered.countDown()
                            release.await()
                            return PlayReferrerResult.Available("lx_v=1&lx_token=${"A".repeat(43)}")
                        }
                        override fun close() {
                            assertTrue(Thread.currentThread().name.startsWith("referrer-main-test"))
                            closes.incrementAndGet()
                        }
                    }
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            assertEquals(PlayReferrerResult.Retryable, withTimeout(2_000) { first.await() })
            assertEquals(1, closes.get()) // Cleanup happens while the fake Binder is still blocked.
            repeat(20) {
                assertEquals(PlayReferrerResult.Retryable, source.fetch { creations.incrementAndGet(); missingConnection() })
            }
            assertEquals(1, creations.get())
            assertEquals(1, reads.get())
            release.countDown()
            val next = withTimeout(2_000) {
                var result: PlayReferrerResult
                do { delay(10); result = source.fetch { creations.incrementAndGet(); missingConnection() } }
                while (result == PlayReferrerResult.Retryable)
                result
            }
            assertEquals(PlayReferrerResult.Missing, next)
            assertEquals(2, creations.get())
            assertEquals(PlayReferrerResult.Retryable, first.await()) // Late data did not replace the timeout.
        } finally { release.countDown(); main.close(); io.close() }
    }

    @Test fun `caller cancellation closes immediately and cannot launch another abandoned Binder read`() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val creations = AtomicInteger()
        val closes = AtomicInteger()
        try {
            val source = BoundedPlayReferrerRead(main, io, 60_000)
            val first = async {
                source.fetch {
                    creations.incrementAndGet()
                    object : PlayReferrerConnection {
                        override suspend fun connect(): PlayReferrerResult? = null
                        override fun read(): PlayReferrerResult {
                            entered.countDown(); release.await(); return PlayReferrerResult.Missing
                        }
                        override fun close() { closes.incrementAndGet() }
                    }
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            first.cancel()
            withTimeout(2_000) { first.join() }
            assertTrue(first.isCancelled)
            assertEquals(1, closes.get())
            repeat(20) { assertEquals(PlayReferrerResult.Retryable, source.fetch { creations.incrementAndGet(); missingConnection() }) }
            assertEquals(1, creations.get())
        } finally { release.countDown(); main.close(); io.close() }
    }

    @Test fun `connection timeout without a Binder read releases the source for a fresh client`() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var closed = false
        try {
            val source = BoundedPlayReferrerRead(main, io, 50)
            val result = withTimeout(2_000) {
                source.fetch {
                    object : PlayReferrerConnection {
                        override suspend fun connect(): PlayReferrerResult? = suspendCancellableCoroutine { }
                        override fun read(): PlayReferrerResult = error("Binding never completed")
                        override fun close() { closed = true }
                    }
                }
            }
            assertEquals(PlayReferrerResult.Retryable, result)
            assertTrue(closed)
            assertEquals(PlayReferrerResult.Missing, source.fetch { missingConnection() })
        } finally { main.close(); io.close() }
    }

    @Test fun `successful persistence syncs verifies the rename and syncs the directory before returning`() {
        val bytes = "a complete installation snapshot".toByteArray()
        val operations = FakeAtomicWrite()
        VerifiedPlayPersistence.write(bytes, operations)
        assertArrayEquals(bytes, operations.committed)
        assertEquals(listOf("start", "sync", "finish", "pending", "read", "directory"), operations.events)
    }

    @Test fun `failed fd sync is STORAGE and does not finish or promise the snapshot`() {
        val operations = FakeAtomicWrite(failAt = "sync")
        expectStorage { VerifiedPlayPersistence.write(byteArrayOf(1), operations) }
        assertEquals(listOf("start", "sync", "rollback"), operations.events)
        assertNull(operations.committed)
    }

    @Test fun `finish throwing and silent pending rename failures never acknowledge a write`() {
        val throwing = FakeAtomicWrite(failAt = "finish")
        expectStorage { VerifiedPlayPersistence.write(byteArrayOf(1), throwing) }
        assertTrue(throwing.events.contains("rollback"))
        val silent = FakeAtomicWrite(leavePending = true)
        expectStorage { VerifiedPlayPersistence.write(byteArrayOf(1), silent) }
        assertFalse(silent.events.contains("directory"))
    }

    @Test fun `stale committed bytes and readback errors never acknowledge a write`() {
        val stale = FakeAtomicWrite(staleReadback = true)
        expectStorage { VerifiedPlayPersistence.write(byteArrayOf(1), stale) }
        assertFalse(stale.events.contains("directory"))
        expectStorage { VerifiedPlayPersistence.write(byteArrayOf(1), FakeAtomicWrite(failAt = "read")) }
    }

    @Test fun `failed directory fsync is STORAGE even after the renamed bytes can be read`() {
        val operations = FakeAtomicWrite(failAt = "directory")
        expectStorage { VerifiedPlayPersistence.write(byteArrayOf(1), operations) }
        assertArrayEquals(byteArrayOf(1), operations.committed)
        assertFalse(operations.events.contains("rollback")) // Already-renamed bytes must not be silently deleted.
    }

    private fun missingConnection() = object : PlayReferrerConnection {
        override suspend fun connect(): PlayReferrerResult? = null
        override fun read(): PlayReferrerResult = PlayReferrerResult.Missing
        override fun close() = Unit
    }

    private fun expectStorage(action: () -> Unit) {
        try { action(); fail("Persistence failure was incorrectly acknowledged") }
        catch (error: PlayFailure) { assertEquals(PlayFailureReason.STORAGE, error.reason) }
    }

    private class FakeAtomicWrite(
        private val failAt: String? = null,
        private val leavePending: Boolean = false,
        private val staleReadback: Boolean = false,
    ) : PlayAtomicWrite {
        val events = mutableListOf<String>()
        var committed: ByteArray? = null
        private val staging = ByteArrayOutputStream()
        private fun step(name: String) { events += name; if (failAt == name) throw IOException("Synthetic persistence failure") }
        override fun start(): OutputStream { step("start"); return staging }
        override fun sync(stream: OutputStream) = step("sync")
        override fun finish(stream: OutputStream) { step("finish"); committed = staging.toByteArray(); stream.close() }
        override fun hasPendingFile(): Boolean { step("pending"); return leavePending }
        override fun readCommitted(maximumBytes: Int): ByteArray { step("read"); return if (staleReadback) byteArrayOf(9) else committed!! }
        override fun syncDirectory() = step("directory")
        override fun rollback(stream: OutputStream) { step("rollback"); stream.close() }
    }
}
