package com.dpsmpx.dpai.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.ui.AnswerButton
import com.dpsmpx.dpai.ui.RequestCard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import android.app.Notification as AndroidNotification

/**
 * Запросы человеку на телефоне (D-44). Источник — ждущие запросы
 * оркестратора, а не поток уведомлений: каждый такт [sync] сверяет их с
 * показанным — новое показывает (уведомление и, если человек включил,
 * окно поверх приложений), закрытое (отвечено, истекло, снято) убирает.
 * Так перезапуск процесса и повторная доставка запрос не теряют и не
 * дублируют; факт показа — `delivered` в журнал.
 *
 * Уведомление: кнопки — варианты; вариант с текстом — поле ввода
 * ([RemoteInput]); опасный вариант — только после разблокировки и
 * подтверждения на экране приложения; нажатие — к задаче или сессии.
 * Кнопки — явные `PendingIntent` своих неэкспортируемых компонентов: чужое
 * приложение ответа не пришлёт, а ссылку с хешем проверит оркестратор.
 */
object RequestPresenter {
    const val TAG = "dpai-request"
    const val CHANNEL = "dpai-request"
    const val EXTRA_REQUEST = "dpai.request"
    const val EXTRA_SENDING = "dpai.request-sending"

    /** Ответы, отправленные из этого процесса и ещё без итога. */
    private val answering = ConcurrentHashMap.newKeySet<String>()

    /** Итог ответа пришёл — запрос больше не «отправляется». */
    fun answered(id: String) {
        answering -= id
    }

    /** Смахнутые человеком: до перезапуска процесса уведомление не возвращается само. */
    private val dismissed = ConcurrentHashMap.newKeySet<String>()

