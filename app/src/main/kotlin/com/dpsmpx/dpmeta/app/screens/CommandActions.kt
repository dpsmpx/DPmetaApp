package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.formats.CommandSpec
import com.dpsmpx.dpmeta.git.Clones
import com.dpsmpx.dpmeta.model.ProjectCatalog
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Команды реестра в Linux-среде приложения (F-40, F-43; D-82): где команда идёт,
 * готова ли она — по шагам — и как уровень 1 проходит
 * `:core:actions` (D-07). Запуск — мостом оболочки к Linux-среде подсистемы «AI»;
 * здесь — только смысл. Чистый Kotlin.
 */
object CommandActions {
    /** Клоны пульта в госте (D-111, D-113): `/root/projects/<владелец>/<имя>`; прежний `/root/repos` — ссылка. */
    const val GUEST_ROOT: String = "/root/projects"

    /** Прежний корень клонов (D-72): ссылка на [GUEST_ROOT]; у команд реестра — тот же смысл. */
    const val LEGACY_GUEST_ROOT: String = "/root/repos"

    /** Строк вывода на экране — последние; длиннее — счёт отброшенных. */
    const val MAX_LINES: Int = 2_000

    /**
     * Где идёт команда: проект, репозиторий клона и каталог внутри клона ([inClone],
     * пусто — корень). У подсистемы клон — репозитория управления, каталог — её `dir`.
     */
    data class Spot(val project: String, val repo: RepoRef, val inClone: String) {
        val guestDir: String get() = "$GUEST_ROOT/$repo" + if (inClone.isEmpty()) "" else "/$inClone"
    }

