package com.dpsmpx.dpai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.orchestrator.log.TickMeter
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Контроллер в фоне (D-36): пока в приложении есть target с каталогом
 * Claude, такт контура идёт в службе переднего плана — с постоянным
 * уведомлением «контроллер работает», — и уведомления о задачах приходят
 * при закрытом экране. Экран в это время только рисует снимок. Тип службы
 * — `specialUse` (Android 14+): ни один стандартный тип не описывает
 * «опрос агентов по SSH»; приложение не из каталога, отдельного
 * обоснования ему не нужно.
 *
 * Процессор держит частичный wakelock (D-39): без него при выключенном
 * экране устройство засыпает, такт встаёт, и уведомления приходят только
 * после включения экрана. Wakelock продлевается каждым тактом на
 * [WAKE_MINUTES]: если такты остановились, он истекает сам.
 *
 * «Остановить» в уведомлении останавливает контроллер до явного запуска
 * на экране ([resume]): отметка — файл [STOPPED] в каталоге приложения,
 * экран без неё службу не поднимает.
 */
class ControllerService : Service() {
    private var timer: ScheduledExecutorService? = null

    /**
     * Такт идёт: [keeper] продлевает wakelock раз в минуту (аудит стабильности
     * 2 ноября 2026, A-1). Шаг проверки внутри такта идёт до 30 мин, а
     * wakelock, взятый в начале такта на 10 мин, истекал посреди него — при
     * выключенном экране процессор засыпал, и опрос агента вставал.
     */
    @Volatile
    private var ticking = false
    private var keeper: ScheduledExecutorService? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            runCatching { NioVfs(filesDir.toPath()).write(STOPPED, "остановлен из уведомления\n".toByteArray()) }
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Контроллер работает", NotificationManager.IMPORTANCE_MIN))
        val open = Notifier.openApp(this)
        // «Остановить» — через подтверждение в приложении (аудит удобства, A-18): одно касание в шторке
        // останавливало такты, уведомления и запросы до ручного запуска.
        val stop = PendingIntent.getActivity(
            this, 1,
            MainActivity.internal(this).setAction(MainActivity.STOP_CONFIRM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DP AI: контроллер работает")
            .setContentText("опрос targets и задач")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .setOngoing(true)
            .build()
        // Перезапуск по START_STICKY после смерти процесса идёт из фона: система может
        // отказать службе переднего плана — тогда служба уходит, а не падает по кругу.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (timer == null) {
            val app = applicationContext
            val model = DemoModel.get(app)
            val notifier = Notifier(app)
            val log = AppLogs.get(app)
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dpai:controller")
                .apply { setReferenceCounted(false) }
            wake = lock
            running = true
            log.step("служба контроллера запущена")
            // Облачная копия, применённая при запуске (D-107), — в журнал: человек видит, что данные заменены.
            when (val restored = Controller.get(app.filesDir).cloudRestored) {
                is com.dpsmpx.dpai.core.result.Outcome.Success -> if (restored.value) log.info("облачная копия", "копия применена при запуске; прежние данные — *.replaced-…")
                is com.dpsmpx.dpai.core.result.Outcome.Failure -> log.error("облачная копия", "копия не применена: ${restored.errors.firstOrNull()?.message}")
            }
            // Канал dpai (D-47) — пока работает служба контроллера.
            ControlServer.start(app)
            val meter = TickMeter(Clock.SYSTEM)
            val device = AppLogs.device(app) { wake?.isHeld }
            // Первая сводка и проверка sshd — в первом такте (поток службы: сеть в главном потоке запрещена).
            var lastSummary = Clock.SYSTEM.monotonicNanos() - TimeUnit.MINUTES.toNanos(1)
            var lastUsage = Clock.SYSTEM.monotonicNanos() - TimeUnit.MINUTES.toNanos(1)
            // Сбои подряд — счётчик службы, а не одного такта (аудит надёжности, A-5): объявленный в теле
            // такта, он обнулялся каждый раз, и служба без работающего контура крутилась вечно с wakelock.
            var failures = 0
            fun failed(what: String, reason: String) {
                failures++
                log.error(what, reason)
                if (failures >= MAX_TICK_FAILURES) {
                    log.error("служба контроллера", "такт падает $failures раз подряд — служба уходит, экран поднимет её снова")
                    // `running` снимает `onDestroy` (аудит стабильности, A-6): снятый здесь, до ухода службы, он звал
                    // экран к `startForegroundService` в гонке со `stopSelf` — система роняла приложение.
                    stopSelf()
                }
            }
            keeper = Executors.newSingleThreadScheduledExecutor().also { k ->
                k.scheduleWithFixedDelay({
                    runCatching { if (ticking) lock.acquire(TimeUnit.MINUTES.toMillis(WAKE_MINUTES)) }
                }, 1, 1, TimeUnit.MINUTES)
            }
            timer = Executors.newSingleThreadScheduledExecutor().also { t ->
                t.scheduleWithFixedDelay({
                    // Всё тело такта — под защитой (D-87): у `ScheduledThreadPoolExecutor` исключение в
                    // периодической задаче отменяет все её следующие запуски, а `Future` никто не хранит.
                    // Такт, умерший молча, оставлял уведомление «контроллер работает», `running = true`
                    // и застывший экран — приложение говорило «работаю», когда работа встала.
                    try {
                        // Targets с каталогом Claude не осталось — контроллеру нечего опрашивать: служба уходит,
                        // а не шагает демо в фоне (ревью 8 октября 2026, A-28). Экран поднимет её, когда target появится.
                        if (!model.hasLive()) {
                            log.step("служба контроллера: нет targets с каталогом Claude")
                            stopSelf()
                            return@scheduleWithFixedDelay
                        }
                        lock.acquire(TimeUnit.MINUTES.toMillis(WAKE_MINUTES))
                        ticking = true
                        meter.begin()
                        // Сбой такта — в журнал приложения, а не молча (D-43): контур идёт дальше.
                        val ok = try {
                            model.step(null).notifications.forEach(notifier::show)
                            // Лимиты слотов с автообновлением — раз в минуту, и без открытой сводки.
                            val auto = UsageAuto.enabled(app)
                            val tick = Clock.SYSTEM.monotonicNanos()
                            if (auto.isNotEmpty() && tick - lastUsage >= TimeUnit.MILLISECONDS.toNanos(UsageAuto.EVERY_MS)) {
                                lastUsage = tick
                                model.refreshUsage(auto)
                            }
                            // Копия настроек — после изменения и раз в час (D-51).
                            SettingsBackups.tick(app, Controller.get(app.filesDir))
                            // Задачи из экосистемы по политике прогона — раз в час (D-106); сбой — в журнал, такт идёт.
                            try {
                                EcosystemAuto.tick(app, model, notifier, log)
                            } catch (e: Exception) {
                                log.error("экосистема", AppLogs.describe(e))
                            }
                            // Облачная копия — раз в сутки, если включена (D-107); после шага такта контур стоит.
                            try {
                                CloudSync.tick(app, model, log)
                            } catch (e: Exception) {
                                log.error("облачная копия", AppLogs.describe(e))
                            }
                            true
                        } catch (e: Exception) {
                            // Сбой шага — тоже сбой подряд: контур, который не строится (журнал не открылся), не работает.
                            failed("такт", AppLogs.describe(e))
                            false
                        }
                        ticking = false
                        if (ok) failures = 0
                        meter.end(ok)
                        // Запросы человеку — по ждущим в журнале: показать новые, убрать закрытые (D-44).
                        try {
                            RequestPresenter.sync(app, model, model.pending())
                        } catch (e: Exception) {
                            log.error("запросы", AppLogs.describe(e))
                        }
                        val now = Clock.SYSTEM.monotonicNanos()
                        if (now - lastSummary >= TimeUnit.MINUTES.toNanos(1)) {
                            lastSummary = now
                            summary(log, meter, model, device)
                            // Termux-target на этом телефоне: sshd не слушает — запустить его в Termux (D-46).
                            try {
                                TermuxLauncher.ensureSshd(app, Controller.get(app.filesDir).targets())?.let { log.info("termux", it) }
                            } catch (e: Exception) {
                                log.warn("termux", AppLogs.describe(e))
                            }
                        }
                    } catch (e: Throwable) {
                        // Сюда попадает то, что вне внутренних `try`: настройки с диска, wakelock (на части
                        // прошивок `acquire` бросает), конец замера, сводка. Считаем сбои подряд и после
                        // [MAX_TICK_FAILURES] уходим, а не делаем вид, что работаем: экран поднимет службу.
                        failed("такт: сбой вне шага", AppLogs.describe(e))
                    }
                }, 0, STEP_SECONDS, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    /**
     * Сводка раз в минуту (D-43): такты и наибольший разрыв между ними (сон
     * устройства), сессии, задачи, запросы, память и сведения платформы.
     */
    private fun summary(log: com.dpsmpx.dpai.orchestrator.log.AppLog, meter: TickMeter, model: DemoModel, device: com.dpsmpx.dpai.orchestrator.log.DeviceProbe) {
        val t = meter.drain()
        val snap = model.published
        val tasks = snap?.board?.columns?.sumOf { it.cards.size }
        val facts = listOf(
            "такты" to "${t.count}", "ср_мс" to "${t.avgMs}", "макс_мс" to "${t.maxMs}", "разрыв_мс" to "${t.maxGapMs}", "сбои" to "${t.failures}",
            "сессии" to "${snap?.dashboard?.sessions?.size ?: "?"}", "задачи" to "${tasks ?: "?"}", "запросы" to "${snap?.requests?.size ?: "?"}",
        ) + runCatching { device.sample() }.getOrDefault(emptyList())
        log.info("сводка", facts.joinToString(" ") { (k, v) -> "$k=$v" })
    }

    override fun onDestroy() {
        runCatching { AppLogs.get(applicationContext).step("служба контроллера остановлена") }
        ControlServer.stop()
        running = false
        // Без прерывания: идущий такт доходит до конца (ревью 8 октября 2026, A-26) — прерванный посреди
        // вызова агента оставлял сессию без итога, занятые аренду и место target и открытую сессию SSH.
        // Следующие такты отменены; после снятия wakelock идущий такт доделает работу, когда процессор проснётся.
        timer?.shutdown()
        timer = null
        keeper?.shutdownNow()
        keeper = null
        ticking = false
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dpai-controller"
        private const val ID = 1001
        private const val STOP = "com.dpsmpx.dpai.STOP_CONTROLLER"
        private const val STEP_SECONDS = 3L

        /**
         * Сколько сбоев такта подряд терпим, прежде чем уйти (D-87): такт, который падает
         * раз за разом, — не «работает»; экран поднимет службу заново, а в журнале останется причина.
         */
        private const val MAX_TICK_FAILURES = 3

        /** Срок wakelock от последнего такта: такты встали — wakelock истёк (D-39). */
        private const val WAKE_MINUTES = 10L

        /** Отметка «остановлен человеком» в каталоге файлов приложения. */
        private const val STOPPED = "controller-stopped"

        /** Служба шагает сама: экран не делает такт, только рисует ([DemoModel.view]). */
        @Volatile
        var running: Boolean = false
            private set

        /** Остановлен ли контроллер кнопкой «Остановить» — до явного [resume]. */
        fun stoppedByUser(context: Context): Boolean = File(context.filesDir, STOPPED).isFile

        /**
         * Запуск службы экраном, если человек её не останавливал. Из фона
         * (Android 12+) запуск службы переднего плана запрещён — это не
         * падение приложения, а пропуск: следующий такт экрана попробует снова.
         */
        fun start(context: Context): Boolean {
            if (stoppedByUser(context)) return false
            return try {
                context.startForegroundService(Intent(context, ControllerService::class.java))
                true
            } catch (_: IllegalStateException) {
                false // ForegroundServiceStartNotAllowedException (API 31+) — её подкласс
            }
        }

        /** Остановить по подтверждению человека (A-18): как «Остановить» прежде — до явного запуска. */
        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ControllerService::class.java).setAction(STOP)) }
        }

        /** Явный запуск человеком после «Остановить». */
        fun resume(context: Context): Boolean {
            runCatching { NioVfs(context.filesDir.toPath()).delete(STOPPED) }
            return start(context)
        }
    }
}