    /** Последние карточки — для повторного показа, если ответ не принят. */
    private val cards = ConcurrentHashMap<String, RequestCard>()

    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotifyGroups.ensure(manager)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Запросы: нужен ответ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Вопросы и подтверждения контура: ответ — кнопкой в уведомлении"
                group = NotifyGroups.DECISIONS
            },
        )
    }

    /**
     * Сверка с ждущими запросами [pending]: показать новые, убрать закрытые;
     * окно поверх приложений — первому ещё не показанному в нём.
     */
    fun sync(context: Context, model: DemoModel, pending: Map<RequestId, RequestCard>) {
        channel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.filter { it.tag == TAG }
        // «Отправлен» — показ, только пока этот процесс ждёт итог ответа; после перезапуска — запрос снова с кнопками.
        val shown = active.mapNotNull { n -> n.notification.extras.getString(EXTRA_REQUEST) ?: n.notification.extras.getString(EXTRA_SENDING)?.takeIf { it in answering } }.toSet()
        for (n in active) {
            val id = n.notification.extras.getString(EXTRA_REQUEST) ?: n.notification.extras.getString(EXTRA_SENDING)
            if (id == null || pending.keys.none { it.value == id }) manager.cancel(TAG, n.id)
        }
        for ((id, card) in pending) {
            cards[id.value] = card
            if (id.value in shown || id.value in dismissed) continue
            show(context, card)
            // Показ — факт, если уведомление и правда в шторке: у приложения не больше 50, лишние
            // система отбрасывает молча (аудит стабильности, A-4).
            if (manager.activeNotifications.any { it.tag == TAG && it.id == notificationId(id.value) }) model.delivered(id, "notification")
        }
        cards.keys.retainAll(pending.keys.map { it.value }.toSet())
        RequestOverlay.sync(context, model, pending)
    }

    fun notificationId(id: String): Int = id.hashCode()

    fun show(context: Context, card: RequestCard) {
        channel(context)
        val id = card.id.value
        val builder = AndroidNotification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ai_requests)
            .setContentTitle(card.title)
            .setContentText(card.body)
            .setSubText(card.origin)
            .setStyle(AndroidNotification.BigTextStyle().bigText("${card.body}\n${card.origin} · срок: ${card.left}"))
            .setCategory(AndroidNotification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setExtras(Bundle().apply { putString(EXTRA_REQUEST, id) })
            // Вопрос Claude и название задачи — не для экрана блокировки (аудит безопасности, A-12).
            .setVisibility(AndroidNotification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notifier.lockScreen(context, CHANNEL, "Запрос контура: нужен ответ"))
            .setContentIntent(Notifier.openLink(context, card.task?.let { "dpai://task/${it.value}" } ?: card.session?.let { "dpai://session/${it.value}" } ?: "dpai://request/$id"))
            .setDeleteIntent(receiver(context, RequestReceiver.DISMISS, id, null, 0, mutable = false))
        // В уведомлении — до трёх кнопок (ограничение Android); остальные варианты — на экране «Запросы».
        for ((i, b) in card.buttons.take(3).withIndex()) builder.addAction(action(context, id, b, i))
        context.getSystemService(NotificationManager::class.java).notify(TAG, notificationId(id), builder.build())
    }

    private fun action(context: Context, id: String, b: AnswerButton, index: Int): AndroidNotification.Action {
        val a = when {
            // Опасное — подтверждение на экране приложения, а не одно касание в шторке (D-44).
            b.confirm -> AndroidNotification.Action.Builder(null as Icon?, b.label, confirm(context, b.uri, index))
            // Поле ввода требует изменяемый PendingIntent; компонент явный — подменить получателя нельзя.
            b.needsText -> AndroidNotification.Action.Builder(null as Icon?, b.label, receiver(context, RequestReceiver.ANSWER, id, b, index, mutable = true))
                .addRemoteInput(RemoteInput.Builder(RequestReceiver.TEXT).setLabel(b.label).build())
            else -> AndroidNotification.Action.Builder(null as Icon?, b.label, receiver(context, RequestReceiver.ANSWER, id, b, index, mutable = false))
        }
        // Android 12+: опасное и ответы со смыслом «да» — только на разблокированном телефоне.
        if (Build.VERSION.SDK_INT >= 31 && (b.confirm || b.tone == com.dpsmpx.dpai.ui.Tone.ACTIVE)) a.setAuthenticationRequired(true)
        return a.build()
    }

    private fun receiver(context: Context, action: String, id: String, b: AnswerButton?, index: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, RequestReceiver::class.java).setAction(action).putExtra(RequestReceiver.REQUEST, id)
        b?.let { intent.putExtra(RequestReceiver.URI, it.uri).putExtra(RequestReceiver.LABEL, it.label) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, notificationId(id) * 31 + index + if (action == RequestReceiver.DISMISS) 7 else 0, intent, flags)
    }

    private fun confirm(context: Context, uri: String, index: Int): PendingIntent = PendingIntent.getActivity(
        context, uri.hashCode() * 31 + index,
        MainActivity.confirm(context, uri),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun dismissed(id: String) {
        dismissed += id
    }

    /**
     * Решающее пришло из `dpai` (D-91): запрос, который человек подтвердит
     * здесь, — снова уведомлением и окном поверх приложений, даже смахнутый.
     * Исключение задачи — её запрос с вариантом «принять» (без проверки, без
     * ревью); такого нет — уведомление к листу задачи, где кнопка
     * «Принять без проверки».
     */
    fun confirmFromCli(context: Context, model: DemoModel, c: Command) {
        val pending = model.pending()
        val id = when (c) {
            is Command.AnswerRequest -> RequestAction.parse(c.uri).valueOrNull()?.request
            // Запрос задачи с опасным вариантом («принять без проверки», «без ревью»); «Слить» тоже с подтверждением (D-128), но это не он.
            is Command.Waive -> pending.values.firstOrNull { it.task == c.task && it.buttons.any { b -> b.tone == com.dpsmpx.dpai.ui.Tone.ERROR } }?.id
            else -> null
        }
        val card = id?.let { pending[it] }
        if (card != null) {
            dismissed -= card.id.value
            cards[card.id.value] = card
            show(context, card)
            RequestOverlay.again(card.id.value)
            return
        }
        if (c !is Command.Waive) return
        val title = model.liveOrchestrator()?.tasks?.tasks?.get(c.task)?.title ?: "задача"
        val strict = com.dpsmpx.dpai.core.diagnostics.Redactor.STRICT
        val channel = Notifier.channelOf(com.dpsmpx.dpai.notifications.NotificationCategory.APPROVAL)
        Notifier(context)
        val n = AndroidNotification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_ai_requests)
            .setContentTitle("dpai просит исключение — подтвердите здесь")
            .setContentText(strict.redact("«$title»: ${c.reason}"))
            .setStyle(AndroidNotification.BigTextStyle().bigText(strict.redact("Задача «$title» станет выполненной без проверки. Причина из dpai: ${c.reason}\nПринять — на листе задачи, «Принять без проверки».")))
            .setVisibility(AndroidNotification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notifier.lockScreen(context, channel, "Нужно подтверждение на телефоне"))
            .setAutoCancel(true)
            .setContentIntent(Notifier.openLink(context, "dpai://task/${c.task.value}"))
            .build()
        context.getSystemService(NotificationManager::class.java).notify("$TAG-waive", notificationId(c.task.value), n)
    }

    /** Ответ не принят, а запрос ещё ждёт: показать его снова с кнопками. */
    fun reshow(context: Context, id: String) {
        cards[id]?.let { show(context, it) }
    }

    /**
     * Итог ответа — заменой уведомления запроса: короткое, само исчезает.
     * Текст — строгой правкой (D-14, ревью A-71): в нём может быть хвост
     * вывода git, а уведомление видно и на экране блокировки. Предел
     * частоты не нужен: итог — один на нажатие.
     */
    fun result(context: Context, id: String, title: String, text: String) {
        channel(context)
        val strict = com.dpsmpx.dpai.core.diagnostics.Redactor.STRICT
        val n = AndroidNotification.Builder(context, Notifier.channelOf(com.dpsmpx.dpai.notifications.NotificationCategory.INFO))
            .setSmallIcon(R.drawable.ic_notify_ai)
            .setContentTitle(strict.redact(title))
            .setContentText(strict.redact(text))
            .setAutoCancel(true)
            .setTimeoutAfter(RESULT_MS)
            .setVisibility(AndroidNotification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notifier.lockScreen(context, Notifier.channelOf(com.dpsmpx.dpai.notifications.NotificationCategory.INFO), "Ответ на запрос"))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(TAG + "-result", notificationId(id), n)
    }

    /**
     * «Отправляю…» сразу после нажатия: кнопки сняты — второе нажатие нечем
     * сделать. Метка — не «запрос показан»: процесс умер посреди ответа —
     * [sync] вернёт запрос с кнопками, а не будет сутки считать его
     * показанным (аудит стабильности, A-2).
     */
    fun sending(context: Context, id: String, label: String) {
        answering += id
        val n = AndroidNotification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ai_requests)
            .setContentTitle("Ответ «$label» отправлен")
            .setContentText("контур применяет решение…")
            .setOnlyAlertOnce(true)
            .setExtras(Bundle().apply { putString(EXTRA_SENDING, id) })
            .build()
        context.getSystemService(NotificationManager::class.java).notify(TAG, notificationId(id), n)
    }

    private const val RESULT_MS = 15_000L
}

