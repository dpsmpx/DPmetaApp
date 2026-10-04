package com.dpsmpx.dppass.app

import android.app.Application

/**
 * Процесс приложения. Здесь — только то, что должно начаться раньше
 * всего: журнал устройства и запись о падении (D-26).
 */
class DPpassApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceLog.init(this)
    }
}
