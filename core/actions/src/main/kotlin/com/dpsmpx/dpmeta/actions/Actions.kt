package com.dpsmpx.dpmeta.actions

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.OperationId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.security.Approval
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.security.Proposal
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.git.RepoRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Действие, которое приложение умеет исполнять (ТЗ, §6.5; D-07, D-36, D-37). Уровень —
 * у вида действия, а не у вызова: исполнитель не может «понизить» цену слиянию или
 * удалению. Чего нет в каталоге, того приложение не делает: мутирующих вызовов Admin
 * API (D-08), выгрузки сайта в обход подсистемы (D-60), действий на площадках (этап 6).
 */
enum class ActionKind(val code: String, val level: ActionLevel, val what: String, val named: Named) {
    // --- уровень 1: обратимое и видимое (D-07, D-36) ---
    COMMENT("comment", ActionLevel.REVERSIBLE, "комментарий к PR или issue", Named.REPOSITORY),
    REVIEW("review", ActionLevel.REVERSIBLE, "ревью PR", Named.REPOSITORY),
    ISSUE_OPEN("issue.open", ActionLevel.REVERSIBLE, "новый issue", Named.REPOSITORY),
    PR_READY("pr.ready", ActionLevel.REVERSIBLE, "перевести PR из черновика в готовые", Named.REPOSITORY),
    ROUTE_RUN("route.run", ActionLevel.REVERSIBLE, "запуск маршрута — поручение сессии Claude", Named.REPOSITORY),
    FILES_COMMIT_PR("files.commit-pr", ActionLevel.REVERSIBLE, "правка файлов коммитом в новую ветку и PR", Named.BRANCH),

    /** Команда реестра уровня 1 (F-40, D-82): правка рабочего дерева клона в Linux-среде. */
    COMMAND_RUN("command.run", ActionLevel.REVERSIBLE, "команда реестра — правка рабочего дерева клона", Named.REPOSITORY),

    /** «Опубликовать на GitHub» (D-150): закрытый репозиторий для проекта с устройства. */
    REPO_CREATE("repo.create", ActionLevel.REVERSIBLE, "новый закрытый репозиторий для проекта с устройства", Named.REPOSITORY),

    // --- уровень 2: значимое (D-07, D-36) ---
    PR_MERGE("pr.merge", ActionLevel.SIGNIFICANT, "влить PR без перезаписи истории", Named.REPOSITORY),
    PR_CLOSE("pr.close", ActionLevel.SIGNIFICANT, "закрыть PR", Named.REPOSITORY),
    COMMIT_DEFAULT("files.commit-default", ActionLevel.SIGNIFICANT, "коммит прямо в ветку по умолчанию", Named.BRANCH),
    BRANCH_DELETE_MERGED("branch.delete-merged", ActionLevel.SIGNIFICANT, "удалить влитую ветку", Named.BRANCH),

    // --- уровень 3: опасное и необратимое (D-37) ---
    HISTORY_REWRITE("history.rewrite", ActionLevel.DANGEROUS, "перезапись истории и force-push", Named.BRANCH),
    BRANCH_DELETE_UNMERGED("branch.delete-unmerged", ActionLevel.DANGEROUS, "удалить невлитую ветку", Named.BRANCH),
    REPO_DELETE("repo.delete", ActionLevel.DANGEROUS, "удалить репозиторий", Named.REPOSITORY),
    REPO_SETTINGS("repo.settings", ActionLevel.DANGEROUS, "изменить настройки репозитория", Named.REPOSITORY),
    BRANCH_PROTECTION("branch.protection", ActionLevel.DANGEROUS, "изменить защиту ветки", Named.BRANCH),
    ;

    /** Что вводит человек на красном экране уровня 3 (D-37): имя репозитория или ветки. */
    enum class Named { REPOSITORY, BRANCH }

    companion object {
        fun of(code: String): ActionKind? = entries.firstOrNull { it.code == code }
    }
}

/** Над чем действие: репозиторий и, если нужно, ветка. */
data class ActionTarget(val repo: RepoRef, val branch: String? = null) {
    /** Как цель видна в журнале и на экране: `владелец/имя` или `владелец/имя@ветка`. */
    val display: String get() = "$repo" + (branch?.let { "@$it" } ?: "")
}

/**
 * Команда действия (ТЗ, §6.5): `{вид, цель, параметры}`. Параметры — строки; в журнал
 * (F-44) они идут через `Redactor` ядра — похожее на токен или ключ вырезается.
 * [profile] — от чьего имени (например, вход GitHub), для журнала.
 */
