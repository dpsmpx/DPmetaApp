package com.dpsmpx.dppass.diagnostics

/**
 * Секретное значение: UUID, пароль, закрытый ключ, адрес подписки.
 *
 * `toString()` никогда не показывает значение, поэтому секрет не утечёт
 * через журнал, сообщение исключения или `toString()` объемлющего
 * data-класса. Достать значение можно только явным [reveal] — его вызовы
 * разрешены в немногих файлах (`tools/security/check_forbidden_apis.py`):
 * компилятор под ядро, хранилище, разбор и запись конфигурации.
 */
class Secret(private val value: String) {
    fun reveal(): String = value

    val isEmpty: Boolean get() = value.isEmpty()

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val REDACTED: String = "<redacted>"
    }
}
