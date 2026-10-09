package com.dpsmpx.dpai.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

/** Коммит работы хода — без кешей, которые порождает проверка (проверка на Redmi Note 13, 9 октября 2026). */
class CommittableTest {
    @Test
    fun cachesAndArtifactsAreNotCommitted() {
        val files = listOf(
            "calc.py", "test_calc.py", "__pycache__/calc.cpython-313.pyc", "pkg/__pycache__/x.pyc", "old.pyc", ".pytest_cache/v/cache",
            "web/node_modules/a/index.js", "src/build.gradle.kts", ".DS_Store", "docs/.DS_Store", "build/out.txt",
        )
        assertEquals(listOf("calc.py", "test_calc.py", "src/build.gradle.kts", "build/out.txt"), Orchestrator.committable(files))
    }
}
