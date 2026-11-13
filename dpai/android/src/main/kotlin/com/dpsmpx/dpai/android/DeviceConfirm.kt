package com.dpsmpx.dpai.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Подтверждение действия уровня 2 (`DPmetaApp:D-07`, `DPmetaApp:D-80`): системный `BiometricPrompt`
 * (API 30+, без сторонних библиотек) — PIN устройства или сильная биометрия. Отказ и закрытие
 * окна — [onDenied] с причиной: действие не выполняется.
 */
internal object DeviceConfirm {
    fun ask(context: Context, subtitle: String, onOk: () -> Unit, onDenied: (String) -> Unit) {
        val activity = context.activity() ?: return onDenied("нет окна для подтверждения")
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Подтвердите действие")
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDenied("не подтверждено: $errString")
            },
        )
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
