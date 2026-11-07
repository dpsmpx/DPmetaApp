package com.dpsmpx.dpai.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Долгие операции «AI» (AI:A-14): установка Alpine и пакетов, Codex и
 * Gemini, llama.cpp и поиск моделей. Состояние — на процесс, вне экрана:
 * уход с вкладки и поворот не теряют ход, вывод и итог; пока операция идёт,
 * повтор того же ключа не запускается (вторая установка параллельно первой
 * была возможна). Итог — строкой у экрана, в журнал приложения ([onDone]) и
 * снэкбаром, если экран открыт. Отмены нет: эти операции — один процесс
 * гостя без точек остановки.
 */
class LongJobs(private val scope: CoroutineScope, private val onDone: (String, String) -> Unit = { _, _ -> }) {
    /** Операция: [running] — идёт; [note] — итог; [output] — хвост вывода. */
    data class Job(val label: String, val running: Boolean, val note: String? = null, val output: List<String> = emptyList())

    /** Итог работы: строка и вывод. */
    data class Done(val note: String, val output: List<String> = emptyList())

    private val flow = MutableStateFlow<Map<String, Job>>(emptyMap())
    val state: StateFlow<Map<String, Job>> = flow

    /** Идёт ли хоть одна из [keys] — у экрана, где кнопки делят один процесс (пакеты, установка). */
    fun busy(vararg keys: String): Boolean = keys.any { flow.value[it]?.running == true }

    /** Запустить, если [key] не идёт; `false` — уже идёт, повтор не запущен. */
    fun start(key: String, label: String, work: suspend () -> Done): Boolean {
        synchronized(this) {
            if (flow.value[key]?.running == true) return false
            flow.value = flow.value + (key to Job(label, running = true))
        }
        scope.launch {
            val done = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Done("$label: ${com.dpsmpx.dpai.core.text.Words.failure(e)}")
            }
            synchronized(this@LongJobs) { flow.value = flow.value + (key to Job(label, running = false, note = done.note, output = done.output)) }
            onDone(label, done.note)
        }
        return true
    }

    /** Убрать законченный итог с экрана. */
    fun dismiss(key: String) {
        synchronized(this) { if (flow.value[key]?.running == false) flow.value = flow.value - key }
    }

    companion object {
        @Volatile
        private var instance: LongJobs? = null

        /** Одни на процесс; итог — строкой журнала приложения (D-43). */
        fun get(context: android.content.Context): LongJobs = instance ?: synchronized(this) {
            instance ?: run {
                val log = AppLogs.get(context.applicationContext)
                LongJobs(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { label, note -> log.info("ai", "$label — $note") }.also { instance = it }
            }
        }
    }
}
