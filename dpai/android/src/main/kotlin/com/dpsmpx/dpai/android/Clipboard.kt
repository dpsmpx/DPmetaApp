package com.dpsmpx.dpai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Буфер обмена приложения. Секрет — команда восстановления с одноразовым
 * кодом и ключом (D-54) — копируется с пометкой «чувствительное»: Android 13+
 * не показывает его в превью буфера и подсказках клавиатуры (аудит
 * безопасности 1 ноября 2026, A-9). Срок кода — по D-54, а не здесь.
 */
object Clipboard {
    /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33) — строкой: так же его понимает и Android 12 и ниже. */
    private const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE, true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
