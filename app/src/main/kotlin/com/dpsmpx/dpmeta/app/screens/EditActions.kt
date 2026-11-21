package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.github.FileBlob
import com.dpsmpx.dpai.core.git.RepoRef
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Правка файла из пульта (F-69; D-36): текст → коммит в новую ветку с PR (уровень 1) или
 * прямо в основную ветку (уровень 2). Перед отправкой — дифф и сообщение коммита. Текст
 * файла в журнал не идёт: в команде — его SHA-256, исполнитель сверяет текст с ним.
 */
object EditActions {
    /** Файл к правке: репозиторий, основная ветка, путь в репозитории и блоб. */
    data class Edit(val project: String, val repo: RepoRef, val base: String, val blob: FileBlob)

    /** Предел сообщения коммита в пульте. */
    const val MAX_MESSAGE: Int = 500

    fun defaultMessage(e: Edit): String = "${e.blob.path.substringAfterLast('/')}: правка с телефона"

    /** Почему отправить нельзя; `null` — можно. */
    fun invalid(e: Edit, text: String, message: String): String? = when {
        text == e.blob.text -> "изменений нет"
        message.isBlank() -> "нужно сообщение коммита"
        message.trim().length > MAX_MESSAGE -> "сообщение длиннее $MAX_MESSAGE знаков"
        else -> null
    }

    /** Ветка правки: `dpmetaapp/edit-<файл>-<время UTC>` — своя на каждую правку. */
    fun branchName(e: Edit, now: Timestamp): String {
        val slug = e.blob.path.substringAfterLast('/').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "file" }
        val time = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now.epochMillis))
        return "dpmetaapp/edit-$slug-$time"
    }

    /**
     * Команда правки: [toDefault] — коммит прямо в основную ветку ([ActionKind.COMMIT_DEFAULT],
     * уровень 2), иначе — в ветку [branch] с PR ([ActionKind.FILES_COMMIT_PR], уровень 1).
     */
    fun command(e: Edit, text: String, message: String, toDefault: Boolean, branch: String): ActionCommand {
        val (added, removed) = LineDiff.stats(LineDiff.diff(e.blob.text, text))
        return ActionCommand(
            if (toDefault) ActionKind.COMMIT_DEFAULT else ActionKind.FILES_COMMIT_PR,
            ActionTarget(e.repo, if (toDefault) e.base else branch),
            mapOf(
                "path" to e.blob.path,
                "blob" to e.blob.sha,
                "base" to e.base,
                "message" to message.trim(),
                "content_sha256" to sha256(text),
                "lines" to "+$added -$removed",
            ),
        )
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Окно «что уйдёт» / «что изменится»: куда, сообщение, счёт строк; дифф — отдельно. */
    fun preview(c: ActionCommand): List<String> = buildList {
        val path = c.params["path"].orEmpty()
        if (c.kind == ActionKind.COMMIT_DEFAULT) {
            add("Коммит прямо в ${c.target.branch} репозитория ${c.target.repo}: $path.")
            add("Проверки репозитория его не увидят до коммита; вернуть — новым коммитом (revert).")
        } else {
            add("Новая ветка ${c.target.branch} от ${c.params["base"]}, коммит $path и PR в ${c.params["base"]}.")
        }
        add("Сообщение: ${c.params["message"]}")
        add("Строк: ${c.params["lines"]}")
    }
}
