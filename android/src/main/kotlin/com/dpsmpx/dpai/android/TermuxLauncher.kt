package com.dpsmpx.dpai.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.TargetEntry
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
    private const val SSHD = "/data/data/com.termux/files/usr/bin/sshd"

    /** Аргументы `sshd`: только ключи, без паролей и интерактивного входа. */
    val SSHD_ARGS = arrayOf("-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no")

    /** Не чаще: Termux при отказе показывает своё уведомление, шуметь им не нужно. */
    private const val RETRY_MS = 5 * 60_000L

    @Volatile
    private var lastStartAt = 0L

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun permitted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

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
            Outcome.success(Unit)
        } catch (e: Exception) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.OPERATION_NOT_ALLOWED, "Termux не принял команду", e)))
        }
    }

    /**
     * Для службы контроллера: у локальных targets закрыт порт — запустить
     * `sshd`, не чаще раза в [RETRY_MS]. Итог — строка для журнала или `null`.
     */
    fun ensureSshd(context: Context, targets: List<TargetEntry>): String? {
        val local = targets.filter(::local)
        if (local.isEmpty() || local.all { listening(it.port) }) return null
        val now = nowMs()
        if (now - lastStartAt < RETRY_MS) return null
        lastStartAt = now
        return when (val r = startSshd(context)) {
            is Outcome.Success -> "порт ${local.first().port} закрыт — sshd запущен в Termux"
            is Outcome.Failure -> "порт ${local.first().port} закрыт, sshd не запущен: ${r.diagnostics.first().message}"
        }
    }

    /** Время для паузы между попытками — монотонное, как в контуре (D-07). */
    private fun nowMs(): Long = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000

    @Suppress("UnusedReceiverParameter")
    private object System
}
