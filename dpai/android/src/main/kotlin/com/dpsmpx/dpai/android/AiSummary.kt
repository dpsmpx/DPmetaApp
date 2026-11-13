package com.dpsmpx.dpai.android

import android.content.Context
import android.content.Intent
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.notifications.DeepLink

/**
 * Строка «AI» на сводке пульта (DPmetaApp:D-76): что ждёт человека и что
 * идёт — из последнего снимка контура. Читает оболочка приложения и отдаёт
 * пульту; подсистема о пульте не знает (DPmetaApp:D-53).
 */
object AiSummary {
    /**
     * [configured] — есть target с каталогом Claude (иначе — демо: «не
     * настроено»). Числа — из последнего снимка службы контроллера; снимка
     * ещё нет или служба остановлена — неизвестны, а не ноль (D-13). [request] — ссылка на первый ждущий
     * запрос: касание строки открывает «Запросы».
     */
    data class Counts(
        val configured: Boolean,
        val waiting: Measure<Long>,
        val running: Measure<Long>,
        val nearLimit: Measure<Long>,
        val request: String?,
        /** Пунктов проверки приложения (DPmetaApp:D-123) по последней проверке. */
        val problems: Int = 0,
    )

    /**
     * Не на главном потоке: читает список targets. Снимок не строится —
     * берётся опубликованный тактом службы или экраном «AI»: пульт не
     * держит замок контура.
     */
    fun read(context: Context): Counts {
        val problems = HealthAuto.count(context)
        val model = DemoModel.get(context)
        if (!model.hasLive()) return Counts(false, NONE, NONE, NONE, null, problems)
        // Контроллер остановлен (D-39) — снимок замер: числа неизвестны, а не «в работе 2» навсегда (D-13).
        if (!ControllerService.running) return Counts(true, STOPPED, STOPPED, STOPPED, null, problems)
        val s = model.published?.takeIf { it.live } ?: return Counts(true, NOT_YET, NOT_YET, NOT_YET, null, problems)
        return Counts(
            problems = problems,
            configured = true,
            waiting = Measure.observed(s.requests.size.toLong()),
            running = Measure.observed(s.dashboard.active.toLong()),
            nearLimit = Measure.observed(s.dashboard.limits.count { it.near }.toLong()),
            request = s.requests.firstOrNull()?.let { DeepLink.of(DeepLink.Kind.REQUEST, it.id).toUri() },
        )
    }

    /**
     * Окно «AI» — уже открытое выходит вперёд (UX-1); с [request] — на
     * вкладке «Запросы» (внутренний вход: ссылку чужое приложение не подсунет).
     */
    fun open(context: Context, request: String?): Intent = MainActivity.internal(context)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .apply { if (request != null) setAction(MainActivity.OPEN).putExtra(MainActivity.EXTRA_LINK, request) }

    private val NONE = Measure.unknown("контур «AI» не настроен")
    private val NOT_YET = Measure.unknown("контур «AI» ещё не опубликовал состояние")
    private val STOPPED = Measure.unknown("контроллер «AI» остановлен")
}
