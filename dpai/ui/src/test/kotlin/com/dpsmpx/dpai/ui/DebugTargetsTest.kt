package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Цели отладки (D-159, D-161): файл C/C++ и Python, APK по JDWP, программа «Запуска» как argv без оболочки. */
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

        // Kotlin и Java — по JDWP в своём APK (D-161): файл сам по себе не запускается.
        assertEquals(DebugTargets.Engine.JDWP, DebugTargets.engineOf("/r/a.kt"))
        assertEquals(DebugTargets.Engine.JDWP, DebugTargets.engineOf("/r/A.java"))
        val kt = assertIs<Outcome.Failure>(DebugTargets.forFile("/r", "/r/a.kt")).diagnostics.first()
        assertEquals(DiagCode.UNSUPPORTED, kt.code)
        assertTrue("Артефакты" in kt.message, kt.message)
        assertEquals(DiagCode.UNSUPPORTED, assertIs<Outcome.Failure>(DebugTargets.forFile("/r", "/r/a.rs")).diagnostics.first().code)
        assertIs<Outcome.Failure>(DebugTargets.forFile("r", "/r/a.c"))

        val apk = ok(DebugTargets.forApk("/root/projects/app", "com.example.app"))
        assertEquals(DebugTargets.Engine.JDWP, apk.engine)
        assertEquals("com.example.app", apk.program)
        assertEquals("/root/projects/app", apk.cwd)
        assertIs<Outcome.Failure>(DebugTargets.forApk("/root/projects/app", "app; reboot"))
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

    /** Цели на ПК-target (`DPmetaApp:D-129`; D-164): адаптер на самом ПК, пути ПК. */
    @Test
    fun pcTargetsPosixAndWindows() {
        fun ok(r: Outcome<DebugTargets.Target>) = assertIs<Outcome.Success<DebugTargets.Target>>(r, r.toString()).value
        // Linux-ПК: программа сборки — gdb в режиме DAP, относительный путь — от папки запуска.
        val gdb = ok(DebugTargets.forPcCommand("/home/u/proj", "/home/u/proj/build", "./app --size 3 'a b'", windows = false))
        assertEquals(DebugTargets.Engine.GDB, gdb.engine)
        assertEquals(DebugTargets.GDB_ADAPTER, gdb.adapter)
        assertEquals("/home/u/proj/build/app", gdb.program)
        assertEquals(listOf("--size", "3", "a b"), gdb.args)
        assertNull(gdb.build, "на ПК программа — та, что собрал профиль")
        // Python — тот же интерпретатор, что в команде, с его флагами.
        val py = ok(DebugTargets.forPcCommand("/home/u/proj", "/home/u/proj", ".venv/bin/python3 -X utf8 lib/run.py --fast", windows = false))
        assertEquals(DebugTargets.Engine.DEBUGPY, py.engine)
        assertEquals(listOf(".venv/bin/python3", "-X", "utf8", "-m", "debugpy.adapter"), py.adapter)
        assertEquals("/home/u/proj/lib/run.py", py.program)
        assertEquals(listOf("--fast"), py.args)
        // Windows: `py -3`, обратные косые — часть пути, путь SFTP /C:/… — к C:/….
        val win = ok(DebugTargets.forPcCommand("/C:/Users/u/proj", "C:\\Users\\u\\proj", "py -3 src\\main.py 7", windows = true))
        assertEquals(listOf("py", "-3", "-m", "debugpy.adapter"), win.adapter)
        assertEquals("C:/Users/u/proj/src/main.py", win.program)
        assertEquals("C:/Users/u/proj", win.cwd)
        assertEquals("D:/x/y.py", ok(DebugTargets.forPcCommand("C:/p", "C:/p", "python.exe D:/x/y.py", windows = true)).program)
        assertEquals("C:/abs/y.py", ok(DebugTargets.forPcCommand("C:/p", "C:/p", "python /abs/y.py", windows = true)).program)
        // Windows и не Python — отказ словами; модуль (-m) и код (-c) — не файл программы.
        val msvc = DebugTargets.forPcCommand("C:/p", "C:/p", "build\\app.exe", windows = true)
        assertTrue(msvc is Outcome.Failure && msvc.diagnostics.single().code == DiagCode.UNSUPPORTED && "Python" in msvc.diagnostics.single().message)
        assertEquals(DebugTargets.Engine.GDB, ok(DebugTargets.forPcCommand("/p", "/p", "python3 -m pkg.main x.py", windows = false)).engine)
        assertTrue(DebugTargets.forPcCommand("relative", "/p", "./app", windows = false) is Outcome.Failure)
        assertTrue(DebugTargets.forPcCommand("/p", "/p", "./app | tee log", windows = false) is Outcome.Failure)
    }

    @Test
    fun pcPathsAndDebuggerCheck() {
        assertEquals("C:/Users/u", DebugTargets.pcPath("/C:/Users/u/"))
        assertEquals("C:/", DebugTargets.pcPath("C:\\"))
        assertEquals("/", DebugTargets.pcPath("/"))
        assertNull(DebugTargets.pcPath("proj"))
        assertNull(DebugTargets.pcPath("/a\nb"))
        assertTrue(DebugTargets.samePcPath("c:\\Users\\u\\main.py", "C:/Users/u/main.py"))
        assertTrue(!DebugTargets.samePcPath("/home/U/a.py", "/home/u/a.py"), "на POSIX регистр важен")
        val gdb = DebugTargets.Target(DebugTargets.Engine.GDB, null, DebugTargets.GDB_ADAPTER, "/p/app", emptyList(), "/p", null)
        assertEquals(listOf("gdb", "--batch", "-ex", "python print('DPAI-GDB ' + gdb.VERSION)"), DebugTargets.pcCheck(gdb))
        assertEquals(Outcome.success("gdb 15.1"), DebugTargets.pcReady(gdb, listOf("DPAI-GDB 15.1")))
        val old = DebugTargets.pcReady(gdb, listOf("DPAI-GDB 12.1"))
        assertTrue(old is Outcome.Failure && "14" in old.diagnostics.single().message)
        val none = DebugTargets.pcReady(gdb, listOf("Python scripting is not supported in this copy of GDB."))
        assertTrue(none is Outcome.Failure && "gdb с Python" in none.diagnostics.single().message)
        val py = DebugTargets.Target(DebugTargets.Engine.DEBUGPY, null, listOf("py", "-3", "-m", "debugpy.adapter"), "C:/p/m.py", emptyList(), "C:/p", null)
        assertEquals(listOf("py", "-3", "-c", "import debugpy; print('DPAI-DEBUGPY ' + debugpy.__version__)"), DebugTargets.pcCheck(py))
        assertEquals(Outcome.success("debugpy 1.8.22"), DebugTargets.pcReady(py, listOf("", "DPAI-DEBUGPY 1.8.22")))
        val missing = DebugTargets.pcReady(py, listOf("ModuleNotFoundError: No module named 'debugpy'"))
        assertTrue(missing is Outcome.Failure && "py -3 -m pip install debugpy" in missing.diagnostics.single().message)
    }
}
