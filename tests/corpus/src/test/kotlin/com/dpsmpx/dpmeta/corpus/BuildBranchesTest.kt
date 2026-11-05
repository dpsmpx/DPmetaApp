package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpmeta.metrics.Rules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ветки сборок экосистемы (D-65, D-33): мимо клонов и веток проекта — все платформы и прежние по версиям. */
class BuildBranchesTest {
    @Test
    fun buildBranchesOfEveryPlatformAndOldVersionsAreBuilds() {
        for (b in listOf("apk_debug", "linux_debug", "windows_debug", "linux-debug", "apk-build", "apk-builds", "apk-0.1.0-dev.1-a1b2c3d", "windows-debug-build", "win_debug_builds")) {
            assertTrue(Rules.isBuildBranch(b), b)
        }
    }

    @Test
    fun workBranchesAreNotBuilds() {
        for (b in listOf("main", "ccr-d89d85c4-h08aww", "dpmeta/fix-links", "apk", "debug", "linux-debug-fix", "feature_debugger")) {
            assertFalse(Rules.isBuildBranch(b), b)
        }
    }
}
