package app.luximio.links

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Explicit customer protocol configuration. The project number is public, not
 * a credential; matching server-side Google authorization is also required.
 * None of these values proves app identity or authorizes a private destination.
 */
class CustomerPlayConfiguration(
    val projectID: String,
    override val origin: String,
    val androidPackageName: String,
    override val actualPackageName: String,
    val cloudProjectNumber: Long,
) : PlayRecoveryConfiguration {
    // This is the coordinator's local model, not the customer wire scope.
    override val scope = PlayScope(projectID, androidPackageName)
    internal val coordinatorConfiguration: PlayRecoveryConfiguration get() = this
    internal val hostname: String

    init {
        scope.validate()
        requirePlay(projectID.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) &&
            androidPackageName == actualPackageName && cloudProjectNumber > 0,
            PlayFailureReason.CONFIGURATION)
        val parsed = runCatching { URI(origin) }.getOrElse { throw PlayFailure(PlayFailureReason.CONFIGURATION) }
        val host = parsed.host ?: throw PlayFailure(PlayFailureReason.CONFIGURATION)
        requirePlay(parsed.scheme == "https" && parsed.rawAuthority == host &&
            parsed.port == -1 && parsed.userInfo == null && parsed.rawPath.isNullOrEmpty() &&
            parsed.rawQuery == null && parsed.rawFragment == null && host.length <= 253 &&
            host == host.lowercase() && host.contains('.') && host.split('.').all { label ->
                label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            } && host.any { it in 'a'..'z' } && origin == "https://$host",
            PlayFailureReason.CONFIGURATION)
        hostname = host
    }

    override fun validateURL(value: String) {
        requireCustomerUnicode(value)
        requirePlay(value.toByteArray(Charsets.UTF_8).size <= 8_192 && value.none {
            it.code <= 0x20 || it.code in 0x7f..0x9f || it == '\\'
        }, PlayFailureReason.INVALID_DATA)
        requirePlay(value.startsWith("$origin/") && !value.contains('#'), PlayFailureReason.WRONG_SCOPE)
        // Query context is opaque. In particular, do not turn '+' into a space
        // or normalize its percent escapes before storing the original URL.
        val pathURL = value.substringBefore('?')
        val parsed = runCatching { URI(pathURL) }.getOrElse { throw PlayFailure(PlayFailureReason.WRONG_SCOPE) }
        val rawPath = parsed.rawPath ?: throw PlayFailure(PlayFailureReason.WRONG_SCOPE)
        val decoded = runCatching { URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") }
            .getOrElse { throw PlayFailure(PlayFailureReason.WRONG_SCOPE) }
        val path = decoded.trim('/')
        requirePlay(parsed.scheme == "https" && parsed.rawAuthority == hostname &&
            parsed.userInfo == null && parsed.port == -1 && parsed.rawFragment == null &&
            !rawPath.contains("//") && rawPath.split('/').none { it == "." || it == ".." } &&
            path.length in 1..200 && path.matches(Regex("[A-Za-z0-9][A-Za-z0-9/_-]*")) &&
            !path.contains("//") && path != "api" && path != "healthz" && !path.startsWith("api/"),
            PlayFailureReason.WRONG_SCOPE)
    }

    internal fun validateDestination(linkID: String, destination: PlayDestination) {
        requireCustomerUnicode(linkID)
        requirePlay(linkID.isNotEmpty() && linkID.length <= 1_024 &&
            linkID.none { it.code < 0x20 || it.code == 0x7f }, PlayFailureReason.INVALID_DATA)
        val route = destination.route
        requirePlay(route.length in 1..200 && route.matches(Regex("/?[A-Za-z0-9_-][A-Za-z0-9/_.-]*")) &&
            !route.contains("//") && !route.endsWith('/') && route.split('/').none { it == "." || it == ".." },
            PlayFailureReason.INVALID_DATA)
        var nodes = 0
        fun validate(value: JsonElement, depth: Int) {
            requirePlay(++nodes <= 256 && depth <= 6, PlayFailureReason.INVALID_DATA)
            when (value) {
                JsonNull -> Unit
                is JsonObject -> value.forEach { (key, child) ->
                    requireCustomerUnicode(key)
                    requirePlay(key.length in 1..64 && key !in setOf("__proto__", "prototype", "constructor"),
                        PlayFailureReason.INVALID_DATA)
                    validate(child, depth + 1)
                }
                is JsonArray -> value.forEach { validate(it, depth + 1) }
                is JsonPrimitive -> {
                    if (value.isString) requireCustomerUnicode(value.content)
                    requirePlay(if (value.isString) value.content.length <= 4_096
                        else value.booleanOrNull != null || value.doubleOrNull?.isFinite() == true,
                        PlayFailureReason.INVALID_DATA)
                }
            }
        }
        validate(destination.data, 0)
        val encoded = JsonObject(mapOf("route" to JsonPrimitive(route), "data" to destination.data))
        requirePlay(encoded.toString().toByteArray(Charsets.UTF_8).size <= 4_096, PlayFailureReason.INVALID_DATA)
    }

    override fun toString() = "CustomerPlayConfiguration(customer protocol, no credentials)"
}

private fun requireCustomerUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isHighSurrogate()) {
            requirePlay(index < value.length && value[index].isLowSurrogate(), PlayFailureReason.INVALID_DATA)
            index++
        } else requirePlay(!char.isLowSurrogate(), PlayFailureReason.INVALID_DATA)
    }
}

/** No directory probing, fallback or import from another protocol/project/host. */
internal fun playJournalDirectory(configuration: PlayRecoveryConfiguration): String = when (configuration) {
    is PlayConfiguration -> "luximio-play-prototype"
    is CustomerPlayConfiguration -> {
        val identity = listOf("customer-recovery-v1", configuration.origin,
            configuration.projectID, configuration.androidPackageName).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        "luximio-play-customer-v1-$digest"
    }
}
