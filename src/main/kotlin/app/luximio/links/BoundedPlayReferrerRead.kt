package app.luximio.links

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A connection has a cancellable setup, one potentially blocking Binder read, and Main-thread cleanup. */
internal interface PlayReferrerConnection {
    /** null means ready to read; a value is an immediate provider outcome. */
    suspend fun connect(): PlayReferrerResult?
    fun read(): PlayReferrerResult
    fun close()
}

/**
 * Binder calls cannot reliably be interrupted. Bound the caller's wait without creating an
 * unbounded queue of abandoned reads: this source stays occupied until its one read actually exits.
 */
internal class BoundedPlayReferrerRead(
    private val mainDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
) {
    private val occupied = AtomicBoolean(false)
    private val reader = CoroutineScope(SupervisorJob() + ioDispatcher)

    suspend fun fetch(create: () -> PlayReferrerConnection): PlayReferrerResult {
        if (!occupied.compareAndSet(false, true)) return PlayReferrerResult.Retryable
        val readStarted = AtomicBoolean(false)
        val readFinished = AtomicBoolean(false)
        val callerFinished = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        fun releaseIfFinished() {
            if (callerFinished.get() && (!readStarted.get() || readFinished.get()) &&
                released.compareAndSet(false, true)) occupied.set(false)
        }
        try {
            return withContext(mainDispatcher) {
                var connection: PlayReferrerConnection? = null
                try {
                    withTimeout(timeoutMillis) {
                        val session = create().also { connection = it }
                        session.connect()?.let { return@withTimeout it }
                        // This detached read is deliberately not a child that withTimeout must join.
                        suspendCancellableCoroutine { continuation ->
                            readStarted.set(true)
                            reader.launch {
                                try {
                                    if (continuation.isActive) continuation.resumeWith(runCatching { session.read() })
                                } finally {
                                    readFinished.set(true)
                                    releaseIfFinished()
                                }
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    PlayReferrerResult.Retryable
                } finally {
                    withContext(NonCancellable + mainDispatcher) {
                        // Closing cannot interrupt an already executing Binder transaction, but unbinds promptly.
                        runCatching { connection?.close() }
                    }
                }
            }
        } finally {
            callerFinished.set(true)
            releaseIfFinished()
        }
    }
}
