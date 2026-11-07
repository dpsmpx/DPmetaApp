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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpmeta.app.ui.PultTheme

/**
 * Экран входа (D-59): непрозрачный, поверх любого окна приложения; спрашивает
 * PIN устройства при каждом показе, пока окно PIN не открыто. Вход —
 * [AppLock.unlocked] закрывает все экраны входа. Окно PIN закрыто без входа —
 * причина и «Войти». Отказ — «Закрыть» или «назад» на этом экране:
 * [AppLock.denied] закрывает все окна приложения; служба контроллера «AI» и
 * уведомления работают дальше. Нет блокировки экрана у телефона — входа нет:
 * экран просит задать PIN; вернулись из настроек — окно PIN сразу.
 */
class LockActivity : ComponentActivity() {
    private var asking: CancellationSignal? = null
    private val noPin = mutableStateOf(false)

    /** Окно PIN закрыто без входа (кем угодно: человек, система, блокировка попыток) — причина и «Войти», «Закрыть». */
    private val failed = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = AppLock.denied()
        })
        // От края до края: значки строки состояния — по теме экрана, а не по теме окна (UX-5).
        enableEdgeToEdge()
        setContent {
            PultTheme {
                // Фон и цвет текста — из темы: без Surface текст был чёрным на тёмном окне (UX-5).
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (noPin.value) {
                            Text("Вход в DPmetaApp — по PIN устройства, а у телефона нет блокировки экрана: задайте PIN.")
                            Button(onClick = ::openSecuritySettings) { Text("Задать PIN") }
                            Button(onClick = AppLock::denied) { Text("Закрыть") }
                        } else {
                            failed.value?.let { why ->
                                Text("Вход не выполнен: $why")
                                Button(onClick = ::ask) { Text("Войти") }
                                Button(onClick = AppLock::denied) { Text("Закрыть") }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        noPin.value = getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true
        // При каждом показе — окно PIN снова (вернулись «Домой» → назад, погас экран); уже открыто — не второе.
        if (!noPin.value) ask()
    }

    override fun onDestroy() {
        asking?.cancel()
        asking = null
        super.onDestroy()
    }

    private fun ask() {
        if (asking != null) return
        failed.value = null
        val signal = CancellationSignal()
        asking = signal
        Lock.ask(this, "Вход в приложение", pinOnly = true, signal = signal, onOpen = {
            asking = null
            AppLock.unlocked()
        }, onDenied = { code, why ->
            asking = null
            // Экран входа закрыт или заменён новым — его окно PIN уже не в счёт. Иначе — не отказ: код
            // «отменил человек» система шлёт и на «Домой», и на погасший экран (SystemUI). Отказ — только
            // «Закрыть» или «назад» на этом экране; здесь — причина, кнопка «Войти» и окно PIN при показе.
            if (!signal.isCanceled && !isFinishing && !isDestroyed) failed.value = why.ifBlank { "код $code" }
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
