package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.providers.ChatMessage
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.ui.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Продолжить CLI-чат на другом официальном CLI или вручную перенести переписку в API (D-219). */
@Composable
internal fun CliExecutorPicker(screen: SessionScreen, model: DemoModel, controller: Controller,
                               onApiChat: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready by ModelsStore.ready.collectAsState()
    val profiles by ModelsStore.profiles.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ModelsStore.init(context) } }
    val enabled = controller.targets().flatMap { model.slotsOf(it.id) }.filter { !it.disabled }.map { it.executor }.toSet()
    val messages = screen.chat.filter { !it.event && !it.thinking && it.text.isNotBlank() }
        .map { ChatMessage(if (it.mine) ChatRole.USER else ChatRole.ASSISTANT, it.text) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Исполнитель и контекст") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CLI-исполнители продолжают эту задачу через штатный handoff. При выборе API будет создан отдельный разговор с историей, которую следующий провайдер получит только после вашего сообщения. Ключи и разрешения не передаются.", style = MaterialTheme.typography.bodySmall)
                for (executor in Executor.entries) {
                    TextButton(enabled = !busy && screen.task != null && executor in enabled, onClick = {
                        val task = screen.task ?: return@TextButton
                        busy = true
                        scope.launch {
                            val key = IdGenerator(Clock.SYSTEM, RandomSource.secure()).next(IdKinds.OPERATION).value
                            val result = withContext(Dispatchers.IO) { model.command(Command.SetExecutor(task, executor, key)) }
                            busy = false
                            when (result) {
                                is Outcome.Failure -> note = result.diagnostics.first().message
                                is Outcome.Success -> note = "Следующий ход — ${executor.title}; контекст задачи сохранён в handoff."
                            }
                        }
                    }) { Text("CLI · ${executor.title}" + (if (screen.executor == executor) " ✓" else "") + (if (executor !in enabled) " · нет слота" else "")) }
                }
                Text("API-профили", style = MaterialTheme.typography.titleSmall)
                if (!ready) Text("Загрузка…", style = MaterialTheme.typography.labelSmall)
                for (profile in profiles) {
                    TextButton(enabled = !busy && messages.isNotEmpty(), onClick = {
                        busy = true
                        scope.launch {
                            val project = controller.project()?.project
                            val chat = withContext(Dispatchers.IO) {
                                ModelsStore.importCliChat(profile.id, project, screen.title ?: "Чат из CLI", messages, screen.executor.title)
                            }
                            busy = false
                            if (chat == null) note = "Не удалось сохранить переданный контекст"
                            else onApiChat(chat.id.value)
                        }
                    }) { Text("API · ${profile.name}") }
                }
                note?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Закрыть") } },
    )
}
