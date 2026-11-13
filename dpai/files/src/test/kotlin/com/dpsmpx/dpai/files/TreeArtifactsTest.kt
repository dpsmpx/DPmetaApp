package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeArtifactsTest {
    private fun ok(r: Outcome<TreeArtifacts.Found>) = (r as Outcome.Success).value

    @Test
    fun globsMatchWholePaths() {
        val apk = TreeArtifacts.pattern("**/*.apk")!!
        assertTrue(apk.matches("app.apk"))
        assertTrue(apk.matches("app/build/outputs/apk/debug/app-debug.apk"))
        assertTrue(!apk.matches("app.apk.idsig"))
        val bin = TreeArtifacts.pattern("build/galdhold")!!
        assertTrue(bin.matches("build/galdhold"))
        assertTrue(!bin.matches("x/build/galdhold"))
        val some = TreeArtifacts.pattern("out/**")!!
        assertTrue(some.matches("out/a") && some.matches("out/a/b.so"))
        assertTrue(TreeArtifacts.pattern("lib?.so")!!.matches("lib1.so"))
        assertTrue(TreeArtifacts.pattern("a+b(c).txt")!!.matches("a+b(c).txt"), "знаки выражений — буквально")
        for (bad in listOf("", "/abs/*.apk", "../x", "a//b", "a\\b", "./a")) assertNull(TreeArtifacts.pattern(bad), bad)
    }

    @Test
    fun patternsTextIsValidatedWhole() {
        assertEquals(listOf("**/*.apk", "build/app"), (TreeArtifacts.patterns("  **/*.apk\nbuild/app  **/*.apk ") as Outcome.Success).value)
        assertTrue(TreeArtifacts.patterns("**/*.apk ../x") is Outcome.Failure)
        assertTrue(TreeArtifacts.patterns((1..21).joinToString(" ") { "f$it" }) is Outcome.Failure)
    }

    @Test
    fun findsInBuildFoldersNewestFirstAndSkipsCaches() {
        val t = MemoryTree(
            mapOf(
                "app/build/outputs/apk/debug/app-debug.apk" to "a",
                "app/build/outputs/bundle/release/app.aab" to "b",
                ".gradle/caches/x.apk" to "c",
                "node_modules/p/y.apk" to "d",
                "README.md" to "e",
                "build/galdhold" to "f",
            ),
        )
        // MemoryTree даёт файлам растущее время: последний — новее.
        val apks = ok(TreeArtifacts.find(t, emptyList()))
        assertEquals(listOf("app/build/outputs/bundle/release/app.aab", "app/build/outputs/apk/debug/app-debug.apk"), apks.items.map { it.path })
        val bin = ok(TreeArtifacts.find(t, listOf("build/galdhold", "**/*.apk")))
        assertEquals(listOf("build/galdhold", "app/build/outputs/apk/debug/app-debug.apk"), bin.items.map { it.path })
        assertEquals(1, ok(TreeArtifacts.find(t, emptyList(), limit = 1)).items.size)
        assertTrue(ok(TreeArtifacts.find(t, emptyList(), limit = 1)).limited)
        assertTrue(TreeArtifacts.find(t, listOf("/abs")) is Outcome.Failure)
    }
}
