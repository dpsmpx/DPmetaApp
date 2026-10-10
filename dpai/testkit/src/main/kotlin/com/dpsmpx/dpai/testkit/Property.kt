package com.dpsmpx.dpai.testkit

import com.dpsmpx.dpai.core.clock.RandomSource

/**
 * Генератор случайных входов property-теста. Случайность — только из
 * [RandomSource] с зерном (D-07): упавший случай воспроизводится.
 */
fun interface Gen<out T> {
    fun next(random: RandomSource): T

    companion object {
        fun int(range: IntRange): Gen<Int> = Gen { r -> range.first + r.nextInt(range.last - range.first + 1) }

        fun long(range: LongRange): Gen<Long> = Gen { r ->
            val span = range.last - range.first + 1
            if (span <= 0) r.nextLong() else range.first + Math.floorMod(r.nextLong(), span)
        }

        fun bool(): Gen<Boolean> = Gen { r -> r.nextInt(2) == 1 }

        fun <T> constant(value: T): Gen<T> = Gen { value }

        fun <T> element(items: List<T>): Gen<T> {
            require(items.isNotEmpty()) { "пустой список" }
            return Gen { r -> items[r.nextInt(items.size)] }
        }

        fun <T> oneOf(vararg gens: Gen<T>): Gen<T> = Gen { r -> gens[r.nextInt(gens.size)].next(r) }

        fun string(alphabet: String, length: IntRange): Gen<String> = Gen { r ->
            val n = int(length).next(r)
            buildString { repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) } }
        }

        fun <T> list(gen: Gen<T>, size: IntRange): Gen<List<T>> = Gen { r -> List(int(size).next(r)) { gen.next(r) } }

        /** Перестановка Фишера — Йейтса. */
        fun <T> shuffled(items: List<T>): Gen<List<T>> = Gen { r ->
            val out = items.toMutableList()
            for (i in out.indices.reversed()) {
                val j = r.nextInt(i + 1)
                val t = out[i]
                out[i] = out[j]
                out[j] = t
            }
            out
        }
    }
}

fun <T, R> Gen<T>.map(f: (T) -> R): Gen<R> = Gen { r -> f(next(r)) }

fun <T, R> Gen<T>.flatMap(f: (T) -> Gen<R>): Gen<R> = Gen { r -> f(next(r)).next(r) }

/**
 * Property-тесты без сторонних библиотек (D-17; `docs/TESTING.md`, «Свойства»).
 *
 * Число итераций и зерно — системные свойства `dpai.property.iterations`
 * (200) и `dpai.property.seed` (фиксированное). Отказ сообщает зерно, номер
 * итерации и вход: этого хватает, чтобы повторить случай.
 */
object Property {
    const val DEFAULT_SEED: Long = 20_261_006L
    private const val DEFAULT_ITERATIONS = 200

    val iterations: Int = System.getProperty("dpai.property.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS
    val seed: Long = System.getProperty("dpai.property.seed")?.toLongOrNull() ?: DEFAULT_SEED

    fun <T> forAll(gen: Gen<T>, iterations: Int = Property.iterations, seed: Long = Property.seed, check: (T) -> Unit) {
        val random = RandomSource.seeded(seed)
        for (i in 1..iterations) {
            val input = gen.next(random)
            try {
                check(input)
            } catch (e: AssertionError) {
                throw AssertionError("свойство нарушено: зерно $seed, итерация $i из $iterations, вход: $input\n${e.message}", e)
            } catch (e: RuntimeException) {
                throw AssertionError("исключение: зерно $seed, итерация $i из $iterations, вход: $input\n$e", e)
            }
        }
    }
}
