package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Параметры ручных профилей (D-183): встроенные, свои с вариантами, окружение, формат v3. */
class ManualProfileParamsTest {
    private val builtIns = mapOf("PROJECT" to "demo", "ROOT" to "/root/projects/demo", "TREE" to "/root/trees/t1", "BRANCH" to "main", "TARGET" to "Linux")

    private fun profile(params: String = "VARIANT | Вариант | debug, release", env: String = "OUT=\${TREE}/out-\${VARIANT}") =
        (ManualProfile.form(ManualProfileKind.BUILD, listOf(Triple("Сборка", "./gradlew assemble\${VARIANT} -Pp=\${PROJECT} '\$\${HOME}'", "600")), emptyList(), params, env) as Outcome.Success).value

    @Test
    fun resolvesBuiltInsChoicesAndEnv() {
        val p = profile()
        assertEquals(listOf(ManualParam("VARIANT", "Вариант", listOf("debug", "release"))), p.params)
        val first = (p.resolve(builtIns) as Outcome.Success).value
        assertEquals(listOf("./gradlew", "assembledebug", "-Pp=demo", "\${HOME}"), first.steps.single().command, "по умолчанию — первый вариант; \$\${ — сам знак")
        assertEquals(listOf("OUT" to "/root/trees/t1/out-debug"), first.env)
        assertTrue(first.params.isEmpty(), "в якорь — уже раскрытый профиль")
        val release = (p.resolve(builtIns + ("VARIANT" to "release")) as Outcome.Success).value
        assertEquals("assemblerelease", release.steps.single().command[1])
        val stranger = (p.resolve(builtIns + ("VARIANT" to "rm -rf")) as Outcome.Success).value
        assertEquals("assembledebug", stranger.steps.single().command[1], "значение не из вариантов — не берётся")
    }

    @Test
    fun formatV3RoundTripAndV2Unchanged() {
        val p = profile()
        assertEquals(com.dpsmpx.dpai.core.serialization.JsonNumber.of(3), p.toJson().fields["version"])
        assertEquals(p, ManualProfile.read(p.toJson()))
        val plain = (ManualProfile.form(ManualProfileKind.CHECK, "make test", "60") as Outcome.Success).value
        assertEquals(com.dpsmpx.dpai.core.serialization.JsonNumber.of(2), plain.toJson().fields["version"], "без параметров и окружения — прежний v2")
        // v2 с полями v3 — не принимается: смысл не меняется молча.
        val forged = com.dpsmpx.dpai.core.serialization.JsonObject(LinkedHashMap(p.toJson().fields).apply { put("version", com.dpsmpx.dpai.core.serialization.JsonNumber.of(2)) })
        assertNull(ManualProfile.read(forged))
    }

    @Test
    fun refusesUnknownSecretsAndBadRows() {
        fun failure(params: String = "", env: String = "", command: String = "make") =
            ManualProfile.form(ManualProfileKind.BUILD, listOf(Triple("Сборка", command, "60")), emptyList(), params, env) as? Outcome.Failure
        assertNotNull(failure(command = "make \${NOPE}"), "неизвестная подстановка")
        assertNotNull(failure(env = "GITHUB_TOKEN=abc"), "похоже на секрет")
        assertNotNull(failure(env = "MY_API_KEY=abc"), "похоже на секрет")
        assertNotNull(failure(env = "1BAD=x"), "имя не с цифры")
        assertNotNull(failure(params = "BRANCH | Ветка | a"), "встроенное имя занято")
        assertNotNull(failure(params = "MODE | Режим"), "нет вариантов")
        assertNotNull(failure(params = "MODE | Режим | a, a"), "одинаковые варианты")
        assertNull(failure(env = "JAVA_OPTS=-Xmx1g\n\nLANG=C.UTF-8"))
    }

    @Test
    fun builtInsFromProjectAndTree() {
        assertEquals(setOf("PROJECT", "TREE"), ManualVars.names("\${PROJECT}/\${TREE} \$\${HOME}"))
        assertEquals("x \${Y}", (ManualVars.substitute("x \$\${Y}", emptyMap()) as Outcome.Success).value)
    }

    @TempDir
    lateinit var dir: java.nio.file.Path

    @Test
    fun lastChoiceIsRememberedPerProjectAndKind() {
        val clock = com.dpsmpx.dpai.core.clock.ManualClock()
        val ids = com.dpsmpx.dpai.core.ids.IdGenerator(clock, com.dpsmpx.dpai.core.clock.RandomSource.seeded(183))
        val store = ManualProfiles(com.dpsmpx.dpai.storage.document.DocumentStore(com.dpsmpx.dpai.storage.vfs.NioVfs(dir), "profiles"), ids, clock)
        val target = TargetForm.inApp(ids.next(com.dpsmpx.dpai.core.ids.IdKinds.TARGET), "Linux", "/root/.claude-slot-1").valueOrNull()!!
        val project = ProjectEntry(ids.next(com.dpsmpx.dpai.core.ids.IdKinds.PROJECT), ids.next(com.dpsmpx.dpai.core.ids.IdKinds.REPOSITORY), target.id, "/root/repo", "/root/trees", "main", emptyList())
        assertEquals(emptyMap(), store.choices(project, ManualProfileKind.BUILD))
        assertTrue(store.saveChoices(project, ManualProfileKind.BUILD, mapOf("VARIANT" to "release", "bad name" to "x")) is Outcome.Success)
        assertEquals(mapOf("VARIANT" to "release"), store.choices(project, ManualProfileKind.BUILD))
        assertEquals(emptyMap(), store.choices(project, ManualProfileKind.CHECK), "у проверки — свой выбор")
    }
}
