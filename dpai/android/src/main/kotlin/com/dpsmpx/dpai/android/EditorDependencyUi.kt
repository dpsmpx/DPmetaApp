package com.dpsmpx.dpai.android

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.CppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Подтверждение пакетов вне PTY и отдельный выбор команды: программа сама не перезапускается (D-139). */
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
        if (!visible() || querying || dialog?.isShowing == true || (automatic && run.offered)) return
        querying = true
        scope.launch {
            try {
                val missing = withContext(Dispatchers.IO) { PultCommands.missing(activity, report.packages) }
                if (!visible() || tab !== TerminalHub.current || run.report !== report) return@launch
                run.offered = true
                val text = buildString {
                    append("Код завершения: ${report.exitCode}.\n")
                    report.advice.forEach { append(it + "\n") }
                    if (report.libraries.isNotEmpty()) append("Библиотеки/инструменты: ${report.libraries.joinToString { it.name }}.\n")
                    if (report.packages.isNotEmpty()) append("Пакеты Alpine: ${report.packages.joinToString()}.\n")
                    if (missing.isNotEmpty()) append("Нужно скачать: ${missing.joinToString()}. Зависимости поставятся вместе с ними.\n")
                    else if (report.packages.isNotEmpty()) append("Пакеты уже установлены: проверьте флаги сборки и пути заголовков.\n")
                    if (report.flags.isNotEmpty()) append("Флаги сборки: ${report.flags.joinToString(" ")}. Установка сама команду не меняет.\n")
                    if (report.unknownHeaders.isNotEmpty()) append("Неизвестные заголовки: ${report.unknownHeaders.joinToString()}. Пакет по ним не угадан.\n")
                    jobs.state.value[LongJobs.LINUX_APK]?.takeIf { it.label == LABEL }?.let { job ->
                        append(if (job.running) "\nУстановка идёт…" else "\n${job.note.orEmpty()}")
                        if (job.output.isNotEmpty()) append("\n" + job.output.takeLast(8).joinToString("\n"))
                    }
                    append("\nПосле установки вернитесь в IDE и нажмите ▶. Для обновления пакетов: «Настройки AI» → «Linux в приложении» → «Обновить всё».")
                }
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 8, 24, 8)
                    addView(TextView(activity).apply { this.text = text; setTextIsSelectable(true) })
                    if (report.packages.isNotEmpty() || report.unknownHeaders.isNotEmpty()) {
                        addView(Button(activity).apply { this.text = "Указать пакеты вручную"; setOnClickListener { packageInput(missing) } })
                    }
                    if (report.issues.any { it in setOf(CppDependencies.Issue.TOOLCHAIN, CppDependencies.Issue.PACKAGE_WRITE, CppDependencies.Issue.EXECUTION_PERMISSION) }) {
                        addView(Button(activity).apply { this.text = "Проверить среду C++"; setOnClickListener { environment(repair = false) } })
                        addView(Button(activity).apply { this.text = "Восстановить пакеты C++…"; setOnClickListener {
                            show(AlertDialog.Builder(activity).setTitle("Восстановить тулчейн?")
                                .setMessage("Переустановить ${CppDependencies.TOOLCHAIN_PACKAGES.joinToString()} и их зависимости через apk fix. Данные проектов не меняются. Нужен интернет.")
                                .setNegativeButton("Отмена", null).setPositiveButton("Восстановить") { _, _ -> environment(repair = true) })
                        } })
                    }
                }
                val builder = AlertDialog.Builder(activity).setTitle("Проблема запуска").setView(ScrollView(activity).apply { addView(content) })
                    .setNegativeButton("Позже", null)
                if (missing.isNotEmpty()) builder.setPositiveButton("Установить (${missing.size})") { _, _ -> install(missing) }
                if (run.file.substringAfterLast('.').lowercase() in setOf("cpp", "cc", "cxx", "c++")) {
                    builder.setNeutralButton("Команда C++20") { _, _ -> command(run, report) }
                }
                show(builder)
            } finally { querying = false }
        }
    }
    private fun packageInput(initial: List<String>) {
        val input = EditText(activity).apply { setText(initial.joinToString(" ")); hint = "Например: ncurses-dev openssl-dev"; setSingleLine() }
        val builder = AlertDialog.Builder(activity).setTitle("Пакеты Alpine").setMessage("Имена через пробел. Установка загрузит пакеты и их зависимости в Linux приложения.")
            .setView(input).setNegativeButton("Отмена", null).setPositiveButton("Установить") { _, _ ->
                val names = input.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val valid = com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment.apk(com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment.Apk.ADD, names)
                if (valid is Outcome.Failure) toast(valid.errors.first().message) else install(names)
            }
        show(builder)
    }
    private fun install(packages: List<String>) {
        val app = activity.applicationContext
        val started = jobs.start(LongJobs.LINUX_APK, LABEL, excludes = LongJobs.GUEST) {
            when (val result = PultCommands.install(app, packages)) {
                is Outcome.Success -> LongJobs.Done("Пакеты установлены. Проверьте флаги и запустите файл из IDE.", result.value)
                is Outcome.Failure -> LongJobs.Done(result.errors.first().message, failed = true)
            }
        }
        toast(if (started) "Устанавливаю: ${packages.joinToString()}. Итог — кнопкой «Решения…»." else "Другая операция Linux ещё идёт. Дождитесь её завершения.")
    }
    private fun environment(repair: Boolean) {
        val app = activity.applicationContext
        val started = jobs.start(LongJobs.LINUX_APK, LABEL, excludes = LongJobs.GUEST) { if (repair) CppSetup.repair(app) else CppSetup.check(app) }
        toast(if (started) "${if (repair) "Восстанавливаю пакеты" else "Проверяю среду"}. Итог — кнопкой «Решения…»." else "Другая операция Linux ещё идёт. Дождитесь её завершения.")
        dialog?.dismiss()
    }
    private fun command(run: TerminalHub.EditorRun, report: CppDependencies.Report) {
        val command = CppDependencies.singleFileCommand(report, run.command)
        val text = TextView(activity).apply { this.text = "Шаблон для ОДНОГО файла C++20. Команда проекта будет заменена только после подтверждения.\n\n$command"; setTextIsSelectable(true); setPadding(24, 8, 24, 8) }
        val builder = AlertDialog.Builder(activity).setTitle("Команда с библиотеками").setView(ScrollView(activity).apply { addView(text) })
            .setNegativeButton("Отмена", null).setNeutralButton("Копировать") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Команда C++", command))
            }
        val key = run.editorRootKey
        if (key != null) builder.setPositiveButton("Сохранить в IDE") { _, _ ->
            val root = IdeState.state.value.roots[key]
            if (root == null) toast("Папка IDE больше не открыта; скопируйте команду вручную")
            else { IdeState.root(key, root.copy(runCommand = command)); toast("Команда сохранена. Вернитесь в IDE и нажмите ▶.") }
        }
        show(builder)
    }
    private fun show(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.create().also { next -> next.setOnDismissListener { if (dialog === next) dialog = null }; next.show() }
    }
    private fun toast(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    companion object { private const val LABEL = "Библиотеки IDE" }
}
