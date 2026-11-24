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
 * Gemini, llama.cpp и поиск моделей, отправка облачной копии. Состояние — на процесс, вне экрана:
 * уход с вкладки и поворот не теряют ход, вывод и итог; пока операция идёт,
 * повтор того же ключа не запускается (вторая установка параллельно первой
 * была возможна). Итог — строкой у экрана, в журнал приложения ([onDone]) и
 * снэкбаром, если экран открыт; неудача — ещё и хвостом вывода в журнале:
 * по одной строке итога причину не найти, когда экрана уже нет (код 159
 * установки Claude Code, D-115). Отмены нет: эти операции — один процесс
 * гостя без точек остановки.
 */
class LongJobs(private val scope: CoroutineScope, private val onDone: (String, Done) -> Unit = { _, _ -> }) {
    /** Операция: [running] — идёт; [note] — итог; [output] — хвост вывода. */
    data class Job(val label: String, val running: Boolean, val note: String? = null, val output: List<String> = emptyList())

    /** Итог работы: строка и вывод; [failed] — неудача, вывод идёт и в журнал. */
    data class Done(val note: String, val output: List<String> = emptyList(), val failed: Boolean = false)

    private val flow = MutableStateFlow<Map<String, Job>>(emptyMap())
    val state: StateFlow<Map<String, Job>> = flow

    /** Идёт ли хоть одна из [keys] — у экрана, где кнопки делят один процесс (пакеты, установка). */
    fun busy(vararg keys: String): Boolean = keys.any { flow.value[it]?.running == true }

    /**
     * Запустить, если не идёт ни [key], ни что-то из [excludes] (например,
     * `apk` гостя — один процесс за раз: база пакетов под замком); `false` —
     * не запущено.
     */
    fun start(key: String, label: String, excludes: Set<String> = emptySet(), onFinished: ((Done) -> Unit)? = null,
        afterFinished: ((Done) -> Unit)? = null, work: suspend () -> Done): Boolean {
        synchronized(this) {
            if ((excludes + key).any { flow.value[it]?.running == true }) return false
            flow.value = flow.value + (key to Job(label, running = true))
        }
        scope.launch {
            val done = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Done("$label: ${com.dpsmpx.dpai.core.text.Words.failure(e)}", failed = true)
            }
            try {
                // Итог принадлежит этому запуску: следующий ещё не может занять ключ.
                onFinished?.invoke(done)
            } finally {
                synchronized(this@LongJobs) { flow.value = flow.value + (key to Job(label, running = false, note = done.note, output = done.output)) }
            }
            onDone(label, done)
            // Явное продолжение только после публикации итога и освобождения ключа.
            afterFinished?.invoke(done)
        }
        return true
    }

    /** Убрать законченный итог с экрана. */
    fun dismiss(key: String) {
        synchronized(this) { if (flow.value[key]?.running == false) flow.value = flow.value - key }
    }

    companion object {
        /** Установка Alpine. */
        const val LINUX_INSTALL = "linux-install"

        /** `apk` и npm гостя: пакеты, Codex и Gemini. */
        const val LINUX_APK = "linux-apk"

        /** Локальная модель: установка llama.cpp (тоже `apk`), поиск, запуск. */
        const val LLAMA = "llama"

        /** Облачная копия (D-107): отправка, замена, отмена восстановления. */
        const val CLOUD = "cloud"

        /** Получение, проверка и синхронизация общей папки. */
        const val SHARED_VERIFY = "shared-verify"

        /** Всё, что запускает процессы гостя: идут по одному. */
        const val WORKSPACE_PROFILE = "workspace-profile"

        val GUEST = setOf(LINUX_INSTALL, LINUX_APK, LLAMA, SHARED_VERIFY, WORKSPACE_PROFILE)

        /** Сколько последних строк вывода неудачи идёт в журнал. */
        private const val MAX_LOGGED = 20

        @Volatile
        private var instance: LongJobs? = null

        /** Одни на процесс; итог — строкой журнала приложения (D-43). */
        fun get(context: android.content.Context): LongJobs = instance ?: synchronized(this) {
            instance ?: run {
                val log = AppLogs.get(context.applicationContext)
                LongJobs(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { label, done ->
                    log.info("ai", "$label — ${done.note}")
                    // Хвост вывода неудачи — строками журнала (через Redactor журнала, D-14).
                    if (done.failed) done.output.takeLast(MAX_LOGGED).forEach { log.warn("ai", "$label | $it") }
                }.also { instance = it }
            }
        }
    }
}