data class ActionCommand(val kind: ActionKind, val target: ActionTarget, val params: Map<String, String> = emptyMap(), val profile: String = "") {
    val level: ActionLevel get() = kind.level

    /** Предложение для одобрения (`dpai/security`): одобрение привязано к его хешу. */
    val proposal: Proposal get() = Proposal(kind.code, target.display, params.toSortedMap().map { (k, v) -> "$k=$v" })

    /** Имя, которое нужно ввести для уровня 3 (D-37). */
    val nameToType: String get() = when (kind.named) {
        ActionKind.Named.REPOSITORY -> "${target.repo}"
        ActionKind.Named.BRANCH -> target.branch ?: "${target.repo}"
    }
}

/**
 * Подтверждение человека (D-07, D-37) — одобрение [approval] на предложение команды
 * (`dpai/security`: одноразовое, с истечением). Его выдаёт оболочка после своего экрана:
 * [Shown] — показала, что уйдёт, и было нажатие; [Device] — экран «что изменится» и
 * успешная проверка PIN или биометрии устройства; [TypedName] — красный экран и
 * введённый текст. Ядро проверяет, что подтверждение того вида, какого требует уровень.
 */
sealed class Confirmation(val approval: Approval, val way: String) {
    class Shown(approval: Approval) : Confirmation(approval, SHOWN)

    class Device(approval: Approval) : Confirmation(approval, DEVICE)

    class TypedName(approval: Approval, val typed: String) : Confirmation(approval, TYPED_NAME)

    companion object {
        const val SHOWN = "shown"
        const val DEVICE = "device"
        const val TYPED_NAME = "typed-name"
    }
}

/** Итог исполнения: строка для человека и, если есть, ссылка на результат (PR, коммит). */
data class ActionResult(val detail: String, val link: String? = null)

/**
 * Действия приложения (ТЗ, §6.5; A-06): политика уровня, исполнение, журнал.
 *
 * - Уровень 0 — исполняется сразу и в журнал не пишется (D-07).
 * - Уровень 1 — нужно подтверждение «показано» или сильнее; 2 — только [Confirmation.Device];
 *   3 — только [Confirmation.TypedName] с точным [ActionCommand.nameToType] (D-37: без
 *   биометрии). Одобрение — на это же предложение, не истекло и не использовано.
 * - Перед исполнением — запись «запрошено» в журнал (с fsync); не записалось — действие
 *   не исполняется. После — итог. Отказ политики — тоже запись. Запрошенное без итога в
 *   журнале — процесс умер во время исполнения: итог неизвестен ([ActionLog]).
 *
 * Исполнитель ([run], `execute`) — у оболочки: `:core:actions` от GitHub и Git не зависит.
 */
class Actions(private val log: ActionLog, private val clock: Clock, random: RandomSource) {
    private val ids = IdGenerator(clock, random)
    private val ledger = ApprovalLedger()

    /** Подтверждение «показано, нажато» (уровень 1) — сразу после экрана «что уйдёт». */
    fun shown(command: ActionCommand): Confirmation.Shown = Confirmation.Shown(approve(command, Confirmation.SHOWN))

    /** Подтверждение PIN или биометрией (уровень 2) — только после успешной проверки устройства. */
    fun deviceConfirmed(command: ActionCommand): Confirmation.Device = Confirmation.Device(approve(command, Confirmation.DEVICE))

    /** Ввод имени на красном экране (уровень 3); совпадение проверяет [run]. */
    fun typedName(command: ActionCommand, typed: String): Confirmation.TypedName = Confirmation.TypedName(approve(command, Confirmation.TYPED_NAME), typed)

    private fun approve(command: ActionCommand, way: String): Approval {
        val now = clock.now()
        return Approval(ids.next(IdKinds.APPROVAL), command.proposal.hash, way, now, now + APPROVAL_TTL)
    }

    /**
     * Исполнить [command] с [confirmation]: политика → запись «запрошено» → [execute] →
     * запись итога. Отказ политики — `OPERATION_NOT_ALLOWED` или отказ одобрения
     * (`APPROVAL_REQUIRED`, `APPROVAL_INVALID`), [execute] не вызывается.
     */
    fun run(command: ActionCommand, confirmation: Confirmation?, execute: () -> Outcome<ActionResult>): Outcome<ActionResult> {
        validate(command)?.let { return refuse(command, confirmation, it) }
        if (command.level == ActionLevel.READ) return guarded(execute)
        policy(command, confirmation)?.let { return refuse(command, confirmation, it) }
        val consumed = ledger.consume(command.proposal, confirmation!!.approval, clock.now())
        if (consumed is Outcome.Failure) return refuse(command, confirmation, consumed.errors.first())
        val id: OperationId = ids.next(IdKinds.OPERATION)
        when (val w = log.requested(id, command, confirmation.way)) {
            is Outcome.Failure -> return w
            is Outcome.Success -> Unit
        }
        val result = guarded(execute)
        // Итог не записался — действие всё равно было: возвращаем итог, а журнал при чтении
        // покажет «итог неизвестен» (запрошено без итога).
        log.settled(id, command, result)
        return result
    }

