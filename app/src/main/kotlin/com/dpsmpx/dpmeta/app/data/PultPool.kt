package com.dpsmpx.dpmeta.app.data

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Пулы пульта — на процесс, а не на окно (D-59, D-65): окно закрыли,
 * повернули или не пустили без PIN, а синхронизация и долгое первое
 * клонирование идут дальше в фоне — и не обрываются прерыванием посреди
 * записи состояния. Клонам — свой небольшой пул: первое клонирование не
 * занимает потоки, на которых открываются файлы карточки (F-09).
 */
object PultPool {
    /** Запросы дат коммитов — пулом (D-50). */
    const val THREADS = 8

    /** Клонирование и обновление клонов — по два репозитория сразу: сеть телефона одна. */
    private const val CLONE_THREADS = 2

    val work: ExecutorService by lazy { Executors.newFixedThreadPool(THREADS) }

    val clones: ExecutorService by lazy { Executors.newFixedThreadPool(CLONE_THREADS) }
}
