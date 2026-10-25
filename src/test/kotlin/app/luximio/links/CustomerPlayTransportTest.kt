package app.luximio.links

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class CustomerPlayTransportTest {
    @Test fun `production client has a whole-call deadline and no proof replay or stateful middleware`() {
        val client = customerPlayHttpClient()
        assertEquals(10_000, client.callTimeoutMillis)
        assertEquals(8_000, client.connectTimeoutMillis)
        assertEquals(8_000, client.readTimeoutMillis)
        assertEquals(8_000, client.writeTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertNull(client.cache)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
    }

    @Test fun `native POST keeps bearer in its header and freezes a one-shot JSON body`() = runBlocking {
        lateinit var captured: Request
        val body = "{\"payload\":{},\"proof\":null}".toByteArray()
        val responseBody = TrackingBody("{}".toByteArray())
        val transport = HttpsCustomerPlayTransport(ORIGIN, factory { call, callback ->
            captured = call.request()
            body.fill(0)
            callback.onResponse(call, response(call.request(), responseBody))
        })
        val result = transport.post(CustomerPlayHttpRequest(URL, TOKEN, body))
        assertEquals("POST", captured.method)
        assertEquals(URL, captured.url.toString())
        assertEquals("Bearer $TOKEN", captured.header("Authorization"))
        assertEquals("application/json", captured.header("Accept"))
        assertNull(captured.header("Cookie"))
        assertTrue(captured.body!!.isOneShot())
        val written = Buffer()
        captured.body!!.writeTo(written)
        assertEquals("{\"payload\":{},\"proof\":null}", written.readUtf8())
        assertArrayEquals("{}".toByteArray(), result.body)
        assertTrue(responseBody.closed.get())
        assertFalse(CustomerPlayHttpRequest(URL, TOKEN, body).toString().contains(TOKEN))
    }

    @Test fun `wrong hosts paths queries and malformed bearers never reach the HTTP engine`() = runBlocking {
        var calls = 0
        val transport = HttpsCustomerPlayTransport(ORIGIN, object : Call.Factory {
            override fun newCall(request: Request): Call { calls++; error("Must not send") }
        })
        listOf("https://other.example/api/v1/recovery/resolve", "$URL?token=$TOKEN",
            "$ORIGIN/api/private/android-continuations/resolve", "$ORIGIN/api/v1/recovery/resolve/",
            "$ORIGIN/api/v1/recovery/register").forEach { url ->
            expectFailure(PlayFailureReason.WRONG_SCOPE) { transport.post(CustomerPlayHttpRequest(url, TOKEN, byteArrayOf(1))) }
        }
        expectFailure(PlayFailureReason.INVALID_DATA) { transport.post(CustomerPlayHttpRequest(URL, "bad\r\ntoken", byteArrayOf(1))) }
        expectFailure(PlayFailureReason.INVALID_DATA) { transport.post(CustomerPlayHttpRequest(URL, TOKEN, ByteArray(65_537))) }
        assertEquals(0, calls)
    }

    @Test fun `unknown-length oversized response is stopped and closed`() = runBlocking {
        val body = TrackingBody(ByteArray(200_000), -1)
        val transport = HttpsCustomerPlayTransport(ORIGIN, factory { call, callback ->
            callback.onResponse(call, response(call.request(), body))
        })
        expectFailure(PlayFailureReason.INVALID_DATA) { transport.post(request()) }
        assertTrue(body.closed.get())
        assertTrue(body.readBytes <= 65_536 + 8_192)
    }

    @Test fun `oversized error bodies stay retryable while retaining the same streaming limit`() = runBlocking {
        for (declaredLength in listOf(-1L, 200_000L)) {
            val body = TrackingBody(ByteArray(200_000), declaredLength)
            val transport = HttpsCustomerPlayTransport(ORIGIN, factory { call, callback ->
                callback.onResponse(call, response(call.request(), body, status = 503))
            })
            expectFailure(PlayFailureReason.NETWORK) { transport.post(request()) }
            assertTrue(body.closed.get())
            assertTrue(body.readBytes <= 65_536 + 8_192)
            if (declaredLength > 65_536) assertEquals(0L, body.readBytes)
        }
    }

    @Test fun `response stream errors are redacted and close the body`() = runBlocking {
        val body = TrackingBody(ByteArray(1), -1, failRead = true)
        val transport = HttpsCustomerPlayTransport(ORIGIN, factory { call, callback ->
            callback.onResponse(call, response(call.request(), body))
        })
        val failure = expectFailure(PlayFailureReason.NETWORK) { transport.post(request()) }
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("sensitive"))
        assertTrue(body.closed.get())
    }

    @Test fun `cancellation cancels the call and closes a response arriving afterward`() = runBlocking {
        lateinit var call: FakeCall
        lateinit var callback: Callback
        val transport = HttpsCustomerPlayTransport(ORIGIN, factory { created, listener -> call = created; callback = listener })
        val operation = async { transport.post(request()) }
        yield()
        operation.cancelAndJoin()
        assertTrue(call.isCanceled())
        val lateBody = TrackingBody("{}".toByteArray())
        callback.onResponse(call, response(call.request(), lateBody))
        assertTrue(lateBody.closed.get())
        assertEquals(0L, lateBody.readBytes)
    }

    @Test fun `whole-call deadline cancels a call that never returns headers`() = runBlocking {
        lateinit var call: FakeCall
        val transport = HttpsCustomerPlayTransport(ORIGIN, factory { created, _ -> call = created }, deadlineMillis = 50)
        withTimeout(2_000) { expectFailure(PlayFailureReason.NETWORK) { transport.post(request()) } }
        assertTrue(call.isCanceled())
    }

    @Test fun `whole-call deadline still cancels after headers while the body is stalled`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        lateinit var call: FakeCall
        val body = object : ResponseBody() {
            private val input = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.complete(Unit)
                    release.await(2, TimeUnit.SECONDS)
                    throw IOException("Synthetic stalled body")
                }
                override fun timeout() = Timeout.NONE
                override fun close() { closed.complete(Unit) }
            }.buffer()
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = input
        }
        val transport = HttpsCustomerPlayTransport(ORIGIN, factory { created, callback ->
            call = created
            call.onCancel = { release.countDown() }
            thread(isDaemon = true, name = "customer-response-test") {
                callback.onResponse(call, response(call.request(), body))
            }
        }, deadlineMillis = 200)
        try {
            val result = async { expectFailure(PlayFailureReason.NETWORK) { transport.post(request()) } }
            withTimeout(2_000) { reading.await() }
            withTimeout(2_000) { result.await(); closed.await() }
            assertTrue(call.isCanceled())
        } finally { release.countDown() }
    }

    private fun request() = CustomerPlayHttpRequest(URL, TOKEN, "{}".toByteArray())

    private fun factory(onEnqueue: (FakeCall, Callback) -> Unit) = object : Call.Factory {
        override fun newCall(request: Request): Call = FakeCall(request, onEnqueue)
    }

    private fun response(request: Request, body: ResponseBody, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic response")
        .header("Content-Type", "application/json").body(body).build()

    private class FakeCall(private val value: Request, private val onEnqueue: (FakeCall, Callback) -> Unit) : Call {
        private var executed = false
        private var canceled = false
        private val tags = mutableMapOf<Class<*>, Any>()
        var onCancel: () -> Unit = {}
        override fun request() = value
        override fun execute(): Response = error("Use asynchronous calls")
        override fun enqueue(responseCallback: Callback) { executed = true; onEnqueue(this, responseCallback) }
        override fun cancel() { canceled = true; onCancel() }
        override fun isExecuted() = executed
        override fun isCanceled() = canceled
        override fun timeout() = Timeout()
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            type.cast(tags.getOrPut(type, computeIfAbsent))
        override fun clone(): Call = FakeCall(value, onEnqueue)
    }

    private class TrackingBody(
        private val bytes: ByteArray,
        private val declaredLength: Long = bytes.size.toLong(),
        private val failRead: Boolean = false,
    ) : ResponseBody() {
        val closed = AtomicBoolean()
        var readBytes = 0L
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failRead) throw IOException("sensitive response failure")
                if (readBytes == bytes.size.toLong()) return -1
                val count = minOf(byteCount, bytes.size - readBytes).toInt()
                sink.write(bytes, readBytes.toInt(), count)
                readBytes += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        override fun contentType(): MediaType = "application/json".toMediaType()
        override fun contentLength() = declaredLength
        override fun source(): BufferedSource = input
    }

    private suspend fun expectFailure(reason: PlayFailureReason, action: suspend () -> Unit): PlayFailure {
        try { action(); fail("HTTP failure was accepted") }
        catch (error: PlayFailure) { assertEquals(reason, error.reason); return error }
        error("Expected HTTP failure")
    }

    private companion object {
        const val ORIGIN = "https://customer.example"
        const val URL = "$ORIGIN/api/v1/recovery/resolve"
        val TOKEN = "A".repeat(43)
    }
}
