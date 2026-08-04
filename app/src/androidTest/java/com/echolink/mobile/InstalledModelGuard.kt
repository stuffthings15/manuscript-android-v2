package com.echolink.mobile

import android.content.Context
import java.io.File

/**
 * Preserves and restores installed AI weights around a destructive test.
 *
 * The missing-model test deliberately wipes every installed model. Without a
 * guard it poisons the rest of a suite run: every later AI test reports
 * "Install AI" and a 1.3 GB re-download is required to recover. The weights
 * are moved aside rather than copied, so no extra device storage is used.
 */
class InstalledModelGuard(context: Context) {
    private val appContext = context.applicationContext
    private val directories = listOf(
        File(appContext.noBackupFilesDir, "qwen3_models"),
        File(appContext.noBackupFilesDir, "ai_models"),
    )
    private val stash = File(appContext.noBackupFilesDir, "test_model_stash")
    private val savedPreferences = mutableMapOf<String, Map<String, *>>()

    fun preserve() {
        stash.mkdirs()
        directories.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            directory.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val target = File(stash, "${directory.name}__${file.name}")
                    file.renameTo(target)
                }
            }
        }
        listOf("manuscript_qwen3", "manuscript_ai").forEach { name ->
            savedPreferences[name] = appContext
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .all
                .toMap()
        }
    }

    fun restore() {
        if (stash.isDirectory) {
            stash.listFiles()?.forEach { file ->
                val separator = file.name.indexOf("__")
                if (separator <= 0) return@forEach
                val directory = File(
                    appContext.noBackupFilesDir,
                    file.name.substring(0, separator),
                )
                directory.mkdirs()
                file.renameTo(File(directory, file.name.substring(separator + 2)))
            }
            stash.delete()
        }
        savedPreferences.forEach { (name, values) ->
            val editor = appContext
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    else -> Unit
                }
            }
            editor.commit()
        }
        savedPreferences.clear()
    }
}
