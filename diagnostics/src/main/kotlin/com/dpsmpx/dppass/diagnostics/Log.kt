package com.dpsmpx.dppass.diagnostics

/**
 * Структурный журнал: событие — имя и поля, а не склеенная строка.
 *
 * Каждое строковое поле проходит [Redactor] **до** попадания в
 * приёмник; [Secret] превращается в метку; явно безопасное значение
 * оборачивается в [Safe] (константы, имена шагов, коды). Сырые строки
 * ядра и сети сюда приходят только через поля — и тоже редактируются.
 */
data class LogEvent(
    val timestampMillis: Long,
    val severity: Severity,
    val component: String,
    val event: String,
    val fields: Map<String, String>,
) {
    fun render(): String = buildString {
        append(timestampMillis).append(' ').append(severity.name).append(' ')
        append(component).append(' ').append(event)
        for ((k, v) in fields) append(' ').append(k).append('=').append(v)
    }
}

/** Значение, заведомо не содержащее секретов: не редактируется. */
@JvmInline
value class Safe(val value: String)

fun interface LogSink {
    fun emit(event: LogEvent)
}

/** Кольцевой буфер в памяти: источник для локального отчёта. */
class MemoryLogSink(private val capacity: Int = 512) : LogSink {
    private val buffer = ArrayDeque<LogEvent>()

    @Synchronized
    override fun emit(event: LogEvent) {
        if (buffer.size == capacity) buffer.removeFirst()
        buffer.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<LogEvent> = buffer.toList()
}

class Logger(
    private val component: String,
    private val sink: LogSink,
    private val nowMillis: () -> Long,
    private val redactor: Redactor = Redactor(),
) {
    fun info(event: String, vararg fields: Pair<String, Any?>) = log(Severity.INFO, event, fields)
    fun warning(event: String, vararg fields: Pair<String, Any?>) = log(Severity.WARNING, event, fields)
    fun error(event: String, vararg fields: Pair<String, Any?>) = log(Severity.ERROR, event, fields)

    fun diagnostic(d: Diagnostic) = log(
        d.severity,
        "diagnostic",
        arrayOf("code" to Safe(d.code.name), "path" to d.path, "message" to d.message),
    )

    private fun log(severity: Severity, event: String, fields: Array<out Pair<String, Any?>>) {
        val rendered = LinkedHashMap<String, String>(fields.size)
        for ((key, value) in fields) {
            rendered[key] = when (value) {
                null -> "null"
                is Secret -> Secret.REDACTED
                is Safe -> value.value
                is Number, is Boolean, is Enum<*> -> value.toString()
                else -> redactor.redact(value.toString())
            }
        }
        sink.emit(LogEvent(nowMillis(), severity, component, event, rendered))
    }
}
