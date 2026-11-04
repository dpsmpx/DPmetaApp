package com.dpsmpx.dpmeta.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpmeta.app.ui.PultTheme

/**
 * Экран входа (D-59): непрозрачный, поверх любого окна приложения; спрашивает
 * PIN устройства. Вход — закрывается, под ним — прерванное окно. Отказ или
 * «назад» — закрываются все окна приложения; служба контроллера «AI» и
 * уведомления работают дальше. Нет блокировки экрана у телефона — входа нет:
 * экран просит задать PIN в настройках Android.
 */
class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        if (getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            setContent {
                PultTheme {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Вход в DPmetaApp — по PIN устройства (D-59), а у телефона нет блокировки экрана.")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) { Text("Задать PIN") }
                        Button(onClick = ::close) { Text("Закрыть") }
                    }
                }
            }
            return
        }
        setContent { PultTheme { Column(Modifier.fillMaxSize()) {} } }
        if (savedInstanceState == null) ask()
    }

    override fun onRestart() {
        super.onRestart()
        // Вернулись из настроек Android: PIN задан — спросить.
        if (getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true) recreate()
    }

    private fun ask() = Lock.ask(this, "Вход в приложение", pinOnly = true, onOpen = {
        AppLock.unlocked()
        finish()
    }, onDenied = { close() })

    private fun close() {
        AppLock.denied()
        finishAffinity()
    }
}
