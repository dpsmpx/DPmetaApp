package com.dpsmpx.dpai.core.clock

import java.time.ZoneId

/**
 * Часовой пояс показа времени — один на приложение (`DPmetaApp:ТЗ, §6.3`,
 * N-16; аудит UI/UX, UX-22): хранится время в UTC (D-07), показывается — в
 * этом поясе. В DPmetaApp его задаёт пульт (по умолчанию +03:00) при запуске
 * процесса и в «Ещё»; отдельная сборка «AI» и `cli` — пояс телефона или
 * компьютера. Экраны читают [current] при построении: новый пояс виден со
 * следующего снимка.
 */
object DisplayZone {
    @Volatile
    var current: ZoneId = ZoneId.systemDefault()
}
