package app.luximio.links

import android.content.Context
import android.os.RemoteException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** Play transport only. It does not establish who the customer is or authorize content. */
class PlayInstallReferrerSource(
    context: Context,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    timeoutMillis: Long = 5_000,
) : PlayReferrerSource {
    private val applicationContext = context.applicationContext
    private val reader = BoundedPlayReferrerRead(mainDispatcher, ioDispatcher, timeoutMillis)

    override suspend fun fetch(): PlayReferrerResult = try {
        reader.fetch { AndroidPlayReferrerConnection(applicationContext) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        PlayReferrerResult.Rejected(PlayReferrerRejection.PERMISSION_DENIED)
    } catch (_: RemoteException) {
        PlayReferrerResult.Retryable
    } catch (_: IllegalStateException) {
        PlayReferrerResult.Retryable
    }
}

/** Instantiated only after the per-source read guard has admitted this request, on Main. */
private class AndroidPlayReferrerConnection(context: Context) : PlayReferrerConnection {
    private val client = InstallReferrerClient.newBuilder(context).build()

    override suspend fun connect(): PlayReferrerResult? {
        val code = suspendCancellableCoroutine<Int> { continuation ->
            val delivered = AtomicBoolean(false)
            fun finish(value: Int) {
                if (delivered.compareAndSet(false, true)) continuation.resume(value)
            }
            continuation.invokeOnCancellation { delivered.set(true) }
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) = finish(responseCode)
                override fun onInstallReferrerServiceDisconnected() =
                    finish(InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED)
            })
        }
        return PlayProviderResponses.failure(code)
    }

    override fun read(): PlayReferrerResult {
        val details = client.installReferrer
        val referrer = details.installReferrer
        return when {
            referrer.isNullOrBlank() || !Regex("(^|&)lx_").containsMatchIn(referrer) -> PlayReferrerResult.Missing
            referrer.toByteArray(Charsets.UTF_8).size > 512 -> PlayReferrerResult.Rejected(PlayReferrerRejection.INVALID)
            else -> PlayReferrerResult.Available(referrer,
                details.referrerClickTimestampSeconds.takeIf { it > 0 },
                details.installBeginTimestampSeconds.takeIf { it > 0 })
        }
    }

    override fun close() = client.endConnection()
}

/** Pure response classification also covers constants omitted from Google's reference table. */
internal object PlayProviderResponses {
    fun failure(code: Int): PlayReferrerResult? = when (code) {
        InstallReferrerClient.InstallReferrerResponse.OK -> null
        InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED,
        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> PlayReferrerResult.Retryable
        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> PlayReferrerResult.Unsupported
        InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR ->
            PlayReferrerResult.Rejected(PlayReferrerRejection.DEVELOPER_ERROR)
        InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR ->
            PlayReferrerResult.Rejected(PlayReferrerRejection.PERMISSION_DENIED)
        else -> PlayReferrerResult.Rejected(PlayReferrerRejection.UNKNOWN_RESPONSE)
    }
}
