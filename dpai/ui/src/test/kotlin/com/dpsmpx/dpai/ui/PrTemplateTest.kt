package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.git.FileChange
import com.dpsmpx.dpai.orchestrator.git.TaskDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Описание PR по шаблону без ИИ (D-189; `DPmetaApp:D-168`). */
class PrTemplateTest {
    @Test
    fun factsOfTaskInTemplateUnknownSaidSo() {
        val files = (1..22).map { FileChange("src/f$it.kt", it, 1) } + FileChange("img.png", null, null)
        val diff = TaskDiff("main", "abc", "def", files, listOf("new.txt"), "", false)
        val body = PrTemplate.body("tsk_1", "Сделать вход по PIN", "task/tsk_1", diff, listOf("Ревью безопасности: принято"))
        assertTrue(body.startsWith("Сделать вход по PIN\n\n## Сделано"), body)
        assertTrue("- Изменено файлов: 23 (+253 −22, двоичных 1); новых вне индекса: 1" in body, body)
        assertTrue("  - `src/f1.kt` (+1 −1)" in body && "  - …и ещё 3" in body, body)
        assertTrue("## Осталось" in body && "## Проверки\n\n- Ревью безопасности: принято" in body, body)
        assertTrue(body.endsWith("Задача tsk_1 контура «AI», ветка task/tsk_1."))
        // Изменения не прочитаны и проверок нет — сказано, а не придумано.
        val unknown = PrTemplate.body("tsk_2", "", "task/tsk_2", null)
        assertTrue(unknown.startsWith("## Сделано\n\n- изменения задачи не прочитаны"), unknown)
        assertTrue("итог проверок не известен приложению" in unknown)
        // Длинная цель — первые 1500 знаков; описание — не длиннее предела.
        val long = PrTemplate.body("t", "x".repeat(50_000), "b", null)
        assertEquals(PrTemplate.MAX_OBJECTIVE, long.count { it == 'x' })
        assertTrue(long.length <= PrTemplate.MAX_BODY)
    }
}
