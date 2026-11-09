package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.formats.Brief
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Поручение Claude из пульта (F-22, F-23, F-25; D-07): маршрут, шаблон, поля → текст →
 * окно «что уйдёт» → запуск маршрута (уровень 1). Маршруты и их токены ведёт подсистема
 * «AI» (`DP_AI_Orcestrator:D-116`); пульт видит только [Route] и зовёт запуск через
 * оболочку. Текст поручения в журнал не идёт: в команде — его SHA-256 и длина,
 * исполнитель сверяет текст с ними. Чистый Kotlin.
 */
object RouteActions {
    /** Маршрут глазами пульта: id, имя, проект и шаблон по умолчанию, есть ли токен. */
    data class Route(val id: String, val name: String, val project: String?, val brief: String?, val hasToken: Boolean)

    /** Запуск состоялся: облачная сессия и ссылка на неё (`https://claude.ai/code/…`). */
    data class Fired(val sessionId: String, val url: String)

    /** Встроенные места шаблона: проект, репозиторий, основная ветка. */
    fun builtIn(project: String, repo: RepoRef, base: String): Map<String, String> =
        mapOf("project" to project, "repo" to "$repo", "base" to base)

    /** Маршрут по умолчанию: заведённый для проекта, иначе — первый с токеном. */
    fun pick(routes: List<Route>, project: String): Route? =
        routes.firstOrNull { it.project == project && it.hasToken } ?: routes.firstOrNull { it.hasToken }

    /** Шаблон по умолчанию: предложенный экраном, иначе — маршрута, иначе — первый. */
    fun pickBrief(briefs: List<Brief>, route: Route?, wanted: String?): Brief? =
        briefs.firstOrNull { it.key == wanted } ?: briefs.firstOrNull { it.key == route?.brief } ?: briefs.firstOrNull()

    fun command(route: Route, project: String, repo: RepoRef, brief: Brief, text: String): ActionCommand = ActionCommand(
        ActionKind.ROUTE_RUN,
        ActionTarget(repo),
        mapOf(
            "route" to route.name,
            "route_id" to route.id,
            "project" to project,
            "brief" to brief.key,
            "chars" to "${text.length}",
            "text_sha256" to EditActions.sha256(text),
        ),
    )

    /** Окно «что уйдёт» (уровень 1): куда и чем; сам текст экран показывает целиком под этими строками. */
    fun preview(c: ActionCommand, brief: Brief): List<String> = listOf(
        "Маршрут «${c.params["route"]}» откроет облачную сессию Claude для ${c.target.repo}.",
        "Поручение «${brief.title}», знаков: ${c.params["chars"]}. Текст — ниже, уйдёт как есть.",
        "Запуск не повторяется сам: предел — 30 запусков в час на маршрут.",
    )

    /**
     * Исполнение (после политики и журнала `:core:actions`): текст — тот, что был показан,
     * иначе отказ; запуск — [fire] оболочки. Итог — ссылка на сессию.
     */
    fun execute(c: ActionCommand, text: String?, fire: (String, String) -> Outcome<Fired>): Outcome<ActionResult> {
        if (c.kind != ActionKind.ROUTE_RUN) return Outcome.failure(DiagCode.UNSUPPORTED, "не запуск маршрута", c.target.display)
        if (text == null || EditActions.sha256(text) != c.params["text_sha256"]) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "текст поручения не тот, что был показан — не отправлено", c.target.display)
        }
        val id = c.params["route_id"] ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет маршрута", c.target.display)
        return when (val r = fire(id, text)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(ActionResult("сессия Claude запущена: ${r.value.sessionId}", r.value.url))
        }
    }
}
