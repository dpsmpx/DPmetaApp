package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.ui.CodeEditorBuffer

/** Сохранённый флаг Activity не даёт права вводить в временный буфер просмотра (D-138). */
internal data class EditorDocumentBinding(val buffer: CodeEditorBuffer, val editing: Boolean)
internal fun editorDocumentBinding(draft: Drafts.FileDraft?, preview: CodeEditorBuffer?, requestedEditing: Boolean): EditorDocumentBinding? {
    val buffer = draft?.editor ?: preview ?: return null
    return EditorDocumentBinding(buffer, requestedEditing && draft != null)
}
