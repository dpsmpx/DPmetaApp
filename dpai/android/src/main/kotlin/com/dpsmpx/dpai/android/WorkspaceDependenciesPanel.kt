package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.execution.project.DependencyTools
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ManualStep
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.WorkspaceLocation

/** Явные операции менеджера используют общую долговечную паузу ручного профиля. */
@Composable
internal fun WorkspaceDependenciesPanel(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?) {
    val context = LocalContext.current
    var managerName by rememberSaveable(view.project.project.value) { mutableStateOf(DependencyTools.Manager.GRADLE.name) }
    val manager = DependencyTools.Manager.valueOf(managerName)
    var python by rememberSaveable(view.project.project.value, view.target?.id?.value) {
        mutableStateOf(if (view.target?.platform == TargetPlatform.WINDOWS) "python" else "python3")
    }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var run by androidx.compose.runtime.remember { mutableStateOf(model.manualProfileView()) }
    Poll(model, periodMs = 1500) { run = model.manualProfileView() }
    val available = location != null && view.ready && !view.paused && !view.pendingEdit && !run.blocked && python.isNotBlank()
    fun start(mode: DependencyTools.Mode) {
        val where = location ?: return
        val name = context.getString(when (mode) {
            DependencyTools.Mode.REPORT -> R.string.dependencies_analyze
            DependencyTools.Mode.PIN -> R.string.dependencies_pin
            DependencyTools.Mode.RESTORE -> R.string.dependencies_restore
        })
        val profile = ManualProfile(ManualProfileKind.DEPENDENCIES,
            listOf(ManualStep(name, DependencyTools.command(manager, mode, python.trim()), 3600)))
        note = context.getString(if (profile.valid && model.startManualProfile(view.project, where, profile)) R.string.dependencies_started else R.string.dependencies_busy)
        if (profile.valid) ControllerService.resume(context)
    }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.dependencies_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.dependencies_explanation), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (m in DependencyTools.Manager.entries) FilterChip(selected = m == manager, onClick = { managerName = m.name },
                label = { Text(when (m) { DependencyTools.Manager.GRADLE -> "Gradle"; DependencyTools.Manager.PYTHON -> "Python / pip"; DependencyTools.Manager.CONAN -> "C++ / Conan 2" }) })
        }
        OutlinedTextField(python, { python = it.take(1000).filterNot(Char::isISOControl) }, label = { Text(stringResource(R.string.dependencies_python)) },
            supportingText = { Text(stringResource(R.string.dependencies_python_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(when (manager) {
            DependencyTools.Manager.GRADLE -> R.string.dependencies_gradle
            DependencyTools.Manager.PYTHON -> R.string.dependencies_pip
            DependencyTools.Manager.CONAN -> R.string.dependencies_conan
        }), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = available, onClick = { start(DependencyTools.Mode.REPORT) }) { Text(stringResource(R.string.dependencies_analyze)) }
        TextButton(enabled = available, onClick = { pending = DependencyTools.Mode.PIN.name }) { Text(stringResource(R.string.dependencies_pin)) }
        if (manager == DependencyTools.Manager.PYTHON) TextButton(enabled = available, onClick = { pending = DependencyTools.Mode.RESTORE.name }) { Text(stringResource(R.string.dependencies_restore)) }
        note?.let { Text(it) }
        if (run.active?.profile?.kind == ManualProfileKind.DEPENDENCIES || run.active == null && run.note.startsWith("DEPENDENCIES:")) {
            Text(run.note)
            Clipboard.Sensitive { SelectionContainer { Text(run.output, style = MaterialTheme.typography.bodySmall) } }
            if (run.blocked) TextButton(onClick = { model.stopManualProfile(); ControllerService.resume(context) }) { Text(stringResource(R.string.dependencies_stop)) }
        }
        ManualHistorySection(model, view.project, ManualProfileKind.DEPENDENCIES, location, run.finished) { _, _ -> }
    }
    pending?.let { name ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.dependencies_confirm)) },
            text = { Text(stringResource(if (name == DependencyTools.Mode.RESTORE.name) R.string.dependencies_restore_notice else when (manager) {
                DependencyTools.Manager.GRADLE -> R.string.dependencies_gradle_notice
                DependencyTools.Manager.PYTHON -> R.string.dependencies_pip_notice
                DependencyTools.Manager.CONAN -> R.string.dependencies_conan_notice
            })) },
            confirmButton = { TextButton(enabled = available, onClick = { pending = null; start(DependencyTools.Mode.valueOf(name)) }) { Text(stringResource(R.string.dependencies_continue)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.dependencies_cancel)) } })
    }
}
