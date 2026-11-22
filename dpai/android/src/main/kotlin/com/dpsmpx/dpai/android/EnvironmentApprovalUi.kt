package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.ui.AndroidSdk
import com.dpsmpx.dpai.ui.AutomationPlan

/** Один экран одобрения для человека; в API-инструментах нет команды, вызывающей answer. */
@Composable
internal fun EnvironmentApprovalUi(owner: String) {
    val broker = EnvironmentApprovalBroker.shared
    val requests by broker.pending.collectAsState()
    val request = requests[owner] ?: return
    var name by remember(request.id) { mutableStateOf("") }
    var license by remember(request.id) { mutableStateOf(false) }
    var details by remember(request.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val dangerous = request.plan.level == ActionLevel.DANGEROUS
    val project = request.plan.scope == AutomationPlan.Scope.PROJECT
    AlertDialog(
        onDismissRequest = { broker.answer(owner, request.id, false) },
        containerColor = if (dangerous) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(if (dangerous) R.string.env_approve_danger_title else if (project) R.string.automation_plan_project else R.string.env_approve_title)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(request.plan.title, style = MaterialTheme.typography.titleSmall)
            Text(request.targetLabel)
            Text(request.root, fontFamily = FontFamily.Monospace)
            Text(stringResource(if (project) R.string.automation_project_scope else R.string.env_approve_scope))
            if (dangerous) Text(stringResource(R.string.env_approve_danger), color = MaterialTheme.colorScheme.error)
            if (project) Text(stringResource(R.string.automation_project_files))
            else Text(stringResource(if (request.plan.snapshot) R.string.env_approve_snapshot else R.string.env_approve_no_snapshot))
            request.plan.effect?.let { effect -> Text(stringResource(when (effect) {
                AutomationPlan.Effect.DEPENDENCY_REPORT -> R.string.dependencies_explanation
                AutomationPlan.Effect.GRADLE_PIN -> R.string.dependencies_gradle_notice
                AutomationPlan.Effect.PYTHON_PIN -> R.string.dependencies_pip_notice
                AutomationPlan.Effect.CONAN_PIN -> R.string.dependencies_conan_notice
                AutomationPlan.Effect.PYTHON_RESTORE -> R.string.dependencies_restore_notice
            })) }
            if (dangerous || details) for (step in request.plan.profile.steps) {
                Text(step.name, style = MaterialTheme.typography.labelMedium)
                Text(CanonicalJson.write(JsonArray(step.command.map(::JsonString))), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else for (step in request.plan.profile.steps) Text(step.name)
            if (!dangerous) TextButton(onClick = { details = !details }) { Text(stringResource(R.string.env_approve_commands)) }
            if (request.plan.sdkLicense) {
                Row {
                    Checkbox(checked = license, onCheckedChange = { license = it })
                    Text(stringResource(R.string.env_approve_license))
                }
                TextButton(onClick = { uri.openUri(AndroidSdk.LICENSE_URL) }) { Text(stringResource(R.string.env_approve_terms)) }
            }
            if (dangerous) OutlinedTextField(name, { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.env_approve_type_name, request.projectName)) })
        } },
        confirmButton = { TextButton(enabled = (!dangerous || name == request.projectName) && (!request.plan.sdkLicense || license),
            onClick = { broker.answer(owner, request.id, true, name, license) }) { Text(stringResource(R.string.env_approve_execute)) } },
        dismissButton = { TextButton(onClick = { broker.answer(owner, request.id, false) }) { Text(stringResource(R.string.env_approve_decline)) } },
    )
}
