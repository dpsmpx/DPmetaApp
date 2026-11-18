package com.dpsmpx.dpmeta.app.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpmeta.app.screens.AskActions

/**
 * Разговор «Спросить Claude» (F-21) — один на процесс: ответ идёт в своём потоке и
 * переживает поворот экрана и уход с экрана; экран только читает состояние. История
 * читается и пишется через [AskStore]; запрос уходит мостом оболочки к подсистеме «AI».
 */
object AskSession {
    /** Ответ, который идёт сейчас: текст до сих пор и что модель читает. */
    data class Running(val text: String = "", val step: String? = null)

    /** Мост к «AI»: вопрос с инструментами; его даёт активность (оболочка `:shell`). */
    fun interface Bridge {
        fun ask(
            request: AskActions.Request,
            onCancel: (() -> Unit) -> Unit,
            onText: (String) -> Unit,
            tool: (String, JsonObject) -> Pair<String, Boolean>,
        ): Outcome<AskActions.Answer>
    }

    /**
     * Ответ начат и кончен (F-58; D-161): служба переднего плана на время ответа и событие
     * «ответ готов», если приложение не на экране. [profile] — профиль ответа.
     */
    interface Watcher {
        fun started()
        fun finished(ok: Boolean, profile: String?)
    }

    @Volatile
    private var watcher: Watcher? = null

    /** Поставить [Watcher] — один раз на процесс (оболочка Android). */
    fun watch(w: Watcher) {
        if (watcher == null) watcher = w
    }

    /** История и выбор автора; `null` — ещё не прочитаны. */
    var saved by mutableStateOf<AskActions.Saved?>(null)
        private set
    var running by mutableStateOf<Running?>(null)
        private set

    /** История не прочитана или не записана — строкой; новая запись заменит испорченный файл. */
    var problem by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var store: AskStore? = null

    @Volatile
    private var cancel: (() -> Unit)? = null

    /** Чтение истории — один раз на процесс, с фонового потока. */
    fun load(store: AskStore) {
        if (this.store != null) return
        this.store = store
        Thread {
            val r = store.load()
            main.post {
                saved = r.valueOrNull() ?: AskActions.Saved()
                if (r is Outcome.Failure) problem = r.errors.first().message.replaceFirstChar { it.uppercase() } + ". Новый вопрос начнёт историю заново."
            }
        }.start()
    }

    private fun persist(next: AskActions.Saved) {
        saved = next
        val s = store ?: return
        Thread {
            val r = s.save(next)
            main.post { problem = (r as? Outcome.Failure)?.errors?.first()?.message?.replaceFirstChar { it.uppercase() } }
        }.start()
    }

    /** Выбор автора: профиль, модель, effort — запоминается вместе с историей. */
    fun choose(profile: String?, model: String?, effort: String?) {
        val s = saved ?: return
        persist(s.copy(profile = profile, model = model, effort = effort))
    }

    /** «Новый разговор»: история стирается; идущий ответ прерывается. */
    fun clear() {
        stop()
        val s = saved ?: return
        persist(s.copy(entries = emptyList()))
    }

    fun stop() {
        cancel?.invoke()
    }

    /**
     * Вопрос: запись в историю сразу, ответ — потоком. [file] и [fileText] — файл,
     * который автор сам приложил; иначе в запрос идут только срез и инструменты чтения.
     */
    fun send(bridge: Bridge, profile: AskActions.Profile, model: String, effort: String?, snap: AskActions.Snapshot, question: String, file: String?, fileText: String?) {
        val s = saved ?: return
        if (running != null || question.isBlank()) return
        val asked = AskActions.Entry(true, question.trim(), Clock.SYSTEM.now().epochMillis, file = file, fileText = fileText)
        val withQuestion = s.copy(entries = (s.entries + asked).takeLast(AskActions.MAX_ENTRIES), profile = profile.id, model = model, effort = effort)
        persist(withQuestion)
        running = Running()
        watcher?.started()
        val request = AskActions.Request(profile.id, model, effort, AskActions.system(snap), AskActions.turns(withQuestion.entries), AskActions.TOOLS)
        Thread {
            val text = StringBuilder()
            val r = try {
                bridge.ask(
                    request,
                    onCancel = { cancel = it },
                    onText = { piece ->
                        val now = synchronized(text) { text.append(piece).toString() }
                        main.post { if (running != null) running = Running(now) }
                    },
                    tool = { name, input ->
                        main.post { running = running?.copy(step = "Читаю данные: " + (AskActions.TOOLS.firstOrNull { it.name == name }?.let { stepTitle(it.name) } ?: name)) }
                        AskActions.run(snap, name, input)
                    },
                )
            } catch (e: RuntimeException) {
                Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", "ask")
            }
            cancel = null
            val at = Clock.SYSTEM.now().epochMillis
            val entry = when (r) {
                is Outcome.Success -> AskActions.entry(r.value, model, at)
                is Outcome.Failure -> {
                    val e = r.errors.first()
                    val note = if (e.code == DiagCode.CANCELLED_BY_USER) "прервано" else "ответ не получен: ${e.message}"
                    AskActions.Entry(false, synchronized(text) { text.toString() }, at, model, note, failed = true)
                }
            }
            main.post {
                running = null
                watcher?.finished(r is Outcome.Success, profile.id)
                // «Новый разговор» во время ответа: история уже пуста — ответ на стёртый вопрос не возвращается.
                val latest = saved ?: return@post
                if (latest.entries.lastOrNull() == asked) persist(latest.copy(entries = (latest.entries + entry).takeLast(AskActions.MAX_ENTRIES)))
            }
        }.start()
    }

    private fun stepTitle(tool: String): String = when (tool) {
        "ecosystem_summary" -> "сводка и проекты"
        "project_card" -> "карточка проекта"
        "pull_requests" -> "PR"
        "questions_to_author" -> "вопросы автору (архив)"
        "recent_events" -> "лента событий"
        "metric_history" -> "история числа"
        else -> tool
    }
}
