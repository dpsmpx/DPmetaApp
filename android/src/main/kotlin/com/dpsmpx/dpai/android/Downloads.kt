package com.dpsmpx.dpai.android

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Файл в «Загрузки/[dir]» через MediaStore (Android 10+ без разрешения на
 * память): виден в файловом менеджере и переживает удаление приложения.
 * Недописанный файл помечен `IS_PENDING` и при сбое удаляется — в
 * «Загрузках» не остаётся обрезков. Итог — имя файла.
 */
object Downloads {
    fun write(context: Context, dir: String, name: String, mime: String, bytes: ByteArray): Outcome<String> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$dir")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл в «Загрузках» не создан", name)
        } catch (e: Exception) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл в «Загрузках» не создан", e, name))
        }
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("нет потока записи")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл в «Загрузках» не записан", e, name))
        }
        return Outcome.success(name)
    }
}
