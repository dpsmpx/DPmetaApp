package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlpinePackageStateTest {
    @Test fun versionedCompilerAndLinkerSatisfyTheirVirtualPackageNames() {
        val names = AlpinePackageState.names(sequenceOf("P:clang22", "V:22.1.3-r2", "p:clang=22.1.3-r2 cmd:clang++=22.1.3-r2", "P:lld22", "p:lld=22.1.3-r0"))
        assertEquals(setOf("clang22", "clang", "lld22", "lld"), names)
        assertFalse("cmd:clang++" in names)
    }
    @Test fun absentPackagesAreNotInferredFromDescriptionsOrDependencies() {
        val names = AlpinePackageState.names(sequenceOf("P:example", "D:clang", "T:lld compiler-rt", "p:alias=1.0-r0"))
        assertTrue("example" in names && "alias" in names)
        assertFalse("clang" in names || "lld" in names)
    }
}
