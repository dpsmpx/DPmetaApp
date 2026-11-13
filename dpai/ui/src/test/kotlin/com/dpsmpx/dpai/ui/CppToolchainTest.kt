package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CppToolchainTest {
    @Test fun probeUsesBusyboxCompatibleTemporaryNamesAndExplicitCppLanguage() {
        val script = CppToolchain.probeCommand.last()
        val templates = Regex("mktemp ([^\\s)]+)").findAll(script).map { it.groupValues[1].trim('"') }.toList()
        assertEquals(3, templates.size)
        assertTrue(templates.all { it.endsWith("XXXXXX") }, "BusyBox требует XXXXXX в конце: $templates")
        assertTrue(script.contains("clang++ -x c++"))
        assertTrue(script.contains("DPAI_CPP_OK"))
    }
}
