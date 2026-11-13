package com.dpsmpx.dpai.android

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.CppDependencies
import com.dpsmpx.dpai.ui.EditorTerminalRun
import com.dpsmpx.dpai.ui.KotlinRuntime
import com.dpsmpx.dpai.ui.PythonRuntime
import com.dpsmpx.dpai.ui.RunDiagnosis
import com.dpsmpx.dpai.ui.RunLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Подтверждения вне пользовательской PTY; после установки запуск только из IDE (D-141). */
internal class EditorDependencyUi(private val activity: TerminalActivity, private val refresh: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = LongJobs.get(activity)
    private var dialog: AlertDialog? = null
    private var querying = false
    init { scope.launch { jobs.state.collect { refresh() } } }
    fun close() { scope.cancel(); dialog?.dismiss() }
    private fun visible() = !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun offer(tab: TerminalHub.Tab, automatic: Boolean) {
        val run = tab.editorRun ?: return
        val report = run.report?.takeIf { it.useful } ?: return
        if (!visible() || querying || dialog?.isShowing == true || automatic && run.offered) return
        querying = true
        scope.launch {
            try {
                val missing = withContext(Dispatchers.IO) { PultCommands.missing(activity, report.packages) }
                if (!visible() || tab !== TerminalHub.current || run.report !== report) return@launch
                run.offered = true
                val text = buildString {
                    append("${run.language.title}: код завершения ${report.exitCode}.\n")
                    report.advice.forEach { append(it + "\n") }
                    if (report.packages.isNotEmpty()) append("Пакеты Alpine: ${report.packages.joinToString()}.\n")
                    if (missing.isNotEmpty()) append("Отсутствуют: ${missing.joinToString()}; зависимости придут вместе с ними.\n")
                    else if (report.packages.isNotEmpty()) append("Пакеты числятся установленными; доступны проверка и восстановление.\n")
                    report.cpp?.flags?.takeIf { it.isNotEmpty() }?.let { append("Флаги сборки: ${it.joinToString(" ")}.\n") }
                    if (report.pip.isNotEmpty()) append("PyPI: ${report.pip.joinToString()}.\n")
                    if (report.maven.isNotEmpty()) append("Maven: ${report.maven.joinToString()}.\n")
                    if (report.unknown.isNotEmpty()) append("Неопределённые зависимости: ${report.unknown.joinToString()}.\n")
                    if (run.language == RunLanguage.PYTHON) append("Модули ставятся во внутреннее окружение проекта. Свой интерпретатор выбирается командой запуска; для этих модулей доступен шаблон Python 3.\n")
                    if (run.language == RunLanguage.KOTLIN && report.environmentProblem) append("Подготовка установит JDK 21 и Kotlin/JVM ${KotlinRuntime.VERSION}: официальный ZIP около 87 МБ с проверкой SHA-256; пакеты JDK скачиваются отдельно.\n")
                    jobs.state.value[LongJobs.LINUX_APK]?.takeIf { it.label == label(run) }?.let { job ->
                        append(if (job.running) "\nОперация идёт…" else "\n${job.note.orEmpty()}")
                        if (job.output.isNotEmpty()) append("\n" + job.output.takeLast(8).joinToString("\n"))
                    }
                    append("\nРабочая папка: ${run.root}. После исправления вернитесь в IDE и нажмите ▶. Обновление пакетов Alpine — в настройках Linux.")
                }
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
                    addView(TextView(activity).apply { this.text = text; setTextIsSelectable(true) })
                }
                fun action(title: String, clicked: () -> Unit) { content.addView(Button(activity).apply { this.text = title; setOnClickListener { clicked() } }) }
                if (report.packages.isNotEmpty() || report.unknown.isNotEmpty()) action("Пакеты Alpine вручную…") { packageInput(run, missing) }
                if (run.language == RunLanguage.PYTHON) {
                    if (report.pip.isNotEmpty()) action("Установить модули PyPI (${report.pip.size})…") { confirmLibraries(run, report.pip) }
                    action("Указать пакет PyPI / версию…") { libraryInput(run) }
                }
                if (run.language == RunLanguage.KOTLIN && !report.projectCommand) {
                    if (report.maven.isNotEmpty()) action("Разрешить библиотеки Maven (${report.maven.size})…") { confirmLibraries(run, report.maven) }
                    action("Указать Maven-координаты / версию…") { libraryInput(run) }
                }
                val environment = report.environmentProblem || report.pip.isNotEmpty() || report.maven.isNotEmpty() || run.language == RunLanguage.PYTHON && report.unknown.isNotEmpty()
                if (environment && run.language != RunLanguage.OTHER) {
                    action("Проверить среду ${run.language.title}") { start(run) { RuntimeSetup.check(it, run) } }
                    action("Восстановить среду ${run.language.title}…") {
                        show(AlertDialog.Builder(activity).setTitle("Восстановить среду?")
                            .setMessage("Пакеты среды будут переустановлены через apk fix. Python: внутреннее venv обновится без удаления его пакетов. Kotlin: официальный компилятор будет скачан заново и проверен. Данные проекта не меняются. Нужен интернет.")
                            .setNegativeButton("Отмена", null).setPositiveButton("Восстановить") { _, _ -> start(run) { RuntimeSetup.repair(it, run) } })
                    }
                }
                val builder = AlertDialog.Builder(activity).setTitle("Проблема запуска").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("Позже", null)
                if (report.environmentProblem && run.language in setOf(RunLanguage.PYTHON, RunLanguage.KOTLIN)) {
                    builder.setPositiveButton("Подготовить ${run.language.title}") { _, _ -> start(run) { RuntimeSetup.prepare(it, run, report.packages) } }
                } else if (missing.isNotEmpty()) builder.setPositiveButton("Установить (${missing.size})") { _, _ -> start(run) { RuntimeSetup.installApk(it, run, missing) } }
                if (report.projectCommand) builder.setNeutralButton("Команда проекта…") { _, _ -> projectCommand(run) }
                else if (run.language in setOf(RunLanguage.PYTHON, RunLanguage.KOTLIN) || report.cpp?.let { it.flags.isNotEmpty() || report.environmentProblem || CppDependencies.Issue.LINK_FLAGS in it.issues } == true) {
                    builder.setNeutralButton("Шаблон ${run.language.title}") { _, _ -> template(run, report) }
                }
                show(builder)
            } finally { querying = false }
        }
    }
    private fun label(run: TerminalHub.EditorRun) = "Среда IDE · ${run.language.title}"
    private fun start(run: TerminalHub.EditorRun, operation: (Context) -> LongJobs.Done) {
        val app = activity.applicationContext
        val started = jobs.start(LongJobs.LINUX_APK, label(run), excludes = LongJobs.GUEST) {
            val done = operation(app)
            if (done.failed) run.report = run.report?.withFailure(done.output.joinToString("\n") + "\n" + done.note)
            done
        }
        if (started) dialog?.dismiss()
        toast(if (started) "Операция началась. Итог — кнопкой «Решения…»." else "Другая операция Linux ещё идёт. Дождитесь её завершения.")
    }
    private fun packageInput(run: TerminalHub.EditorRun, initial: List<String>) {
        input("Пакеты Alpine", "Имена через пробел, например ncurses-dev", initial.joinToString(" ")) { text ->
            val names = words(text)
            val valid = LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, names)
            if (valid is Outcome.Failure) { toast(valid.errors.first().message); false }
            else { start(run) { RuntimeSetup.installApk(it, run, names) }; true }
        }
    }
    private fun libraryInput(run: TerminalHub.EditorRun) {
        val hint = if (run.language == RunLanguage.PYTHON) "пакет или пакет==версия через пробел; пример requests==2.32.5" else "group:artifact:version через пробел; пример com.google.code.gson:gson:2.13.2"
        input(if (run.language == RunLanguage.PYTHON) "Модули PyPI" else "Библиотеки Maven", hint, "") { text ->
            val names = words(text)
            val valid = if (run.language == RunLanguage.PYTHON) PythonRuntime.install(run.root, run.file, names) else KotlinRuntime.dependencies(run.root, run.file, names)
            if (valid is Outcome.Failure) { toast(valid.errors.first().message); false }
            else { confirmLibraries(run, names); true }
        }
    }
    private fun confirmLibraries(run: TerminalHub.EditorRun, names: List<String>) {
        val source = if (run.language == RunLanguage.PYTHON) "PyPI, отдельное внутреннее venv" else "Maven Central, classpath одиночного JVM-файла"
        show(AlertDialog.Builder(activity).setTitle("Установить библиотеки?")
            .setMessage("$source:\n${names.joinToString("\n")}\n\nЗагрузятся и транзитивные зависимости; при необходимости установится среда ${run.language.title}. Gradle/.venv проекта не меняются. Новая программа сама не запустится.")
            .setNegativeButton("Отмена", null).setPositiveButton("Установить") { _, _ -> start(run) { RuntimeSetup.libraries(it, run, names) } })
    }
    private fun template(run: TerminalHub.EditorRun, report: RunDiagnosis) {
        val cpp = report.cpp
        val command = if (run.language == RunLanguage.CPP && cpp != null) CppDependencies.singleFileCommand(cpp, run.command) else EditorTerminalRun.defaultCommand(run.file)
        val text = TextView(activity).apply { this.text = activity.getString(R.string.editor_command_template, run.language.title, command); setTextIsSelectable(true); setPadding(24, 8, 24, 8) }
        val builder = AlertDialog.Builder(activity).setTitle("Команда запуска").setView(ScrollView(activity).apply { addView(text) })
            .setNegativeButton("Отмена", null).setNeutralButton("Копировать") { _, _ -> activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Команда", command)) }
        if (run.editorRootKey != null) builder.setPositiveButton("Сохранить в IDE") { _, _ -> saveCommand(run, command) }
        show(builder)
    }
    private fun projectCommand(run: TerminalHub.EditorRun) {
        input("Команда проекта", "Рабочая папка: ${run.root}. Например: sh ./gradlew :app:run. Укажите свою задачу/переход в папку проекта.", "") { command ->
            if (command.isBlank() || command.length > 4096 || '\u0000' in command) { toast("Нужна команда до 4096 знаков"); false } else saveCommand(run, command)
        }
    }
    private fun saveCommand(run: TerminalHub.EditorRun, command: String): Boolean {
        val key = run.editorRootKey
        val root = key?.let { IdeState.state.value.roots[it] }
        if (key == null || root == null) { toast("Папка IDE больше не открыта; скопируйте команду вручную"); return false }
        IdeState.root(key, root.withCommand(run.file, command)); toast("Команда сохранена. Вернитесь в IDE и нажмите ▶."); return true
    }
    private fun input(title: String, message: String, initial: String, accept: (String) -> Boolean) {
        val field = EditText(activity).apply { setText(initial) }
        val next = show(AlertDialog.Builder(activity).setTitle(title).setMessage(message).setView(field).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null))
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (accept(field.text.toString())) next.dismiss() }
    }
    private fun words(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    private fun show(builder: AlertDialog.Builder): AlertDialog {
        dialog?.dismiss()
        return builder.create().also { next -> dialog = next; next.setOnDismissListener { if (dialog === next) dialog = null }; next.show() }
    }
    private fun toast(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
