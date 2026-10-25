package app.luximio.links

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PlayStatus {
    data object Idle : PlayStatus
    data object Checking : PlayStatus
    data object Held : PlayStatus
    data object NoReferrer : PlayStatus
    data object Unsupported : PlayStatus
    data class ProviderRejected(val reason: PlayReferrerRejection) : PlayStatus
    data class RetryAvailable(val reason: PlayFailureReason) : PlayStatus
    data class Rejected(val reason: PlayFailureReason) : PlayStatus
    data class UncertainApplication(val deliveryID: String) : PlayStatus
    data class Applied(val deliveryID: String) : PlayStatus
    data class Suppressed(val reason: PlayFailureReason) : PlayStatus
}

/**
 * One Play installation, one pending continuation. The customer owns readiness,
 * authorization and navigation; the synchronous router must check its current session.
 * Call from the app's UI coroutine context. No lock is held over provider or HTTP work.
 */
class PlayDeferredCoordinator(
    private val configuration: PlayRecoveryConfiguration,
    private val journal: PlayInstallJournal,
    private val source: PlayReferrerSource,
    private val backend: PlayBackend,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val route: (PlayLinkDelivery) -> PlayDisposition,
    private val onStatus: (PlayStatus) -> Unit = {},
    private val onCompletionPending: (Boolean) -> Unit = {},
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var running = false
    private var acknowledgementRunning = false
    private var retryRequired = false
    private var cancellationRequired = false
    private var expectedInstallationID: String? = null

    suspend fun activate() = resume(Trigger.ACTIVATION)
    suspend fun retry() = resume(Trigger.RETRY)
    suspend fun readinessChanged() = resume(Trigger.READINESS)
    suspend fun confirmUncertain(deliveryID: String) = resume(Trigger.CONFIRM, deliveryID)
    /** Completion sync is independent of customer navigation and never dispatches a route. */
    suspend fun flushCompletion() = syncCompletion()

    /** A deliberate newer action is saved before the app promises cancellation/navigation. */
    suspend fun cancelForManualNavigation() {
        mutex.withLock {
            generation++
            retryRequired = false
            cancellationRequired = true
            try {
                val state = load()
                val entry = state.delivery
                val next = if (entry == null) state.copy(acquisition = PlayAcquisition.SUPPRESSED)
                    else state.copy(delivery = terminal(entry, PlayPhase.DISCARDED))
                save(next)
                cancellationRequired = false
                onStatus(PlayStatus.Idle)
            } catch (error: Exception) {
                onStatus(PlayStatus.Suppressed(reason(error)))
                throw PlayFailure(reason(error))
            }
        }
    }

    /** Explicit diagnostic read. Does not purge, query Play, retry, or navigate. */
    suspend fun inspect(): PlayInstallState = mutex.withLock { load() }

    private enum class Trigger { ACTIVATION, READINESS, RETRY, CONFIRM }

    private suspend fun resume(trigger: Trigger, confirmedID: String? = null) {
        val work = mutex.withLock {
            try {
                var state = load()
                if (cancellationRequired) {
                    if (trigger != Trigger.RETRY) {
                        onStatus(PlayStatus.Suppressed(PlayFailureReason.STORAGE))
                        return@withLock null
                    }
                    val entry = state.delivery
                    save(if (entry == null) state.copy(acquisition = PlayAcquisition.SUPPRESSED)
                        else state.copy(delivery = terminal(entry, PlayPhase.DISCARDED)))
                    cancellationRequired = false
                    onStatus(PlayStatus.Idle)
                    return@withLock null
                }
                val expired = state.delivery?.let { it.deadline <= nowMillis() } == true
                if (expired) {
                    state = state.copy(delivery = null)
                    save(state) // Acquisition remains COMPLETE: expiry never re-queries Play.
                    onStatus(PlayStatus.Rejected(PlayFailureReason.EXPIRED))
                }
                if (running) return@withLock null
                var entry = state.delivery
                if (entry?.phase == PlayPhase.APPLYING) {
                    val id = entry.receipt!!.deliveryID
                    if (trigger != Trigger.CONFIRM || confirmedID != id) {
                        onStatus(PlayStatus.UncertainApplication(id))
                        return@withLock null
                    }
                    entry = entry.copy(phase = PlayPhase.PINNED)
                    state = state.copy(delivery = entry)
                    save(state)
                } else if (trigger == Trigger.CONFIRM) return@withLock null
                if (entry?.phase in setOf(PlayPhase.APPLIED, PlayPhase.DISCARDED)) {
                    onStatus(PlayStatus.Idle)
                    return@withLock null
                }
                if (entry?.phase == PlayPhase.HELD && trigger == Trigger.ACTIVATION) {
                    onStatus(PlayStatus.Held)
                    return@withLock null
                }
                if (retryRequired && trigger != Trigger.RETRY) return@withLock null
                if (entry == null && state.acquisition != PlayAcquisition.FRESH) {
                    if (!expired) onStatus(PlayStatus.Idle)
                    return@withLock null
                }
                retryRequired = false
                running = true
                onStatus(PlayStatus.Checking)
                Work(generation, state.installationID, entry)
            } catch (error: Exception) {
                onStatus(PlayStatus.Suppressed(reason(error)))
                null
            }
        }
        if (work == null) { syncCompletion(); return }
        try {
            val entry = work.entry ?: acquire(work) ?: return
            recover(work.copy(entry = entry))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutex.withLock {
                if (generation == work.generation) {
                    retryRequired = true
                    onStatus(PlayStatus.RetryAvailable(reason(error)))
                }
            }
        } finally {
            mutex.withLock { running = false }
        }
        syncCompletion()
    }

    private data class Work(val generation: Long, val installationID: String, val entry: PlayDeliveryRecord?)

    private suspend fun acquire(work: Work): PlayDeliveryRecord? {
        val result = source.fetch()
        return mutex.withLock {
            if (generation != work.generation) return@withLock null
            val state = load()
            requirePlay(state.installationID == work.installationID && state.acquisition == PlayAcquisition.FRESH &&
                state.delivery == null, PlayFailureReason.STORAGE)
            when (result) {
                is PlayReferrerResult.Available -> {
                    if (!Regex("(^|&)lx_").containsMatchIn(result.referrer)) {
                        save(state.copy(acquisition = PlayAcquisition.COMPLETE))
                        onStatus(PlayStatus.NoReferrer)
                        return@withLock null
                    }
                    val envelope = try { PlayEnvelope.parse(result.referrer) }
                    catch (_: PlayFailure) {
                        save(state.copy(acquisition = PlayAcquisition.COMPLETE))
                        onStatus(PlayStatus.Rejected(PlayFailureReason.INVALID_DATA))
                        return@withLock null
                    }
                    val capturedAt = nowMillis()
                    val entry = PlayDeliveryRecord(envelope.token, capturedAt,
                        capturedAt + PLAY_CONTINUATION_LIFETIME_MILLIS)
                    save(state.copy(acquisition = PlayAcquisition.COMPLETE, delivery = entry))
                    entry // Durable bearer first; no resolve is possible before this write succeeds.
                }
                PlayReferrerResult.Retryable -> {
                    retryRequired = true
                    onStatus(PlayStatus.RetryAvailable(PlayFailureReason.NETWORK))
                    null
                }
                PlayReferrerResult.Missing, PlayReferrerResult.Unsupported, is PlayReferrerResult.Rejected -> {
                    save(state.copy(acquisition = PlayAcquisition.COMPLETE))
                    onStatus(when (result) {
                        PlayReferrerResult.Missing -> PlayStatus.NoReferrer
                        PlayReferrerResult.Unsupported -> PlayStatus.Unsupported
                        is PlayReferrerResult.Rejected -> PlayStatus.ProviderRejected(result.reason)
                        else -> error("Unreachable provider result")
                    })
                    null
                }
            }
        }
    }

    private suspend fun recover(work: Work) {
        val entry = work.entry!!
        val receipt = try {
            backend.resolve(entry.token, entry.receipt).also {
                it.validate(configuration, nowMillis())
                requirePlay(entry.receipt == null || entry.receipt == it, PlayFailureReason.CHANGED_DESTINATION)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutex.withLock {
                if (!isCurrent(work)) return@withLock
                val why = reason(error)
                // A customer challenge may expire in flight or be purged before
                // verification. Rejection grants no native trust: retain the
                // same bounded handoff, requiring explicit retry with fresh proof.
                val retryNativeProof = configuration is CustomerPlayConfiguration && why == PlayFailureReason.UNVERIFIED
                if (retryNativeProof || why in setOf(PlayFailureReason.NETWORK, PlayFailureReason.BUSY, PlayFailureReason.STORAGE)) {
                    retryRequired = true
                    onStatus(PlayStatus.RetryAvailable(why))
                } else {
                    val state = load()
                    save(state.copy(delivery = terminal(state.delivery!!, PlayPhase.DISCARDED)))
                    onStatus(PlayStatus.Rejected(why))
                }
            }
            return
        }
        mutex.withLock {
            if (!isCurrent(work)) return@withLock
            var state = load()
            var current = state.delivery!!
            if (current.deadline <= nowMillis()) {
                save(state.copy(delivery = terminal(current, PlayPhase.DISCARDED)))
                onStatus(PlayStatus.Rejected(PlayFailureReason.EXPIRED))
                return@withLock
            }
            if (current.receipt == null) {
                current = current.copy(receipt = receipt, phase = PlayPhase.PINNED)
                state = state.copy(delivery = current)
                save(state) // Pin before any application callback or navigation.
            }
            if (current.deadline <= nowMillis()) {
                save(state.copy(delivery = terminal(current, PlayPhase.DISCARDED)))
                onStatus(PlayStatus.Rejected(PlayFailureReason.EXPIRED))
                return@withLock
            }
            current = current.copy(phase = PlayPhase.APPLYING)
            state = state.copy(delivery = current)
            save(state)
            try {
                if (current.deadline <= nowMillis()) {
                    save(state.copy(delivery = terminal(current, PlayPhase.DISCARDED)))
                    onStatus(PlayStatus.Rejected(PlayFailureReason.EXPIRED))
                    return@withLock
                }
                // No suspension between durable APPLYING and this current-session callback.
                val disposition = route(PlayLinkDelivery(receipt.deliveryID, receipt.destination, receipt.invocation.originalURL))
                val next = when (disposition) {
                    PlayDisposition.HOLD -> current.copy(phase = PlayPhase.HELD)
                    PlayDisposition.APPLIED -> terminal(current, PlayPhase.APPLIED)
                    PlayDisposition.DISCARDED -> terminal(current, PlayPhase.DISCARDED)
                }
                save(state.copy(delivery = next))
                onStatus(when (disposition) {
                    PlayDisposition.HOLD -> PlayStatus.Held
                    PlayDisposition.APPLIED -> PlayStatus.Applied(receipt.deliveryID)
                    PlayDisposition.DISCARDED -> PlayStatus.Rejected(PlayFailureReason.UNAVAILABLE)
                })
            } catch (error: Exception) {
                // Router/terminal-save failure leaves APPLYING durable, requiring explicit confirmation.
                onStatus(PlayStatus.UncertainApplication(receipt.deliveryID))
            }
        }
    }

    private suspend fun syncCompletion() {
        val pending = mutex.withLock {
            if (acknowledgementRunning) return@withLock null
            try {
                val state = load()
                val entry = state.delivery ?: return@withLock null
                val acknowledgement = entry.acknowledgement ?: run { onCompletionPending(false); return@withLock null }
                if (entry.deadline <= nowMillis()) return@withLock null
                acknowledgementRunning = true
                Triple(state.installationID, entry.token, acknowledgement)
            } catch (_: Exception) { onCompletionPending(true); null }
        } ?: return
        try {
            backend.acknowledge(pending.second, pending.third)
            mutex.withLock {
                val state = load()
                if (state.installationID == pending.first && state.delivery?.acknowledgement == pending.third) {
                    save(state.copy(delivery = state.delivery.copy(acknowledgement = null)))
                    onCompletionPending(false)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { onCompletionPending(true) }
        finally { mutex.withLock { acknowledgementRunning = false } }
    }

    private fun terminal(entry: PlayDeliveryRecord, phase: PlayPhase): PlayDeliveryRecord {
        if (entry.phase in setOf(PlayPhase.APPLIED, PlayPhase.DISCARDED)) return entry
        val receipt = entry.receipt
        val acknowledgement = receipt?.let {
            PlayAcknowledgement(it.id, it.deliveryID, UUID.randomUUID().toString(),
                if (phase == PlayPhase.APPLIED) PlayOutcome.applied else PlayOutcome.discarded)
        }
        return entry.copy(phase = phase, acknowledgement = acknowledgement)
    }

    private fun isCurrent(work: Work): Boolean {
        if (generation != work.generation) return false
        val state = load()
        return state.installationID == work.installationID && state.delivery == work.entry &&
            state.delivery?.phase in setOf(PlayPhase.RETRIEVED, PlayPhase.PINNED, PlayPhase.HELD)
    }

    private fun load(): PlayInstallState {
        val state = journal.read()
        state.validate(configuration, nowMillis())
        requirePlay(expectedInstallationID == null || expectedInstallationID == state.installationID,
            PlayFailureReason.STORAGE)
        expectedInstallationID = state.installationID
        return state
    }

    private fun save(state: PlayInstallState) {
        state.validate(configuration, nowMillis())
        try { journal.write(state) }
        catch (_: Exception) { throw PlayFailure(PlayFailureReason.STORAGE) }
    }

    private fun reason(error: Exception): PlayFailureReason = (error as? PlayFailure)?.reason ?: PlayFailureReason.STORAGE
}
