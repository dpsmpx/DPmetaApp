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
    /** Только действующий родитель разрешает вложенный шаг с тем же ресурсом; сетевого представления нет. */
    class Permit internal constructor(internal val key: String, internal val ticket: Any, internal val runId: String)
    fun permit(key: String): Permit? = synchronized(this) {
        tickets[key]?.takeIf { flow.value[key]?.running == true }?.let { Permit(key, it, flow.value.getValue(key).runId) }
    }
    /** Операция: [running] — идёт; [note] — итог; [output] — хвост вывода. */
    data class Job(val label: String, val running: Boolean, val note: String? = null, val output: List<String> = emptyList(),
        val runId: String = "", val failed: Boolean = false, val result: com.dpsmpx.dpai.core.serialization.JsonObject? = null)

    /** Итог работы: строка и вывод; [failed] — неудача, вывод идёт и в журнал. */
    data class Done(val note: String, val output: List<String> = emptyList(), val failed: Boolean = false)

    private val flow = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val tickets = mutableMapOf<String, Any>()
    private val guestOwners = mutableSetOf<String>()
    val state: StateFlow<Map<String, Job>> = flow

    /** Идёт ли хоть одна из [keys] — у экрана, где кнопки делят один процесс (пакеты, установка). */
    fun busy(vararg keys: String): Boolean = keys.any { flow.value[it]?.running == true }
    fun busyGuest(): Boolean = synchronized(this) { guestOwners.isNotEmpty() || GUEST.any { flow.value[it]?.running == true } }

    /**
     * Запустить, если не идёт ни [key], ни что-то из [excludes] (например,
     * `apk` гостя — один процесс за раз: база пакетов под замком); `false` —
     * не запущено.
     */
    fun start(key: String, label: String, excludes: Set<String> = emptySet(), onFinished: ((Done) -> Unit)? = null,
        afterFinished: ((Done) -> Unit)? = null, onAccepted: (String) -> Unit = {}, parent: Permit? = null,
        work: suspend () -> Done): Boolean {
        synchronized(this) {
            val guest = excludes.containsAll(GUEST)
            if (parent != null && (key == parent.key || tickets[parent.key] !== parent.ticket ||
                    flow.value[parent.key]?.running != true)) return false
            if (guest && guestOwners.any { it != parent?.key }) return false
            if ((excludes + key).any { it != parent?.key && flow.value[it]?.running == true }) return false
            tickets[key] = Any()
            if (guest) guestOwners += key
            val id = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM,
                com.dpsmpx.dpai.core.clock.RandomSource.secure()).next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
            val previous = flow.value[key]
            flow.value = flow.value + (key to Job(label, running = true, runId = id))
            try { onAccepted(id) } catch (_: Exception) {
                tickets.remove(key); guestOwners.remove(key)
                flow.value = if (previous == null) flow.value - key else flow.value + (key to previous)
                return false
            }
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
                synchronized(this@LongJobs) {
                    val current = flow.value[key]
                    val id = current?.runId.orEmpty()
                    tickets.remove(key); guestOwners.remove(key)
                    flow.value = flow.value + (key to Job(label, running = false, note = done.note, output = done.output,
                        runId = id, failed = done.failed, result = current?.result))
                }
                // Продолжение получает исход и при сбое уведомления; ключ уже свободен.
                try { onDone(label, done) } finally { afterFinished?.invoke(done) }
            }
        }
        return true
    }

    /**
     * Вывод принадлежит конкретному запуску: поздний reader старого процесса
     * не может поменять прогресс нового. Не журналируем секреты (D-14).
     */
    fun guestReporter(): (String) -> Unit {
        val target = synchronized(this) {
            val key = GUEST.firstOrNull { flow.value[it]?.running == true } ?: return {}
            key to tickets.getValue(key)
        }
        return { text ->
            val safe = com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(text).take(4000)
            synchronized(this) {
                val current = flow.value[target.first]
                if (tickets[target.first] === target.second && current?.running == true && current.output.lastOrNull() != safe) {
                    flow.value = flow.value + (target.first to current.copy(output = (current.output + safe).takeLast(40)))
                }
            }
        }
    }

    /** Убрать законченный итог с экрана. */
    fun dismiss(key: String) {
        synchronized(this) { if (flow.value[key]?.running == false) flow.value = flow.value - key }
    }

    fun report(key: String, runId: String, note: String) {
        synchronized(this) {
            val current = flow.value[key]
            if (current?.running == true && current.runId == runId) flow.value = flow.value +
                (key to current.copy(note = com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(note).take(1000)))
        }
    }

    /** Результат основной сборки не заменяется итогом её вложенной проверки. */
    fun result(key: String, runId: String, value: com.dpsmpx.dpai.core.serialization.JsonObject) {
        synchronized(this) {
            val current = flow.value[key]
            if (current?.running == true && current.runId == runId)
                flow.value = flow.value + (key to current.copy(result = value))
        }
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

        const val WORKSPACE_CHILD = "workspace-profile-child"

        /** Всё, что запускает процессы гостя: идут по одному. */
        const val WORKSPACE_PROFILE = "workspace-profile"

        val GUEST = setOf(LINUX_INSTALL, LINUX_APK, LLAMA, SHARED_VERIFY, WORKSPACE_PROFILE, WORKSPACE_CHILD)

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
