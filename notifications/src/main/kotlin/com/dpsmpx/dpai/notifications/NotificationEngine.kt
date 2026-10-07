package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.clock.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Категория уведомления (ТЗ, §35) и её вес: рост веса по тому же ключу —
 * эскалация, она проходит сразу.
 */
enum class NotificationCategory(val rank: Int) {
    INFO(0),
    PROGRESS(0),
    SUCCESS(1),
    SYSTEM(1),
    WARNING(2),
    BLOCKED(3),
    FAILURE(3),
    APPROVAL(4),
    SECURITY(5),
}

/** Кнопка уведомления: подпись и ссылка `dpai://…`, которую оркестратор разберёт строго. */
data class NotificationAction(val label: String, val uri: String)

/**
 * Что сообщить. [key] — один предмет: повторы по нему агрегируются (ТЗ,
 * §36). [actions] — кнопки (ТЗ, §37); у обновлённого уведомления — кнопки
 * последнего запроса, чтобы старая кнопка не действовала на новое.
 */
data class NotificationRequest(
    val key: String,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val link: DeepLink?,
    val at: Timestamp,
    val actions: List<NotificationAction> = emptyList(),
)

/** Показанное уведомление; [count] — сколько раз случилось с момента показа. */
data class Notification(
    val key: String,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val link: DeepLink?,
    val count: Int,
    val firstAt: Timestamp,
    val lastAt: Timestamp,
    val actions: List<NotificationAction> = emptyList(),
)

sealed interface NotificationDecision {
    /** Показать (со звуком, если ОС позволяет). */
    data class Post(val notification: Notification) : NotificationDecision

    /** Обновить уже показанное без звука: счётчик повторов вырос. */
    data class Update(val notification: Notification) : NotificationDecision

    /** Придержано пределом частоты; попадёт в сводку. */
    data class Held(val request: NotificationRequest) : NotificationDecision
}

/**
 * Политика (ТЗ, §36, §82): окно агрегации по ключу, предел показов в
 * минуту; `SECURITY` и `APPROVAL` предел не держит — их нельзя потерять.
 */
data class NotificationPolicy(
    val aggregationWindow: Duration = 10.minutes,
    val maxPostsPerMinute: Int = 6,
    val unlimited: Set<NotificationCategory> = setOf(NotificationCategory.SECURITY, NotificationCategory.APPROVAL),
) {
    init {
        require(aggregationWindow.isPositive() && maxPostsPerMinute >= 1) { "политика уведомлений неверна" }
    }
}

/**
 * Решения о показе уведомлений — без шторма (ТЗ, §36; этап 12):
 *
 * - повтор того же ключа в окне агрегации — [NotificationDecision.Update]
 *   показанного со счётчиком, а не новое уведомление;
 * - рост серьёзности по ключу — сразу [NotificationDecision.Post];
 * - сверх предела в минуту — [NotificationDecision.Held], затем одна
 *   сводка [flush];
 * - решённый предмет ([resolve]) — следующий раз показывается заново.
 *
 * Время — из запросов (D-07); решения детерминированы.
 */
class NotificationEngine(private val policy: NotificationPolicy = NotificationPolicy()) {
    private val shown = HashMap<String, Pair<Notification, Timestamp>>() // ключ → показанное и время показа
    private val recentPosts = ArrayDeque<Timestamp>()
    private val held = mutableListOf<NotificationRequest>()

    @Synchronized
    fun submit(raw: NotificationRequest): NotificationDecision {
        // Уведомление видно на экране блокировки: текст — через строгий редактор (A-6).
        val r = raw.copy(title = Redactor.STRICT.redact(raw.title), body = Redactor.STRICT.redact(raw.body))
        val prev = shown[r.key]
        if (prev != null && r.at - prev.second < policy.aggregationWindow && r.category.rank <= prev.first.category.rank) {
            val updated = prev.first.copy(count = prev.first.count + 1, lastAt = r.at, body = r.body, actions = r.actions)
            shown[r.key] = updated to prev.second
            return NotificationDecision.Update(updated)
        }
        if (r.category !in policy.unlimited && !rateAllows(r.at)) {
            held += r
            return NotificationDecision.Held(r)
        }
        val n = Notification(r.key, r.category, r.title, r.body, r.link, 1, r.at, r.at, r.actions)
        shown[r.key] = n to r.at
        recentPosts += r.at
        return NotificationDecision.Post(n)
    }

    /** Предмет решён (например, предел снят): следующий раз по ключу — новый показ. */
    @Synchronized
    fun resolve(key: String) {
        shown.remove(key)
        held.removeAll { it.key == key }
    }

    /** Сводка придержанного, если предел частоты позволяет; нечего сводить — `null`. */
    @Synchronized
    fun flush(now: Timestamp): NotificationDecision.Post? {
        if (held.isEmpty() || !rateAllows(now)) return null
        val byKey = held.groupBy { it.key }
        val top = byKey.values.sortedByDescending { g -> g.maxOf { it.category.rank } }
        val lines = top.take(5).map { g -> g.first().title + if (g.size > 1) " (×${g.size})" else "" }
        val more = byKey.size - lines.size
        val n = Notification(
            key = DIGEST_KEY,
            category = NotificationCategory.SYSTEM,
            title = "Сводка: придержано уведомлений — ${held.size}",
            body = (lines + if (more > 0) listOf("и ещё $more") else emptyList()).joinToString("\n"),
            link = null,
            count = held.size,
            firstAt = held.minOf { it.at },
            lastAt = now,
        )
        held.clear()
        recentPosts += now
        return NotificationDecision.Post(n)
    }

    private fun rateAllows(now: Timestamp): Boolean {
        while (recentPosts.isNotEmpty() && now - recentPosts.first() >= 1.minutes) recentPosts.removeFirst()
        return recentPosts.size < policy.maxPostsPerMinute
    }

    companion object {
        const val DIGEST_KEY = "system:digest"
    }
}
