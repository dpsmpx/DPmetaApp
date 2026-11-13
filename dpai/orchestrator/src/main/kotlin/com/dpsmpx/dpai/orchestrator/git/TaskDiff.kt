package com.dpsmpx.dpai.orchestrator.git

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.git.GitOp

/** Файл в изменениях: строк добавлено и удалено; у двоичного — `null` (Git пишет «-»). */
data class FileChange(val path: String, val added: Int?, val removed: Int?)

/**
 * Изменения задачи (D-42, ТЗ, §70): от базы ветки интеграции [base] до
 * рабочего дерева задачи — с незакоммиченным — или, если дерево уже убрано,
 * до её ветки. [untracked] — новые файлы вне индекса (их нет в diff Git).
 * [patch] — текст изменений не длиннее предела; [truncated] — обрезан.
 */
data class TaskDiff(
    val base: String,
    val mergeBase: String,
    val head: String,
    val files: List<FileChange>,
    val untracked: List<String>,
    val patch: String,
    val truncated: Boolean,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "base" to JsonString(base), "mergeBase" to JsonString(mergeBase), "head" to JsonString(head),
            "files" to JsonArray(
                files.map { f ->
                    JsonObject.of("path" to JsonString(f.path), "added" to (f.added?.let { JsonNumber.of(it) } ?: JsonNull), "removed" to (f.removed?.let { JsonNumber.of(it) } ?: JsonNull))
                },
            ),
            "untracked" to JsonArray(untracked.map(::JsonString)),
            "patch" to JsonString(patch), "truncated" to JsonBool(truncated),
        ),
    )

    companion object {
        /** Предел текста изменений: экран и ответ канала управления, а не выгрузка репозитория. */
        const val MAX_CHARS = 200_000

        /** Предел списка файлов `--numstat`: и он не растёт без предела на огромных изменениях. */
        private const val MAX_STAT_CHARS = 2_000_000

        /**
         * Изменения в [path]: дерево задачи ([branch] = `null` — до рабочего
         * дерева) или репозиторий (до ветки [branch]). Только чтение: команды
         * Git без записи (`GitCommands`, T4).
         */
        fun load(git: GitService, path: String, base: String, branch: String?, maxChars: Int = MAX_CHARS): Outcome<TaskDiff> {
            val head = branch ?: "HEAD"
            // Ветка уже слита в [base] (ревью 17 октября 2026): база слияния — сама её вершина, и diff был бы
            // пуст. Тогда база — от первого родителя коммита, которым её слили (`merge --no-ff`).
            val merged = when (val r = git.run(GitOp.MERGES_BETWEEN, path, listOf(head, base))) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value.stdout.firstOrNull { it.isNotBlank() }?.trim()
            }
            val mergeBase = when (val r = git.run(GitOp.MERGE_BASE, path, listOf(merged?.let { "$it^1" } ?: base, head))) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value.stdout.firstOrNull { it.isNotBlank() }?.trim()
                    ?: return Outcome.failure(DiagCode.VALUE_INVALID, "у $head и $base нет общей базы", path)
            }
            val revs = listOfNotNull(mergeBase, branch)
            val statRun = when (val r = git.run(GitOp.DIFF_STAT_FROM, path, revs, maxStdoutChars = MAX_STAT_CHARS)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            // Текст — не длиннее предела уже при чтении: diff на сотни МБ не занимает память телефона.
            // Только предпросмотр: ниже неполнота явно записывается в truncated и в текст.
            val textRun = when (val r = git.run(GitOp.DIFF_FROM, path, revs, maxStdoutChars = maxChars + 1, allowTruncated = true)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val text = textRun.stdout.joinToString("\n")
            val untracked = if (branch != null) emptyList() else git.status(path).valueOrNull()?.untracked.orEmpty().sorted()
            val cut = textRun.truncated || statRun.truncated || text.length > maxChars
            val patch = when {
                textRun.truncated -> text.take(maxChars).substringBeforeLast('\n') + "\n… обрезано: больше $maxChars знаков"
                text.length > maxChars -> text.take(maxChars).substringBeforeLast('\n') + "\n… обрезано: ${text.length} знаков"
                else -> text
            }
            return Outcome.success(TaskDiff(base, mergeBase, if (branch == null) "рабочее дерево" else branch, statRun.stdout.mapNotNull(::numstat), untracked, patch, cut))
        }

        /** Строка `--numstat`: «добавлено<TAB>удалено<TAB>путь»; у двоичного — «-». */
        private fun numstat(line: String): FileChange? {
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return null
            return FileChange(parts[2], parts[0].toIntOrNull(), parts[1].toIntOrNull())
        }
    }
}
