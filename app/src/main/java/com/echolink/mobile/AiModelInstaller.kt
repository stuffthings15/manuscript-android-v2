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

class AiModelIntegrityException(message: String) : IOException(message)

class RangeDownloadResponse(
    val statusCode: Int,
    val content: InputStream,
    val etag: String?,
    private val disconnect: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { content.close() }
        disconnect()
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
) {
    fun stage(
        artifact: AiModelArtifact,
        stopped: () -> Boolean,
        progress: (Long, Long) -> Unit,
    ): File {
        require(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "Cannot create the local AI folder."
        }
        val candidate = candidateFile(artifact)
        migrateLegacyCandidate(artifact, candidate, stopped)
        if (isValid(candidate, artifact, stopped)) return candidate
        if (candidate.exists() && !candidate.delete()) {
            throw IOException("Cannot remove an incompatible AI candidate.")
        }

        val partial = File(modelDirectory, "${artifact.fileName}.part")
        val etagFile = File(modelDirectory, "${artifact.fileName}.etag")
        if (partial.length() > artifact.sizeBytes) {
            partial.delete()
            etagFile.delete()
        }
        val startingOffset = partial.length()
        val storedEtag = etagFile.takeIf(File::isFile)?.readText()?.takeIf(String::isNotBlank)
        var response = transport.open(
            artifact.downloadUrl,
            startingOffset,
            storedEtag,
        )
        if (
            startingOffset > 0L &&
            response.statusCode == HTTP_PARTIAL &&
            storedEtag != null &&
            response.etag != null &&
            response.etag != storedEtag
        ) {
            response.close()
            partial.delete()
            etagFile.delete()
            response = transport.open(artifact.downloadUrl, 0L, null)
        }

        response.use { download ->
            val append = download.statusCode == HTTP_PARTIAL && partial.length() > 0L
            val downloadedBefore = if (append) partial.length() else 0L
            if (!download.etag.isNullOrBlank()) etagFile.writeText(download.etag)
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var downloaded = downloadedBefore
                while (true) {
                    if (stopped()) throw InterruptedIOException("AI download stopped.")
                    val count = download.content.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    progress(downloaded, artifact.sizeBytes)
                }
                output.fd.sync()
            }
        }

        if (!isValid(partial, artifact, stopped)) {
            partial.delete()
            candidate.delete()
            etagFile.delete()
            throw AiModelIntegrityException("AI download failed its security check.")
        }
        candidate.delete()
        if (!partial.renameTo(candidate)) {
            throw IOException("Cannot stage the downloaded AI.")
        }
        etagFile.delete()
        return candidate
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
        previous.delete()
        val hadPrevious = active.isFile
        if (hadPrevious && !active.renameTo(previous)) {
            throw IOException("Cannot preserve the previous AI.")
        }
        if (!candidate.renameTo(active)) {
            if (hadPrevious) previous.renameTo(active)
            throw IOException("Cannot activate the tested AI.")
        }
        previous.delete()
        return AiModelActivation(active, activated = true)
    }

    fun activeFile(artifact: AiModelArtifact): File =
        File(modelDirectory, artifact.fileName)

    fun candidateFile(artifact: AiModelArtifact): File =
        File(modelDirectory, candidateFileName(artifact))

    private fun migrateLegacyCandidate(
        artifact: AiModelArtifact,
        candidate: File,
        stopped: () -> Boolean,
    ) {
        if (candidate.exists()) return
        val legacy = File(modelDirectory, "${artifact.fileName}.candidate")
        if (!legacy.exists()) return
        if (isValid(legacy, artifact, stopped)) {
            if (!legacy.renameTo(candidate)) {
                throw IOException("Cannot migrate the downloaded AI candidate.")
            }
        } else if (!legacy.delete()) {
            throw IOException("Cannot remove an incompatible AI candidate.")
        }
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
