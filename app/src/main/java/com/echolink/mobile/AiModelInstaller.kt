package com.echolink.mobile

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class AiModelIntegrityException(message: String) : IOException(message)
class AiModelCleanupException(message: String) : IOException(message)

class RangeDownloadResponse(
    val statusCode: Int,
    val content: InputStream,
    val etag: String?,
    private val disconnect: () -> Unit = {},
) : Closeable {
    override fun close() {
        var closeFailure: Throwable? = null
        try {
            content.close()
        } catch (failure: Throwable) {
            closeFailure = failure
        }
        try {
            disconnect()
        } catch (failure: Throwable) {
            if (closeFailure == null) {
                closeFailure = failure
            } else if (closeFailure !== failure) {
                closeFailure.addSuppressed(failure)
            }
        }
        closeFailure?.let { throw it }
    }
}

interface ModelDownloadTransport {
    fun open(
        url: String,
        startingOffset: Long,
        ifRangeEtag: String?,
    ): RangeDownloadResponse
}

class HttpModelDownloadTransport : ModelDownloadTransport {
    override fun open(
        url: String,
        startingOffset: Long,
        ifRangeEtag: String?,
    ): RangeDownloadResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Manuscript-Android-AI")
            if (startingOffset > 0L) {
                setRequestProperty("Range", "bytes=$startingOffset-")
                if (!ifRangeEtag.isNullOrBlank()) {
                    setRequestProperty("If-Range", ifRangeEtag)
                }
            }
        }
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK && status != HTTP_PARTIAL) {
            connection.disconnect()
            throw IOException("AI download failed with HTTP $status.")
        }
        return RangeDownloadResponse(
            statusCode = status,
            content = connection.inputStream,
            etag = connection.getHeaderField("ETag"),
            disconnect = connection::disconnect,
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val HTTP_PARTIAL = 206
    }
}

data class AiModelActivation(
    val activeFile: File,
    val activated: Boolean,
)

