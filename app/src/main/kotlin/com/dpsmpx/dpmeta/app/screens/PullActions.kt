package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.formats.PrDescription
import com.dpsmpx.dpmeta.github.PullCommit
import com.dpsmpx.dpmeta.github.PullDetail
import com.dpsmpx.dpmeta.github.PullFile
import com.dpsmpx.dpmeta.github.PullNote
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Действия с PR в пульте (F-12, F-13; D-07, D-36): какие команды, что о них
 * показать до подтверждения и когда «Влить» нельзя или можно только после
 * предупреждения. Чистый Kotlin — проверяется на JVM; окна и PIN — у экрана.
 */
object PullActions {
    /** Как подтверждено: показом (уровень 1), PIN устройства (2), вводом имени (3). */
    enum class Way { SHOWN, DEVICE, TYPED }

    /**
     * Команда и способ подтверждения — от экрана к активности: подтверждение создаёт она.
     * [text] — новый текст файла у правки (F-69): идёт исполнителю, не в журнал.
     */
    data class Request(val command: ActionCommand, val way: Way, val typed: String = "", val text: String? = null)

    fun comment(repo: RepoRef, p: PullDetail, body: String) =
        ActionCommand(ActionKind.COMMENT, ActionTarget(repo), mapOf("number" to "${p.number}", "body" to body))

    fun review(repo: RepoRef, p: PullDetail, approve: Boolean, body: String) =
        ActionCommand(ActionKind.REVIEW, ActionTarget(repo), mapOf("number" to "${p.number}", "event" to if (approve) "approve" else "changes", "body" to body))

    fun ready(repo: RepoRef, p: PullDetail) = ActionCommand(ActionKind.PR_READY, ActionTarget(repo), mapOf("number" to "${p.number}"))

    /** «Влить» — показанного коммита: его SHA — в команде, и одобрение на него же. */
    fun merge(repo: RepoRef, p: PullDetail) = ActionCommand(ActionKind.PR_MERGE, ActionTarget(repo), mapOf("number" to "${p.number}", "head" to p.headSha))

    fun close(repo: RepoRef, p: PullDetail) = ActionCommand(ActionKind.PR_CLOSE, ActionTarget(repo), mapOf("number" to "${p.number}"))

    /** Строки PR на экране. [checks] — вердикт раздела «Проверки» описания (F-15): `green`, `red` или `null`. */
    fun lines(p: PullDetail, checks: String?): List<String> = listOf(
        "${p.headRef} → ${p.baseRef}",
        when {
            p.merged -> "Влит"
            p.state != "open" -> "Закрыт"
            p.draft -> "Черновик"
            else -> "Открыт, готов к ревью"
        },
        "Слияние: " + merging(p),
        "Проверки (раздел описания): " + Screens.verdictTitle(checks),
        "Коммит: ${p.headSha.take(7)}",
    )

    private fun merging(p: PullDetail): String = when {
        p.mergeable == false || p.mergeableState == "dirty" -> "конфликт с ${p.baseRef}"
        p.mergeable == null -> "GitHub ещё считает"
        p.mergeableState == "blocked" -> "заблокировано правилами ветки"
        p.mergeableState == "behind" -> "ветка отстаёт от ${p.baseRef}"
        p.mergeableState == "unstable" -> "есть непройденные проверки"
        else -> "без конфликта"
    }

    // --- Карточка PR (F-11) ---

    /** Коммиты, файлы и обсуждение PR — по кнопке, свежие с GitHub. */
    data class Card(val commits: List<PullCommit>, val files: List<PullFile>, val notes: List<PullNote>)

    /** Автор и размер: строки плюс и минус, файлы, коммиты; неизвестное — не ноль, а пропуск. */
    fun sizeLine(p: PullDetail): String = listOfNotNull(
        p.author.takeIf { it.isNotEmpty() }?.let { "автор $it" },
        if (p.additions != null && p.deletions != null) "+${p.additions} −${p.deletions}" else null,
        p.changedFiles?.let { "файлов $it" },
        p.commits?.let { "коммитов $it" },
    ).joinToString(" · ")

    /**
     * Описание по разделам (F-11): «Сделано», «Осталось», «Проверки» — первыми и выделены;
     * прочие разделы — после. Без разделов второго уровня — описание целиком одним куском.
     */
    fun sections(p: PullDetail): List<Pair<String, String>> {
        if (p.body.isBlank()) return emptyList()
        val all = PrDescription.parse(p.body).valueOrNull()?.sections.orEmpty()
        if (all.isEmpty()) return listOf("Описание" to p.body)
        val main = PrDescription.REQUIRED.mapNotNull { k -> all[k]?.let { k to it } }
        return main + all.filterKeys { it !in PrDescription.REQUIRED }.toList()
    }

