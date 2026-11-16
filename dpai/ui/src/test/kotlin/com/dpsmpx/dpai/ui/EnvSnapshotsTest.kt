package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Снимки окружения и план отката (D-194). */
class EnvSnapshotsTest {
    private fun inv(vararg pkgs: Pair<String, String>, world: String, repos: String = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n") =
        AlpinePackages.parse(pkgs.joinToString("\n\n") { "P:${it.first}\nV:${it.second}" }, world, repos, "3.24.3")

    private val before = inv("busybox" to "1.37.0-r31", "bash" to "5.3.9-r1", "git" to "2.54.0-r0", world = "busybox bash git")
    private val vars = listOf(OwnVariables.Var("TZ", "UTC"))
    private val snap = EnvSnapshots.take("s1", 1_000, "apk upgrade bash", before, vars)

    @Test
    fun takeAndSameState() {
        assertEquals(mapOf("bash" to "5.3.9-r1", "busybox" to "1.37.0-r31", "git" to "2.54.0-r0"), snap.packages)
        assertEquals(listOf("busybox", "bash", "git"), snap.world)
        assertTrue(snap.sameState(EnvSnapshots.take("s2", 2_000, "другое", before, vars)))
        assertTrue(!snap.sameState(EnvSnapshots.take("s3", 2_000, "x", before, emptyList())))
    }

    @Test
    fun planNamesWhatComesBackAndWhatMayNot() {
        val after = inv("busybox" to "1.37.0-r31", "bash" to "5.3.9-r2", "git" to "2.54.1-r0", "tree" to "2.3.2-r0", world = "busybox bash git tree", repos = "https://dl-cdn.alpinelinux.org/alpine/edge/main\n")
        val cache = listOf(EnvSnapshots.cacheFile("bash-5.3.9-r1.2ce9606f.apk")!!, EnvSnapshots.cacheFile("git-init-template-2.54.0-r0.0a1b2c3d.apk")!!)
        val p = EnvSnapshots.plan(snap, after, emptyList(), cache)
        assertTrue(p.repositories && p.vars)
        assertEquals(listOf("tree"), p.worldRemove)
        assertEquals(listOf("tree"), p.extra)
        assertEquals(listOf(EnvSnapshots.VersionBack("bash", "5.3.9-r2", "5.3.9-r1", "bash-5.3.9-r1.2ce9606f.apk"), EnvSnapshots.VersionBack("git", "2.54.1-r0", "2.54.0-r0", null)), p.versions)
        val text = EnvSnapshots.planText(p)
        assertTrue("Версии из кеша (вернутся): bash 5.3.9-r1" in text, text)
        assertTrue("Только если зеркало ещё даёт эту версию (файла в кеше нет): git 2.54.0-r0" in text, text)
        assertTrue(EnvSnapshots.plan(snap, before, vars, emptyList()).empty)
    }

    @Test
    fun leftoverAfterRollback() {
        val partly = inv("busybox" to "1.37.0-r31", "bash" to "5.3.9-r1", "git" to "2.54.1-r0", world = "busybox bash git")
        assertEquals(listOf("git — 2.54.1-r0 вместо 2.54.0-r0"), EnvSnapshots.leftover(snap, partly, vars))
        assertEquals(emptyList(), EnvSnapshots.leftover(snap, before, vars))
    }

    @Test
    fun cacheNamesPruneAndFormat() {
        assertEquals(EnvSnapshots.CacheFile("git-init-template", "2.54.0-r0", "git-init-template-2.54.0-r0.0a1b2c3d.apk"), EnvSnapshots.cacheFile("git-init-template-2.54.0-r0.0a1b2c3d.apk"))
        assertNull(EnvSnapshots.cacheFile("APKINDEX.630cb808.tar.gz"))
        val many = (1..12).map { EnvSnapshots.take("s$it", it * 1000L, "x", before, vars) }
        val (keep, drop) = EnvSnapshots.prune(many)
        assertEquals(EnvSnapshots.KEEP, keep.size)
        assertEquals(listOf("s2", "s1"), drop.map { it.id })
        val cache = listOf(EnvSnapshots.cacheFile("bash-5.3.9-r1.2ce9606f.apk")!!, EnvSnapshots.cacheFile("bash-5.3.8-r0.11111111.apk")!!)
        assertEquals(listOf("bash-5.3.8-r0.11111111.apk"), EnvSnapshots.prunableCache(keep, null, cache).map { it.file })
        val text = EnvSnapshots.encode(snap)
        assertEquals(snap, EnvSnapshots.decode(text).valueOrNull())
        assertIs<Outcome.Failure>(EnvSnapshots.decode(text.replace("dpai.env-snapshot", "dpai.other")))
    }
}
