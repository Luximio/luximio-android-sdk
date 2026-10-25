package app.luximio.links

import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val PLAY_CONTINUATION_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1_000

@Serializable
data class PlayScope(
    val projectID: String,
    val androidPackageName: String,
    val channel: String = "googlePlay",
) {
    fun validate() {
        requirePlay(channel == "googlePlay" && projectID.matches(Regex("[A-Za-z0-9_-]{1,200}")) &&
            androidPackageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")),
            PlayFailureReason.CONFIGURATION)
    }
}

/** Approved runtime scopes for the shared durable coordinator, not a wire-protocol selector. */
sealed interface PlayRecoveryConfiguration {
    val scope: PlayScope
    val origin: String
    val actualPackageName: String
    fun validateURL(value: String)
}

data class PlayConfiguration(
    override val scope: PlayScope,
    override val origin: String,
    override val actualPackageName: String,
) : PlayRecoveryConfiguration {
    init {
        scope.validate()
        requirePlay(scope.androidPackageName == actualPackageName &&
            origin == "https://sdk-ticketdesk-android.luximio.app", PlayFailureReason.CONFIGURATION)
    }

    override fun validateURL(value: String) {
        requirePlay(value.toByteArray(Charsets.UTF_8).size <= 8_192 && value.none { it.isISOControl() },
            PlayFailureReason.INVALID_DATA)
        val uri = runCatching { URI(value) }.getOrElse { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
        requirePlay(uri.scheme == "https" && uri.rawAuthority == URI(origin).rawAuthority &&
            uri.userInfo == null && uri.port == -1 && uri.fragment == null && !uri.path.isNullOrEmpty() &&
            uri.rawPath != "/" && uri.normalize() == uri, PlayFailureReason.WRONG_SCOPE)
    }
}

/** A bearer recovered through Play, never an identity or authorization grant. */
class PlayEnvelope private constructor(val token: String) {
    override fun toString() = "PlayEnvelope(<redacted>)"

    companion object {
        fun parse(referrer: String): PlayEnvelope {
            requirePlay(referrer.toByteArray(Charsets.UTF_8).size <= 512, PlayFailureReason.INVALID_DATA)
            val match = Regex("lx_v=1&lx_token=([A-Za-z0-9_-]{43})").matchEntire(referrer)
                ?: throw PlayFailure(PlayFailureReason.INVALID_DATA)
            val token = match.groupValues[1]
            validateToken(token)
            return PlayEnvelope(token)
        }

        fun validateToken(token: String) {
            requirePlay(token.matches(Regex("[A-Za-z0-9_-]{43}")), PlayFailureReason.INVALID_DATA)
            val bytes = runCatching { Base64.getUrlDecoder().decode(token + "=") }
                .getOrElse { throw PlayFailure(PlayFailureReason.INVALID_DATA) }
            requirePlay(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == token,
                PlayFailureReason.INVALID_DATA)
        }
    }
}

sealed interface PlayReferrerResult {
    data class Available(
        val referrer: String,
        val clickTimestampSeconds: Long? = null,
        val installBeginTimestampSeconds: Long? = null,
    ) : PlayReferrerResult {
        override fun toString() = "Available(referrer=<redacted>)"
    }
    data object Retryable : PlayReferrerResult
    data object Unsupported : PlayReferrerResult
    data object Missing : PlayReferrerResult
    data class Rejected(val reason: PlayReferrerRejection) : PlayReferrerResult
}

enum class PlayReferrerRejection { PERMISSION_DENIED, DEVELOPER_ERROR, UNKNOWN_RESPONSE, INVALID }

fun interface PlayReferrerSource { suspend fun fetch(): PlayReferrerResult }

interface PlayBackend {
    suspend fun resolve(token: String, expected: PlayReceipt? = null): PlayReceipt
    suspend fun acknowledge(token: String, acknowledgement: PlayAcknowledgement)
}

@Serializable
data class PlayInvocation(
    val id: String,
    val operationID: String,
    val originalURL: String,
    val capturedAt: Long,
    val expiresAt: Long,
    val scope: PlayScope,
)

@Serializable
data class PlayDestination(val route: String, val data: JsonObject)

@Serializable
data class PlayReceipt(
    val id: String,
    val deliveryID: String,
    val invocation: PlayInvocation,
    val canonicalLinkID: String,
    val destination: PlayDestination,
    val expiresAt: Long,
) {
    fun validate(configuration: PlayRecoveryConfiguration, nowMillis: Long) {
        requirePlay(invocation.scope == configuration.scope, PlayFailureReason.WRONG_SCOPE)
        listOf(id, deliveryID, invocation.id, invocation.operationID).forEach(::validatePlayUUID)
        configuration.validateURL(invocation.originalURL)
        val maximumNow = if (configuration is CustomerPlayConfiguration) 9_007_199_254_740_991 else 9_007_199_254_440_991
        val futureCaptureBound = minOf(nowMillis, 9_007_199_254_440_991) + 300_000
        requirePlay(nowMillis in 0..maximumNow && invocation.capturedAt >= 0 && invocation.capturedAt <= futureCaptureBound &&
            invocation.expiresAt > invocation.capturedAt &&
            invocation.expiresAt - invocation.capturedAt <= PLAY_CONTINUATION_LIFETIME_MILLIS &&
            expiresAt > invocation.capturedAt && expiresAt <= invocation.expiresAt &&
            invocation.expiresAt <= 9_007_199_254_740_991, PlayFailureReason.INVALID_DATA)
        when (configuration) {
            is PlayConfiguration -> requirePlay(canonicalLinkID.matches(Regex("[A-Za-z0-9_-]{1,1024}")) &&
                destination.route.matches(Regex("[A-Za-z0-9][A-Za-z0-9/_-]{0,199}")) &&
                destination.data.toString().toByteArray(Charsets.UTF_8).size <= 4_096,
                PlayFailureReason.INVALID_DATA)
            is CustomerPlayConfiguration -> configuration.validateDestination(canonicalLinkID, destination)
        }
        requirePlay(expiresAt > nowMillis, PlayFailureReason.EXPIRED)
    }

    override fun toString() = "PlayReceipt(id=$id, deliveryID=$deliveryID, context=<redacted>)"
}

@Serializable
enum class PlayOutcome { applied, discarded }

@Serializable
data class PlayAcknowledgement(
    val receiptID: String,
    val deliveryID: String,
    val operationID: String,
    val outcome: PlayOutcome,
)

enum class PlayDisposition { HOLD, APPLIED, DISCARDED }
data class PlayLinkDelivery(val id: String, val destination: PlayDestination, val originalURL: String)

enum class PlayFailureReason {
    NETWORK, BUSY, STORAGE, INVALID_DATA, WRONG_SCOPE, EXPIRED, CHANGED_DESTINATION,
    UNAVAILABLE, COMPLETED, CONFIGURATION, UNVERIFIED, CONFLICT,
}
class PlayFailure(val reason: PlayFailureReason) : Exception("Play continuation: $reason")

internal fun requirePlay(condition: Boolean, reason: PlayFailureReason) {
    if (!condition) throw PlayFailure(reason)
}

internal fun validatePlayUUID(value: String) {
    requirePlay(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) &&
        runCatching { UUID.fromString(value).toString() == value }
        .getOrDefault(false), PlayFailureReason.INVALID_DATA)
}
