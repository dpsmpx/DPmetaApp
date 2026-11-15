package com.dpsmpx.dpmeta.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.Lock
import com.dpsmpx.dpmeta.app.PushChannelService
import com.dpsmpx.dpmeta.app.data.PushPrefs
import com.dpsmpx.dpmeta.net.AllowList
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.PushSignature
import com.dpsmpx.dpmeta.net.UrlTransport
import com.dpsmpx.dpmeta.notify.PushChannel
import com.dpsmpx.dpmeta.notify.PushEvents
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Мгновенный канал» (D-152; F-55, F-56): включить (по умолчанию выключен), сервер ntfy,
 * тема, ключ подписи — копия для хука и смена только после входа, проверка отправкой.
 */
@Composable
internal fun PushSettings() {
    val local = LocalContext.current
    val context = local.applicationContext
    val activity = remember(local) { local.activity() }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(PushPrefs.enabled(context)) }
    var server by remember { mutableStateOf(PushPrefs.server(context)) }
    var topic by remember { mutableStateOf(PushPrefs.topic(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val state by PushChannelService.state.collectAsState()
    val event by PushChannelService.event.collectAsState()
    val dropped by PushChannelService.dropped.collectAsState()

    fun work(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                note = block()
            } finally {
                busy = false
            }
        }
    }

    /** Ключ — только после входа: копия для хука и смена ключа (T30). */
    fun withLogin(reason: String, block: suspend () -> String) {
        val a = activity ?: run { note = "Вход недоступен с этого экрана"; return }
        Lock.ask(a, reason, pinOnly = false, onOpen = { work(block) }, onDenied = { _, _ -> note = "Вход не подтверждён" })
    }

    Text("Мгновенный канал", style = MaterialTheme.typography.titleSmall)
    Text(
        "Хук Claude Code на ПК шлёт событие «закончил», «ждёт ввода», «ошибка» через сервер ntfy — уведомление приходит за секунды, " +
            "а не со следующей синхронизацией. Соединение держит само приложение и тратит заряд — по умолчанию выключено. " +
            "Событие без верной подписи или старше 10 минут отбрасывается.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row {
        Checkbox(enabled, enabled = !busy, onCheckedChange = { on ->
            work {
                withContext(Dispatchers.IO) {
                    if (on) {
                        when (val k = PushPrefs.ensureKey(context)) {
                            is Outcome.Failure -> return@withContext "Ключ не создан: ${k.diagnostics.first().message}"
                            is Outcome.Success -> Unit
                        }
                        PushPrefs.setEnabled(context, true)
                        enabled = true
                        if (PushChannelService.start(context, restart = true)) "Канал включён" else "Канал включён — поднимется, когда приложение будет на экране"
                    } else {
                        PushChannelService.stop(context)
                        enabled = false
                        "Канал выключен"
                    }
                }
            }
        })
        Text("Включён")
    }
    Text("Состояние: $state", style = MaterialTheme.typography.bodySmall)
    event?.let { Text("Последнее событие: $it", style = MaterialTheme.typography.bodySmall) }
    dropped?.let { Text("Последнее отброшенное: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(server, { server = it.take(200) }, label = { Text("Сервер ntfy (https)") }, singleLine = true)
    TextButton(enabled = !busy, onClick = {
        work {
            withContext(Dispatchers.IO) {
                when (val r = PushPrefs.setServer(context, server)) {
                    is Outcome.Failure -> r.diagnostics.first().message
                    is Outcome.Success -> {
                        server = r.value
                        PushChannelService.start(context, restart = true)
                        "Сервер: ${r.value} — обновите адрес у хука"
                    }
                }
            }
        }
    }) { Text("Сохранить сервер") }
    Text("Тема: $topic", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = !busy, onClick = {
        work {
            withContext(Dispatchers.IO) {
                topic = PushPrefs.newTopic(context)
                PushChannelService.start(context, restart = true)
                "Новая тема — старая больше не слушается; обновите адрес у хука"
            }
        }
    }) { Text("Новая тема") }
    TextButton(enabled = !busy, onClick = {
        withLogin("Ключ мгновенного канала для хука") {
            val env = withContext(Dispatchers.IO) {
                when (val k = PushPrefs.ensureKey(context)) {
                    is Outcome.Failure -> Outcome.failure(k.diagnostics.first())
                    is Outcome.Success -> Outcome.success(PushSignature.hookEnv(PushPrefs.publishUrl(context), k.value))
                }
            }
            when (env) {
                is Outcome.Failure -> "Ключ не прочитан: ${env.diagnostics.first().message}"
                is Outcome.Success -> {
                    copySensitive(context, env.value)
                    "Скопированы DPMETA_PUSH_URL и DPMETA_PUSH_KEY — вставьте в окружение хука на ПК (tools/notify/README.md)"
                }
            }
        }
    }) { Text("Скопировать для хука…") }
    TextButton(enabled = !busy, onClick = {
        withLogin("Новый ключ мгновенного канала") {
            withContext(Dispatchers.IO) {
                when (val k = PushPrefs.newKey(context)) {
                    is Outcome.Failure -> "Ключ не сохранён: ${k.diagnostics.first().message}"
                    is Outcome.Success -> {
                        PushChannelService.start(context, restart = true)
                        "Новый ключ сохранён — старый больше не проходит; скопируйте новый для хука"
                    }
                }
            }
        }
    }) { Text("Новый ключ…") }
    TextButton(enabled = !busy && enabled, onClick = { work { withContext(Dispatchers.IO) { sendTest(context) } } }) { Text("Проверить канал") }
    TextButton(onClick = {
        runCatching { local.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }) { Text("Ограничения заряда Android…") }
    Text("Если канал часто рвётся в фоне — снимите для приложения ограничение заряда.", style = MaterialTheme.typography.bodySmall)
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Проверка: подписанное событие — в свою тему тем же путём, что у хука. */
private fun sendTest(context: Context): String {
    val key = when (val k = PushPrefs.key(context)) {
        is Outcome.Failure -> return "Ключ не прочитан: ${k.diagnostics.first().message}"
        is Outcome.Success -> k.value ?: return "Нет ключа — включите канал"
    }
    val id = RandomSource.secure().nextBytes(16).joinToString("") { "%02x".format(it) }
    val e = PushEvents.test(Clock.SYSTEM.now().epochMillis / 1000, id)
    val server = PushPrefs.server(context)
    val request = HttpRequest(
        "POST", URI(PushChannel.publishUrl(server, PushPrefs.topic(context))),
        headers = mapOf("Content-Type" to "text/plain"),
        body = PushEvents.json(e, PushSignature.hex(key, e.canonical)).toByteArray(Charsets.UTF_8),
    )
    (AllowList.push(PushChannel.host(server)).check(request) as? Outcome.Failure)?.let { return "Адрес не разрешён: ${it.diagnostics.first().message}" }
    return when (val r = UrlTransport().send(request)) {
        is Outcome.Failure -> "Не отправлено: ${r.diagnostics.first().message}"
        is Outcome.Success -> if (r.value.status in 200..299) "Отправлено — уведомление «Проверка мгновенного канала» придёт, если канал подключён" else "Сервер ответил ${r.value.status}"
    }
}

/** В буфер — с пометкой «секрет»: Android 13+ не показывает его во всплывающем предпросмотре. */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("мгновенный канал", text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
