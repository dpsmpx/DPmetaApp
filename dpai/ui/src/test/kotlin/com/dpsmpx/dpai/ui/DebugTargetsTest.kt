package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Цели отладки (D-159): файл C/C++ и Python, программа «Запуска» как argv без оболочки. */
class DebugTargetsTest {
    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    @Test
    fun filesOfCCppAndPython() {
        val cpp = ok(DebugTargets.forFile("/root/projects/local/game", "/root/projects/local/game/src/main.cpp"))
        assertEquals(DebugTargets.Engine.GDB, cpp.engine)
        assertEquals(listOf("gdb", "-i=dap", "-q"), cpp.adapter)
        assertTrue(cpp.program.startsWith("/tmp/dpai-debug/") && cpp.program.endsWith("-main"), cpp.program)
        val build = cpp.build!!
        assertTrue("clang++ -g -O0" in build[2] && "DPAI_CXX_TOOLCHAIN" in build[2], build[2])
        assertEquals(listOf("/root/projects/local/game", "/root/projects/local/game/src/main.cpp", cpp.program), build.takeLast(3), "путь — значением, не кодом оболочки")
        assertEquals("/root/projects/local/game/src", cpp.cwd)
        assertTrue("clang -g -O0" in ok(DebugTargets.forFile("/r", "/r/a.c")).build!![2])
        assertEquals(cpp.program, ok(DebugTargets.forFile("/root/projects/local/game", "/root/projects/local/game/src/main.cpp")).program, "повтор — та же программа")

        val py = ok(DebugTargets.forFile("/r", "/r/tools/t.py"))
        assertEquals(DebugTargets.Engine.DEBUGPY, py.engine)
        assertNull(py.build)
        assertTrue("-m debugpy.adapter" in py.adapter[2] && "DPAI_VENV" in py.adapter[2])
        assertEquals("/r/tools/t.py", py.program)

        assertEquals(DiagCode.UNSUPPORTED, assertIs<Outcome.Failure>(DebugTargets.forFile("/r", "/r/a.kt")).diagnostics.first().code)
        assertIs<Outcome.Failure>(DebugTargets.forFile("r", "/r/a.c"))
    }

    @Test
    fun runCommandIsOneProgramNotAShellScript() {
        val gdb = ok(DebugTargets.forCommand("/r", "/r/build", "./game --level 2 'имя с пробелом' \"a\\\"b\""))
        assertEquals(DebugTargets.Engine.GDB, gdb.engine)
        assertEquals("/r/build/game", gdb.program)
        assertEquals(listOf("--level", "2", "имя с пробелом", "a\"b"), gdb.args)
        assertEquals("/r/build", gdb.cwd)
        assertEquals("/r/bin/tool", ok(DebugTargets.forCommand("/r", "/r/build", "../bin/tool")).program)
        assertEquals("cmake", ok(DebugTargets.forCommand("/r", "/r", "cmake --version")).program, "имя без / — gdb ищет в PATH")

        val py = ok(DebugTargets.forCommand("/r", "/r", "python3 app/main.py --port 8000"))
        assertEquals(DebugTargets.Engine.DEBUGPY, py.engine)
        assertEquals("/r/app/main.py", py.program)
        assertEquals(listOf("--port", "8000"), py.args)

        for (bad in listOf("make && ./game", "./game | tee log", "./game > out", "./game; rm -rf /", "./game \$HOME", "echo `id`", "./game &", "A=1 ./game", "./game 'x", "   ")) {
            assertIs<Outcome.Failure>(DebugTargets.forCommand("/r", "/r", bad), bad)
        }
        assertEquals(listOf("a b", "c"), ok(DebugTargets.argv("a\\ b c")))
    }
}
