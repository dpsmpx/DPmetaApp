package com.dpsmpx.dpai.android

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.GuestSecrets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Секреты приложения одним списком и выдача ИИ по разрешению (`DPmetaApp:D-111`,
 * `DPmetaApp:D-114`; D-144). В списке — имя, переменная окружения и признаки, значения
 * нет. «Давать ИИ» можно только токену GitHub, ключам API-профилей и токену Hugging
 * Face; остальное — только в списке. Выданное идёт в окружение процессов Linux
 * приложения ([environment]): ходы «в приложении» и терминал; ПК-target его не получают.
 *
 * Секреты оболочки (токен GitHub пульта) подключает приложение через [register]:
 * подсистема от модулей пульта не зависит.
 */
object SecretGrants {
    private const val PREFS = "dpmeta.secrets"
    private const val GRANTED = "granted"
    private const val CUSTOM_NAMES = "custom"
    private const val CUSTOM = "custom:"
    const val MAX_CUSTOM = 32

    /**
     * Секрет оболочки: [variable] — имя для ИИ (`null` — только в списке); [value] —
     * значение или `null` (нет его или нужен вход); [unlocked] и [unlock] — для секрета
     * за входом (PIN или биометрия, D-59): открыт ли в этом запуске и как открыть.
     */
    class Source(
        val id: String,
        val name: String,
        val variable: String?,
        val present: (Context) -> Boolean,
        val value: (Context) -> Secret?,
        val unlocked: () -> Boolean = { true },
        val unlock: ((Activity, (Outcome<Unit>) -> Unit) -> Unit)? = null,
    )

    /** Строка списка: [alias] — имя у слотов, где стандартное удаляется; [locked] — выдан, но нужен вход. */
    data class Row(
        val id: String,
        val name: String,
        val variable: String?,
        val alias: String?,
        val present: Boolean,
        val granted: Boolean,
        val locked: Boolean,
    ) {
        val eligible: Boolean get() = variable != null
        val toAi: Boolean get() = granted && present && !locked
    }

    private val sources = CopyOnWriteArrayList<Source>()
    private val lastIssued = AtomicReference<List<String>?>(null)

    fun register(source: Source) {
        sources.removeAll { it.id == source.id }
        sources += source
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun granted(context: Context): Set<String> = prefs(context).getStringSet(GRANTED, emptySet()).orEmpty().toSet()

    /** Все источники: оболочки и свои (Hugging Face, API-профили, Admin-ключи, маршруты, облачная копия). */
    private fun all(context: Context): List<Source> {
        val app = context.applicationContext
        ModelsStore.init(app)
        val own = buildList {
            add(Source("huggingface", "Токен Hugging Face", "HF_TOKEN", HfModels::hasToken, HfModels::token))
            for (p in ModelsStore.profiles.value) {
                add(Source("profile:${p.id.value}", "Ключ API «${p.name}»", GuestSecrets.variableFor(p.kind, p.host), { ApiKeys.has(it, p.id) }, { ApiKeys.get(it, p.id) }))
                add(Source("admin:${p.id.value}", "Admin-ключ «${p.name}»", null, { ModelsStore.hasAdminKey(p.id) }, { null }))
            }
            for (r in ModelsStore.routines.value) add(Source("routine:${r.id.value}", "Токен маршрута «${r.name}»", null, { ApiKeys.has(it, r.id) }, { null }))
            add(Source("cloud.token", "Токен облачной копии", null, CloudSync::hasToken, { null }))
            add(Source("cloud.passphrase", "Пароль облачной копии", null, CloudSync::hasPassphrase, { null }))
            // Свои секреты автора (DPmetaApp:D-117): имя — оно же переменная; значение — под тем же ключом Keystore, что ключи API.
            for (name in customNames(app)) add(Source(CUSTOM + name, "Свой секрет $name", name, { ApiKeys.hasNamed(it, CUSTOM + name) }, { ApiKeys.getNamed(it, CUSTOM + name) }))
        }
        return sources.toList() + own
    }

    private fun customNames(context: Context): List<String> = prefs(context).getStringSet(CUSTOM_NAMES, emptySet()).orEmpty().sorted()

    /** Свой ли это секрет автора: его можно удалить из списка. */
    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM)

