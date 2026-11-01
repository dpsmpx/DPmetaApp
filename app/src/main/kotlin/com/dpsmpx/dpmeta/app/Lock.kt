package com.dpsmpx.dpmeta.app

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Блокировка пульта (F-45; ТЗ, §6.6): при входе — биометрия или PIN
 * устройства. Системный `BiometricPrompt` (API 30+, без сторонних библиотек):
 * сильная биометрия или учётные данные устройства. Отказ — пульт не
 * показывается.
 */
object Lock {
    fun ask(activity: Activity, onOpen: () -> Unit, onDenied: (String) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("DPmetaApp")
            .setSubtitle("Вход в пульт")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOpen()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDenied(errString.toString())
            },
        )
    }
}
