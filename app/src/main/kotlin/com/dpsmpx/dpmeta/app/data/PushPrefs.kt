package com.dpsmpx.dpmeta.app.data

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.notify.PushChannel

/**
 * Настройки мгновенного канала (D-152; F-55): включён ли (по умолчанию нет), сервер
 * (по умолчанию `ntfy.sh`), тема (случайная, создаётся при первом чтении) — в
 * `dpmeta.push`; ключ подписи — секрет ([SecretStore.push]), не здесь.
 */
object PushPrefs {
    const val PREFS = "dpmeta.push"
    const val ENABLED = "enabled"
    private const val SERVER = "server"
    private const val TOPIC = "topic"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(ENABLED, on) }

    /** Сохранённый сервер; испорченный — по умолчанию. */
    fun server(context: Context): String =
        prefs(context).getString(SERVER, null)?.let { (PushChannel.server(it) as? Outcome.Success)?.value } ?: PushChannel.DEFAULT_SERVER

    /** Сменить сервер: только https, без пути (D-10). Итог — сохранённый адрес. */
    fun setServer(context: Context, text: String): Outcome<String> = PushChannel.server(text).also { r ->
        if (r is Outcome.Success) prefs(context).edit { putString(SERVER, r.value) }
    }

    @Synchronized
    fun topic(context: Context): String = prefs(context).getString(TOPIC, null)?.takeIf(PushChannel::topicValid) ?: newTopic(context)

    /** Новая тема: старая перестаёт работать — хуку нужен новый адрес. */
    @Synchronized
    fun newTopic(context: Context): String = PushChannel.newTopic(RandomSource.secure()).also { t -> prefs(context).edit { putString(TOPIC, t) } }

    /** Адрес для хука (`DPMETA_PUSH_URL`). */
    fun publishUrl(context: Context): String = PushChannel.publishUrl(server(context), topic(context))

    fun key(context: Context): Outcome<Secret?> = SecretStore.push(context).load()

    fun hasKey(context: Context): Boolean = SecretStore.push(context).exists()

    /** Ключ есть — он; нет — новый. */
    @Synchronized
    fun ensureKey(context: Context): Outcome<Secret> = when (val k = key(context)) {
        is Outcome.Success -> k.value?.let { Outcome.success(it) } ?: newKey(context)
        is Outcome.Failure -> newKey(context)
    }

    /** Новый ключ: старый перестаёт проходить проверку — хуку нужен новый `DPMETA_PUSH_KEY`. */
    @Synchronized
    fun newKey(context: Context): Outcome<Secret> {
        val key = Secret(PushChannel.newKeyText(RandomSource.secure()))
        return when (val r = SecretStore.push(context).save(key)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(key)
        }
    }
}
