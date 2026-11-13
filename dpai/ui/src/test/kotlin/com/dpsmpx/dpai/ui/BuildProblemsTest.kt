package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Образцы вывода — синтетические, в формате инструментов; не копии чужих журналов. */
class BuildProblemsTest {
    private fun p(path: String, line: Int, col: Int?, sev: ProblemSeverity, msg: String) = BuildProblem(path, line, col, sev, msg)

    @Test
    fun clangAndGccWithColorsNotesAndLinkerNoise() {
        val esc = "\u001b"
        val out = """
            In file included from src/main.cpp:2:
            $esc[1minclude/shape.hpp:7:12: $esc[0;1;31merror: $esc[0munknown type name 'Vec3'$esc[0m
                7 |     Vec3 center;
                  |     ^
            src/main.cpp:14:5: warning: unused variable 'total' [-Wunused-variable]
            src/main.cpp: In function 'int main()':
            /root/projects/local/galdhold/src/world.cpp:30: error: expected ';' before '}' token
            /usr/include/c++/14/bits/stl_vector.h:1120:7: note: candidate: 'void push_back(const value_type&)'
            /usr/bin/ld: main.o: in function `main':
            main.cpp:(.text+0x1a): undefined reference to `foo()'
            ld.lld: error: undefined symbol: foo()
            clang++: error: linker command failed with exit code 1 (use -v to see invocation)
            collect2: error: ld returned 1 exit status
            make: *** [Makefile:12: all] Error 1
            g++: fatal error: no input files
            src/main.cpp:1:10: fatal error: 'missing.h' file not found
        """.trimIndent().replace("\n", "\r\n")
        val r = BuildProblems.parse(out)
        assertEquals(
            listOf(
                p("include/shape.hpp", 7, 12, ProblemSeverity.ERROR, "unknown type name 'Vec3'"),
                p("src/main.cpp", 14, 5, ProblemSeverity.WARNING, "unused variable 'total' [-Wunused-variable]"),
                p("/root/projects/local/galdhold/src/world.cpp", 30, null, ProblemSeverity.ERROR, "expected ';' before '}' token"),
                p("/usr/include/c++/14/bits/stl_vector.h", 1120, 7, ProblemSeverity.NOTE, "candidate: 'void push_back(const value_type&)'"),
                p("src/main.cpp", 1, 10, ProblemSeverity.ERROR, "'missing.h' file not found"),
            ),
            r.items,
        )
        assertEquals("ошибок: 3 · предупреждений: 1", r.summary())
    }

    @Test
    fun kotlinGradleAndJava() {
        val out = """
            e: file:///root/projects/me/app/src/main/kotlin/My%20App.kt:12:5 Unresolved reference 'foo'.
            w: file:///root/projects/me/app/build.gradle.kts:3:1 'kotlinOptions' is deprecated.
            e: /p/Old.kt: (4, 9): Type mismatch: inferred type is String but Int was expected
            main.kt:3:13: error: unresolved reference 'prinln'.
            src/Main.java:5: error: cannot find symbol
            > Task :app:compileKotlin FAILED
            FAILURE: Build failed with an exception.
        """.trimIndent()
        assertEquals(
            listOf(
                p("/root/projects/me/app/src/main/kotlin/My App.kt", 12, 5, ProblemSeverity.ERROR, "Unresolved reference 'foo'."),
                p("/root/projects/me/app/build.gradle.kts", 3, 1, ProblemSeverity.WARNING, "'kotlinOptions' is deprecated."),
                p("/p/Old.kt", 4, 9, ProblemSeverity.ERROR, "Type mismatch: inferred type is String but Int was expected"),
                p("main.kt", 3, 13, ProblemSeverity.ERROR, "unresolved reference 'prinln'."),
                p("src/Main.java", 5, null, ProblemSeverity.ERROR, "cannot find symbol"),
            ),
            BuildProblems.parse(out).items,
        )
    }

    @Test
    fun pythonTracebacksLintersAndPytest() {
        val out = """
            Traceback (most recent call last):
              File "/root/projects/me/tool/run.py", line 40, in <module>
                main()
              File "<frozen runpy>", line 88, in _run_code
              File "/root/projects/me/tool/core.py", line 12, in main
                value = parse(text)
                        ^^^^^^^^^^^
              File "/root/projects/me/tool/.venv/lib/python3.12/site-packages/lib.py", line 7, in parse
                raise ValueError("bad")
            ValueError: bad input
              File "/root/projects/me/tool/broken.py", line 3
                print(
                     ^
            SyntaxError: '(' was never closed
            core.py:12: error: Incompatible types in assignment  [assignment]
            core.py:1:8: F401 [*] `os` imported but unused
            ./core.py:2:1: E302 expected 2 blank lines, found 1
            tests/test_core.py:21: AssertionError
            core.py:5:1: just a line that is not a finding
        """.trimIndent()
        val r = BuildProblems.parse(out).items
        assertEquals(
            listOf(
                p("/root/projects/me/tool/.venv/lib/python3.12/site-packages/lib.py", 7, null, ProblemSeverity.ERROR, "ValueError: bad input"),
                p("/root/projects/me/tool/core.py", 12, null, ProblemSeverity.NOTE, "вызвано отсюда: ValueError: bad input"),
                p("/root/projects/me/tool/run.py", 40, null, ProblemSeverity.NOTE, "вызвано отсюда: ValueError: bad input"),
                p("/root/projects/me/tool/broken.py", 3, null, ProblemSeverity.ERROR, "SyntaxError: '(' was never closed"),
                p("core.py", 12, null, ProblemSeverity.ERROR, "Incompatible types in assignment  [assignment]"),
                p("core.py", 1, 8, ProblemSeverity.WARNING, "F401 [*] `os` imported but unused"),
                p("./core.py", 2, 1, ProblemSeverity.WARNING, "E302 expected 2 blank lines, found 1"),
                p("tests/test_core.py", 21, null, ProblemSeverity.ERROR, "AssertionError"),
            ),
            r,
        )
    }

