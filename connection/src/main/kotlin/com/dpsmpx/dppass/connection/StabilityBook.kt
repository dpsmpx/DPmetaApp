package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.storage.BlobStore
import com.dpsmpx.dppass.storage.Quarantine

/**
 * Хранение адаптивной модели (D-44): агрегаты и веса обобщения в
 * зашифрованном хранилище (D-19), запись `adaptive/state` версии 1.
 * Переживает перезапуск процесса. Повреждение — карантин (D-06), не
 * затирает: модель начинается с пустой.
 *
 * Формат текстовый и построчный: заголовок с версией, строка весов
 * [TransportAdvisor], по строке на запись «ключ|числа». В модель не
 * попадают ни домены, ни адреса, ни секреты, ни идентификаторы
 * устройства — только числа (D-12).
 */
class StabilityBook(
    private val blobs: BlobStore? = null,
    private val quarantine: Quarantine? = null,
) {
    /** Прочитать модель; её нет — пустая; повреждена — карантин и пустая. */
    fun load(): Outcome<StabilityModel> {
        val store = blobs ?: return Outcome.Success(StabilityModel())
        val bytes = when (val r = store.read(KEY)) {
            is Outcome.Failure -> return Outcome.Success(StabilityModel(), r.diagnostics)
            is Outcome.Success -> r.value ?: return Outcome.Success(StabilityModel())
        }
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        val parsed = text?.let(::parse)
        if (parsed != null) return Outcome.Success(parsed)
        // Не прочиталось — в карантин (D-06), модель с нуля.
        val reason = Diagnostic.warning(DiagCode.STORAGE_CORRUPTED, "модель устойчивости не прочитана; начинаю с пустой")
        val q = quarantine?.keep("adaptive", bytes, reason)
        return Outcome.Success(StabilityModel(), listOfNotNull((q as? Outcome.Success)?.let { reason }) + (q as? Outcome.Failure)?.diagnostics.orEmpty())
    }

    fun save(model: StabilityModel): Outcome<Unit> {
        val store = blobs ?: return Outcome.Success(Unit)
        val (records, weights) = model.snapshot()
        val body = buildString {
            append(HEADER).append('\n')
            append(weights.joinToString(",")).append('\n')
            for ((key, s) in records.toSortedMap()) {
                // Разделитель полей — табуляция: её нет ни в ключе (он на '|' и '~'),
                // ни в числах, поэтому поля не сталкиваются с содержимым ключа.
                if (SEP in key || '\n' in key) continue
                append(key).append(SEP)
                    .append(s.alpha).append(SEP).append(s.beta).append(SEP)
                    .append(s.srttMillis).append(SEP).append(s.rttVarMillis).append(SEP)
                    .append(s.jitterMillis).append(SEP).append(s.ewmaStall).append(SEP)
                    .append(s.lastRttMillis ?: -1.0).append(SEP)
                    .append(s.phCusum).append(SEP).append(s.phMin).append(SEP)
                    .append(s.badStreak).append(SEP).append(s.observations).append(SEP)
                    .append(s.lastUpdatedMillis).append('\n')
            }
        }
        return store.write(KEY, body.toByteArray(Charsets.UTF_8))
    }

    private fun parse(text: String): StabilityModel? {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != HEADER) return null
        val weights = if (lines.hasNext()) AdaptiveCodec.weights(lines.next()) else DoubleArray(TransportAdvisor.FEATURES)
        val records = LinkedHashMap<String, StabilityStats>()
        var n = 0
        for (line in lines) {
            if (line.isEmpty()) continue
            if (n++ >= StabilityModel.MAX_RECORDS) break
            val p = line.split(SEP)
            if (p.size != 13) continue
            records[p[0]] = StabilityStats(
                alpha = AdaptiveCodec.double(p[1], StabilityStats.PRIOR).coerceAtLeast(StabilityStats.PRIOR),
                beta = AdaptiveCodec.double(p[2], StabilityStats.PRIOR).coerceAtLeast(StabilityStats.PRIOR),
                srttMillis = AdaptiveCodec.double(p[3], 0.0).coerceIn(0.0, StabilityStats.MAX_RTT_MILLIS.toDouble()),
                rttVarMillis = AdaptiveCodec.double(p[4], 0.0).coerceIn(0.0, StabilityStats.MAX_RTT_MILLIS.toDouble()),
                jitterMillis = AdaptiveCodec.double(p[5], 0.0).coerceIn(0.0, StabilityStats.MAX_RTT_MILLIS.toDouble()),
                ewmaStall = AdaptiveCodec.double(p[6], 0.0).coerceIn(0.0, 1.0),
                lastRttMillis = AdaptiveCodec.double(p[7], -1.0).takeIf { it >= 0.0 },
                phCusum = AdaptiveCodec.double(p[8], 0.0),
                phMin = AdaptiveCodec.double(p[9], 0.0),
                badStreak = AdaptiveCodec.int(p[10], 0).coerceIn(0, StabilityStats.MAX_BAD_STREAK),
                observations = AdaptiveCodec.long(p[11], 0).coerceIn(0, StabilityStats.MAX_OBSERVATIONS),
                lastUpdatedMillis = AdaptiveCodec.long(p[12], 0),
            )
        }
        return StabilityModel(records, TransportAdvisor(weights))
    }

    companion object {
        const val KEY: String = "adaptive/state"
        const val HEADER: String = "dppass-adaptive-v1"
        const val SEP: String = "\t"
    }
}
