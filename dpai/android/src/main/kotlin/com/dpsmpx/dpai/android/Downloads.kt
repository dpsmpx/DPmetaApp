package com.dpsmpx.dpai.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Файлы приложения в «Загрузки/<каталог>» через MediaStore — без
 * разрешений: копии настроек (D-51) и выгрузка журнала (D-43). Такие
 * файлы переживают удаление приложения; пишет их только своё приложение —
 * таймером копий или кнопкой человека.
 */
object Downloads {
    /**
     * Записать [bytes] в «Загрузки/[dir]/[name]». Файл виден другим, только
     * когда дописан (`IS_PENDING`); сбой записи — файл удаляется, а не
     * остаётся обрезанным. [what] — что пишется, для сообщения.
     */
    fun write(context: Context, dir: String, name: String, mime: String, bytes: ByteArray, what: String): Outcome<Uri> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$dir")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "$what: файл не создан", name)
        } catch (e: Exception) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "$what: файл не создан", e, name))
        }
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("нет потока записи")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "$what: файл не записан", e, name))
        }
        return Outcome.success(uri)
    }

    /** Старше [keep] своих файлов «Загрузки/[dir]/[prefix]…» — удалить (чужие, прежней установки, MediaStore не отдаёт и не трогаем). */
    fun prune(context: Context, dir: String, prefix: String, keep: Int) {
        val resolver = context.contentResolver
        val ids = mutableListOf<Long>()
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$dir/", "$prefix%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        }
        ids.drop(keep).forEach { id -> runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) } }
    }
}
