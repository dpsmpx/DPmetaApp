package com.dpsmpx.dpai.orchestrator

/**
 * Последние [keep] записей и сквозной номер (этап 19): читатель из
 * другого потока берёт новое по номеру ([since]), а память не растёт с
 * числом записей. Потокобезопасен.
 */
class RecentLog<T>(private val keep: Int) {
    private val items = ArrayDeque<T>()

    init {
        require(keep > 0) { "keep > 0" }
    }

    /** Сколько записей было всего — сквозной номер следующей. */
    var total: Long = 0
        @Synchronized get
        private set

    @Synchronized
    fun add(item: T) {
        items.addLast(item)
        total++
        while (items.size > keep) items.removeFirst()
    }

    /** Последние записи (копия). */
    @Synchronized
    fun toList(): List<T> = items.toList()

    /** Записи после номера [seen] и новый номер; отставший читатель получает последние [keep]. */
    @Synchronized
    fun since(seen: Long): Pair<List<T>, Long> {
        val first = total - items.size
        val from = maxOf(seen, first)
        return items.drop((from - first).toInt()) to total
    }
}
