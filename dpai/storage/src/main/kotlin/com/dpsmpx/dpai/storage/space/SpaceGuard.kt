package com.dpsmpx.dpai.storage.space

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Защита места (`DPmetaApp:D-113`): любая загрузка, установка, клон и сборка
 * заранее оценивают размер и не начинаются, если после них приложение выйдет
 * за предел автора или на телефоне останется меньше [RESERVE] — жёсткий
 * запас, настройкой не меняется. Чистая функция: факты о месте собирает
 * оболочка.
 */
object SpaceGuard {
    /** Жёсткий запас свободного места телефона: 1 ГБ. */
    const val RESERVE: Long = 1L shl 30

    private const val MIB: Long = 1L shl 20

    /**
     * Что знает оболочка: [free] — свободно на томе данных приложения; [used] —
     * сколько занимает приложение целиком (`null` — неизвестно); [limit] — предел
     * автора в байтах, `0` — всё свободное место телефона.
     */
    data class Facts(val free: Long, val used: Long?, val limit: Long)

    /**
     * Вид работы и его осторожная оценка, когда размер заранее неизвестен:
     * пакеты Alpine и npm — по 256 МБ, клон — 512 МБ, сборка — 1 ГБ.
     */
    enum class Kind(val title: String, val estimate: Long) {
        DOWNLOAD("загрузка", 256 * MIB),
        PACKAGES("установка пакетов", 256 * MIB),
        SERVICE("установка службы", 512 * MIB),
        CLONE("клон", 512 * MIB),
        BUILD("сборка", 1L shl 30),
    }

    /**
     * Можно ли начать [kind] размером [need] байт (`null` — неизвестно: берётся
     * оценка вида). Отказ `STORAGE_SPACE_LOW` называет числа и что сделать.
     */
    fun check(f: Facts, kind: Kind, need: Long?, what: String): Outcome<Unit> {
        val size = (need ?: kind.estimate).coerceAtLeast(0)
        val guessed = if (need == null) " (оценка: размер заранее неизвестен)" else ""
        val freeAfter = f.free - size
        if (freeAfter < RESERVE) {
            return Outcome.failure(
                DiagCode.STORAGE_SPACE_LOW,
                "${kind.title} «$what» не начата: нужно ${size(size)}$guessed, свободно ${size(f.free)}; " +
                    "после неё осталось бы ${size(freeAfter.coerceAtLeast(0))} — меньше запаса ${size(RESERVE)}. Освободите место на телефоне",
                what,
            )
        }
        if (f.limit > 0) {
            val used = f.used ?: return Outcome.failure(
                DiagCode.STORAGE_SPACE_LOW,
                "${kind.title} «$what» не начата: не удалось узнать, сколько занимает приложение, а предел — ${size(f.limit)}",
                what,
            )
            if (used + size > f.limit) {
                return Outcome.failure(
                    DiagCode.STORAGE_SPACE_LOW,
                    "${kind.title} «$what» не начата: нужно ${size(size)}$guessed, приложение уже занимает ${size(used)} из предела ${size(f.limit)}. " +
                        "Поднимите предел в настройках хранилища или удалите лишнее",
                    what,
                )
            }
        }
        return Outcome.success(Unit)
    }

    /** Предел в байтах из настройки в гигабайтах: `0` и меньше — без предела. */
    fun limitOf(gigabytes: Int): Long = if (gigabytes <= 0) 0 else gigabytes.toLong() shl 30

    /** Размер для человека: МБ целыми, от ГБ — с десятой. */
    fun size(b: Long): String {
        val gib = 1L shl 30
        return if (b < gib) "${(b + MIB - 1) / MIB} МБ" else (b * 10 / gib).let { "${it / 10},${it % 10} ГБ" }
    }
}
