package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

/**
 * База установленных пакетов Alpine гостя — `/lib/apk/db/installed` (D-158),
 * читается с хоста, без процесса гостя.
 *
 * Запись пакета — строки `X:значение`, записи — через пустую строку; `P:` —
 * имя, `V:` — версия, `f:` — пакет повреждён: `f` — не все файлы на месте,
 * `s` — скрипт пакета не прошёл, `x` — атрибуты файлов. Такой пакет apk
 * считает ошибкой каждой следующей команды («N errors; …» и код возврата N):
 * на телефоне так случилось с binutils, gcc и g++, когда proot ещё не
 * заменял жёсткие ссылки (`docs/research/LINUX_IN_APP.md`, §11). Чинит его
 * `apk fix` тех же пакетов.
 */
object ApkDatabase {
    /** Путь базы от корня гостя. */
    const val PATH: String = "lib/apk/db/installed"

    /** Больше — не база пакетов среды приложения (у 100 пакетов — сотни КБ). */
    const val MAX_BYTES: Int = 32 shl 20

    /** Повреждённый пакет: имя, версия и что именно не так. */
    data class Broken(val name: String, val version: String?, val files: Boolean, val script: Boolean, val xattrs: Boolean) {
        /** Что не так — словами, для экрана и журнала. */
        val why: String
            get() = listOfNotNull("не все файлы".takeIf { files }, "скрипт пакета".takeIf { script }, "атрибуты файлов".takeIf { xattrs })
                .ifEmpty { listOf("отмечен повреждённым") }.joinToString(", ")
    }

    /** Разбор: только пакеты с отметкой `f:`; имя вне [LinuxEnvironment.PACKAGE] — пропуск (в `apk` оно не уйдёт). */
    fun broken(text: String): List<Broken> = records(text).mapNotNull { r ->
        val name = r["P"] ?: return@mapNotNull null
        val flags = r["f"] ?: return@mapNotNull null
        if (!LinuxEnvironment.PACKAGE.matches(name)) return@mapNotNull null
        Broken(name, r["V"], 'f' in flags, 's' in flags, 'x' in flags)
    }

    /** Имена установленных пакетов. */
    fun installed(text: String): Set<String> = records(text).mapNotNull { it["P"] }.toSet()

    /**
     * Повреждённые пакеты корневой системы [rootfs]. Базы нет — пустой список
     * (среда не установлена или apk ещё ничего не ставил). Каталог по пути —
     * символьная ссылка гостя — отказ: базу пишет гость, читать чужой файл
     * приложения под её видом незачем (как `resolv.conf`, A-3).
     */
    fun read(vfs: Vfs, rootfs: String): Outcome<String?> {
        var at = rootfs
        for (part in PATH.split('/')) {
            at = Vfs.join(at, part)
            if (vfs.isSymlink(at)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "база пакетов гостя — символьная ссылка", at)
        }
        return try {
            val size = vfs.size(at) ?: return Outcome.success(null)
            if (size > MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "база пакетов гостя больше ${MAX_BYTES shr 20} МБ", at)
            Outcome.success(vfs.read(at)?.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "база пакетов гостя не прочитана", e, at)))
        }
    }

    private fun records(text: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        var cur = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) {
                if (cur.isNotEmpty()) out += cur
                cur = HashMap()
                continue
            }
            if (line.length < 2 || line[1] != ':') continue
            // Первое значение поля: у пакета одно имя и одна версия, файлы (`R:`) не нужны.
            cur.putIfAbsent(line.substring(0, 1), line.substring(2))
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}