class AiModelInstaller(
    private val modelDirectory: File,
    private val transport: ModelDownloadTransport = HttpModelDownloadTransport(),
    private val deleteFile: (File) -> Boolean = { file -> !file.exists() || file.delete() },
    private val renameFile: (File, File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    },
    private val syncOutput: (FileOutputStream) -> Unit = { output -> output.fd.sync() },
) {
    fun stage(
        artifact: AiModelArtifact,
        stopped: () -> Boolean,
        progress: (Long, Long) -> Unit,
    ): File {
        require(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "Cannot create the local AI folder."
        }
        throwIfStopped(stopped)
        val candidate = candidateFile(artifact)
        migrateLegacyCandidate(artifact, candidate, stopped)
        if (isValid(candidate, artifact, stopped)) return candidate
        deleteRequired(candidate, "Cannot remove an incompatible AI candidate.")

        val partial = partialFile(artifact)
        val etagFile = etagFile(artifact)
        if (partial.length() > artifact.sizeBytes) {
            deleteRequired(partial, "Cannot remove oversized AI download bytes.")
            deleteRequired(etagFile, "Cannot remove the oversized download marker.")
        }
        var requestOffset = partial.length()
        progress(requestOffset, artifact.sizeBytes)
        if (requestOffset == artifact.sizeBytes) {
            if (!isValid(partial, artifact, stopped)) {
                cleanupCorruptDownload(partial, candidate, etagFile)
                throw AiModelIntegrityException("AI download failed its security check.")
            }
            return stagePartialCandidate(partial, candidate, etagFile)
        }
        val storedEtag = etagFile.takeIf(File::isFile)?.readText()?.takeIf(String::isNotBlank)
        throwIfStopped(stopped)
        var response = transport.open(
            artifact.downloadUrl,
            requestOffset,
            storedEtag,
        )
        var zeroBaselineReported = false
        if (
            requestOffset > 0L &&
            response.statusCode == HTTP_PARTIAL &&
            storedEtag != null &&
            response.etag != null &&
            response.etag != storedEtag
        ) {
            response.close()
            deleteRequired(partial, "Cannot restart AI bytes after the download changed.")
            deleteRequired(etagFile, "Cannot replace the changed download marker.")
            progress(0L, artifact.sizeBytes)
            zeroBaselineReported = true
            requestOffset = 0L
            throwIfStopped(stopped)
            response = transport.open(artifact.downloadUrl, 0L, null)
        }

        var downloaded = requestOffset
        try {
            response.use { download ->
                val append = download.statusCode == HTTP_PARTIAL && partial.length() > 0L
                val downloadedBefore = if (append) partial.length() else 0L
                if (!append) {
                    deleteRequired(
                        etagFile,
                        "Cannot remove the stale download marker before restarting.",
                    )
                    if (requestOffset > 0L && !zeroBaselineReported) {
                        progress(0L, artifact.sizeBytes)
                        zeroBaselineReported = true
                    }
                }
                if (!download.etag.isNullOrBlank()) {
                    etagFile.writeText(download.etag)
                }
                downloaded = downloadedBefore
                FileOutputStream(partial, append).use { output ->
                    var primaryFailure: Throwable? = null
                    try {
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            throwIfStopped(stopped)
                            val count = download.content.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            val remaining = artifact.sizeBytes - downloaded
                            if (count.toLong() > remaining) {
                                throw AiModelIntegrityException(
                                    "AI download exceeded its pinned byte count.",
                                )
                            }
                            output.write(buffer, 0, count)
                            downloaded += count
                            progress(downloaded, artifact.sizeBytes)
                        }
                    } catch (failure: Throwable) {
                        primaryFailure = failure
                        throw failure
                    } finally {
                        try {
                            output.flush()
                            syncOutput(output)
                        } catch (cleanupFailure: Throwable) {
                            if (primaryFailure == null) {
                                throw cleanupFailure
                            }
                            if (primaryFailure !== cleanupFailure) {
                                primaryFailure.addSuppressed(cleanupFailure)
                            }
                        }
                    }
                }
            }
        } catch (failure: AiModelIntegrityException) {
            cleanupCorruptDownload(partial, candidate, etagFile)
            throw failure
        }

        if (downloaded < artifact.sizeBytes) {
            throw InterruptedIOException("AI download ended before all pinned bytes arrived.")
        }

        if (!isValid(partial, artifact, stopped)) {
            cleanupCorruptDownload(partial, candidate, etagFile)
            throw AiModelIntegrityException("AI download failed its security check.")
        }
        return stagePartialCandidate(partial, candidate, etagFile)
    }

    fun qualifyAndActivate(
        artifact: AiModelArtifact,
        candidate: File,
        selfTest: (File) -> Boolean,
    ): AiModelActivation {
        require(candidate.parentFile == modelDirectory) {
            "Invalid AI candidate folder."
        }
        require(candidate.name == candidateFileName(artifact)) {
            "Invalid AI candidate name."
        }
        if (!isValid(candidate, artifact) || !selfTest(candidate)) {
            return AiModelActivation(
                activeFile = activeFile(artifact).takeIf(File::isFile) ?: candidate,
                activated = false,
            )
        }

        val active = activeFile(artifact)
        val previous = File(modelDirectory, "${artifact.fileName}.previous")
        deleteRequired(previous, "Cannot remove the previous AI backup.")
        val hadPrevious = active.isFile
        if (hadPrevious && !renameFile(active, previous)) {
            throw IOException("Cannot preserve the previous AI.")
        }
        if (!renameFile(candidate, active)) {
            if (hadPrevious && !restorePrevious(previous, active)) {
                throw IOException(
                    "Cannot activate the tested AI or restore the previous AI.",
                )
            }
            throw IOException("Cannot activate the tested AI.")
        }
        deleteBestEffort(previous)
        return AiModelActivation(active, activated = true)
    }

    fun activeFile(artifact: AiModelArtifact): File =
        File(modelDirectory, artifact.fileName)

    fun candidateFile(artifact: AiModelArtifact): File =
        File(modelDirectory, candidateFileName(artifact))

    fun partialFile(artifact: AiModelArtifact): File =
        File(modelDirectory, "${artifact.fileName}.part")

    fun partialBytes(artifact: AiModelArtifact): Long =
        partialFile(artifact)
            .takeIf(File::isFile)
            ?.length()
            ?.takeIf { it in 0L..artifact.sizeBytes }
            ?: 0L

    fun remainingBytes(artifact: AiModelArtifact): Long =
        artifact.sizeBytes - partialBytes(artifact)

    private fun migrateLegacyCandidate(
        artifact: AiModelArtifact,
        candidate: File,
        stopped: () -> Boolean,
    ) {
        if (candidate.exists()) return
        val legacy = File(modelDirectory, "${artifact.fileName}.candidate")
        if (!legacy.exists()) return
        if (isValid(legacy, artifact, stopped)) {
            if (!renameFile(legacy, candidate)) {
                throw IOException("Cannot migrate the downloaded AI candidate.")
            }
        } else {
            deleteRequired(legacy, "Cannot remove an incompatible AI candidate.")
        }
    }

    private fun etagFile(artifact: AiModelArtifact): File =
        File(modelDirectory, "${artifact.fileName}.etag")

    private fun stagePartialCandidate(
        partial: File,
        candidate: File,
        etagFile: File,
    ): File {
        deleteRequired(candidate, "Cannot replace the staged AI candidate.")
        if (!renameFile(partial, candidate)) {
            throw IOException("Cannot stage the downloaded AI.")
        }
        deleteBestEffort(etagFile)
        return candidate
    }

    private fun cleanupCorruptDownload(
        partial: File,
        candidate: File,
        etagFile: File,
    ) {
        deleteRequired(partial, "Cannot remove corrupted AI download bytes.")
        deleteRequired(candidate, "Cannot remove a corrupted AI candidate.")
        deleteRequired(etagFile, "Cannot remove the corrupted download marker.")
    }

    private fun deleteRequired(file: File, message: String) {
        if (file.exists() && !deleteFile(file)) throw AiModelCleanupException(message)
    }

    private fun deleteBestEffort(file: File) {
        try {
            if (file.exists()) deleteFile(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (_: Exception) {
            // The completed candidate or active file is already durable. A stale marker or
            // backup is safe to clean during a later maintenance pass.
        }
    }

    private fun restorePrevious(previous: File, active: File): Boolean {
        if (renameFile(previous, active)) return true
        return try {
            previous.copyTo(active, overwrite = true)
            active.isFile && active.length() == previous.length()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun throwIfStopped(stopped: () -> Boolean) {
        if (stopped()) throw InterruptedIOException("AI download stopped.")
    }

    fun isValid(
        file: File,
        artifact: AiModelArtifact,
        stopped: () -> Boolean = { false },
    ): Boolean =
        file.isFile &&
            file.length() == artifact.sizeBytes &&
            sha256(file, stopped) == artifact.sha256

    private fun sha256(file: File, stopped: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                if (stopped()) throw InterruptedIOException("AI verification stopped.")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val HTTP_PARTIAL = 206
        private const val DOWNLOAD_BUFFER_BYTES = 256 * 1_024

        private fun candidateFileName(artifact: AiModelArtifact): String {
            val extension = artifact.fileName.substringAfterLast('.', missingDelimiterValue = "")
            val baseName = if (extension.isEmpty()) {
                artifact.fileName
            } else {
                artifact.fileName.removeSuffix(".$extension")
            }
            return if (extension.isEmpty()) {
                "$baseName.candidate"
            } else {
                "$baseName.candidate.$extension"
            }
        }
    }
}
