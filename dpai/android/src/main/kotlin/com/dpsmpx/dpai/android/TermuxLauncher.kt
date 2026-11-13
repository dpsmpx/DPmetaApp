package com.dpsmpx.dpai.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.TermuxState
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Запуск `sshd` Termux-target на этом же телефоне (D-46) — официальным
 * намерением Termux `RUN_COMMAND`: после перезагрузки или смерти Termux
 * контроллеру не нужно, чтобы человек открывал терминал.
 *
 * Только одна команда — `sshd` с входом лишь по ключам; ничего другого
 * приложение в Termux не запускает. Нужны разрешение
 * `com.termux.permission.RUN_COMMAND` (даёт человек) и
 * `allow-external-apps = true` в `~/.termux/termux.properties`.
 */
object TermuxLauncher {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PACKAGE = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    // Это исполняемый файл внешнего Termux, а не каталог данных DPmetaApp.
    @SuppressLint("SdCardPath")
    private const val SSHD = "/data/data/com.termux/files/usr/bin/sshd"

    /** Аргументы `sshd`: только ключи, без паролей и интерактивного входа. */
    val SSHD_ARGS = arrayOf("-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no")

    /** Не чаще: Termux при отказе показывает своё уведомление, шуметь им не нужно. */
    private const val RETRY_MS = 5 * 60_000L

    @Volatile
    private var lastStartAt = -RETRY_MS


    /** Страница приложения в настройках: там «Другие разрешения» — если HyperOS не показал окно запроса. */
    fun appSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun permitted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Объявляет ли установленный Termux `RUN_COMMAND`. Сборка из Google Play
     * (проверка на Redmi Note 13, 9 октября 2026) — нет: разрешения не
     * выдать, и приложение не пытается; `sshd` — автозапуском
     * `~/.termux/boot` или виджетом.
     */
    fun supportsRunCommand(context: Context): Boolean = try {
        context.packageManager.getPermissionInfo(PERMISSION, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Target на этом телефоне: `sshd` Termux слушает loopback. */
    fun local(t: TargetEntry): Boolean = t.host == "127.0.0.1" || t.host == "localhost" || t.host == "::1"

    /** Слушает ли порт loopback: соединение принято — да (отказ — нет, ничего не шлём). */
    fun listening(port: Int, timeoutMs: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    /** Попросить Termux запустить `sshd` в фоне. */
    fun startSshd(context: Context): Outcome<Unit> {
        if (!installed(context)) return Outcome.failure(DiagCode.UNSUPPORTED, "Termux не установлен")
        if (!permitted(context)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "нет разрешения «Запуск команд в среде Termux»")
        val intent = Intent(ACTION).setClassName(PACKAGE, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", SSHD)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", SSHD_ARGS)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return try {
            context.startForegroundService(intent)
            lastStartAt = nowMs()
            requested = true
            Outcome.success(Unit)
        } catch (e: Exception) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.OPERATION_NOT_ALLOWED, "Termux не принял команду", e)))
        }
    }

    /**
     * Состояние Termux-target на этом телефоне для проверки приложения (D-158); локальных
     * targets нет — `null`. «Запрос ушёл, порт закрыт» — [TermuxState.STARTING] первые
     * [STARTUP_MS], дальше — [TermuxState.REQUESTED_NOT_UP].
     */
    fun state(context: Context, targets: List<TargetEntry>): Pair<TermuxState, Int>? {
        val local = targets.filter(::local).ifEmpty { return null }
        val port = local.first().port
        if (local.all { listening(it.port) }) return TermuxState.LISTENING to port
        val s = when {
            !installed(context) -> TermuxState.NOT_INSTALLED
            !supportsRunCommand(context) -> TermuxState.NO_RUN_COMMAND
            !permitted(context) -> TermuxState.NO_PERMISSION
            // Запроса ещё не было (служба только встала) или он только что ушёл — ждём, это не проблема.
            !requested || nowMs() - lastStartAt < STARTUP_MS -> TermuxState.STARTING
            else -> TermuxState.REQUESTED_NOT_UP
        }
        return s to port
    }

    /**
     * Для службы контроллера: у локальных targets закрыт порт — запустить
     * `sshd`, не чаще раза в [RETRY_MS]. Итог — строка для журнала или `null`.
     * «Запущен» — только когда порт ответил (D-158): прежде строка «sshd запущен»
     * писалась сразу после запроса, даже если Termux его не выполнил. Одно и то
     * же состояние — одной строкой, а не каждые 5 минут.
     */
    fun ensureSshd(context: Context, targets: List<TargetEntry>): String? {
        val local = targets.filter(::local)
        if (local.isEmpty()) return null
        val port = local.first().port
        if (local.all { listening(it.port) }) {
            val was = lastNote
            lastNote = null
            requested = false
            return if (was != null) "порт $port открыт — sshd Termux слушает" else null
        }
        if (!supportsRunCommand(context)) return once("порт $port закрыт; этот Termux не принимает команды от приложений — sshd: автозапуск ~/.termux/boot или виджет")
        // Нет разрешения — не попытка: сказать (раз) и не задерживать первый настоящий запуск после выдачи.
        if (!permitted(context)) return once("порт $port закрыт, sshd не запущен: нет разрешения «Запуск команд в среде Termux»")
        if (nowMs() - lastStartAt < RETRY_MS) return null
        // Запрос уже был, а порт закрыт — Termux его не выполнил (обычно нет allow-external-apps = true).
        val failedBefore = requested
        return when (val r = startSshd(context)) {
            is Outcome.Success ->
                if (failedBefore) once("порт $port закрыт: sshd не поднялся после прошлого запроса — проверьте allow-external-apps = true в ~/.termux/termux.properties; запрос повторён")
                else "порт $port закрыт — попросил Termux запустить sshd"
            is Outcome.Failure -> once("порт $port закрыт, sshd не запущен: ${r.diagnostics.first().message}")
        }
    }

    /** Запрос `sshd` уже уходил в этом процессе. */
    @Volatile
    private var requested = false

    /** Последняя строка о закрытом порте: та же — молчим. */
    @Volatile
    private var lastNote: String? = null

    private fun once(line: String): String? {
        if (line == lastNote) return null
        lastNote = line
        return line
    }

    /** Сколько ждать порт после запроса, прежде чем считать, что sshd не поднялся. */
    private const val STARTUP_MS = 60_000L

    /** Время для паузы между попытками — монотонное, как в контуре (D-07). */
    private fun nowMs(): Long = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000

    @Suppress("UnusedReceiverParameter")
    private object System
}