    @Test
    fun msvcCmakeAndRust() {
        val out = """
            C:\Users\me\src\game\main.cpp(42,17): error C2065: 'player': undeclared identifier
            src\render.cpp(7): warning C4244: conversion from 'double' to 'float'
            CMake Error at CMakeLists.txt:12 (add_executable):
              Cannot find source file:
            CMake Warning (dev) at cmake/deps.cmake:3 (find_package):
            error[E0425]: cannot find value `x` in this scope
             --> src/main.rs:2:5
            warning: unused variable: `y`
              --> src/lib.rs:10:9
            error: aborting due to 1 previous error
        """.trimIndent()
        assertEquals(
            listOf(
                p("C:\\Users\\me\\src\\game\\main.cpp", 42, 17, ProblemSeverity.ERROR, "C2065: 'player': undeclared identifier"),
                p("src\\render.cpp", 7, null, ProblemSeverity.WARNING, "C4244: conversion from 'double' to 'float'"),
                p("CMakeLists.txt", 12, null, ProblemSeverity.ERROR, "add_executable: Cannot find source file:"),
                p("cmake/deps.cmake", 3, null, ProblemSeverity.WARNING, "find_package:"),
                p("src/main.rs", 2, 5, ProblemSeverity.ERROR, "cannot find value `x` in this scope"),
                p("src/lib.rs", 10, 9, ProblemSeverity.WARNING, "unused variable: `y`"),
            ),
            BuildProblems.parse(out).items,
        )
    }

    @Test
    fun noiseIsNotAFinding() {
        val out = """
            12:30:45 INFO started
            https://example.com/docs.html:80: see the guide
            Version 1.2.3 build ok
            [Код выхода: 1]
            Error: something failed
            ERROR in ./src/app.ts
        """.trimIndent()
        assertEquals(emptyList(), BuildProblems.parse(out).items)
        assertEquals("", BuildProblems.parse("").summary())
    }

    @Test
    fun duplicatesCollapseAndLimitIsReported() {
        val line = "a.cpp:1:1: error: x"
        assertEquals(1, BuildProblems.parse("$line\n$line\n").items.size)
        val many = (1..20).joinToString("\n") { "a.cpp:$it:1: error: x" }
        val r = BuildProblems.parse(many, limit = 5)
        assertEquals(5, r.items.size)
        assertTrue(r.limited)
        assertEquals("ошибок: 5 · показаны первые 5", r.summary())
    }

    @Test
    fun relativePathsResolveInsideRootOnly() {
        val root = "/root/projects/me/galdhold"
        assertEquals("src/main.cpp", BuildProblems.relative("/root/projects/me/galdhold/src/main.cpp", root))
        assertEquals("src/main.cpp", BuildProblems.relative("src/main.cpp", root))
        assertEquals("src/main.cpp", BuildProblems.relative("./src/../src/./main.cpp", root))
        assertEquals("include/a.h", BuildProblems.relative("../include/a.h", root, cwd = "$root/build"))
        assertEquals("My App.kt", BuildProblems.relative("file:///root/projects/me/galdhold/My%20App.kt", root))
        assertEquals("main.cpp", BuildProblems.relative("main.cpp", "$root/"))
        assertNull(BuildProblems.relative("/usr/include/stdio.h", root))
        assertNull(BuildProblems.relative("/root/projects/me/galdhold-old/a.cpp", root), "соседняя папка с общим началом имени — не внутри")
        assertNull(BuildProblems.relative("../../etc/passwd", root))
        assertNull(BuildProblems.relative(root, root))
        // Windows: обе косые черты, регистр диска и папок не важен.
        assertEquals("src/main.cpp", BuildProblems.relative("c:\\users\\me\\game\\src\\main.cpp", "C:\\Users\\Me\\Game"))
        assertEquals("src/render.cpp", BuildProblems.relative("src\\render.cpp", "C:/Users/Me/Game"))
    }
}
