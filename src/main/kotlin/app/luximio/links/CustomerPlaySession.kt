package app.luximio.links

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Pairs the customer protocol, install-referrer source and isolated no-backup
 * journal. Keep one session per configured installation. Coordinator calls and
 * navigation belong to the app's UI coroutine scope; cancel that scope before
 * closing the session. Closing never cancels or resets the durable handoff.
 */
class CustomerPlaySession private constructor(
    val configuration: CustomerPlayConfiguration,
    journal: PlayInstallJournal,
    source: PlayReferrerSource,
    backend: PlayBackend,
    clock: () -> Long,
    route: (PlayLinkDelivery) -> PlayDisposition,
    onStatus: (PlayStatus) -> Unit,
    onCompletionPending: (Boolean) -> Unit,
    private val ownedProof: Closeable?,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private fun checkOpen() { if (closed.get()) throw CancellationException("Customer recovery session closed") }

    val coordinator = PlayDeferredCoordinator(configuration, journal, PlayReferrerSource {
        checkOpen()
        source.fetch().also { checkOpen() }
    }, object : PlayBackend {
        override suspend fun resolve(token: String, expected: PlayReceipt?): PlayReceipt {
            checkOpen()
            return backend.resolve(token, expected).also { checkOpen() }
        }
        override suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement) {
            checkOpen()
            backend.acknowledge(token, acknowledgement)
            checkOpen()
        }
    }, clock, route = { if (closed.get()) PlayDisposition.HOLD else route(it) },
        onStatus = { if (!closed.get()) onStatus(it) },
        onCompletionPending = { if (!closed.get()) onCompletionPending(it) })

    override fun close() {
        if (closed.compareAndSet(false, true)) ownedProof?.close()
    }

    companion object {
        /** Injected proof/transport still face the real server's verification; neither grants native trust. */
        fun create(
            context: Context,
            configuration: CustomerPlayConfiguration,
            route: (PlayLinkDelivery) -> PlayDisposition,
            onStatus: (PlayStatus) -> Unit = {},
            onCompletionPending: (Boolean) -> Unit = {},
            proofProvider: PlayIntegrityTokenProvider? = null,
            transport: CustomerPlayTransport? = null,
        ): CustomerPlaySession {
            val app = context.applicationContext
            requirePlay(app.packageName == configuration.androidPackageName &&
                app.packageName == configuration.actualPackageName, PlayFailureReason.CONFIGURATION)
            val journal = NoBackupPlayJournal(app, configuration)
            val owned = if (proofProvider == null) GooglePlayIntegrityTokenProvider(app, configuration.cloudProjectNumber) else null
            return try {
                CustomerPlaySession(configuration, journal, PlayInstallReferrerSource(app),
                    CustomerPlayBackend(configuration, transport ?: HttpsCustomerPlayTransport(configuration.origin),
                        proofProvider ?: owned!!), System::currentTimeMillis, route, onStatus, onCompletionPending, owned)
            } catch (error: Exception) {
                owned?.close()
                if (error is PlayFailure) throw error
                throw PlayFailure(PlayFailureReason.CONFIGURATION)
            }
        }

        internal fun forTesting(
            configuration: CustomerPlayConfiguration,
            journal: PlayInstallJournal,
            source: PlayReferrerSource,
            backend: PlayBackend,
            clock: () -> Long,
            route: (PlayLinkDelivery) -> PlayDisposition,
            onStatus: (PlayStatus) -> Unit = {},
            onCompletionPending: (Boolean) -> Unit = {},
        ) = CustomerPlaySession(configuration, journal, source, backend, clock, route, onStatus, onCompletionPending, null)
    }
}
