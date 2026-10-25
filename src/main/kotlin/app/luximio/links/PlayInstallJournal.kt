package app.luximio.links

import kotlinx.serialization.Serializable

/** COMPLETE survives receipt/token erasure. Never query Play again for this installation. */
@Serializable
enum class PlayAcquisition { FRESH, COMPLETE, SUPPRESSED }

@Serializable
enum class PlayPhase { RETRIEVED, PINNED, HELD, APPLYING, APPLIED, DISCARDED }

@Serializable
data class PlayDeliveryRecord(
    val token: String,
    val retrievedAt: Long,
    val unresolvedDeadline: Long,
    val phase: PlayPhase = PlayPhase.RETRIEVED,
    val receipt: PlayReceipt? = null,
    val acknowledgement: PlayAcknowledgement? = null,
) {
    val deadline: Long get() = receipt?.expiresAt?.let { minOf(it, unresolvedDeadline) } ?: unresolvedDeadline
    override fun toString() = "PlayDeliveryRecord(phase=$phase, context=<redacted>)"
}

@Serializable
data class PlayInstallState(
    val schemaVersion: Int = 1,
    val scope: PlayScope,
    val installationID: String,
    val acquisition: PlayAcquisition = PlayAcquisition.FRESH,
    val delivery: PlayDeliveryRecord? = null,
) {
    /** Structural validation does not purge or renew a deadline. */
    fun validate(configuration: PlayRecoveryConfiguration, nowMillis: Long) {
        requirePlay(schemaVersion == 1 && scope == configuration.scope, PlayFailureReason.WRONG_SCOPE)
        validatePlayUUID(installationID)
        requirePlay(acquisition == PlayAcquisition.COMPLETE || delivery == null, PlayFailureReason.INVALID_DATA)
        val entry = delivery ?: return
        PlayEnvelope.validateToken(entry.token)
        requirePlay(entry.retrievedAt >= 0 && entry.retrievedAt <= nowMillis + 300_000 &&
            entry.unresolvedDeadline > entry.retrievedAt &&
            entry.unresolvedDeadline - entry.retrievedAt == PLAY_CONTINUATION_LIFETIME_MILLIS,
            PlayFailureReason.INVALID_DATA)
        val receipt = entry.receipt
        if (receipt != null) {
            // An expired receipt may still be loaded only so the coordinator can erase its context.
            try { receipt.validate(configuration, nowMillis) }
            catch (failure: PlayFailure) { if (failure.reason != PlayFailureReason.EXPIRED) throw failure }
        }
        requirePlay(when (entry.phase) {
            PlayPhase.RETRIEVED -> receipt == null && entry.acknowledgement == null
            PlayPhase.PINNED, PlayPhase.HELD, PlayPhase.APPLYING -> receipt != null && entry.acknowledgement == null
            PlayPhase.APPLIED -> receipt != null
            PlayPhase.DISCARDED -> true
        }, PlayFailureReason.INVALID_DATA)
        entry.acknowledgement?.let { acknowledgement ->
            validatePlayUUID(acknowledgement.operationID)
            requirePlay(receipt != null && acknowledgement.receiptID == receipt.id &&
                acknowledgement.deliveryID == receipt.deliveryID &&
                ((entry.phase == PlayPhase.APPLIED && acknowledgement.outcome == PlayOutcome.applied) ||
                    (entry.phase == PlayPhase.DISCARDED && acknowledgement.outcome == PlayOutcome.discarded)),
                PlayFailureReason.INVALID_DATA)
        }
    }
    override fun toString() = "PlayInstallState(acquisition=$acquisition, phase=${delivery?.phase}, context=<redacted>)"
}

/** Implementations atomically persist one immutable snapshot in backup-excluded install storage. */
interface PlayInstallJournal {
    fun read(): PlayInstallState
    fun write(state: PlayInstallState)
}
