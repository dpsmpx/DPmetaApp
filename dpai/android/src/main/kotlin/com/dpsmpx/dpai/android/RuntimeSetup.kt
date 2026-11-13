package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.CppDependencies
import com.dpsmpx.dpai.ui.KotlinRuntime
import com.dpsmpx.dpai.ui.PythonRuntime
import com.dpsmpx.dpai.ui.RunDiagnostics
import com.dpsmpx.dpai.ui.RunLanguage
import kotlin.time.Duration.Companion.seconds

/** Установка вне пользовательской PTY: без системного pip и самовольной сборки Gradle (D-141). */
internal object RuntimeSetup {
    private fun packages(language: RunLanguage): List<String> = when (language) {
        RunLanguage.PYTHON -> PythonRuntime.packages
        RunLanguage.KOTLIN -> KotlinRuntime.packages
        RunLanguage.CPP -> CppDependencies.TOOLCHAIN_PACKAGES
        RunLanguage.OTHER -> emptyList()
    }
    private fun apk(action: LinuxEnvironment.Apk, names: List<String>): Outcome<ProcessSpec> = LinuxEnvironment.apk(action, names)
    private fun done(run: TerminalHub.EditorRun, result: Outcome<Completed>, success: String, marker: String? = null): LongJobs.Done = when (result) {
        is Outcome.Failure -> LongJobs.Done(result.errors.first().message, failed = true)
        is Outcome.Success -> {
            val output = result.value.stdout + result.value.stderr
            val ok = result.value.ok && (marker == null || marker in result.value.stdout)
            val advice = if (ok) success else RunDiagnostics.analyze(run.language, output.joinToString("\n"), result.value.exitCode.takeIf { it != 0 } ?: 1).advice.joinToString(" ")
            LongJobs.Done(if (ok) advice else "Операция не завершена: код ${result.value.exitCode}. $advice", output.takeLast(40), failed = !ok)
        }
    }
    private fun postSetup(run: TerminalHub.EditorRun, force: Boolean): ProcessSpec? = when (run.language) {
        RunLanguage.PYTHON -> ProcessSpec(PythonRuntime.prepareCommand(run.root, run.file))
        RunLanguage.KOTLIN -> ProcessSpec(KotlinRuntime.installCompiler(run.root, run.file, force))
        else -> null
    }
    fun prepare(context: Context, run: TerminalHub.EditorRun, needed: List<String>): LongJobs.Done {
        val first = when (val spec = apk(LinuxEnvironment.Apk.ADD, (packages(run.language) + needed).distinct())) {
            is Outcome.Failure -> return LongJobs.Done(spec.errors.first().message, failed = true)
            is Outcome.Success -> spec.value
        }
        val steps = listOfNotNull(first, postSetup(run, force = false))
        return done(run, LinuxSetup.runSteps(context, steps), "Среда ${run.language.title} подготовлена. Вернитесь в IDE и нажмите ▶.")
    }
    fun repair(context: Context, run: TerminalHub.EditorRun): LongJobs.Done {
        if (run.language == RunLanguage.CPP) return CppSetup.repair(context)
        val first = when (val spec = apk(LinuxEnvironment.Apk.FIX, packages(run.language))) {
            is Outcome.Failure -> return LongJobs.Done(spec.errors.first().message, failed = true)
            is Outcome.Success -> spec.value
        }
        return done(run, LinuxSetup.runSteps(context, listOfNotNull(first, postSetup(run, force = true))), "Среда ${run.language.title} восстановлена. Проверьте её и повторите запуск из IDE.")
    }
    fun check(context: Context, run: TerminalHub.EditorRun): LongJobs.Done {
        if (run.language == RunLanguage.CPP) return CppSetup.check(context)
        val host = LinuxSetup.guestHost(context) ?: return LongJobs.Done("Сначала установите Linux приложения", failed = true)
        val (command, marker) = when (run.language) {
            RunLanguage.PYTHON -> PythonRuntime.probe(run.root, run.file) to "DPAI_PYTHON_OK"
            RunLanguage.KOTLIN -> KotlinRuntime.probe(run.root, run.file) to "DPAI_KOTLIN_OK"
            else -> return LongJobs.Done("Проверка для этого типа команды не настроена", failed = true)
        }
        return done(run, host.run(ProcessSpec(command), 120.seconds, maxStdoutChars = 32_768), "Среда ${run.language.title} проверена.", marker)
    }
    fun installApk(context: Context, run: TerminalHub.EditorRun, names: List<String>): LongJobs.Done =
        done(run, LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, names), "Пакеты установлены. Вернитесь в IDE и повторите запуск.")
    fun libraries(context: Context, run: TerminalHub.EditorRun, names: List<String>): LongJobs.Done {
        val plan = when (run.language) {
            RunLanguage.PYTHON -> PythonRuntime.install(run.root, run.file, names)
            RunLanguage.KOTLIN -> KotlinRuntime.dependencies(run.root, run.file, names)
            else -> return LongJobs.Done("Используйте пакеты Alpine для этого языка", failed = true)
        }
        val arguments = when (plan) {
            is Outcome.Failure -> return LongJobs.Done(plan.errors.first().message, failed = true)
            is Outcome.Success -> plan.value
        }
        val initial = when (val spec = apk(LinuxEnvironment.Apk.ADD, packages(run.language))) {
            is Outcome.Failure -> return LongJobs.Done(spec.errors.first().message, failed = true)
            is Outcome.Success -> spec.value
        }
        val sdk = if (run.language == RunLanguage.KOTLIN) ProcessSpec(KotlinRuntime.installCompiler(run.root, run.file, force = false)) else null
        val steps = listOfNotNull(initial, sdk, ProcessSpec(arguments))
        return done(run, LinuxSetup.runSteps(context, steps), "Библиотеки установлены для шаблона ${run.language.title} этого проекта. Выберите шаблон, если была своя команда, и нажмите ▶.")
    }
}
