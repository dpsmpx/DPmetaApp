package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.git.TaskChanges
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Действия задачи по состоянию (D-42): экран не предлагает того, что оркестратор отклонит. */
class TaskScreensTest {
    private val clock = ManualClock()
    private val id = IdGenerator(clock, RandomSource.seeded(3)).next(IdKinds.TASK)
    private fun task(state: TaskState, held: Boolean = false) =
        TaskRecord(id, "задача", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = state, held = held)

    @Test
    fun actionsFollowState() {
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.RUNNING)), "идущей — без правки политики")
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.RESUME, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY, held = true)))
        assertTrue(TaskAction.REPLY in TaskScreens.actions(task(TaskState.BLOCKED)) && TaskAction.RETRY in TaskScreens.actions(task(TaskState.BLOCKED)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.VERIFY, TaskAction.WAIVE, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.COMPLETION_UNVERIFIED)))
        assertEquals(listOf(TaskAction.POLICY, TaskAction.RETRY, TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.FAILED)))
        assertEquals(listOf(TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.DONE)))
        assertTrue(TaskAction.CANCEL.dangerous && TaskAction.WAIVE.dangerous && !TaskAction.HOLD.dangerous)
        assertEquals(id, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).id)
    }

    @Test
    fun changesKeepPartsAndKinds() {
        val committed = listOf(
            " calc.py | 3 ++-", " 1 file changed, 2 insertions(+), 1 deletion(-)", "",
            "diff --git a/calc.py b/calc.py", "index 1111111..2222222 100644", "--- a/calc.py", "+++ b/calc.py",
            "@@ -1,2 +1,3 @@", " def add(a, b):", "-    return a - b", "+    return a + b", "+++ строка, начатая с ++", "--- строка, начатая с --",
            "\\ No newline at end of file",
        )
        val c = TaskChanges("task/x", "main", committed, emptyList(), listOf("notes.txt"), truncated = true)
        val v = TaskChangesScreen.of(c, listOf("незакоммиченное: нет связи"))
        assertEquals("слияние в main: файлов 1 · новых файлов 1", v.summary)
        val kinds = v.lines.map { it.kind }
        assertEquals(DiffKind.SECTION, kinds.first())
        assertEquals(listOf(DiffKind.STAT, DiffKind.STAT, DiffKind.STAT, DiffKind.FILE, DiffKind.FILE, DiffKind.FILE, DiffKind.FILE), kinds.subList(1, 8))
        assertEquals(
            listOf(DiffKind.HUNK, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.REMOVED, DiffKind.NOTE),
            kinds.subList(8, 15),
            "после @@ строки +++ и --- — содержимое, а не имена файлов",
        )
        assertTrue(v.lines.none { it.text == "Не закоммичено в дереве задачи" }, "пустая часть — без заголовка")
        assertEquals(DiffLine(DiffKind.FILE, "notes.txt"), v.lines[v.lines.size - 3])
        assertTrue(v.lines.takeLast(2).all { it.kind == DiffKind.NOTE } && "обрезан" in v.lines[v.lines.size - 2].text)

        assertTrue(TaskChangesScreen.of(TaskChanges("task/x", "main", null, null, emptyList(), false)).summary.contains("не начиналась"))
        assertTrue(TaskChangesScreen.of(TaskChanges("task/x", "main", emptyList(), null, emptyList(), false)).summary.startsWith("Изменений нет"))
        val long = TaskChangesScreen.of(c, limit = 5)
        assertEquals(6, long.lines.size)
        assertTrue("dpai task <id> diff" in long.lines.last().text, "длинное — целиком в dpai")
    }
}
