package com.dpsmpx.dppass.storage

import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Неизвлекаемый ключ-обёртка (на Android — ключ Keystore, `app/`).
 * Ключ данных хранилища хранится только обёрнутым им (D-19).
 *
 * Методы бросают исключения платформы — их переводит в [Outcome]
 * [WrappedKeyProvider]. Постоянный отказ (ключа больше нет, он
 * необратимо испорчен) обёртка сообщает [KeyLost]; всё прочее считается
 * временным: повторить позже.
 */
interface KeyWrapper {
    /** Есть ли ключ-обёртка. */
    fun exists(): Boolean

    /** Создать ключ-обёртку, если его нет. */
    fun create()

    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(sealed: ByteArray): ByteArray

    /** Ключ-обёртка утрачен или обёрнутый ключ необратимо испорчен — само не пройдёт. */
    class KeyLost(message: String, cause: Throwable? = null) : Exception(message, cause)
}

/**
 * Ключ данных хранилища — конвертом (D-19): 32 случайных байта, на диске
 * только обёрнутые [KeyWrapper]. Ожидаемые отказы — значения (D-22):
 *
 *  * `STORAGE_DATA_KEY_UNAVAILABLE` — временный отказ (Keystore занят,
 *    ошибка ввода-вывода): ничего не записано и не запомнено, следующий
 *    вызов пробует снова;
 *  * `STORAGE_DATA_KEY_LOST` — обёрнутый ключ есть, а развернуть его
 *    нечем (ключ-обёртка утрачен) или он испорчен; или обёрнутого ключа
 *    нет, а записи есть. Новый ключ молча не создаётся: он сделал бы
 *    сохранённое нечитаемым без следа. Выход — [StorageReset] действием
 *    пользователя.
 *
 * Обёрнутый ключ пишется через [BlobStore] — на устройстве
 * [FileBlobStore]: временный файл, `fsync`, атомарная замена; обрыв
 * питания не оставит пустой ключ. Ключ, который не удалось сохранить,
 * не возвращается: зашифрованное им после перезапуска было бы не
 * прочитать.
 */