/**
 * Нажатая кнопка запроса (D-44): приёмник не экспортируется; ссылку с
 * хешем и вариантом проверяет оркестратор, текст — из поля ввода
 * уведомления. Повторное нажатие — тот же ответ без второго действия.
 */
class RequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(REQUEST)?.takeIf { IdKinds.REQUEST.parse(it).isSuccess } ?: return
        if (intent.action == DISMISS) {
            RequestPresenter.dismissed(id)
            return
        }
        if (intent.action != ANSWER) return
        val uri = intent.getStringExtra(URI) ?: return
        // Кнопка относится к своему запросу: подменённый id в extras ответа не даст.
        if (RequestAction.parse(uri).valueOrNull()?.request?.value != id) return
        val label = intent.getStringExtra(LABEL) ?: "ответ"
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TEXT)?.toString()
        val app = context.applicationContext
        RequestPresenter.sending(app, id, label)
        val pending = goAsync()
        // Такт держит замок модели, пока ходит по SSH: при работающей службе процесс жив
        // и без приёмника — широковещание отпускается сразу (ревью 8 октября 2026, A-23).
        // Службы нет (процесс убит системой) — поднять её: нажатие на уведомление это разрешает. Иначе
        // широковещание ждало сборку контура со сверкой по SSH — 60 с, ANR и потерянный ответ (аудит
        // стабильности 2 ноября 2026, A-2).
        val detached = ControllerService.running || (model(app) && ControllerService.start(app))
        if (detached) pending.finish()
        worker.execute {
            try {
                Answers.apply(app, id, uri, text, label, "notification")
            } finally {
                if (!detached) pending.finish()
            }
        }
    }

    /** Контроллер настоящий (не демо): только ему нужна служба. */
    private fun model(app: Context): Boolean = runCatching { DemoModel.get(app).hasLive() }.getOrDefault(false)

    companion object {
        const val ANSWER = "com.dpsmpx.dpai.REQUEST_ANSWER"
        const val DISMISS = "com.dpsmpx.dpai.REQUEST_DISMISS"
        const val REQUEST = "request"
        const val URI = "uri"
        const val LABEL = "label"
        const val TEXT = "text"
        private val worker = Executors.newSingleThreadExecutor()
    }
}

