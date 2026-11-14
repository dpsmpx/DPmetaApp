package com.dpsmpx.dpai.android

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.tools.SharedToolsStatus
import com.dpsmpx.dpai.execution.tools.SharedToolsSync
import com.dpsmpx.dpai.jsch.SshKeys
import com.dpsmpx.dpai.ui.TargetEntry
import com.jcraft.jsch.JSch
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** ПК/Termux по закреплённому SSH: токен только после пробы обновлённого агента (D-175). */
internal object RemoteSharedVerify {
    private val awaitingAccount = MutableStateFlow(false)
    /** Только состояние ожидания; сам аккаунт остаётся у оболочки и текущего задания. */
    val needsAuthorization: StateFlow<Boolean> = awaitingAccount
    private fun prefs(context: Context) = context.getSharedPreferences("dpai.remote-shared-verify", Context.MODE_PRIVATE)
    fun key(target: TargetEntry) = "remote-shared-verify:${target.id.value}"
    fun automatic(context: Context, target: TargetEntry) = prefs(context).getBoolean("${target.id.value}.automatic", false)
    fun connected(context: Context, target: TargetEntry) = prefs(context).contains("${target.id.value}.binding")
    fun automatic(context: Context, target: TargetEntry, value: Boolean) {
        prefs(context).edit { putBoolean("${target.id.value}.automatic", value) }
        if (value) AiBackground.ensureService(context)
    }

