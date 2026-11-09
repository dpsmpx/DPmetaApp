package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Маршруты Claude Code для пульта DPmetaApp (D-116): оболочка `:shell` видит их
 * строками, без типов модулей `accounts` и `providers`; токен остаётся в Keystore
 * подсистемы. Цену и журнал запуска держит пульт (`DPmetaApp:D-07`).
 * Вызовы — не на главном потоке.
 */
object PultRoutes {
    /** Маршрут глазами пульта: id `acc_…`, имя, проект, шаблон, есть ли токен, подписка `sub_…`. */
    data class View(val id: String, val name: String, val project: String?, val brief: String?, val hasToken: Boolean, val subscription: String?)

    fun list(context: Context): List<View> {
        ModelsStore.init(context)
        return ModelsStore.routines.value.map { View(it.id.value, it.name, it.project, it.brief, ModelsStore.hasKey(it.id), it.subscription?.value) }
    }

    /** Запуск: итог — id сессии и ссылка на неё (`https://claude.ai/code/…`). */
    fun fire(context: Context, id: String, text: String): Outcome<Pair<String, String>> {
        ModelsStore.init(context)
        val account = IdKinds.ACCOUNT.parse(id).valueOrNull()
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "id маршрута не того вида", "routine")
        return when (val r = ModelsStore.fireRoutine(account, text)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.sessionId to r.value.sessionUrl)
        }
    }
}