    /**
     * Добавить или заменить свой секрет (DPmetaApp:D-117): [name] проверяет
     * `GuestSecrets.customName`, значение — в Keystore. «Давать ИИ» не включается
     * само. В журнал — имя, без значения.
     */
    fun addCustom(context: Context, name: String, value: Secret): Outcome<String> {
        val app = context.applicationContext
        val valid = when (val n = GuestSecrets.customName(name)) {
            is Outcome.Failure -> return n
            is Outcome.Success -> n.value
        }
        if (value.isEmpty) return Outcome.failure(DiagCode.FIELD_MISSING, "введите значение секрета")
        val names = customNames(app)
        if (valid !in names && names.size >= MAX_CUSTOM) return Outcome.failure(DiagCode.VALUE_INVALID, "своих секретов — не больше $MAX_CUSTOM")
        if (!ApiKeys.putNamed(app, CUSTOM + valid, value)) return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "секрет не сохранён: хранилище ключей недоступно")
        prefs(app).edit { putStringSet(CUSTOM_NAMES, (names + valid).toSet()) }
        AppLogs.info(app, "секреты", if (valid in names) "свой секрет $valid заменён" else "добавлен свой секрет $valid")
        StorageMap.refresh(app)
        return Outcome.success(valid)
    }

    /** Удалить свой секрет: значение, выдачу ИИ и имя из списка. */
    fun removeCustom(context: Context, id: String) {
        if (!isCustom(id)) return
        val app = context.applicationContext
        val name = id.removePrefix(CUSTOM)
        ApiKeys.removeNamed(app, id)
        prefs(app).edit {
            putStringSet(CUSTOM_NAMES, (customNames(app) - name).toSet())
            putStringSet(GRANTED, granted(app) - id)
        }
        AppLogs.info(app, "секреты", "свой секрет $name удалён")
        StorageMap.refresh(app)
    }

    /** Список для экрана и карты: только существующие секреты; читает Keystore — не с главного потока. */
    fun rows(context: Context): List<Row> {
        val on = granted(context)
        return all(context).map { s ->
            val present = runCatching { s.present(context) }.getOrDefault(false)
            val granted = s.variable != null && s.id in on
            Row(s.id, s.name, s.variable, s.variable?.let(GuestSecrets::alias), present, granted, locked = granted && present && !s.unlocked())
        }.filter { it.present }
    }

    /**
     * Включить или выключить «давать ИИ». Одна переменная — один секрет: включённый
     * второй с той же переменной выключает первый. Изменение — строкой в журнале.
     */
    fun grant(context: Context, id: String, on: Boolean) {
        val app = context.applicationContext
        val list = all(app)
        val s = list.firstOrNull { it.id == id } ?: return
        val variable = s.variable ?: return
        val now = granted(app).toMutableSet()
        if (on) {
            val same = list.filter { it.id != id && it.variable == variable && it.id in now }
            now.removeAll(same.map { it.id }.toSet())
            now += id
            AppLogs.info(app, "секреты", "«${s.name}» выдаётся ИИ: $variable" + (GuestSecrets.alias(variable)?.let { ", слотам — $it" } ?: "") +
                same.joinToString("") { "; «${it.name}» больше не выдаётся" })
        } else {
            if (!now.remove(id)) return
            AppLogs.info(app, "секреты", "«${s.name}» больше не выдаётся ИИ")
        }
        prefs(app).edit { putStringSet(GRANTED, now) }
        StorageMap.refresh(app)
    }

    /** Открыть секрет за входом (токен GitHub) — до остановки процесса или службы. */
    fun unlock(activity: Activity, id: String, done: (Outcome<Unit>) -> Unit) {
        val s = sources.firstOrNull { it.id == id }?.unlock ?: return done(Outcome.success(Unit))
        s(activity) { r ->
            if (r is Outcome.Success) StorageMap.refresh(activity.applicationContext)
            done(r)
        }
    }

    /**
     * Окружение для процессов Linux приложения: выданные и открытые секреты. Перечень
     * имён — в журнал при первой выдаче за запуск и при каждом изменении; значений нет.
     */
    fun environment(context: Context): Map<String, String> {
        val app = context.applicationContext
        val on = granted(app)
        if (on.isEmpty()) return emptyMap()
        val issued = all(app).filter { it.id in on && it.variable != null }.mapNotNull { s ->
            if (!s.unlocked()) return@mapNotNull null
            runCatching { s.value(app) }.getOrNull()?.let { GuestSecrets.Issued(s.variable!!, it) }
        }
        val names = GuestSecrets.names(issued)
        if (lastIssued.getAndSet(names) != names && names.isNotEmpty()) AppLogs.info(app, "секреты", "в окружении Linux: ${names.joinToString()}")
        return GuestSecrets.environment(issued)
    }
}
