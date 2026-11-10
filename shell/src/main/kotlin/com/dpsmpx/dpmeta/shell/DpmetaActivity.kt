package com.dpsmpx.dpmeta.shell

import android.content.Intent
import android.os.Bundle
import com.dpsmpx.dpai.android.AiBackground
import com.dpsmpx.dpai.android.AiSummary
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpai.android.PultAsk
import com.dpsmpx.dpai.android.PultRoutes
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.PultActivity
import com.dpsmpx.dpmeta.app.screens.AskActions
import com.dpsmpx.dpmeta.app.screens.RouteActions
import com.dpsmpx.dpmeta.app.screens.Screens

/**
 * Первый экран приложения (D-53): пульт с пунктом «AI». Пульт и оболочка
 * подсистемы друг о друге не знают — связывает их только этот модуль.
 */
class DpmetaActivity : PultActivity() {
    /** Последние числа «AI» — ссылка на ждущий запрос для перехода со строки сводки (D-76). */
    @Volatile
    private var lastAi: AiSummary.Counts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Служба «AI» и разрешение на уведомления — уже с пульта, а не только из вкладки «AI» (UX-4).
        AiBackground.attach(this)
    }

    // Окно «AI» — одно: уже открытое выходит вперёд, второго экземпляра нет (UX-1).
    override fun aiIntent(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    // Со строки «AI»: ждут ответа — сразу на «Запросы» (D-76).
    override fun aiIntent(requests: Boolean): Intent = lastAi?.request?.takeIf { requests }?.let { AiSummary.open(this, it) } ?: aiIntent()

    override fun aiVersion(): String = com.dpsmpx.dpai.android.BuildConfig.DPAI_VERSION

    /** Строки пульта — в журнал приложения подсистемы «AI» (D-39: один журнал отладки на приложение; там же Redactor). */
    override fun debugLog(tag: String, line: String) = com.dpsmpx.dpai.android.AppLogs.info(applicationContext, tag, line)

    override fun aiCounts(): Screens.AiCounts = AiSummary.read(this).also { lastAi = it }.let { Screens.AiCounts(it.configured, it.waiting, it.running, it.nearLimit) }

    /** Маршруты Claude Code (F-22) — из подсистемы «AI» (`DP_AI_Orcestrator:D-116`): токен остаётся там. */
    override fun routes(): List<RouteActions.Route> =
        PultRoutes.list(applicationContext).map { RouteActions.Route(it.id, it.name, it.project, it.brief, it.hasToken, it.subscription) }

    /** Запуск маршрута: цену и журнал действия уже прошёл пульт (`:core:actions`); здесь — только вызов. */
    override fun fireRoute(id: String, text: String): Outcome<RouteActions.Fired> =
        when (val r = PultRoutes.fire(applicationContext, id, text)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(RouteActions.Fired(r.value.first, r.value.second))
        }

    /** «Спросить Claude» (F-21): профили Anthropic и ход с инструментами — у подсистемы «AI» (`DP_AI_Orcestrator:D-118`). */
    override fun askProfiles(): List<AskActions.Profile> =
        PultAsk.profiles(applicationContext).map { AskActions.Profile(it.id, it.name, it.model, it.hasKey) }

    override fun askModels(profile: String): Outcome<List<AskActions.Model>> =
        when (val r = PultAsk.models(applicationContext, profile)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.map { AskActions.Model(it.first, it.second) })
        }

    override fun ask(
        request: AskActions.Request,
        onCancel: (() -> Unit) -> Unit,
        onText: (String) -> Unit,
        tool: (String, JsonObject) -> Pair<String, Boolean>,
    ): Outcome<AskActions.Answer> {
        val r = PultAsk.ask(
            applicationContext, request.profile, request.model, request.effort, request.system,
            request.turns.map { PultAsk.Turn(it.user, it.text) },
            request.tools.map { PultAsk.Tool(it.name, it.description, it.schema) },
            onCancel, onText, tool,
        )
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(
                AskActions.Answer(
                    r.value.text,
                    when (r.value.stop) {
                        "end" -> AskActions.Stop.END
                        "length" -> AskActions.Stop.LENGTH
                        "refusal" -> AskActions.Stop.REFUSAL
                        else -> AskActions.Stop.OTHER
                    },
                    r.value.stopReason, r.value.inputTokens, r.value.outputTokens,
                ),
            )
        }
    }
}