/**
 * Применение ответа с любого канала (уведомление, окно, экран) и показ итога.
 * Такт занят — ответ в журнале и применится после текущего шага (D-94):
 * это «принято», а не «не принято» — кнопки не возвращаются, иначе второй
 * вариант встал бы в очередь за первым (аудит надёжности, A-2); итог —
 * уведомлением, когда такт исполнит ответ.
 */
object Answers {
    /** Ответ принят в журнал, итога ещё нет (D-94). */
    fun queued(r: Outcome<*>): Boolean = r is Outcome.Failure && r.diagnostics.all { it.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.COMMAND_QUEUED }

    fun apply(context: Context, id: String, uri: String, text: String?, label: String, channel: String): Outcome<*> {
        val model = DemoModel.get(context)
        // Ответ на экране — итог там же (AI:A-18): уведомление о нём — двойной отклик. Отложенный итог
        // (такт был занят) — уведомлением всегда: экран к тому времени может быть закрыт.
        val inApp = channel == "app"
        val r = model.answer(uri, text, channel) { later -> shown(context, model, id, label, later, notify = true) }
        if (queued(r)) {
            RequestPresenter.sending(context, id, label)
            if (!inApp) RequestPresenter.result(context, id, "Принято: $label", "применится после текущего шага контура — итог придёт уведомлением")
            RequestOverlay.waiting(id)
        } else {
            shown(context, model, id, label, r, notify = !inApp)
        }
        return r
    }

    private fun shown(context: Context, model: DemoModel, id: String, label: String, r: Outcome<com.dpsmpx.dpai.notifications.RequestAnswer>, notify: Boolean) {
        RequestPresenter.answered(id)
        when (r) {
            is Outcome.Success -> {
                context.getSystemService(NotificationManager::class.java).cancel(RequestPresenter.TAG, RequestPresenter.notificationId(id))
                if (notify) RequestPresenter.result(context, id, "Принято: $label", r.value.effect ?: "записано")
                RequestOverlay.closed(id)
            }
            is Outcome.Failure -> {
                if (notify) RequestPresenter.result(context, id, "Не принято: $label", r.diagnostics.first().message)
                if (model.pending().keys.any { it.value == id }) {
                    RequestPresenter.reshow(context, id)
                    RequestOverlay.again(id)
                } else {
                    context.getSystemService(NotificationManager::class.java).cancel(RequestPresenter.TAG, RequestPresenter.notificationId(id))
                }
            }
        }
    }
}
