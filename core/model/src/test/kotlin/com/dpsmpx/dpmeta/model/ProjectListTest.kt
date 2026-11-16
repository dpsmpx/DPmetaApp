package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Список проектов пульта (D-176): синтетические репозитории, без имён проектов автора. */
class ProjectListTest {
    private fun ref(s: String) = RepoRef.parse(s).valueOrNull()!!

    private fun list(vararg repos: String): ProjectList =
        repos.fold(ProjectList()) { l, r -> (l.add(ref(r)) as Outcome.Success).value }

    @Test
    fun addKeepsOrderAndIgnoresRepeatsWithoutCase() {
        val l = list("alice/app", "bob/lib", "Alice/App")
        assertEquals(listOf("alice/app", "bob/lib"), l.projects.map { it.repo.toString() })
        assertTrue(l.contains(ref("BOB/LIB")))
    }

    @Test
    fun removeAndMoveStayInBounds() {
        val l = list("a/one", "a/two", "a/three")
        assertEquals(listOf("a/three", "a/one", "a/two"), l.move(ref("a/three"), -5).projects.map { it.repo.toString() })
        assertEquals(listOf("a/two", "a/three", "a/one"), l.move(ref("a/one"), 9).projects.map { it.repo.toString() })
        assertEquals(listOf("a/one", "a/three"), l.remove(ref("A/Two")).projects.map { it.repo.toString() })
        assertEquals(l, l.move(ref("a/none"), 1))
    }

    @Test
    fun registryNamesAreRepoNamesUnlessTheyClash() {
        val r = list("alice/app", "bob/app", "bob/lib").toRegistry()
        assertEquals(listOf("alice/app", "bob/app", "lib"), r.projects.map { it.name })
        assertTrue(r.projects.all { it.place == Place.USER && it.dir == null && it.decisions == null })
        assertEquals(r.projects, r.shown)
    }

    @Test
    fun jsonRoundTripKeepsDocsAndChecklists() {
        val l = ProjectList(listOf(UserProject(ref("alice/app"), listOf("README.md", "docs/plan.md"), listOf("TODO.md")), UserProject(ref("bob/lib"))))
        val back = ProjectList.parse(JsonParser().parse(CanonicalJson.write(l.toJson())).valueOrNull()!!)
        assertEquals(l, back.valueOrNull())
    }

    @Test
    fun badInputIsRefused() {
        fun parse(text: String) = ProjectList.parse(JsonParser().parse(text).valueOrNull()!!)
        val head = "\"format\":\"dpmeta.projects\",\"version\":1"
        assertIs<Outcome.Success<*>>(parse("{$head,\"projects\":[]}"))
        for (bad in listOf(
            "{$head,\"projects\":[{\"repo\":\"not-a-repo\"}]}",
            "{$head,\"projects\":[{\"repo\":\"a/b\"},{\"repo\":\"A/B\"}]}",
            "{$head,\"projects\":[{\"repo\":\"a/b\",\"docs\":[\"../secret\"]}]}",
            "{$head,\"projects\":[{\"repo\":\"a/b\",\"docs\":[\"/etc/passwd\"]}]}",
            "{$head,\"projects\":[{\"repo\":\"a/b\",\"checklists\":\"TODO.md\"}]}",
            "{$head,\"projects\":[{\"repo\":\"a/b\",\"extra\":1}]}",
            "{\"format\":\"dpmeta.projects\",\"version\":9,\"projects\":[]}",
        )) {
            assertIs<Outcome.Failure>(parse(bad), bad)
        }
    }

    @Test
    fun limitIsEnforced() {
        var l = ProjectList()
        repeat(ProjectList.MAX) { l = (l.add(ref("o/r$it")) as Outcome.Success).value }
        assertIs<Outcome.Failure>(l.add(ref("o/extra")))
    }

    @Test
    fun pathsAreRelativeInsideTheRepo() {
        for (good in listOf("README.md", "docs/a b.md", "ROADMAP/ROADMAP.md")) assertTrue(ProjectList.validPath(good), good)
        for (bad in listOf("", "/abs", "a/../b", "./a", "a//b", "a\\b", "a\nb")) assertTrue(!ProjectList.validPath(bad), bad)
    }
}
