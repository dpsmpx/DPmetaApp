package com.dpsmpx.dpai.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Долгие операции «AI» (AI:A-14): повтор не запускается, итог остаётся вне экрана. */
class LongJobsTest {
    @Test
    fun secondStartIsRefusedWhileRunningAndResultStays() {
        val gate = CompletableDeferred<Unit>()
        val logged = mutableListOf<String>()
        val jobs = LongJobs(CoroutineScope(Dispatchers.Unconfined)) { label, done -> logged += "$label — ${done.note}" }
        assertTrue(jobs.start("apk", "Поставить") { gate.await(); LongJobs.Done("Поставить: готово", listOf("OK")) })
        assertTrue(jobs.busy("apk"))
        assertFalse(jobs.start("apk", "Поставить") { LongJobs.Done("второй") }, "вторая установка параллельно не идёт")
        gate.complete(Unit)
        assertEquals(LongJobs.Job("Поставить", running = false, note = "Поставить: готово", output = listOf("OK")), jobs.state.value["apk"])
        assertEquals(listOf("Поставить — Поставить: готово"), logged)
        jobs.dismiss("apk")
        assertEquals(null, jobs.state.value["apk"])
    }

    @Test
    fun guestProcessesRunOneAtATime() {
        val gate = CompletableDeferred<Unit>()
        val jobs = LongJobs(CoroutineScope(Dispatchers.Unconfined))
        jobs.start(LongJobs.LINUX_APK, "Поставить", excludes = LongJobs.GUEST) { gate.await(); LongJobs.Done("готово") }
        assertFalse(jobs.start(LongJobs.LLAMA, "Установка llama.cpp", excludes = LongJobs.GUEST) { LongJobs.Done("готово") }, "apk гостя — один процесс за раз")
        gate.complete(Unit)
        assertTrue(jobs.start(LongJobs.LLAMA, "Установка llama.cpp", excludes = LongJobs.GUEST) { LongJobs.Done("готово") })
    }

    /** Неудача — с выводом в журнал: по строке итога «код 159» причину не найти (D-115). */
    @Test
    fun failedJobHandsItsOutputToTheJournal() {
        val logged = mutableListOf<LongJobs.Done>()
        val jobs = LongJobs(CoroutineScope(Dispatchers.Unconfined)) { _, done -> logged += done }
        jobs.start("apk", "Поставить Claude Code") { LongJobs.Done("код 159", listOf("Bad system call"), failed = true) }
        jobs.start("llama", "Поставить llama.cpp") { error("нет места") }
        assertEquals(listOf(true to listOf("Bad system call"), true to emptyList()), logged.map { it.failed to it.output })
    }

    @Test
    fun failureBecomesANoteNotACrash() {
        val jobs = LongJobs(CoroutineScope(Dispatchers.Unconfined))
        jobs.start("llama", "Поставить llama.cpp") { error("нет места") }
        val job = jobs.state.value.getValue("llama")
        assertFalse(job.running)
        assertTrue(job.note!!.startsWith("Поставить llama.cpp: "))
    }
}
