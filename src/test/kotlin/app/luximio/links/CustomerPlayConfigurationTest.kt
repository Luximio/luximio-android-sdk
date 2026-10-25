package app.luximio.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CustomerPlayConfigurationTest {
    private fun config(project: String = "project_customer", origin: String = "https://links.example.com",
        name: String = "example.customer.app", actual: String = name, cloud: Long = 123456789012L) =
        CustomerPlayConfiguration(project, origin, name, actual, cloud)

    private fun receipt(configuration: CustomerPlayConfiguration = config()) = testReceipt().let {
        it.copy(invocation = it.invocation.copy(scope = configuration.scope,
            originalURL = configuration.origin + "/events/aurora?campaign=hello+world&literal=%ZZ"),
            canonicalLinkID = "link:customer/play.v1", destination = PlayDestination("/catalog/item.v2",
                Json.parseToJsonElement("""{"price":1.25,"nested":{"title":"A place 😀"}}""").jsonObject))
    }

    @Test fun explicitCustomerOriginPackageAndPublicProjectAreRequired() {
        val value = config()
        receipt(value).validate(value.coordinatorConfiguration, testNow)
        failure(PlayFailureReason.CONFIGURATION) { config(actual = "another.customer.app") }
        failure(PlayFailureReason.CONFIGURATION) { config(cloud = 0) }
        failure(PlayFailureReason.CONFIGURATION) { config(project = "_invalid") }
        listOf("http://links.example.com", "https://links.example.com/", "https://links.example.com:443",
            "https://user@links.example.com", "https://links.example.com?key=value", "https://links.example.com#fragment",
            "https://LINKS.example.com", "https://127.0.0.1", "https://links.example.com.evil/").forEach {
            failure(PlayFailureReason.CONFIGURATION) { config(origin = it) }
        }
    }

    @Test fun customerURLKeepsOpaqueQueryButRejectsAmbiguousPathsAndOrigins() {
        val c = config()
        c.validateURL(c.origin + "/events/%61urora?utm=hello+world&literal=%ZZ")
        c.validateURL(c.origin + "/events/aurora/?future_context=survives")
        listOf("https://another.example/events/aurora", c.origin + "/api", c.origin + "/api/resolve",
            c.origin + "/healthz", c.origin + "/events//aurora", c.origin + "/events/../aurora",
            c.origin + "/events/%2e%2e/aurora", c.origin + "/events/%ZZ", c.origin + "/events/aurora#fragment")
            .forEach { failure(PlayFailureReason.WRONG_SCOPE) { c.validateURL(it) } }
        failure(PlayFailureReason.INVALID_DATA) { c.validateURL(c.origin + "/events/aurora?name=bad value") }
        failure(PlayFailureReason.INVALID_DATA) { c.validateURL(c.origin + "/events/aurora?name=\ud800") }
    }

    @Test fun namespacesNeverProbePrototypeOtherProjectHostOrPackage() {
        assertEquals("luximio-play-prototype", playJournalDirectory(testConfiguration))
        val original = playJournalDirectory(config())
        assertTrue(original.startsWith("luximio-play-customer-v1-"))
        assertNotEquals(playJournalDirectory(testConfiguration), original)
        assertEquals(original, playJournalDirectory(config()))
        assertEquals(original, playJournalDirectory(config(cloud = 999999999999L)))
        listOf(config(project = "project_another"), config(origin = "https://another.example.com"),
            config(name = "example.another.app")).forEach { assertNotEquals(original, playJournalDirectory(it)) }
    }

    @Test fun customerReceiptJournalRoundTripsBroaderRoutesLinkIDsAndDecimals() {
        val c = config()
        val receipt = receipt(c)
        val state = PlayInstallState(scope = c.scope, installationID = "60000000-0000-4000-8000-000000000001",
            acquisition = PlayAcquisition.COMPLETE, delivery = PlayDeliveryRecord(testToken, testNow,
                testNow + PLAY_CONTINUATION_LIFETIME_MILLIS, PlayPhase.HELD, receipt))
        val encoded = PlayJournalCodec.encode(state, c, testNow)
        assertEquals(state, PlayJournalCodec.decode(encoded, c, testNow))
        assertFalse(state.toString().contains(testToken))
        failure(PlayFailureReason.WRONG_SCOPE) { PlayJournalCodec.decode(encoded, config(project = "another_project"), testNow) }
        failure(PlayFailureReason.INVALID_DATA) { receipt.copy(destination = receipt.destination.copy(
            data = Json.parseToJsonElement("""{"bad":"\ud800"}""").jsonObject)).validate(c, testNow) }
        failure(PlayFailureReason.INVALID_DATA) { receipt.copy(destination = receipt.destination.copy(
            data = Json.parseToJsonElement("""{"constructor":"reserved"}""").jsonObject)).validate(c, testNow) }
    }

    @Test fun safeIntegerTimestampEdgeDoesNotOverflowOrGetArtificiallyRejected() {
        val c = config()
        val max = 9_007_199_254_740_991L
        val receipt = receipt(c).let { it.copy(invocation = it.invocation.copy(capturedAt = max - 10,
            expiresAt = max), expiresAt = max) }
        receipt.validate(c, max - 1)
        failure(PlayFailureReason.EXPIRED) { receipt.validate(c, max) }
    }
}
