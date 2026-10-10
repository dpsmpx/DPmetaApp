package com.dpsmpx.dpai.core.result

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Severity

/**
 * Результат шага: значение с предупреждениями или отказ с ошибками (D-05, D-06).
 *
 * Ожидаемые отказы (плохой ввод, сеть, недоступный target, недопустимый
 * переход) — значения, а не исключения: каждый шаг возвращает [Outcome], и
 * находки шагов складываются, ничего не теряя. Противоречие «успех с
 * ошибкой» и «отказ без ошибки» невыразимо: конструкторы его отвергают.
 */
sealed interface Outcome<out T> {
    val diagnostics: List<Diagnostic>

    class Success<out T>(val value: T, override val diagnostics: List<Diagnostic> = emptyList()) : Outcome<T> {
        init {
            require(diagnostics.none { it.isError }) { "Success с ошибкой — это Failure" }
        }

        override fun equals(other: Any?): Boolean =
            other is Success<*> && value == other.value && diagnostics == other.diagnostics

        override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + diagnostics.hashCode()

        override fun toString(): String = "Success($value, $diagnostics)"
    }

    class Failure(override val diagnostics: List<Diagnostic>) : Outcome<Nothing> {
        init {
            require(diagnostics.any { it.isError }) { "Failure без ошибки — это Success с предупреждениями" }
        }

        override fun equals(other: Any?): Boolean = other is Failure && diagnostics == other.diagnostics

        override fun hashCode(): Int = diagnostics.hashCode()

        override fun toString(): String = "Failure($diagnostics)"
    }

    val isSuccess: Boolean get() = this is Success

    val errors: List<Diagnostic> get() = diagnostics.filter { it.isError }

    val warnings: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.WARNING }

    fun valueOrNull(): T? = (this as? Success<T>)?.value

    companion object {
        fun <T> success(value: T, diagnostics: List<Diagnostic> = emptyList()): Outcome<T> = Success(value, diagnostics)

        fun failure(d: Diagnostic): Failure = Failure(listOf(d))

        fun failure(code: DiagCode, message: String, subject: String? = null): Failure =
            Failure(listOf(Diagnostic.error(code, message, subject)))
    }
}

/** Следующий шаг получает значение; находки обоих шагов складываются. */
inline fun <T, R> Outcome<T>.then(next: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Failure -> this
    is Outcome.Success -> when (val r = next(value)) {
        is Outcome.Success -> Outcome.Success(r.value, diagnostics + r.diagnostics)
        is Outcome.Failure -> Outcome.Failure(diagnostics + r.diagnostics)
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Failure -> this
    is Outcome.Success -> Outcome.Success(transform(value), diagnostics)
}

/** Добавить находки; ошибка среди них превращает успех в отказ. */
fun <T> Outcome<T>.withDiagnostics(extra: List<Diagnostic>): Outcome<T> = when (this) {
    is Outcome.Failure -> Outcome.Failure(diagnostics + extra)
    is Outcome.Success ->
        if (extra.any { it.isError }) Outcome.Failure(diagnostics + extra) else Outcome.Success(value, diagnostics + extra)
}

/**
 * Отказ шага верхнего уровня с причинами нижнего (ТЗ, §5.1): ошибки
 * становятся причинами новой диагностики, предупреждения остаются рядом.
 */
fun <T> Outcome<T>.wrapFailure(code: DiagCode, message: String, subject: String? = null): Outcome<T> = when (this) {
    is Outcome.Success -> this
    is Outcome.Failure -> Outcome.Failure(
        diagnostics.filterNot { it.isError } +
            Diagnostic.of(code, message, subject, causes = errors),
    )
}

/** Все успешны — список значений; иначе отказ со всеми находками всех шагов. */
fun <T> List<Outcome<T>>.combine(): Outcome<List<T>> {
    val all = flatMap { it.diagnostics }
    return if (all.any { it.isError }) {
        Outcome.Failure(all)
    } else {
        Outcome.Success(map { (it as Outcome.Success<T>).value }, all)
    }
}