    /** Раздел шаблона «Сделано / Осталось / Проверки» — выделяется на экране. */
    fun isMain(title: String): Boolean = title in PrDescription.REQUIRED

    fun commitLine(c: PullCommit): String = "${c.sha.take(7)} ${c.message}" + listOfNotNull(c.author.takeIf { it.isNotEmpty() }, date(c.date)).joinToString(" · ").let { if (it.isEmpty()) "" else " — $it" }

    fun fileLine(f: PullFile): String = "${f.path} +${f.additions} −${f.deletions}" + when (f.status) {
        "added" -> " (новый)"
        "removed" -> " (удалён)"
        "renamed" -> " (переименован)"
        else -> ""
    }

    fun noteHead(n: PullNote): String = listOfNotNull(
        n.author.ifEmpty { "?" },
        when (val r = n.review) {
            null -> null
            "APPROVED" -> "одобрил"
            "CHANGES_REQUESTED" -> "просит изменений"
            "COMMENTED" -> "отзыв"
            "DISMISSED" -> "отзыв снят"
            else -> r.lowercase()
        },
        date(n.date),
    ).joinToString(" · ")

    /** Дата GitHub (ISO 8601, UTC) — «2026-11-09 12:00 UTC»; неразборчивое — как есть. */
    private fun date(iso: String): String? = iso.takeIf { it.isNotEmpty() }?.let {
        Regex("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}).*").matchEntire(it)?.let { m -> "${m.groupValues[1]} ${m.groupValues[2]} UTC" } ?: it
    }

    /** Действия с PR есть только у открытого PR. */
    fun open(p: PullDetail): Boolean = p.state == "open" && !p.merged

    /**
     * Почему «Влить» нельзя совсем: PR не открыт, черновик (GitHub его не сольёт — сначала
     * «Из черновика») или конфликт. `null` — можно, возможно с [mergeWarnings].
     */
    fun mergeBlocked(p: PullDetail): String? = when {
        !open(p) -> "PR не открыт"
        p.draft -> "PR — черновик: сначала «Из черновика»"
        p.mergeable == false || p.mergeableState == "dirty" -> "конфликт с ${p.baseRef} — сначала разрешить в ветке"
        else -> null
    }

    /**
     * F-13: «Влить» без предупреждения — только если раздел «Проверки» распознан и не
     * красный, а GitHub не видит препятствий. Иначе — эти причины и второе подтверждение.
     */
    fun mergeWarnings(p: PullDetail, checks: String?): List<String> = buildList {
        when (checks) {
            "green" -> Unit
            "red" -> add("раздел «Проверки» описания — красный")
            else -> add("раздел «Проверки» описания не распознан")
        }
        when {
            p.mergeable == null -> add("GitHub ещё не посчитал, есть ли конфликт")
            p.mergeableState == "blocked" -> add("ветка защищена правилами (проверки или отзывы) — GitHub может отказать")
            p.mergeableState == "behind" -> add("ветка отстаёт от ${p.baseRef}")
            p.mergeableState == "unstable" -> add("на GitHub есть непройденные проверки")
        }
    }

    /** Что уйдёт или изменится — до подтверждения (D-07): по строке на факт. */
    fun preview(c: ActionCommand, p: PullDetail): List<String> {
        val where = "${c.target.repo} #${p.number} «${p.title}»"
        return when (c.kind) {
            ActionKind.COMMENT -> listOf("Комментарий к $where:", c.params["body"].orEmpty())
            ActionKind.REVIEW -> listOf(
                (if (c.params["event"] == "approve") "Одобрение" else "Запрос изменений") + " — $where" + if (c.params["body"].isNullOrBlank()) "" else ":",
                c.params["body"].orEmpty(),
            ).filter { it.isNotEmpty() }
            ActionKind.PR_READY -> listOf("$where — из черновика в готовые к ревью.")
            ActionKind.PR_MERGE -> listOf(
                "Влить $where: ${p.headRef} → ${p.baseRef}.",
                "Способ — merge-коммит: история ${p.baseRef} не переписывается.",
                "Вливается коммит ${p.headSha.take(7)}; пришёл новый — GitHub откажет.",
                "Вернуть можно только новым коммитом (revert).",
            )
            ActionKind.PR_CLOSE -> listOf("Закрыть $where без слияния.", "Ветка ${p.headRef} остаётся; открыть снова можно на GitHub.")
            else -> listOf("${c.kind.what}: $where")
        }
    }
}
