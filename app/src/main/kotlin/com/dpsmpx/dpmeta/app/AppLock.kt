package com.dpsmpx.dpmeta.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock

/**
 * Вход на всё приложение (D-59): ставит его приложение `:shell` в своём
 * `Application`. Пока [LockPolicy] закрыта, любое окно — пульт, «AI», в том
 * числе из уведомлений (в своей задаче) и «недавних» — невидимо и при каждом
 * показе закрыто экраном [LockActivity]: залипающего «вход уже спрошен» нет.
 * Отказ закрывает все окна приложения во всех задачах. Службы и уведомления
 * блокировка не трогает: она смотрит только на окна.
 */
object AppLock {
    private var policy: LockPolicy? = null
    private var visible = 0
    private val windows = LinkedHashSet<Activity>()

    /** Блокировка всего приложения включена — у окон своей нет. */
    val installed: Boolean get() = policy != null

    /**
     * Нужен ли вход сейчас — для окна запроса «AI» поверх приложений (D-61):
     * считает и фон, который длится. Блокировка не включена — нет. Только из
     * главного потока, как и всё здесь.
     */
    val loginNeeded: Boolean get() = policy?.locked == true

    fun install(app: Application, elapsedMs: () -> Long = SystemClock::elapsedRealtime) {
        if (policy != null) return
        policy = LockPolicy(elapsedMs)
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Вход удался: экраны входа закрываются, под ними — прерванные окна. */
    internal fun unlocked() {
        policy?.unlocked()
        windows.filterIsInstance<LockActivity>().forEach { it.finish() }
    }

    /** Отказ от входа: закрыть все окна приложения во всех задачах; службы работают. */
    internal fun denied() {
        windows.toList().forEach { it.finishAndRemoveTask() }
    }

    private fun locked(a: Activity): Boolean = a !is LockActivity && policy?.locked == true

    private fun hide(a: Activity, hidden: Boolean) {
        a.window?.let { window ->
            window.decorView.alpha = if (hidden) 0f else 1f
            window.decorView.importantForAccessibility = if (hidden) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (hidden) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            windows += activity
            // «Недавние» без снимка окна (API 33+): до входа там нечего видеть, а снимки экрана — можно.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity !is LockActivity) activity.setRecentsScreenshotEnabled(false)
        }

        override fun onActivityStarted(activity: Activity) {
            if (visible++ == 0) policy?.foreground()
            // До первого кадра: закрытое окно невидимо, пока не будет входа.
            if (locked(activity)) hide(activity, true)
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity is LockActivity) return
            if (locked(activity)) {
                hide(activity, true)
                // Прежний экран входа (под этим окном или в другой задаче) — закрыть: окно PIN одно.
                windows.filterIsInstance<LockActivity>().forEach { it.finish() }
                activity.startActivity(Intent(activity, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } else {
                hide(activity, false)
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            // Поворот — не уход в фон: окно пересоздаётся сразу.
            if (--visible == 0 && !activity.isChangingConfigurations) policy?.background()
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            windows -= activity
        }
    }
}
