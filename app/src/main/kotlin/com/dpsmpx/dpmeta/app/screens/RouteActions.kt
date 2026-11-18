package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionEntry
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.actions.ActionStatus
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.formats.Brief
import com.dpsmpx.dpmeta.formats.SessionLink
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.model.Event
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Поручение Claude из пульта (F-22, F-23, F-25; D-07): маршрут, шаблон, поля → текст →
 * окно «что уйдёт» → запуск маршрута (уровень 1). Маршруты и их токены ведёт подсистема
 * «AI» (`DP_AI_Orcestrator:D-116`); пульт видит только [Route] и зовёт запуск через
 * оболочку. Текст поручения в журнал не идёт: в команде — его SHA-256 и длина,
 * исполнитель сверяет текст с ними. Чистый Kotlin.
 */
object RouteActions {
    /**
     * Маршрут глазами пульта: id, имя, проект и шаблон по умолчанию, есть ли токен;
     * [subscription] — подписка (аккаунт) маршрута, если указана: у аккаунта свой предел;
     * [color] — цвет профиля подписки (кружок у имени, D-83), без цвета — `null`.
     */
    data class Route(val id: String, val name: String, val project: String?, val brief: String?, val hasToken: Boolean, val subscription: String? = null, val color: String? = null)

    /** Пределы запусков (приложение В ТЗ; `DP_AI_Orcestrator:docs/research/ROUTINES_FIRE.md`). */
    const val ROUTE_PER_HOUR: Int = 30
    const val ACCOUNT_PER_HOUR: Int = 100

    /** Расход подписки — страница claude.ai: приложение его не знает и не придумывает (F-19, L-03). */
    const val USAGE_PAGE: String = "https://claude.ai/settings/usage"

    /** Запуски за последний час, известные пульту: у маршрута и у аккаунта (`null` — подписка не указана). */
    data class HourCount(val route: Int, val account: Int?)

    /**
     * F-19: сколько раз за час пульт запустил маршрут и маршруты его аккаунта — по журналу
     * действий; итог «неизвестен» считается запуском (мог состояться). Запуски с других
     * устройств и из Claude («Run now») сюда не попадают — строка экрана так и говорит.
     */
    fun firedLastHour(entries: List<ActionEntry>, routes: List<Route>, route: Route, now: Timestamp): HourCount {
        val since = now.epochMillis - HOUR_MILLIS
        val fired = entries.filter {
            it.kind == ActionKind.ROUTE_RUN.code && it.at.epochMillis > since && it.at.epochMillis <= now.epochMillis &&
                (it.status == ActionStatus.DONE || it.status == ActionStatus.UNKNOWN)
        }.mapNotNull { it.params["route_id"] }
        val sameAccount = route.subscription?.let { s -> routes.filter { it.subscription == s }.map { it.id }.toSet() + route.id }
        return HourCount(fired.count { it == route.id }, sameAccount?.let { ids -> fired.count { it in ids } })
    }

    /** Строка экрана о пределах (F-19): что знает приложение и о чём не знает. */
    fun hourLine(c: HourCount): String =
        "С этого телефона за час: ${c.route} из $ROUTE_PER_HOUR у маршрута" +
            (c.account?.let { "; $it из $ACCOUNT_PER_HOUR у аккаунта" } ?: "; подписка маршрута не указана") +
            ". Запуски из Claude и с других устройств сюда не входят."

    /**
     * F-20: отказ по пределу ([DiagCode.ROUTINE_LIMIT_REACHED]) — подсказка о другом маршруте
     * с токеном; сам пульт не переключает. Иначе — пусто.
     */
    fun limitHint(code: DiagCode, routes: List<Route>, routeId: String?): String {
        if (code != DiagCode.ROUTINE_LIMIT_REACHED) return ""
        val other = routes.firstOrNull { it.hasToken && it.id != routeId } ?: return " Другого маршрута с токеном нет — повторите позже."
        return " Можно поручить другим маршрутом — выберите его выше, например «${other.name}»."
    }

    private const val HOUR_MILLIS: Long = 3_600_000

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

    /**
     * [question] — вопрос автору, на который поручен ответ (F-25): его заголовок из `TODO.md`
     * — в параметрах, чтобы у вопроса показать поручение и PR после него.
     */
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

    /**
     * F-58 (D-161): сессии запусков маршрутов из пульта — id `session_…` → имя маршрута, по
     * журналу действий (итог «выполнено» или «неизвестен»). По ним лента узнаёт PR своего
     * запуска; позднейший запуск с тем же id (не бывает) побеждает.
     */
    fun sessions(entries: List<ActionEntry>): Map<String, String> = entries
        .filter { it.kind == ActionKind.ROUTE_RUN.code && (it.status == ActionStatus.DONE || it.status == ActionStatus.UNKNOWN) }
        .mapNotNull { e -> SessionLink.of(e.link)?.let { it to (e.params["route"] ?: "маршрут") } }
        .toMap()

    /**
     * Запуск состоялся (F-58; D-161): строка ленты «запуск маршрута» и событие
     * `claude.routine.fired` для правил — маршрут, подписка, сессия и [count] запусков за час
     * уже с этим (число `left` — сколько осталось до предела маршрута).
     */
    fun fired(c: ActionCommand, route: Route?, link: String?, count: HourCount, at: Timestamp): Pair<FeedEvent, Event> {
        val name = c.params["route"] ?: route?.name ?: "маршрут"
        val project = c.params["project"] ?: c.target.display
        val text = "Маршрут «$name» запущен"
        val fields = buildMap {
            put("route", name)
            route?.subscription?.let { put("account", it) }
            SessionLink.of(link)?.let { put("session", it) }
        }
        val event = Event(
            "claude.routine.fired", at, project, text, fields,
            mapOf("left" to (ROUTE_PER_HOUR - count.route).coerceAtLeast(0).toDouble()),
            key = fields["session"] ?: "$name ${at.epochMillis}",
        )
        return FeedEvent(at, project, "routine", text) to event
    }

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
