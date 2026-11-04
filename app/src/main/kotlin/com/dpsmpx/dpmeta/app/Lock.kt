package com.dpsmpx.dpmeta.app

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Окно входа (F-45; ТЗ, §6.6) — системный `BiometricPrompt` (API 30+, без
 * сторонних библиотек). Вход в приложение — только PIN устройства (учётные
 * данные экрана блокировки, D-59); секрет под ключом Keystore открывает и
 * сильная биометрия. Не вошли — [onDenied] с кодом и причиной. Код отказа
 * человека и закрытия окна системой («Домой», погас экран) один —
 * `BIOMETRIC_ERROR_USER_CANCELED`, поэтому отказом его не считают.
 */
object Lock {
    fun ask(activity: Activity, subtitle: String, pinOnly: Boolean, signal: CancellationSignal = CancellationSignal(), onOpen: () -> Unit, onDenied: (Int, String) -> Unit) {
        val allowed = if (pinOnly) Authenticators.DEVICE_CREDENTIAL else Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("DPmetaApp")
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(
            signal,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOpen()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDenied(errorCode, errString.toString())
            },
        )
    }
}
