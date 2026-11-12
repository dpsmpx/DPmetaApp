package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.CppDependencies
import com.dpsmpx.dpai.ui.CppToolchain
import kotlin.time.Duration.Companion.seconds

/** Проверка записи, стандартных заголовков, компоновки и запуска без изменения проекта (D-140). */
internal object CppSetup {
    fun check(context: Context): LongJobs.Done {
        val host = LinuxSetup.guestHost(context) ?: return LongJobs.Done("Сначала установите Linux приложения", failed = true)
        return when (val result = host.run(ProcessSpec(CppToolchain.probeCommand), 60.seconds, maxStdoutChars = 16_384)) {
            is Outcome.Failure -> LongJobs.Done(result.errors.first().message, failed = true)
            is Outcome.Success -> {
                val completed = result.value
                val output = completed.stdout + completed.stderr
                if (completed.ok && completed.stdout.any { it == "DPAI_CPP_OK" }) LongJobs.Done("Тулчейн исправен: запись в системные каталоги, заголовки C/C++, компоновка и запуск проверены.", output)
                else {
                    val report = CppDependencies.analyze(output.joinToString("\n"), completed.exitCode)
                    LongJobs.Done("Проверка среды не прошла. " + report.advice.joinToString(" "), output.takeLast(20), failed = true)
                }
            }
        }
    }
    fun repair(context: Context): LongJobs.Done = when (val result = LinuxSetup.apk(context, LinuxEnvironment.Apk.FIX, CppDependencies.TOOLCHAIN_PACKAGES)) {
        is Outcome.Failure -> LongJobs.Done(result.errors.first().message, failed = true)
        is Outcome.Success -> {
            val output = result.value.stdout + result.value.stderr
            LongJobs.Done(if (result.value.ok) "Пакеты тулчейна восстановлены. Нажмите «Проверить среду», затем запустите файл из IDE."
                else "Восстановление: код ${result.value.exitCode}. " + CppDependencies.analyze(output.joinToString("\n"), result.value.exitCode).advice.joinToString(" "), output.takeLast(20), failed = !result.value.ok)
        }
    }
}
