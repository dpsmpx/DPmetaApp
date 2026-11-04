package com.dpsmpx.dpmeta.app

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpmeta.app.ui.PultTheme

/**
 * Экран входа (D-59): непрозрачный, поверх любого окна приложения; спрашивает
 * PIN устройства при каждом показе, пока окно PIN не открыто. Вход —
 * [AppLock.unlocked] закрывает все экраны входа. Отказ или «назад» —
 * [AppLock.denied] закрывает все окна приложения; служба контроллера «AI» и
 * уведомления работают дальше. Нет блокировки экрана у телефона — входа нет:
 * экран просит задать PIN; вернулись из настроек — окно PIN сразу.
 */
class LockActivity : ComponentActivity() {
    private var asking: CancellationSignal? = null
    private val noPin = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = AppLock.denied()
        })
        setContent {
            PultTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (noPin.value) {
                        Text("Вход в DPmetaApp — по PIN устройства (D-59), а у телефона нет блокировки экрана.")
                        Button(onClick = ::openSecuritySettings) { Text("Задать PIN") }
                        Button(onClick = AppLock::denied) { Text("Закрыть") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        noPin.value = getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true
        if (!noPin.value && asking == null) ask()
    }

    override fun onDestroy() {
        asking?.cancel()
        asking = null
        super.onDestroy()
    }

    private fun ask() {
        val signal = CancellationSignal()
        asking = signal
        Lock.ask(this, "Вход в приложение", pinOnly = true, signal = signal, onOpen = {
            asking = null
            AppLock.unlocked()
        }, onDenied = {
            asking = null
            // Окно закрыто системой (поворот, смена окна) — не отказ: спросить снова при показе.
            if (!isFinishing && !isDestroyed && !signal.isCanceled) AppLock.denied()
        })
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
