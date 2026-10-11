package com.dpsmpx.dpai.core.measure

/**
 * Откуда известно значение (ТЗ, §16). Порядок — от сильного к слабому: при
 * сочетании берётся более слабый.
 */
enum class MeasureSource { OBSERVED, DERIVED, ESTIMATED }

/**
 * Наблюдаемая величина: известна с источником или неизвестна с причиной
 * (D-13; ТЗ, §5.7, §49). Неизвестное — не ноль: нет heartbeat — «активность
 * неизвестна», нет данных провайдера о токенах — [Unknown], а не `0`.
 */
sealed interface Measure<out T> {
    data class Known<out T>(val value: T, val source: MeasureSource) : Measure<T>

    data class Unknown(val reason: String) : Measure<Nothing>

    fun orNull(): T? = (this as? Known<T>)?.value

    companion object {
        fun <T> observed(value: T): Measure<T> = Known(value, MeasureSource.OBSERVED)

        fun <T> estimated(value: T): Measure<T> = Known(value, MeasureSource.ESTIMATED)

        fun unknown(reason: String): Measure<Nothing> = Unknown(reason)
    }
}

/** Вычисление из наблюдения — уже не наблюдение: источник не сильнее `DERIVED`. */
inline fun <T, R> Measure<T>.map(f: (T) -> R): Measure<R> = when (this) {
    is Measure.Unknown -> this
    is Measure.Known -> Measure.Known(f(value), maxOf(source, MeasureSource.DERIVED))
}

/** Сочетание двух величин: хоть одна неизвестна — неизвестно (с обеими причинами). */
inline fun <A, B, R> Measure<A>.combine(other: Measure<B>, f: (A, B) -> R): Measure<R> = when {
    this is Measure.Known && other is Measure.Known ->
        Measure.Known(f(value, other.value), maxOf(source, other.source, MeasureSource.DERIVED))
    else -> Measure.Unknown(
        listOfNotNull((this as? Measure.Unknown)?.reason, (other as? Measure.Unknown)?.reason).joinToString("; "),
    )
}

/** Сумма: неизвестное слагаемое делает неизвестной всю сумму; пустая сумма — ноль наблюдённый. */
fun Iterable<Measure<Long>>.sum(): Measure<Long> =
    fold(Measure.observed(0L)) { acc, m -> acc.combine(m) { a, b -> a + b } }
