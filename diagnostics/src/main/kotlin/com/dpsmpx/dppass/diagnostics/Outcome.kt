package com.dpsmpx.dppass.diagnostics

/**
 * Результат шага: значение с предупреждениями или отказ с причинами.
 *
 * Исключения не используются для ожидаемых отказов (плохой ввод, сеть,
 * ядро): каждый шаг конвейера возвращает [Outcome], и диагностика
 * накапливается от шага к шагу, ничего не теряя.
 */
sealed interface Outcome<out T> {
    val diagnostics: List<Diagnostic>

    data class Success<T>(val value: T, override val diagnostics: List<Diagnostic> = emptyList()) : Outcome<T>

    data class Failure(override val diagnostics: List<Diagnostic>) : Outcome<Nothing> {
        init {
            require(diagnostics.any { it.isError }) { "Failure без ошибки — это Success с предупреждениями" }
        }
    }

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success<T>)?.value

    val errors: List<Diagnostic> get() = diagnostics.filter { it.isError }

    companion object {
        fun failure(d: Diagnostic): Failure = Failure(listOf(d))

        fun failure(code: DiagCode, message: String, path: String? = null): Failure =
            Failure(listOf(Diagnostic.error(code, message, path)))
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

fun <T> Outcome<T>.withDiagnostics(extra: List<Diagnostic>): Outcome<T> = when (this) {
    is Outcome.Failure -> Outcome.Failure(diagnostics + extra)
    is Outcome.Success -> if (extra.any { it.isError }) Outcome.Failure(diagnostics + extra) else Outcome.Success(value, diagnostics + extra)
}
