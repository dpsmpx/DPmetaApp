package com.dpsmpx.dpmeta.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.dpsmpx.dpmeta.app.R

/** Адаптер пульта: общий контракт копируемой диагностики без зависимости от dpai/android (D-194). */
@Composable
internal fun DiagnosticBlock(text: String, color: Color = Color.Unspecified) {
    if (text.isEmpty()) return
    val context = LocalContext.current
    val copy = stringResource(R.string.ui_copy)
    val copied = stringResource(R.string.ui_copied)
    val failed = stringResource(R.string.ui_copy_failed)
    val clipboard = LocalClipboard.current
    val sensitive = remember(clipboard) { object : androidx.compose.ui.platform.Clipboard by clipboard {
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            clipEntry?.clipData?.description?.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setClipEntry(clipEntry)
        }
    } }
    Column {
        CompositionLocalProvider(LocalClipboard provides sensitive) {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = color) }
        }
        TextButton(onClick = {
            val clip = ClipData.newPlainText(copy, text)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            val ok = try {
                val manager = context.getSystemService(ClipboardManager::class.java)
                if (manager == null) false else { manager.setPrimaryClip(clip); true }
            } catch (_: RuntimeException) { false }
            if (!ok || android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, if (ok) copied else failed, android.widget.Toast.LENGTH_SHORT).show()
        }) { Text(copy) }
    }
}
