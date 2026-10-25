package app.luximio.links

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Standard Play Integrity for one Cloud project. Its matching server account must be
 * authorized to decode the token. Close this instance when its owning SDK is closed.
 */
class GooglePlayIntegrityTokenProvider internal constructor(
    private val engine: PlayIntegrityEngine,
    private val cloudProjectNumber: Long,
    private val prepareTimeoutMillis: Long = 60_000,
    private val requestTimeoutMillis: Long = 20_000,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PlayIntegrityTokenProvider, Closeable {
    constructor(context: Context, cloudProjectNumber: Long) : this(
        AndroidPlayIntegrityEngine(context.applicationContext), cloudProjectNumber,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var closed = false
    private var provider: PreparedPlayIntegrityProvider? = null
    private var preparing: Deferred<PreparedPlayIntegrityProvider>? = null

    init {
        requirePlay(cloudProjectNumber > 0 && prepareTimeoutMillis > 0 && requestTimeoutMillis > 0,
            PlayFailureReason.CONFIGURATION)
    }

    /** Optional early warm-up; a canceled waiter does not discard shared preparation. */
    suspend fun prepare() {
        redacted { getProvider() }
    }

    override suspend fun requestToken(requestHash: String): String = redacted {
        requirePlay(requestHash.isNotEmpty() && requestHash.toByteArray(Charsets.UTF_8).size < 500 &&
            requestHash.none { it.isISOControl() }, PlayFailureReason.INVALID_DATA)
        val activeProvider = getProvider()
        try {
            request(activeProvider, requestHash)
        } catch (_: InvalidPlayIntegrityProvider) {
            synchronized(lock) {
                // A late failure from the old provider cannot discard a newer one.
                if (provider === activeProvider) provider = null
            }
            request(getProvider(), requestHash)
        }
    }

    private suspend fun getProvider(): PreparedPlayIntegrityProvider {
        val pending = synchronized(lock) {
            requirePlay(!closed, PlayFailureReason.UNAVAILABLE)
            provider?.let { return it }
            preparing ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    withTimeout(prepareTimeoutMillis) { engine.prepare(cloudProjectNumber) }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw PlayFailure(PlayFailureReason.NETWORK)
                }
            }.also { operation ->
                preparing = operation
                operation.invokeOnCompletion { error ->
                    if (error != null) synchronized(lock) {
                        if (preparing === operation) preparing = null
                    }
                }
            }
        }
        pending.start()
        val ready = pending.await()
        return synchronized(lock) {
            requirePlay(!closed, PlayFailureReason.UNAVAILABLE)
            if (preparing === pending) {
                provider = ready
                preparing = null
            }
            provider ?: ready
        }
    }

    private suspend fun request(activeProvider: PreparedPlayIntegrityProvider, requestHash: String): String {
        val token = try {
            withTimeout(requestTimeoutMillis) { activeProvider.requestToken(requestHash) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw PlayFailure(PlayFailureReason.NETWORK)
        }
        requirePlay(token.isNotEmpty() && token.length <= 16 * 1_024 && token.none { it.isWhitespace() || it.isISOControl() },
            PlayFailureReason.NETWORK)
        return token
    }

    private suspend fun <T> redacted(operation: suspend () -> T): T = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: PlayFailure) {
        throw error
    } catch (_: Exception) {
        // Google errors can carry request or device details. Keep them out of errors and logs.
        throw PlayFailure(PlayFailureReason.NETWORK)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            provider = null
            preparing = null
        }
        scope.cancel()
    }
}

internal fun interface PlayIntegrityEngine {
    suspend fun prepare(cloudProjectNumber: Long): PreparedPlayIntegrityProvider
}

internal fun interface PreparedPlayIntegrityProvider {
    suspend fun requestToken(requestHash: String): String
}

internal class InvalidPlayIntegrityProvider : Exception("Play Integrity provider invalid")

private class AndroidPlayIntegrityEngine(context: Context) : PlayIntegrityEngine {
    private val manager = IntegrityManagerFactory.createStandard(context)
    private val lock = Any()
    private var preparing: Task<StandardIntegrityManager.StandardIntegrityTokenProvider>? = null

    override suspend fun prepare(cloudProjectNumber: Long): PreparedPlayIntegrityProvider {
        val operation = synchronized(lock) {
            preparing ?: manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build(),
            ).also { preparing = it }
        }
        val provider = try {
            operation.await()
        } catch (error: CancellationException) {
            // Google Tasks cannot be canceled here. A timed-out waiter must not start
            // another native warm-up while this one is still pending.
            throw error
        } catch (error: Exception) {
            synchronized(lock) { if (preparing === operation) preparing = null }
            throw error
        }
        synchronized(lock) { if (preparing === operation) preparing = null }
        return PreparedPlayIntegrityProvider { requestHash ->
            try {
                provider.request(
                    StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build(),
                ).await().token()
            } catch (error: StandardIntegrityException) {
                if (error.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                    throw InvalidPlayIntegrityProvider()
                }
                throw error
            }
        }
    }
}
