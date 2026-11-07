package com.dpsmpx.dpai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard

/**
 * Буфер обмена приложения. Секрет — команда восстановления с одноразовым
 * кодом и ключом (D-54) — копируется с пометкой «чувствительное»: Android 13+
 * не показывает его в превью буфера и подсказках клавиатуры (аудит
 * безопасности 1 ноября 2026, A-9). Срок кода — по D-54, а не здесь.
 * Все записи в буфер приложения идут здесь, без второго пути (аудит UI/UX
 * 5 ноября 2026, терминал:A-11).
 */
object Clipboard {
    /**
     * Android 13+ сам подтверждает копирование своим окном: своё
     * «Скопировано» — только на Android 12 и ниже, иначе их два (терминал:A-24).
     */
    val systemConfirms: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33) — строкой: так же его понимает и Android 12 и ниже. */
    private const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) mark(clip)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun mark(clip: ClipData) {
        clip.description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE, true) }
    }

    /**
     * Выделение текста Compose («Копировать» долгим нажатием) внутри [content]
     * — тоже с пометкой «чувствительное»: иначе выделение было бы вторым путём
     * в буфер мимо [copy] (ревью PR #170).
     */
    @Composable
    fun Sensitive(content: @Composable () -> Unit) {
        val inner = LocalClipboard.current
        val marked = remember(inner) {
            object : androidx.compose.ui.platform.Clipboard by inner {
                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    clipEntry?.clipData?.let(::mark)
                    inner.setClipEntry(clipEntry)
                }
            }
        }
        CompositionLocalProvider(LocalClipboard provides marked, content = content)
    }
}
