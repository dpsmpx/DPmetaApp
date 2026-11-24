package com.dpsmpx.dpai.android

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Диагностика: выделение и явное копирование доступного текста целиком.
 * [copyText] позволяет показать короткий хвост, сохранив копирование всего результата.
 * Принимает уже подготовленный для экрана текст: существующее скрытие секретов
 * не отменяется, скрытые значения не запрашиваются. Оба пути идут через Clipboard
 * с пометкой «чувствительное» (D-14, DPmetaApp:D-151).
 */
@Composable
internal fun DiagnosticText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    copyText: String = text,
    @StringRes copyLabel: Int = R.string.diagnostic_copy,
) {
    if (text.isEmpty() && copyText.isEmpty()) return
    Column(modifier) {
        Clipboard.Sensitive {
            SelectionContainer {
                Text(text, style = style, color = color, fontFamily = fontFamily, maxLines = maxLines)
            }
        }
        CopyDiagnostic(copyText, copyLabel = copyLabel)
    }
}

/** Копирование одного снимка доступного вывода; не меняет исходную ошибку/результат. */
@Composable
internal fun CopyDiagnostic(text: String, modifier: Modifier = Modifier, @StringRes copyLabel: Int = R.string.diagnostic_copy) {
    val context = LocalContext.current
    val label = stringResource(R.string.diagnostic_clip_label)
    val copied = stringResource(R.string.diagnostic_copied)
    val failed = stringResource(R.string.diagnostic_copy_failed)
    TextButton(modifier = modifier, enabled = text.isNotEmpty(), onClick = {
        val ok = try {
            Clipboard.copy(context, label, text, sensitive = true)
            true
        } catch (_: RuntimeException) {
            false
        }
        if (!ok || !Clipboard.systemConfirms) Toast.makeText(context, if (ok) copied else failed, Toast.LENGTH_SHORT).show()
    }) { Text(stringResource(copyLabel)) }
}