class WrappedKeyProvider(
    private val store: BlobStore,
    private val wrapper: KeyWrapper,
    /** Есть ли уже зашифрованные записи (тогда новый ключ создавать нельзя). */
    private val dataPresent: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
) : KeyProvider {
    @Volatile
    private var cached: ByteArray? = null

    override fun key(): Outcome<ByteArray> {
        cached?.let { return Outcome.Success(it) }
        return synchronized(this) {
            cached?.let { Outcome.Success(it) } ?: load().also { r -> r.valueOrNull()?.let { cached = it } }
        }
    }

    /**
     * Отложить обёрнутый ключ в [lostPrefix] и забыть ключ: следующий
     * [key] создаст новый. Только из [StorageReset] — после того как
     * записи, зашифрованные прежним ключом, отложены.
     */
    internal fun reset(lostPrefix: String): Outcome<Unit> = synchronized(this) {
        val sealed = when (val r = store.read(KEY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (sealed != null) {
            store.write("$lostPrefix/$KEY", sealed).let { if (it is Outcome.Failure) return it }
            store.delete(KEY).let { if (it is Outcome.Failure) return it }
        }
        cached = null
        Outcome.Success(Unit)
    }

    private fun load(): Outcome<ByteArray> {
        val sealed = when (val r = store.read(KEY)) {
            is Outcome.Failure -> return unavailable("обёрнутый ключ данных не прочитан", r.diagnostics)
            is Outcome.Success -> r.value
        }
        return if (sealed == null) create() else unwrap(sealed)
    }

    private fun unwrap(sealed: ByteArray): Outcome<ByteArray> {
        val dek = try {
            if (!wrapper.exists()) return lost("ключа-обёртки (Keystore) больше нет — обёрнутый им ключ данных не развернуть")
            wrapper.unwrap(sealed)
        } catch (e: KeyWrapper.KeyLost) {
            return lost("ключ данных не развернуть: ${e.message}", e)
        } catch (e: AEADBadTagException) {
            return lost("обёрнутый ключ данных испорчен или обёрнут другим ключом Keystore", e)
        } catch (e: Exception) {
            return unavailable("ключ данных сейчас не развернуть", cause = e)
        }
        return if (dek.size == KEY_BYTES) Outcome.Success(dek) else lost("развёрнутый ключ данных — ${dek.size} байт вместо $KEY_BYTES")
    }

    private fun create(): Outcome<ByteArray> {
        if (dataPresent()) {
            return lost("обёрнутого ключа данных нет, а зашифрованные записи есть — новый ключ сделал бы их нечитаемыми")
        }
        val dek = ByteArray(KEY_BYTES).also(random::nextBytes)
        val sealed = try {
            wrapper.create()
            wrapper.wrap(dek)
        } catch (e: Exception) {
            return unavailable("ключ данных не создан", cause = e)
        }
        return when (val w = store.write(KEY, sealed)) {
            is Outcome.Failure -> unavailable("новый ключ данных не сохранён — не используется", w.diagnostics)
            is Outcome.Success -> Outcome.Success(dek)
        }
    }

    private fun unavailable(message: String, causes: List<Diagnostic> = emptyList(), cause: Throwable? = null): Outcome<ByteArray> {
        val d = cause?.let { Diagnostic.fromThrowable(DiagCode.STORAGE_DATA_KEY_UNAVAILABLE, message, it) }
            ?: Diagnostic.error(DiagCode.STORAGE_DATA_KEY_UNAVAILABLE, message)
        return Outcome.failure(d.copy(causes = d.causes + causes))
    }

    private fun lost(message: String, cause: Throwable? = null): Outcome<ByteArray> = Outcome.failure(
        cause?.let { Diagnostic.fromThrowable(DiagCode.STORAGE_DATA_KEY_LOST, message, it) }
            ?: Diagnostic.error(DiagCode.STORAGE_DATA_KEY_LOST, message),
    )

    companion object {
        /** Запись с обёрнутым ключом в своём [BlobStore]. */
        const val KEY = "dek.wrapped"
        private const val KEY_BYTES = 32
    }
}

/** Восстановление хранилища, которое не читается; подключает оболочка (на Android — `AppGraph`). */
fun interface StorageRecovery {
    /** Отложить всё сохранённое и начать с чистого; результат — куда отложено. */
    fun reset(): Outcome<String>
}

/**
 * Сброс хранилища без потери (D-19, D-06): когда сохранённое не
 * прочитать — прежде всего при `STORAGE_DATA_KEY_LOST`, — всё сохранённое
 * переносится как есть в `lost/<время>/`, обёрнутый ключ — туда же в
 * своём хранилище, и следующий доступ создаёт новый ключ. Ничего не
 * удаляется: исходники конфигураций и подписок — у пользователя, а
 * отложенное остаётся на случай, если ключ найдётся. Только действием
 * пользователя: молча хранилище не сбрасывается никогда.
 *
 * Сначала переносятся записи, потом ключ: обрыв посередине оставляет
 * часть записей на месте при прежнем ключе, повторный сброс доделывает.
 */
class StorageReset(
    /** Записи как они лежат на диске — без шифрования поверх (под [EncryptingBlobStore]). */
    private val data: BlobStore,
    private val keys: WrappedKeyProvider,
    private val clock: Clock,
) : StorageRecovery {
    override fun reset(): Outcome<String> {
        val prefix = "$LOST/${clock.nowMillis()}"
        val items = when (val k = data.keys("")) {
            is Outcome.Failure -> return k
            is Outcome.Success -> k.value.filter { !it.startsWith("$LOST/") }
        }
        for (key in items) {
            val bytes = when (val r = data.read(key)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value ?: continue
            }
            data.write("$prefix/$key", bytes).let { if (it is Outcome.Failure) return it }
            data.delete(key).let { if (it is Outcome.Failure) return it }
        }
        return when (val r = keys.reset(prefix)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.Success(prefix)
        }
    }

    companion object {
        /** Куда откладывается сброшенное (вне чтения ядром). */
        const val LOST = "lost"

        /** Есть ли в [data] записи, кроме отложенных; не прочитать — считать, что есть. */
        fun hasData(data: BlobStore): Boolean =
            data.keys("").valueOrNull()?.any { !it.startsWith("$LOST/") } ?: true
    }
}
