package com.dpsmpx.dpmeta.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.dpsmpx.dpai.core.clock.Clock

/**
 * Вход на всё приложение (D-59): ставит его приложение `:shell` в своём
 * `Application`. Любое окно — пульт, «AI», в том числе из уведомлений и
 * «недавних» — до входа закрыто экраном [LockActivity]; когда входить,
 * решает [LockPolicy]. Службы и уведомления блокировка не трогает: она
 * смотрит только на окна.
 */
object AppLock {
    private var policy: LockPolicy? = null
    private var visible = 0
    private var prompting = false

    /** Блокировка всего приложения включена — у окон своей нет. */
    val installed: Boolean get() = policy != null

    fun install(app: Application, clock: Clock = Clock.SYSTEM) {
        if (policy != null) return
        policy = LockPolicy(clock)
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    internal fun unlocked() {
        policy?.unlocked()
        prompting = false
    }

    internal fun denied() {
        prompting = false
    }

    /** Окно закрыть экраном входа — ещё до первого кадра ([Activity.onCreate]) или при возвращении из фона. */
    private fun guard(a: Activity) {
        val p = policy ?: return
        if (a is LockActivity || !p.locked || prompting) return
        prompting = true
        a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard(activity)

        override fun onActivityStarted(activity: Activity) {
            if (visible++ == 0) policy?.foreground()
        }

        override fun onActivityResumed(activity: Activity) = guard(activity)

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            // Поворот — не уход в фон: окно пересоздаётся сразу.
            if (--visible == 0 && !activity.isChangingConfigurations) policy?.background()
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
