package app.luximio.links

import android.content.Context
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.util.UUID

/** One install-local snapshot. Never shared with analytics, backed up, or silently reset on corruption. */
class NoBackupPlayJournal(
    context: Context,
    private val configuration: PlayRecoveryConfiguration,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlayInstallJournal {
    private val file: AtomicFile
    private val installationMarker: AtomicFile

    init {
        try {
            val noBackup = context.noBackupFilesDir
            val directory = File(noBackup, playJournalDirectory(configuration))
            if (!directory.isDirectory) {
                if (!directory.mkdirs() || !directory.isDirectory) throw IOException("Journal directory")
            }
            // Persist both private-directory entries, including recovery after an earlier sync failure.
            syncPlayDirectory(noBackup.parentFile!!)
            syncPlayDirectory(noBackup)
            file = AtomicFile(File(directory, "installation-v1.json"))
            installationMarker = AtomicFile(File(directory, "installation-id-v1"))
        } catch (_: Exception) {
            throw PlayFailure(PlayFailureReason.STORAGE)
        }
    }

    @Synchronized
    override fun read(): PlayInstallState {
        try {
            val hasSnapshot = exists(file)
            val hasMarker = exists(installationMarker)
            if (!hasSnapshot && !hasMarker) {
                val state = PlayInstallState(scope = configuration.scope, installationID = UUID.randomUUID().toString())
                // Marker first: an interrupted first write must fail closed on the next attempt.
                writeAtomic(installationMarker, state.installationID.toByteArray(Charsets.UTF_8))
                write(state)
                return state
            }
            if (!hasSnapshot || !hasMarker) throw IOException("Incomplete installation journal")
            val marker = markerID()
            val state = PlayJournalCodec.decode(readBounded(file, PlayJournalCodec.MAXIMUM_BYTES), configuration, clock())
            if (state.installationID != marker) throw IOException("Installation journal mismatch")
            return state
        } catch (_: Exception) {
            // A lost/corrupt/imported journal must never appear to be a fresh installation.
            throw PlayFailure(PlayFailureReason.STORAGE)
        }
    }

    @Synchronized
    override fun write(state: PlayInstallState) {
        try {
            if (markerID() != state.installationID) throw IOException("Installation journal mismatch")
            writeAtomic(file, PlayJournalCodec.encode(state, configuration, clock()))
        } catch (_: Exception) {
            throw PlayFailure(PlayFailureReason.STORAGE)
        }
    }

    private fun markerID(): String {
        val id = readBounded(installationMarker, 36).toString(Charsets.UTF_8)
        if (runCatching { UUID.fromString(id).toString() != id }.getOrDefault(true)) {
            throw IOException("Invalid installation marker")
        }
        return id
    }

    private fun exists(value: AtomicFile): Boolean = value.baseFile.exists() ||
        File(value.baseFile.path + ".bak").exists() || File(value.baseFile.path + ".new").exists()

    private fun readBounded(value: AtomicFile, maximum: Int): ByteArray = value.openRead().use { readBounded(it, maximum) }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(2_048)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximum) throw IOException("Journal size")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeAtomic(value: AtomicFile, bytes: ByteArray) = VerifiedPlayPersistence.write(bytes,
        object : PlayAtomicWrite {
            override fun start(): OutputStream = value.startWrite()
            override fun sync(stream: OutputStream) = (stream as FileOutputStream).fd.sync()
            override fun finish(stream: OutputStream) = value.finishWrite(stream as FileOutputStream)
            override fun hasPendingFile(): Boolean = File(value.baseFile.path + ".new").exists() ||
                File(value.baseFile.path + ".bak").exists()
            override fun readCommitted(maximumBytes: Int): ByteArray =
                value.baseFile.inputStream().use { readBounded(it, maximumBytes) }
            override fun syncDirectory() = syncPlayDirectory(value.baseFile.parentFile!!)
            override fun rollback(stream: OutputStream) = value.failWrite(stream as FileOutputStream)
        })
}

private fun syncPlayDirectory(directory: File) {
    // Android 8's Bionic supports O_CLOEXEC atomically, but its Java constant
    // became public in API 27. Use the same Android/Linux ABI value on API 26.
    // https://android.googlesource.com/platform/bionic/+/android-8.0.0_r1/libc/kernel/uapi/asm-generic/fcntl.h
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or ANDROID_O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
    try {
        if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IOException("Journal parent is not a directory")
        Os.fsync(descriptor)
    }
    finally { Os.close(descriptor) }
}

private const val ANDROID_O_CLOEXEC = 0x80000
