package com.dpsmpx.dpmeta.app.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.CommandActions

/**
 * Запуски команд реестра (F-40; D-82) — на процесс: команда идёт в своём потоке и
 * переживает поворот и уход с экрана; экран только читает [runs]. Вывод — в памяти
 * (последние строки), на диск и в журнал не пишется: в журнал действий идёт только
 * итог уровня 1 (`:core:actions`).
 */
object CommandSession {
    /** Запуск: строки вывода до сих пор, идёт ли, итог и когда начат. */
    data class Run(val lines: List<String>, val running: Boolean, val result: String?, val failed: Boolean, val startedAt: Long)

    /**
     * Работа запуска: получает приёмник строк и место для остановки; итог — строка для
     * экрана (успех) или отказ. Уровень 1 оборачивает её политикой и журналом.
     */
    fun interface Job {
        fun run(onLine: (String) -> Unit, onStop: (() -> Unit) -> Unit): Outcome<String>
    }

    /** Запуски по ключу «команда@проект»: последний у каждой. */
    val runs = mutableStateMapOf<String, Run>()

    private val main = Handler(Looper.getMainLooper())
    private val stops = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun key(id: String, project: String): String = "$id@$project"

    fun running(key: String): Boolean = runs[key]?.running == true

    fun stop(key: String) {
        stops[key]?.invoke()
    }

    /** Запустить [job] под ключом [key]; уже идёт — ничего. */
    fun start(key: String, job: Job, done: (Outcome<String>) -> Unit = {}) {
        if (running(key)) return
        val started = Clock.SYSTEM.now().epochMillis
        runs[key] = Run(emptyList(), true, null, false, started)
        val buffer = ArrayList<String>()
        var dropped = 0
        var posted = false
        fun flush() {
            val now = synchronized(buffer) {
                posted = false
                CommandActions.tail(buffer.toList(), dropped)
            }
            runs[key]?.let { runs[key] = it.copy(lines = now) }
        }
        Thread {
            val r = try {
                job.run(
                    onLine = { line ->
                        val post = synchronized(buffer) {
                            buffer += line
                            // Память — не больше предела экрана: лишнее — счётом.
                            if (buffer.size > CommandActions.MAX_LINES) {
                                buffer.removeAt(0)
                                dropped++
                            }
                            (!posted).also { posted = true }
                        }
                        // Экран обновляется не чаще кадра: строк бывает тысячи.
                        if (post) main.postDelayed({ flush() }, 100)
                    },
                    onStop = { stops[key] = it },
                )
            } catch (e: RuntimeException) {
                Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", key)
            }
            stops.remove(key)
            main.post {
                flush()
                val seconds = (Clock.SYSTEM.now().epochMillis - started) / 1000
                runs[key] = runs[key]!!.copy(
                    running = false,
                    result = when (r) {
                        is Outcome.Success -> r.value
                        is Outcome.Failure -> r.errors.first().message.replaceFirstChar { it.uppercase() } + " ($seconds с)."
                    },
                    failed = r is Outcome.Failure,
                )
                done(r)
            }
        }.start()
    }
}