    /** Не включает чужие хосты в доверие: используются только уже подтверждённые ключи. */
    private fun binding(controller: Controller, target: TargetEntry, root: String, python: String): String? = runCatching {
        val ssh = JSch().apply { setKnownHosts(controller.knownHostsFile) }
        val keys = ssh.hostKeyRepository.getHostKey(SshKeys.knownHostsName(target.host, target.port), null).orEmpty()
        if (keys.isEmpty()) return null
        val fields = listOf(target.host, target.port.toString(), target.user, target.platform.name, target.agent, root, python) +
            keys.map { "${it.type}:${it.key}" }.sorted()
        MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun probe(controller: Controller, target: TargetEntry): Outcome<SharedToolsStatus> {
        val host = when (val r = controller.pcHost(target)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val t = target.toTarget(controller.knownHostsFile)
        val argv = t.agentCommand + listOf("tools", "status", "--json") + (t.agentHome?.let { listOf("--home", it) } ?: emptyList())
        val result = when (val r = host.run(ProcessSpec(argv), 30.seconds, maxStdoutChars = 16384, maxStderrChars = 0)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!result.ok || result.truncated) return Outcome.failure(DiagCode.UNSUPPORTED, "агент не подтвердил поддержку DP-Verify; обновите его и проверьте SSH")
        val value = when (val r = JsonParser().parse(result.stdout.joinToString("\n"))) {
            is Outcome.Failure -> return Outcome.failure(DiagCode.UNSUPPORTED, "обновите агент dpai: нужен ответ tools status --json")
            is Outcome.Success -> r.value
        }
        return when (val parsed = SharedToolsStatus.parse(value)) {
            is Outcome.Failure -> parsed
            is Outcome.Success -> if (parsed.value.windows != (target.platform == TargetPlatform.WINDOWS)) {
                Outcome.failure(DiagCode.VALUE_INVALID, "платформа агента отличается от настроек target")
            } else parsed
        }
    }

    /** Операция переживает закрытие диалога; несколько операций одного target не пересекаются. */
    fun start(context: Context, target: TargetEntry, root: String, python: String, background: Boolean = false): Boolean {
        val app = context.applicationContext
        val started = LongJobs.get(app).start(key(target), "DP-Verify: ${target.label}") {
            val controller = Controller.get(app.filesDir)
            if (controller.targets().none { it == target }) return@start LongJobs.Done("Настройка target изменилась; подключите заново", failed = true)
            val identity = binding(controller, target, root, python)
                ?: return@start LongJobs.Done("Сначала подтвердите SSH-ключ target", failed = true)
            val config = prefs(app)
            if (background && (!automatic(app, target) || config.getString("${target.id.value}.binding", null) != identity)) {
                return@start LongJobs.Done("Адрес, ключ SSH или настройка папки изменились; нужен новый вход и подключение", failed = true)
            }
            val status = when (val r = probe(controller, target)) {
                is Outcome.Failure -> return@start LongJobs.Done(r.diagnostics.first().message, failed = true)
                is Outcome.Success -> r.value
            }
            val current = status.configured
            if (current != null && (current.repository != root || current.python != python)) {
                return@start LongJobs.Done("На ПК подключена другая папка или Python; сначала отключите её", failed = true)
            }
            if (background && current == null) return@start LongJobs.Done("На ПК папка отключена; подключите её заново", failed = true)
            // Повторная проверка после сети — до выдачи токена новому процессу.
            if (controller.targets().none { it == target } || binding(controller, target, root, python) != identity) {
                return@start LongJobs.Done("Target изменился во время проверки; повторите подключение", failed = true)
            }
            val account = (app as? DpaiApplication)?.sharedToolsAccount()
            val token = (account as? Outcome.Success)?.value
            if (token == null) {
                awaitingAccount.value = true
                return@start LongJobs.Done("Нужен вход по PIN/биометрии кнопкой синхронизации", failed = true)
            }
            awaitingAccount.value = false
            val host = when (val r = controller.pcHost(target)) {
                is Outcome.Failure -> return@start LongJobs.Done(r.diagnostics.first().message, failed = true)
                is Outcome.Success -> r.value
            }
            val t = target.toTarget(controller.knownHostsFile)
            val spec = SharedToolsSync.agentRequest(t.agentCommand, t.agentHome, current == null, root, python, token)
            val done = when (val r = host.run(spec, 31.minutes, maxStdoutChars = 8192, maxStderrChars = 0)) {
                is Outcome.Failure -> return@start LongJobs.Done("Синхронизация не завершена; проверьте SSH, Python и Git на target", failed = true)
                is Outcome.Success -> r.value
            }
            val reply = (JsonParser().parse(done.stdout.lastOrNull().orEmpty()) as? Outcome.Success)?.value as? JsonObject
            val revision = (reply?.get("revision") as? JsonString)?.value?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            if (!done.ok || done.truncated || revision == null) {
                val reason = (reply?.get("reason") as? JsonString)?.value
                val text = when {
                    done.exitCode == 75 -> "Инструменты или настройка заняты; повторю в паузе"
                    reason == "validation_failed" -> "Полная проверка версии не прошла; журнал в shared-tools-controller/state-* на target"
                    reason in setOf("diverged", "local_remote_changes") -> "Истории разошлись; обе копии сохранены, нужен разбор"
                    reason == "unexpected_remote" -> "Origin отличается от официального DP-Verify; обновление остановлено"
                    else -> "Синхронизация не выполнена; проверьте права аккаунта, Python 3.11+, Git и сеть на target"
                }
                return@start LongJobs.Done(text, failed = done.exitCode != 75)
            }
            if (!background && controller.targets().any { it == target } && binding(controller, target, root, python) == identity) {
                config.edit { putString("${target.id.value}.binding", identity); putString("${target.id.value}.root", root); putString("${target.id.value}.python", python) }
            }
            LongJobs.Done("Проверенная версия ${revision.take(12)} подключена к новым ИИ-ходам")
        }
        if (started && !ControllerService.running) ControllerService.start(app)
        return started
    }

    fun disconnect(context: Context, target: TargetEntry): Boolean = LongJobs.get(context).start(key(target), "Отключить DP-Verify: ${target.label}") {
        val controller = Controller.get(context.filesDir)
        if (controller.targets().none { it == target }) return@start LongJobs.Done("Target изменился; повторите", failed = true)
        val host = when (val r = controller.pcHost(target)) {
            is Outcome.Failure -> return@start LongJobs.Done(r.diagnostics.first().message, failed = true)
            is Outcome.Success -> r.value
        }
        val t = target.toTarget(controller.knownHostsFile)
        val argv = t.agentCommand + listOf("tools", "disable") + (t.agentHome?.let { listOf("--home", it) } ?: emptyList())
        val done = host.run(ProcessSpec(argv), 30.seconds, maxStdoutChars = 0, maxStderrChars = 0)
        if (done is Outcome.Success && done.value.ok) {
            automatic(context, target, false)
            prefs(context).edit { remove("${target.id.value}.binding") }
            LongJobs.Done("Отключено для новых ИИ-ходов; папка и история сохранены")
        } else LongJobs.Done("Не удалось отключить; дождитесь текущей синхронизации и проверьте SSH", failed = true)
    }

    private var lastAttempt = Long.MIN_VALUE
    fun enabled(context: Context): Boolean = Controller.get(context.filesDir).targets().any { !it.inApp && automatic(context, it) }
    fun working(context: Context): Boolean = LongJobs.get(context).state.value.any { it.key.startsWith("remote-shared-verify:") && it.value.running }

    @Synchronized
    fun tick(context: Context) {
        val now = Clock.SYSTEM.monotonicNanos()
        if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < 60_000_000_000L) return
        lastAttempt = now
        val app = context.applicationContext as? DpaiApplication ?: return
        val targets = Controller.get(context.filesDir).targets().filter { !it.inApp && automatic(context, it) }
        if (targets.isEmpty()) { awaitingAccount.value = false; return }
        val available = (app.sharedToolsAccount() as? Outcome.Success)?.value != null
        awaitingAccount.value = !available
        if (!available) return
        val config = prefs(context)
        for (target in targets) {
            val root = config.getString("${target.id.value}.root", null) ?: continue
            val python = config.getString("${target.id.value}.python", null) ?: continue
            start(context, target, root, python, background = true)
        }
    }
}
