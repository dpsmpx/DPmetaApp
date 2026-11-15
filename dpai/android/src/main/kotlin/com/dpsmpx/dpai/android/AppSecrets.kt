package com.dpsmpx.dpai.android

import android.app.Activity
import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.SecretRevoke
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Все секреты устройства (`DPmetaApp:D-151`; D-182): для аварийного отзыва (F-46) — где
 * каждый отозвать и «стереть»; для копии под паролем (F-47) — вынуть и записать.
 * `ApiKeys` — по именам; внешние (токен GitHub пульта) — регистрирует оболочка;
 * ключ SSH и ключи подписи живут в Keystore — их можно только стереть.
 */
object AppSecrets {
    /**
     * Секрет вне `ApiKeys`: [read] и [write] могут требовать входа — [unlock] открывает
     * его перед копией или восстановлением; [erase] входа не требует.
     */
    class External(
        val id: String,
        val name: String,
        val revoke: String?,
        val present: (Context) -> Boolean,
        val read: (Context) -> Outcome<Secret?>,
        val write: (Context, Secret) -> Outcome<Unit>,
        val erase: (Context) -> Outcome<Unit>,
        val unlock: ((Activity, (Outcome<Unit>) -> Unit) -> Unit)? = null,
        val hint: String = "отзовите на странице и создайте новый",
    )

    /** Строка списка: [revoke] — страница отзыва; [hint] — что ещё сделать; [exportable] — уходит ли в копию. */
    data class Row(val id: String, val name: String, val revoke: String?, val hint: String, val exportable: Boolean)

    private val externals = CopyOnWriteArrayList<External>()

    fun register(e: External) {
        externals.removeAll { it.id == e.id }
        externals += e
    }

    private const val API = "api:"
    private const val EXT = "ext:"
    private const val SSH = "keystore:ssh"
    private const val SIGN = "keystore:sign:"
    private const val GLOBAL = "global"

    /** Все секреты, что есть сейчас. Звать не из главного потока. */
    fun rows(context: Context): List<Row> {
        val app = context.applicationContext
        ModelsStore.init(app)
        val profiles = ModelsStore.profiles.value.associateBy { it.id.value }
        val routines = ModelsStore.routines.value.associateBy { it.id.value }
        val out = ArrayList<Row>()
        for (e in externals) if (runCatching { e.present(app) }.getOrDefault(false)) out += Row(EXT + e.id, e.name, e.revoke, e.hint, true)
        for (name in ApiKeys.names(app)) {
            val admin = name.removeSuffix(":admin").takeIf { it != name }
            out += when {
                name == "huggingface" -> Row(API + name, "Токен Hugging Face", SecretRevoke.HUGGING_FACE, "отзовите токен на странице", true)
                name == "cloud.token" -> Row(API + name, "Токен облачной копии", SecretRevoke.GITHUB_TOKENS, "отзовите токен на GitHub", true)
                name == "cloud.passphrase" -> Row(API + name, "Пароль облачной копии", null, "копии под ним остаются в репозитории — удалите их там, если пароль утёк", true)
                name.startsWith("custom:") -> Row(API + name, "Свой секрет ${name.removePrefix("custom:")}", null, "отзовите там, где он выдан", true)
                admin != null -> Row(API + name, "Admin-ключ «${profiles[admin]?.name ?: admin}»", SecretRevoke.ANTHROPIC_ADMIN, "отзовите ключ в консоли", true)
                profiles[name] != null -> profiles.getValue(name).let { p ->
                    Row(API + name, "Ключ API «${p.name}»", SecretRevoke.apiKey(p.host), if (SecretRevoke.apiKey(p.host) == null) "отзовите у сервиса ${p.host}" else "отзовите ключ на странице", true)
                }
                routines[name] != null -> Row(API + name, "Токен маршрута «${routines.getValue(name).name}»", SecretRevoke.CLAUDE_CODE, "смените токен маршрута в Claude Code", true)
                else -> Row(API + name, "Секрет $name", null, "отзовите там, где он выдан", true)
            }
        }
        if (KeystoreKey.exists()) out += Row(SSH, "Ключ SSH приложения", null, "уберите его строку из authorized_keys на ПК и в Termux", false)
        for (p in SigningKeys.all()) out += Row(SIGN + (p ?: GLOBAL), if (p == null) "Ключ подписи APK — общий" else "Ключ подписи APK проекта $p", null, "копия — ваш файл .p12", false)
        return out
    }

