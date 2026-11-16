package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.AlpineView
import com.dpsmpx.dpai.ui.AppComponents
import java.io.File

/**
 * Раздел «Компоненты» экрана «Окружение» (D-192; `DPmetaApp:D-171`, шаг 2): строки
 * компонентов, подробности, «Убрать…» и «Поставить заново» — тем же установщиком, что и на
 * своём экране (CLI исполнителей, службы IDE). Где установщик просит выбора человека
 * (лицензия SDK, файл модели), — подсказка, куда идти.
 */
internal object EnvironmentComponents {
    private const val MAX_OUTPUT = 20

    fun rootfs(context: Context): File = File(context.filesDir, LinuxSetup.environment(context).rootfs)

    /** Установщик компонента, если он запускается без выбора человека. */
    fun reinstall(c: AppComponents.Component): ((Context) -> Outcome<com.dpsmpx.dpai.execution.process.Completed>?)? = when {
        c.kind == AppComponents.Kind.NPM && c.id == "npm:@anthropic-ai/claude-code" -> { ctx -> LinuxSetup.runSteps(ctx, LinuxEnvironment.claudeInstall()) }
        c.kind == AppComponents.Kind.NPM -> { ctx -> LinuxSetup.runSteps(ctx, LinuxEnvironment.executorsInstall()) }
        else -> null
    }

    /** Служба IDE ставится через [CodeService] (итог без вывода процесса). */
    fun ideInstall(c: AppComponents.Component): CodeService.Install? = when (c.kind) {
        AppComponents.Kind.KOTLIN_LSP -> CodeService.Install.KOTLIN_UPDATE
        AppComponents.Kind.PYRIGHT -> CodeService.Install.PYTHON_HEAVY
        else -> null
    }

    /** Куда идти ставить то, что требует выбора человека. */
    fun hint(c: AppComponents.Component): String? = when (c.kind) {
        AppComponents.Kind.ANDROID_SDK -> "Поставить заново — «Linux в приложении» → «Android SDK…» (лицензия Google, сборки сообщества — ваши адрес и сумма)."
        AppComponents.Kind.IDE_PLATFORM -> "Ставится из редактора Android-проекта: «Поставить платформу Android» после лицензии."
        AppComponents.Kind.KOTLINC -> "Ставится при первом запуске Kotlin-файла в IDE («Подготовить Kotlin»)."
        AppComponents.Kind.PROJECT_ENVS -> "Окружения создаются заново при запуске проекта; убрать — освободить место, проекты не тронутся."
        AppComponents.Kind.MODEL -> "Загрузить снова — «Модели» → Hugging Face."
        else -> null
    }

    fun remove(context: Context, jobs: LongJobs, c: AppComponents.Component) {
        val label = "Убрать: ${c.title}"
        jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
            when (val r = c.remove) {
                is AppComponents.Remove.Tree -> when (val d = AppComponents.delete(rootfs(context).toPath(), r)) {
                    is Outcome.Success -> LongJobs.Done("$label: освобождено ${AlpineView.size(d.value)}")
                    is Outcome.Failure -> LongJobs.Done("$label: ${d.errors.first().message}", failed = true)
                }
                is AppComponents.Remove.Npm -> {
                    val argv = (AppComponents.npmUninstall(r.pkg) as? Outcome.Success)?.value
                        ?: return@start LongJobs.Done("$label: пакет не из списка", failed = true)
                    done(label, LinuxSetup.runSteps(context, listOf(ProcessSpec(argv))))
                }
            }
        }
    }

    fun install(context: Context, jobs: LongJobs, c: AppComponents.Component) {
        val label = "Поставить: ${c.title}"
        val ide = ideInstall(c)
        val steps = reinstall(c)
        jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
            when {
                ide != null -> when (val r = CodeService.install(context, ide)) {
                    is Outcome.Success -> LongJobs.Done("$label: готово")
                    is Outcome.Failure -> LongJobs.Done("$label: ${r.diagnostics.first().message}", failed = true)
                }
                steps != null -> done(label, steps(context))
                else -> LongJobs.Done("$label: ставится на своём экране", failed = true)
            }
        }
    }

    private fun done(label: String, r: Outcome<com.dpsmpx.dpai.execution.process.Completed>?): LongJobs.Done = when (r) {
        is Outcome.Success -> LongJobs.Done(
            if (r.value.ok) "$label: готово" else "$label: код ${r.value.exitCode} — причина в выводе ниже",
            (r.value.stdout + r.value.stderr).takeLast(MAX_OUTPUT),
            failed = !r.value.ok,
        )
        is Outcome.Failure -> LongJobs.Done("$label: ${r.errors.first().message}", failed = true)
        null -> LongJobs.Done("$label: Linux в приложении недоступен", failed = true)
    }
}

/** Строка компонента; касание раскрывает подробности и действия. */
@Composable
internal fun ComponentRow(
    c: AppComponents.Component,
    open: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onInstall: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(c.version ?: "—", style = MaterialTheme.typography.labelSmall)
            Text((if (c.sizeApprox) "≥ " else "") + AlpineView.size(c.size), style = MaterialTheme.typography.labelSmall)
        }
        if (c.outdated) Text("Эта сборка DP_IDE ставит ${c.expected}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        if (open) {
            for ((k, v) in AppComponents.details(c)) Text("$k: $v", style = MaterialTheme.typography.bodySmall)
            EnvironmentComponents.hint(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onInstall?.let { TextButton(enabled = !running, onClick = it) { Text(if (c.outdated) "Обновить" else "Поставить заново") } }
                TextButton(enabled = !running, onClick = onRemove) { Text("Убрать…") }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** Окно «Убрать компонент?». */
@Composable
internal fun RemoveComponentDialog(c: AppComponents.Component, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Убрать «${c.title}»?") },
        text = {
            Text(
                "Освободится ${if (c.sizeApprox) "не меньше " else ""}${AlpineView.size(c.size)}. " +
                    (EnvironmentComponents.hint(c) ?: "Вернуть — «Поставить заново» здесь или на его экране.") +
                    if (c.kind == AppComponents.Kind.KOTLIN_LSP || c.kind == AppComponents.Kind.PYRIGHT) " Открытые в редакторе файлы потеряют подсказки службы до установки." else "",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Убрать") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
