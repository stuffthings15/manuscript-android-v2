package com.echolink.mobile

import androidx.work.Data
import java.util.UUID

internal object DownloadWorkerAttemptDecoder {
    fun decodeGemma(data: Data, actualWorkerId: UUID): DownloadAttempt? =
        decode(data, actualWorkerId, AiModelFamily.GEMMA)

    fun decodeLegacy(data: Data, actualWorkerId: UUID): DownloadAttempt? =
        decode(data, actualWorkerId, AiModelFamily.LEGACY_QWEN)

    private fun decode(
        data: Data,
        actualWorkerId: UUID,
        expectedFamily: AiModelFamily,
    ): DownloadAttempt? = DownloadWorkContract.decode(data, actualWorkerId)
        ?.takeIf { attempt -> attempt.target.family == expectedFamily }
}
