package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.LanguageSession
import com.dpsmpx.dpai.lsp.LineIndex
import com.dpsmpx.dpai.lsp.WorkspaceEdit
import com.dpsmpx.dpai.ui.WorkspaceEditPlan
import com.dpsmpx.dpai.ui.WorkspaceEdits
import com.dpsmpx.dpai.ui.WorkspaceSource
import com.dpsmpx.dpai.ui.WorkspaceRename

internal class ProjectEditPlace(val tree: FileTree, val root: String, val dirty: () -> Boolean,
    val apply: (WorkspaceEditPlan) -> Outcome<Unit>, val changed: () -> Unit) {
    private fun text(path: String): Outcome<String> = when (val result = tree.read(path, TextFile.MAX_EDIT_BYTES)) {
        is Outcome.Failure -> result
        is Outcome.Success -> TextFile.decode(result.value)
    }

    fun prepareRename(client: LanguageSession, requireProjectIndex: Boolean = false, request: () -> Outcome<WorkspaceEdit>): Outcome<WorkspaceEditPlan> {
        if (dirty()) return Outcome.failure(DiagCode.STATE_CONFLICT, "сначала сохраните или отмените правки редактора в этом дереве")
        val result = WorkspaceRename.prepare(client, root, ::text, requireProjectIndex, request)
        return if (dirty()) Outcome.failure(DiagCode.STATE_CONFLICT, "правки редактора изменились; сохраните их и повторите") else result
    }

    fun prepare(edit: WorkspaceEdit, client: LanguageSession): Outcome<WorkspaceEditPlan> {
        if (dirty()) return Outcome.failure(DiagCode.STATE_CONFLICT, "сначала сохраните или отмените правки редактора в этом дереве")
        return WorkspaceEdits.prepare(edit, root) load@{ path ->
            val text = when (val result = text(path)) {
                is Outcome.Failure -> return@load result
                is Outcome.Success -> result.value
            }
            val document = client.main.document(DocumentUri.ofPath("${root.trimEnd('/')}/$path"))
            if (document != null && document.second != text) Outcome.failure(DiagCode.STATE_CONFLICT, "текст службы отличается от файла; сохраните или переоткройте файл", path)
            else Outcome.success(WorkspaceSource(text, document?.first))
        }
    }
}

internal val LocalProjectEdits = staticCompositionLocalOf<ProjectEditPlace?> { null }

/** Весь набор, включая все замены; исходники ещё не записаны. */
@Composable
internal fun ProjectEditPreview(plan: WorkspaceEditPlan, busy: Boolean, note: String?, onApply: () -> Unit, onClose: () -> Unit) {
    val indexes = remember(plan) { plan.files.associate { it.path to LineIndex(it.before) } }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.project_edit_title, plan.files.size, plan.editCount)) },
        text = { Column {
            Text(stringResource(R.string.project_edit_copy), style = MaterialTheme.typography.bodySmall)
            note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn {
                for (file in plan.files) {
                    item(key = "file:${file.path}") { Text(file.path, style = MaterialTheme.typography.titleSmall) }
                    items(file.edits) { edit ->
                        val index = indexes.getValue(file.path)
                        val start = index.offset(edit.range.start)
                        val end = index.offset(edit.range.end)
                        SelectionContainer { Text("${edit.range.start.line + 1}:${edit.range.start.character + 1}\n− ${if (start != null && end != null) file.before.substring(start, end) else "?"}\n+ ${edit.newText}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = onApply) { Text(stringResource(R.string.project_edit_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(android.R.string.cancel)) } })
}
