package app.luximio.links

import java.io.OutputStream

/** Throwing persistence boundary; Android's AtomicFile.finishWrite alone only logs some failures. */
internal interface PlayAtomicWrite {
    fun start(): OutputStream
    fun sync(stream: OutputStream)
    fun finish(stream: OutputStream)
    fun hasPendingFile(): Boolean
    fun readCommitted(maximumBytes: Int): ByteArray
    fun syncDirectory()
    fun rollback(stream: OutputStream)
}

internal object VerifiedPlayPersistence {
    fun write(bytes: ByteArray, operation: PlayAtomicWrite) {
        var stream: OutputStream? = null
        try {
            stream = operation.start()
            stream.write(bytes)
            operation.sync(stream)
            operation.finish(stream)
            stream = null // The finish operation owns closing the stream.
            if (operation.hasPendingFile() || !operation.readCommitted(bytes.size + 1).contentEquals(bytes)) {
                throw PlayFailure(PlayFailureReason.STORAGE)
            }
            operation.syncDirectory()
        } catch (_: Exception) {
            stream?.let { runCatching { operation.rollback(it) } }
            throw PlayFailure(PlayFailureReason.STORAGE)
        }
    }
}
