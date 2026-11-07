package com.dpsmpx.dpai.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.concurrent.thread

/**
 * Фон «AI», когда приложение открывается не её вкладкой (аудит UI/UX
 * 5 ноября 2026, UX-4): в DPmetaApp первым показывается пульт
 * (`DPmetaApp:D-53`), а служба контроллера и разрешение на уведомления
 * поднимались только экраном «AI». После перезагрузки или обновления APK
 * задачи не шли и запросы не приходили, пока не коснуться вкладки, хотя
 * фон должен работать (`DPmetaApp:D-59`).
 *
 * Оболочка приложения подключает сюда своё первое окно; пульт об «AI» не
 * знает (D-55, `DPmetaApp:D-53`).
 */
object AiBackground {
    /**
     * При создании окна — разрешение на уведомления (Android 13+): без него
     * запросы и итоги задач не показываются. При каждом показе окна —
     * служба контроллера, если контур настоящий ([ensureService]).
     */
    fun attach(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Отказ — не ошибка: сводка и запросы видны в приложении (как у экрана «AI»).
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = activity.applicationContext
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ensureService(app) },
        )
    }

    /**
     * Служба контроллера — то же правило, что у экрана «AI» (D-36, D-39): есть
     * target с каталогом слота и человек не останавливал службу кнопкой —
     * поднять. Чтение targets — не в главном потоке; из фона Android 12+
     * службу не пустит — это пропуск, а не падение ([ControllerService.start]).
     */
    fun ensureService(context: Context) {
        if (ControllerService.running) return
        thread(name = "dpai-background", isDaemon = true) {
            val live = runCatching { Controller.get(context.filesDir).targets().any { it.configDir != null } }.getOrDefault(false)
            if (live && !ControllerService.running) ControllerService.start(context)
        }
    }
}
