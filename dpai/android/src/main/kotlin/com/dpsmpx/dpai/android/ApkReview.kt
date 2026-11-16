package com.dpsmpx.dpai.android

import android.annotation.SuppressLint
import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Проверка APK перед установкой и подписью (D-157; угроза T22). Файл дерева
 * могут подменить между показом сведений и нажатием — ход ИИ, процесс гостя,
 * — поэтому он сначала копируется в хранилище приложения ([stage]); сведения
 * (пакет, версия, подпись, SHA-256) читаются с копии, и ставится или
 * подписывается та же копия. Окно установки Android отпечаток подписи не
 * показывает — его показывает окно приложения до установки.
 */
internal object ApkReview {
    /** Предел копии: APK больше — не сборка проекта на телефоне. */
    const val MAX_BYTES: Long = 512L shl 20

    /** Запас свободного места сверх копии. */
    private const val RESERVE_BYTES: Long = 64L shl 20

    private const val DIR = "apk-review"

    /** Что показано человеку: файл дерева, его копия, сведения и строка журнала. */
    data class Review(val source: File, val copy: File, val meta: ApkInstaller.Meta?, val sha256: String, val text: String, val log: String) {
        val self: Boolean get() = meta?.self == true
    }

    /** Копия [file] в [dir]; прежние копии удаляются — проверка идёт одна. */
    // Консервативная проверка фактически свободного места: кэш других приложений не вытесняем.
    @SuppressLint("UsableSpace")
    fun stage(file: File, dir: File): Outcome<File> {
        if (!file.isFile) return Outcome.failure(DiagCode.VALUE_INVALID, "файл вне дерева или исчез", file.name)
        if (file.length() > MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "APK больше ${MAX_BYTES shr 20} МБ", file.name)
        dir.deleteRecursively()
        if (!dir.mkdirs() && !dir.isDirectory) return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "нет папки для проверки APK", dir.path)
        // Защита места (D-152): копия и запас под систему.
        val free = dir.usableSpace
        if (free < file.length() + RESERVE_BYTES) {
            return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "не хватает места для проверки APK: нужно ${(file.length() + RESERVE_BYTES) shr 20} МБ, свободно ${free shr 20} МБ", file.name)
        }
        val copy = File(dir, file.name)
        return try {
            val copied = file.inputStream().use { input -> copy.outputStream().use { copyAtMost(input, it, MAX_BYTES + 1) } }
            if (copied > MAX_BYTES) {
                copy.delete()
                Outcome.failure(DiagCode.VALUE_INVALID, "APK вырос больше ${MAX_BYTES shr 20} МБ во время копирования", file.name)
            } else {
                Outcome.success(copy)
            }
        } catch (e: IOException) {
            copy.delete()
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "APK не скопирован для проверки", e, file.name))
        }
    }

    /** Скопировать [file] и прочитать сведения копии; [keySha256] — отпечаток своего ключа подписи. */
    fun prepare(context: Context, file: File, keySha256: String?): Outcome<Review> {
        val copy = when (val c = stage(file, File(context.cacheDir, DIR))) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        return try {
            val meta = ApkInstaller.meta(context, copy)
            val sig = ApkSign.inspect(copy)
            val sha = ApkSign.fileSha256(copy)
            Outcome.success(Review(file, copy, (meta as? Outcome.Success)?.value, sha, describe(meta, sig, sha, keySha256), logLine(meta, sig, sha)))
        } catch (e: IOException) {
            copy.delete()
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "копия APK не прочитана", e, file.name))
        }
    }

    /** Убрать копию: установка её уже передала системе, отмена — не нужна. */
    fun discard(review: Review?) {
        review?.copy?.delete()
    }

    /** Сведения словами — для окна «Сведения» и окна перед установкой или подписью. */
    fun describe(meta: Outcome<ApkInstaller.Meta>, sig: Outcome<ApkSign.Signature>, sha256: String, keySha256: String?): String = buildString {
        when (meta) {
            is Outcome.Success -> {
                append("Пакет: ${meta.value.packageName}\nВерсия: ${meta.value.versionName ?: "?"} (${meta.value.versionCode})\n")
                append("Установлено: ${meta.value.installed ?: "нет"}\n")
                if (meta.value.self) append("Это APK самого DP_IDE.\n")
            }
            is Outcome.Failure -> append(meta.diagnostics.first().message + "\n")
        }
        when (sig) {
            is Outcome.Success -> {
                append("Подпись: ${if (sig.value.verified) sig.value.schemes.joinToString() else "нет или неверна"}\n")
                sig.value.subject?.let { append("Кем: $it\n") }
                sig.value.certSha256?.let { append("Сертификат SHA-256: $it\n") }
                if (keySha256 != null && sig.value.certSha256 == keySha256) append("Подписан вашим ключом.\n")
            }
            is Outcome.Failure -> append(sig.diagnostics.first().message + "\n")
        }
        append("SHA-256 файла: $sha256")
    }

    /** Строка журнала (T22): пакет и версия, субъект и отпечаток подписи, сумма файла. */
    fun logLine(meta: Outcome<ApkInstaller.Meta>, sig: Outcome<ApkSign.Signature>, sha256: String): String {
        val what = (meta as? Outcome.Success)?.value?.let { "${it.packageName} ${it.versionName ?: "?"} (${it.versionCode})" } ?: "пакет не прочитан"
        val who = (sig as? Outcome.Success)?.value?.takeIf { it.verified }?.let { "подпись ${it.subject ?: "?"}, сертификат SHA-256 ${it.certSha256 ?: "?"}" } ?: "подписи нет или она неверна"
        return "$what; $who; файл SHA-256 $sha256"
    }

    private fun copyAtMost(input: InputStream, output: OutputStream, limit: Long): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
        return total
    }
}