    /**
     * Внешние секреты, которым нужен вход, — открыть до копии или восстановления;
     * [includeAbsent] — и те, которых ещё нет (запись из копии на новом телефоне).
     */
    fun unlockAll(activity: Activity, includeAbsent: Boolean = false, done: (Outcome<Unit>) -> Unit) {
        val pending = externals.filter { it.unlock != null && (includeAbsent || runCatching { it.present(activity) }.getOrDefault(false)) }.toMutableList()
        fun next() {
            val e = pending.removeFirstOrNull() ?: return done(Outcome.success(Unit))
            e.unlock!!.invoke(activity) { r -> if (r is Outcome.Failure) done(r) else next() }
        }
        next()
    }

    /** Значения для копии под паролем: имя в копии → секрет. Нечитаемый — отказ с причиной. */
    fun export(context: Context): Outcome<Map<String, Secret>> {
        val app = context.applicationContext
        val out = LinkedHashMap<String, Secret>()
        for (e in externals) {
            if (!runCatching { e.present(app) }.getOrDefault(false)) continue
            when (val r = e.read(app)) {
                is Outcome.Failure -> return Outcome.failure(r.errors.first().code, "«${e.name}» не прочитан: ${r.errors.first().message}", e.id)
                is Outcome.Success -> r.value?.let { out[EXT + e.id] = it }
            }
        }
        for (name in ApiKeys.names(app)) {
            val v = ApiKeys.getNamed(app, name) ?: return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "секрет $name не расшифровался — сохраните его заново или сотрите", name)
            out[API + name] = v
        }
        return Outcome.success(out)
    }

    /** Записать секреты из копии; итог — сколько записано и что нет. */
    fun import(context: Context, values: Map<String, Secret>): Outcome<Int> {
        val app = context.applicationContext
        var n = 0
        val failed = ArrayList<String>()
        for ((key, v) in values) {
            when {
                key.startsWith(API) -> if (ApiKeys.putNamed(app, key.removePrefix(API), v)) n++ else failed += key
                key.startsWith(EXT) -> {
                    val e = externals.firstOrNull { it.id == key.removePrefix(EXT) }
                    if (e != null && e.write(app, v) is Outcome.Success) n++ else failed += key
                }
                else -> failed += key
            }
        }
        if (failed.isNotEmpty()) return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "записано $n, не записаны: ${failed.joinToString()}")
        AppLogs.info(app, "секреты", "из копии записано секретов: $n")
        return Outcome.success(n)
    }

    /**
     * Стереть отмеченные (F-46): итог — id и причина отказа для каждого нестёртого.
     * В журнал — только имена и число, без значений.
     */
    fun erase(context: Context, ids: Set<String>): Map<String, String> {
        val app = context.applicationContext
        val failed = LinkedHashMap<String, String>()
        for (id in ids) {
            val r: Outcome<Unit> = when {
                id.startsWith(API) -> { ApiKeys.removeNamed(app, id.removePrefix(API)); Outcome.success(Unit) }
                id.startsWith(EXT) -> externals.firstOrNull { it.id == id.removePrefix(EXT) }?.erase?.invoke(app)
                    ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого секрета")
                id == SSH -> KeystoreKey.erase()
                id.startsWith(SIGN) -> SigningKeys.delete(app, id.removePrefix(SIGN).takeIf { it != GLOBAL })
                else -> Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого секрета")
            }
            if (r is Outcome.Failure) failed[id] = r.errors.first().message
        }
        AppLogs.info(app, "секреты", "аварийный отзыв: стёрто ${ids.size - failed.size} из ${ids.size}" + if (failed.isEmpty()) "" else "; не стёрты: ${failed.keys.joinToString()}")
        return failed
    }
}
