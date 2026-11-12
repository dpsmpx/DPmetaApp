package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * Команды реестра пульта DPmetaApp в Linux-среде приложения (`DPmetaApp:D-82`,
 * `DPmetaApp:D-49`): оболочка `:shell` видит их строками, без типов модулей
 * `execution` и `orchestrator`. Что запускать и с каким уровнем, решает пульт —
 * его реестр и `:core:actions`; здесь — только Linux-среда: готова ли, есть ли
 * пакеты, поставить их, запустить команду в каталоге гостя. Вызовы — не на
 * главном потоке.
 */
object PultCommands {
    /** Linux-среда: «unsupported» — не arm64, «absent» — не установлена, «ready» — готова. */
    fun linux(context: Context): String = when {
        !LinuxSetup.available(context) -> "unsupported"
        LinuxSetup.environment(context).installed() == null -> "absent"
        else -> "ready"
    }

    /**
     * Каких пакетов Alpine из [packages] нет — по базе `apk` гостя
     * (`/lib/apk/db/installed`, строки `P:<имя>`), без запуска процесса.
     * Linux не установлен — все.
     */
    fun missing(context: Context, packages: List<String>): List<String> {
        val db = File(LinuxSetup.layout(context).rootfs, "lib/apk/db/installed")
        val installed = runCatching { db.useLines { com.dpsmpx.dpai.ui.AlpinePackageState.names(it) } }.getOrNull()
            ?: return packages
        return packages.filter { it !in installed }
    }

    /** Поставить пакеты (`apk add`, как «Пакеты» Linux-среды «AI»): итог — последние строки вывода. */
    fun install(context: Context, packages: List<String>): Outcome<List<String>> =
        when (val r = LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, packages)) {
            is Outcome.Failure -> r
            is Outcome.Success -> if (r.value.ok) {
                Outcome.success((r.value.stdout + r.value.stderr).takeLast(20))
            } else {
                Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, "apk: код ${r.value.exitCode}; " + (r.value.stderr + r.value.stdout).takeLast(3).joinToString(" "), "apk")
            }
        }

    /** Итог команды: код возврата (`null` — остановлена), остановил ли человек, вышел ли предел времени. */
    data class Finished(val code: Int?, val stopped: Boolean, val timedOut: Boolean)

    /**
     * Запустить [argv] в каталоге гостя [dir] (окружение гостя, DNS сети телефона) и
     * ждать выхода не дольше [minutes]; строки stdout и stderr — в [onLine] по ходу;
     * [onStop] получает остановку — её зовёт экран. Предел вышел — остановка всего
     * дерева процессов.
     */
    fun run(
        context: Context,
        dir: String,
        argv: List<String>,
        minutes: Int,
        onLine: (String) -> Unit,
        onStop: (() -> Unit) -> Unit,
    ): Outcome<Finished> {
        if (!(dir.startsWith("/root/projects/") || dir.startsWith("/root/repos/")) || dir.split('/').any { it == ".." }) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "команды реестра — только в клонах /root/projects", dir)
        }
        val host = LinuxSetup.guestHost(context) ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux", "linux")
        val p = when (val s = host.start(ProcessSpec(argv, workDir = dir)) { onLine(it.text) }) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        val stopped = AtomicBoolean(false)
        onStop {
            stopped.set(true)
            p.stop()
        }
        val code = p.awaitExit(minutes.minutes)
        if (code == null) {
            p.stop()
            return Outcome.success(Finished(null, stopped.get(), timedOut = !stopped.get()))
        }
        return Outcome.success(Finished(if (stopped.get()) null else code, stopped.get(), false))
    }
}
