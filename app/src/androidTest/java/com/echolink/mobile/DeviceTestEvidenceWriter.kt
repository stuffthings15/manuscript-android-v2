package com.echolink.mobile

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object DeviceTestEvidenceWriter {
    fun writePrivate(context: Context, fileName: String, content: String): String {
        require(Regex("[A-Za-z0-9._-]+").matches(fileName)) {
            "Evidence filename contains unsupported characters."
        }
        val directory = File(context.filesDir, "test-evidence").apply { mkdirs() }
        val evidenceFile = File(directory, fileName)
        require(!evidenceFile.exists()) {
            "Evidence filename already exists."
        }
        return evidenceFile.apply {
            writeText(content, Charsets.UTF_8)
        }.absolutePath
    }

    fun write(context: Context, fileName: String, content: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/ManuscriptTestEvidence",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            )
            requireNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                it.write(content)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        }

        val directory = context.getExternalFilesDir(null)?.apply { mkdirs() }
            ?: context.filesDir.apply { mkdirs() }
        return File(directory, fileName).apply { writeText(content) }.absolutePath
    }
}