    private fun guarded(execute: () -> Outcome<ActionResult>): Outcome<ActionResult> = try {
        execute()
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "исполнитель действия упал", e)))
    }

    private fun policy(command: ActionCommand, c: Confirmation?): Diagnostic? {
        val level = command.level
        if (c == null) return Diagnostic.of(DiagCode.APPROVAL_REQUIRED, "действие уровня ${level.code} — ${level.confirmation}", command.target.display)
        return when (level) {
            ActionLevel.READ -> null
            ActionLevel.REVERSIBLE -> null
            ActionLevel.SIGNIFICANT -> if (c is Confirmation.Device) null else
                Diagnostic.of(DiagCode.OPERATION_NOT_ALLOWED, "уровень 2 — только после PIN или биометрии устройства", command.target.display)
            ActionLevel.DANGEROUS -> when {
                c !is Confirmation.TypedName -> Diagnostic.of(DiagCode.OPERATION_NOT_ALLOWED, "уровень 3 — только с вводом имени", command.target.display)
                c.typed.trim() != command.nameToType -> Diagnostic.of(DiagCode.OPERATION_NOT_ALLOWED, "введённое имя не совпало — действие не выполнено", command.target.display)
                else -> null
            }
        }
    }

    private fun refuse(command: ActionCommand, c: Confirmation?, why: Diagnostic): Outcome<ActionResult> {
        // Отказ действия уровня 1–3 — в журнал: «что я пытался сделать» тоже ответ (F-44).
        if (command.level != ActionLevel.READ) log.refused(ids.next(IdKinds.OPERATION), command, c?.way, why)
        return Outcome.Failure(listOf(why))
    }

    companion object {
        /** Сколько живёт подтверждение экрана до исполнения: старое нажатие — не согласие на новое. */
        val APPROVAL_TTL: Duration = 2.minutes

        private val PARAM_KEY = Regex("[a-z][a-z0-9_.-]{0,39}")

        /** Имя ветки: без пробелов и управляющих знаков, не с «-» (не станет ключом `git`), без «..». */
        private val BRANCH = Regex("[A-Za-z0-9._/-]{1,255}")
        const val MAX_PARAM_CHARS: Int = 4000
        const val MAX_PARAMS: Int = 32

        /**
         * Команда пригодна к исполнению и журналу: ключи параметров — `[a-z][a-z0-9_.-]*`,
         * значения без управляющих знаков (кроме перевода строки) и не длиннее
         * [MAX_PARAM_CHARS]; ветка — где нужна, и имя её допустимо. Похожее на секрет в
         * значениях не отказ (SHA коммита и слово «auth» в тексте комментария — не секреты):
         * в журнал значения идут через `Redactor` ядра ([ActionLog]), секрет туда не попадает.
         */
        fun validate(command: ActionCommand): Diagnostic? {
            if (command.params.size > MAX_PARAMS) return Diagnostic.of(DiagCode.VALUE_INVALID, "параметров больше $MAX_PARAMS", command.kind.code)
            if (command.kind.named == ActionKind.Named.BRANCH && command.target.branch.isNullOrBlank()) {
                return Diagnostic.of(DiagCode.FIELD_MISSING, "для «${command.kind.what}» нужна ветка", command.target.display)
            }
            command.target.branch?.let { br ->
                if (!BRANCH.matches(br) || br.startsWith("-") || ".." in br || br.endsWith("/") || br.startsWith("/")) {
                    return Diagnostic.of(DiagCode.VALUE_INVALID, "недопустимое имя ветки", br)
                }
            }
            for ((k, v) in command.params) {
                if (!PARAM_KEY.matches(k)) return Diagnostic.of(DiagCode.VALUE_INVALID, "ключ параметра — латиница, цифры, «._-»", k)
                if (v.length > MAX_PARAM_CHARS) return Diagnostic.of(DiagCode.VALUE_INVALID, "значение длиннее $MAX_PARAM_CHARS знаков", k)
                if (v.any { it.isISOControl() && it != '\n' }) return Diagnostic.of(DiagCode.VALUE_INVALID, "управляющие знаки в значении", k)
            }
            return null
        }
    }
}
