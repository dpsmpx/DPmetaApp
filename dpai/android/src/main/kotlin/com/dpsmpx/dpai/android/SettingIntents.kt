package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.control.CommandCodec
import java.util.concurrent.CompletableFuture

/**
 * Намерения человека о настройках слотов и подписок (D-57): что он хочет
 * — записано сразу, исполнится, когда контур свободен. Такт контура
 * держит замок минутами (SSH, проверка лимитов), а переключатель не
 * должен ждать и откатываться: экран показывает желаемое значение, пока
 * оно не стало состоянием контура.
 *
 * На один предмет — одно намерение, последнее побеждает. Хранятся в
 * настройках телефона (командой канала D-47): переживают перезапуск
 * приложения и пересборку контроллера; снимаются, когда состояние контура
 * совпало с желаемым или команда отклонена (тогда — причина для экрана).
 */
object SettingIntents {
    private const val PREFS = "setting-intents"
    private val lock = Any()
    private val pending = LinkedHashMap<String, Command>()
    private var loaded = false

    /** Отправленные контуру: какой команды и какому контуру — новый контур получает их заново. */
    private val sent = HashMap<String, Triple<Command, Orchestrator, CompletableFuture<*>>>()

    /** Последний отказ по предмету — экран показывает его вместо «применится». */
    private val refused = HashMap<String, String>()

    /** Предмет настройки: команды не о настройках — `null` (они идут как раньше). */
    fun key(c: Command): String? = when (c) {
        is Command.SetTargetEnabled -> "target:${c.target.value}"
        is Command.SetSlotEnabled -> "slot:${c.slot.value}"
        is Command.SetRotation -> "rotation:${c.subscription.value}"
        is Command.SetNearPercent -> "near:${c.subscription.value}"
        is Command.SetSessionLimit -> "session-limit"
        is Command.SetExecutorSwitch -> "executor-switch"
        else -> null
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((k, v) in prefs.all) {
            val json = (v as? String)?.let { JsonParser().parse(it).valueOrNull() as? JsonObject } ?: continue
            CommandCodec.decode(json, "человек").valueOrNull()?.let { pending[k] = it.command }
        }
    }

    /** Записать намерение: желаемое значение видно сразу. */
    fun record(context: Context, c: Command) {
        val k = key(c) ?: return
        synchronized(lock) {
            load(context)
            pending[k] = c
            refused.remove(k)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(k, CanonicalJson.write(CommandCodec.encode(c))).apply()
        }
    }

    /** Желаемое, ещё не применённое значение предмета [k]; нет — `null`. */
    fun desired(context: Context, k: String): Command? = synchronized(lock) {
        load(context)
        pending[k]
    }

    fun refusal(k: String): String? = synchronized(lock) { refused[k] }

    private fun drop(context: Context, k: String) {
        pending.remove(k)
        sent.remove(k)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(k).apply()
    }

    /**
     * Свести намерения с контуром [o] (такт службы и экран): совпало —
     * снять; отклонено — снять с причиной; не отправлено этому контуру —
     * отправить (в очередь команд; выполнится в его такте).
     */
    fun reconcile(context: Context, o: Orchestrator) = synchronized(lock) {
        load(context)
        for ((k, c) in pending.entries.toList()) {
            if (applied(o, c)) {
                drop(context, k)
                continue
            }
            val s = sent[k]
            if (s != null && s.first == c && s.second === o) {
                if (!s.third.isDone) continue
                val r = runCatching { s.third.get() as com.dpsmpx.dpai.core.operation.OperationResult<*> }.getOrNull()
                if (r?.outcome is com.dpsmpx.dpai.core.result.Outcome.Failure) {
                    refused[k] = (r.outcome as com.dpsmpx.dpai.core.result.Outcome.Failure).errors.firstOrNull()?.message ?: "отклонено"
                    drop(context, k)
                    continue
                }
                // Выполнена, а состояние ещё не то (гонка с тактом) — отправить снова ниже.
            }
            sent[k] = Triple(c, o, o.submit(c))
        }
    }

    /** Состояние контура уже такое, как хочет человек. */
    private fun applied(o: Orchestrator, c: Command): Boolean = when (c) {
        is Command.SetTargetEnabled -> (c.target !in o.control.disabledTargets) == c.enabled
        is Command.SetSlotEnabled -> o.accounts.slots[c.slot]?.let { !it.disabled == c.enabled } ?: false
        is Command.SetRotation -> o.accounts.subscriptions[c.subscription]?.rotationAllowed == c.allowed &&
            o.accounts.subscriptions[c.subscription]?.rotationExplicit == true
        is Command.SetNearPercent -> o.accounts.subscriptions[c.subscription]?.nearPercent == c.percent
        is Command.SetSessionLimit -> o.control.maxSessions == c.limit
        is Command.SetExecutorSwitch -> o.control.autoExecutorSwitch == c.auto
        else -> true
    }
}