    /**
     * Место команды [spec]; у команды для любого проекта — [chosen]. Проект — из
     * реестра и не из архива; «любой» — только проект со своим репозиторием (у
     * подсистемы рабочее дерево — DPmetaApp, его догоняет строка DPmetaApp).
     */
    fun spot(spec: CommandSpec, catalog: ProjectCatalog, chosen: String?): Outcome<Spot> {
        val name = if (spec.anyProject) chosen ?: return Outcome.failure(DiagCode.FIELD_MISSING, "выберите проект", spec.id) else spec.project
        val entry = catalog.byName(name)
            ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "проекта нет в списке пользователя", name)
        return Outcome.success(Spot(name, entry.repo, spec.workdir.orEmpty()))
    }

    /** Проекты из списка пользователя с локальным клоном. */
    fun anyProjects(catalog: ProjectCatalog, hasClone: (RepoRef) -> Boolean): List<String> =
        catalog.projects.filter { hasClone(it.repo) }.map { it.name }

    /** Командная строка гостя: программа из клона без «/» — от каталога (`./`), а не из `PATH`. */
    fun argv(spec: CommandSpec): List<String> =
        listOf(if (spec.absolute || '/' in spec.path) spec.path else "./${spec.path}") + spec.args

    /**
     * Файл программы в клоне — путь от корня клона: сама программа из клона или скрипт —
     * первый аргумент программы гостя (`/bin/bash tools/check.sh`); иначе — `null`.
     */
    fun scriptInClone(spec: CommandSpec, spot: Spot): String? {
        val script = if (!spec.absolute) spec.path else spec.args.firstOrNull()?.takeIf { a -> !a.startsWith("-") && !a.startsWith("/") && ('/' in a || '.' in a) && a.split('/').none { it == ".." } }
        return script?.let { listOf(spot.inClone, it).filter(String::isNotEmpty).joinToString("/") }
    }

    /** Реестр команд для экрана: команды, реестр проектов и проекты для команды «любой проект». */
    data class Catalog(val commands: List<CommandSpec>, val catalog: ProjectCatalog, val anyProjects: List<String>)

    /**
     * Команда в Linux-среде приложения: рабочее дерево клона, готовность по
     * шагам и каких пакетов нет. Мост Termux снят D-173.
     */
    data class Checked(
        val spot: Spot,
        val tree: Clones.Worktree?,
        val steps: List<Step>,
        val missing: List<String> = emptyList(),
    ) {
        val ready: Boolean get() = ready(steps)
        val dir: String get() = spot.guestDir
    }

    /** Состояние Linux-среды приложения. */
    enum class Linux { UNSUPPORTED, NOT_INSTALLED, READY }

    /**
     * Факты о готовности, собранные оболочкой: Linux, недостающие пакеты, клон,
     * каталог и файл программы в клоне ([script] — `null`, если проверять нечего).
     */
    data class Facts(val linux: Linux, val missing: List<String>, val clone: Boolean, val workdir: Boolean, val script: Boolean?)

    /** Шаг готовности: что проверено, прошло ли и что сделать, если нет. */
    data class Step(val title: String, val ok: Boolean, val hint: String? = null)

    /** Готовность по шагам (D-82): первый непройденный шаг объясняет, что сделать. */
    fun readiness(spec: CommandSpec, spot: Spot, f: Facts): List<Step> = listOf(
        when (f.linux) {
            Linux.READY -> Step("Linux в приложении установлен", true)
            Linux.NOT_INSTALLED -> Step("Linux в приложении не установлен", false, "Установите: «AI» → «Настройки AI» → «Linux в приложении».")
            Linux.UNSUPPORTED -> Step("Linux в приложении — только на телефоне с arm64", false, "Команды реестра на этом телефоне не идут.")
        },
        if (spec.packages.isEmpty()) {
            Step("Пакеты не нужны", true)
        } else if (f.linux != Linux.READY) {
            Step("Пакеты ${spec.packages.joinToString(", ")} — после установки Linux", false)
        } else if (f.missing.isEmpty()) {
            Step("Пакеты есть: ${spec.packages.joinToString(", ")}", true)
        } else {
            Step("Нет пакетов: ${f.missing.joinToString(", ")}", false, "Нажмите «Поставить пакеты» — их поставит apk Linux-среды.")
        },
        Step(
            if (f.clone) "Клон ${spot.repo} есть" else "Клона ${spot.repo} нет",
            f.clone,
            if (f.clone) null else "Отметьте репозиторий: «Ещё» → «Клоны…», затем «Обновить».",
        ),
        Step(
            if (spot.inClone.isEmpty()) "Каталог — корень клона" else if (f.workdir) "Каталог ${spot.inClone} есть" else "Каталога ${spot.inClone} нет",
            f.clone && f.workdir,
            if (f.clone && !f.workdir) "Рабочее дерево клона без этого каталога — догоните origin или проверьте ветку." else null,
        ),
        when (f.script) {
            null -> Step("Программа — ${spec.path}", f.clone && f.workdir)
            true -> Step("Скрипт ${scriptInClone(spec, spot)} есть", true)
            false -> Step("Скрипта ${scriptInClone(spec, spot)} нет", false, "В рабочем дереве клона его нет — догоните origin или проверьте ветку.")
        },
    )

    fun ready(steps: List<Step>): Boolean = steps.all { it.ok }

    /** Строка о рабочем дереве (D-70): ветка, коммит и голова GitHub — на чём идёт команда. */
    fun treeLine(t: Clones.Worktree?): String {
        if (t == null) return "Рабочее дерево не прочитано."
        val sha = t.sha?.take(7) ?: return "В клоне нет коммитов."
        val branch = t.branch ?: return "Рабочее дерево — на коммите $sha без ветки."
        val up = t.upstream?.take(7) ?: return "Рабочее дерево — ветка $branch, $sha; на GitHub такой ветки нет."
        return if (t.even) "Рабочее дерево — ветка $branch, $sha, как на GitHub." else "Рабочее дерево — ветка $branch, $sha; на GitHub — $up."
    }

    /**
     * Команда журнала для уровня 1 (D-07, D-82): какая команда, где и на каком коммите
     * рабочего дерева; вывода и аргументов от человека нет — их и не бывает (F-40).
     */
    fun command(spec: CommandSpec, spot: Spot, tree: Clones.Worktree?): ActionCommand {
        require(spec.level == 1) { "в журнал — только команды уровня 1" }
        return ActionCommand(
            ActionKind.COMMAND_RUN,
            ActionTarget(spot.repo),
            linkedMapOf(
                "command" to spec.id,
                "project" to spot.project,
                "dir" to spot.guestDir,
                "line" to spec.line,
            ) + (tree?.sha?.let { mapOf("before" to it) } ?: emptyMap()),
        )
    }

    /** Окно «что изменится» (уровень 1): что, где и чего ждать. */
    fun preview(spec: CommandSpec, spot: Spot, tree: Clones.Worktree?): List<String> = listOfNotNull(
        "«${spec.title}» в ${spot.guestDir}.",
        "Команда: ${spec.line}",
        treeLine(tree),
        spec.expect?.let { "Ждать: $it." },
        "Меняется только рабочее дерево клона на телефоне; GitHub не меняется.",
    )

    /** Итог запуска: код возврата (`null` — процесс остановлен), остановлен ли человеком или пределом времени. */
    data class Exit(val code: Int?, val stopped: Boolean = false, val timedOut: Boolean = false)

    /** Строка итога для экрана. */
    fun exitLine(e: Exit, seconds: Long): String = when {
        e.timedOut -> "Остановлено: предел времени вышел ($seconds с)."
        e.stopped -> "Остановлено вами ($seconds с)."
        e.code == 0 -> "Готово: код 0 ($seconds с)."
        else -> "Код возврата ${e.code} ($seconds с)."
    }

    /**
     * Исполнение уровня 1 после политики и журнала `:core:actions`: [run] — запуск
     * оболочки; код 0 — выполнено, иначе — отказ с кодом (журнал: «не выполнено»).
     */
    fun execute(c: ActionCommand, run: () -> Outcome<Exit>): Outcome<ActionResult> {
        if (c.kind != ActionKind.COMMAND_RUN) return Outcome.failure(DiagCode.UNSUPPORTED, "не команда реестра", c.target.display)
        return when (val r = run()) {
            is Outcome.Failure -> r
            is Outcome.Success -> when {
                r.value.timedOut -> Outcome.failure(DiagCode.PROCESS_TIMEOUT, "предел времени вышел — команда остановлена", c.target.display)
                r.value.stopped -> Outcome.failure(DiagCode.CANCELLED_BY_USER, "команда остановлена", c.target.display)
                r.value.code == 0 -> Outcome.success(ActionResult("«${c.params["command"]}» — код 0", null))
                else -> Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, "код возврата ${r.value.code}", c.target.display)
            }
        }
    }

    /** Вывод для экрана: последние [MAX_LINES] строк; отброшенные — счётом первой строкой. */
    fun tail(lines: List<String>, dropped: Int = 0): List<String> {
        val cut = (lines.size - MAX_LINES).coerceAtLeast(0) + dropped
        return (if (cut > 0) listOf("… выше — ещё строк: $cut") else emptyList()) + lines.takeLast(MAX_LINES)
    }
}
