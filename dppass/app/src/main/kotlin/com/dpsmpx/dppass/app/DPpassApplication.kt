package com.dpsmpx.dppass.app

import android.app.Application

/**
 * Процесс приложения. Здесь — только то, что должно начаться раньше
 * всего: журнал устройства и запись о падении (D-26) и задача
 * автообновления подписок (D-39): она не переживает перезагрузку и
 * ставится заново при каждом запуске процесса.
 */
class DPpassApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceLog.init(this)
        SubscriptionJobService.schedule(this)
    }
}
