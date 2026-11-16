package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Свои переменные окружения (D-193): не секреты, не переменные приложения и среды. */
class OwnVariablesTest {
    @Test
    fun allowsOrdinarySettings() {
        for ((n, v) in listOf("JAVA_OPTS" to "-Xmx1g", "GRADLE_OPTS" to "-Dorg.gradle.daemon=false", "NODE_OPTIONS" to "--max-old-space-size=2048", "PYTHONPATH" to "/root/projects/local/x/src", "TZ" to "Europe/Moscow", "CC" to "clang")) {
            assertNull(OwnVariables.problem(n, v), n)
        }
    }

    @Test
    fun refusesSecretsAndAppVariables() {
        // Образец ключа — из частей: в репозитории нет даже «тестовых» ключей (CLAUDE.md).
        val ghp = "gh" + "p_" + "A".repeat(36)
        val cases = listOf(
            "MY_TOKEN" to "x", "DB_PASSWORD" to "x", "SERVICE" to ghp, "GH_TOKEN" to "x", "ANTHROPIC_BASE_URL" to "x",
            "PATH" to "/x", "LD_PRELOAD" to "/x.so", "GIT_DIR" to "/x", "LANG" to "ru_RU.UTF-8", "LC_ALL" to "C", "DPAI_KEY" to "x",
            "DPMETA_X" to "x", "ANDROID_HOME" to "/x", "lower" to "x", "MULTI" to "a\nb",
        )
        for ((n, v) in cases) assertNotNull(OwnVariables.problem(n, v), n)
        // Окружение гостя задаёт приложение: ни одно его имя своей переменной не перекрыть.
        for (n in com.dpsmpx.dpai.execution.linux.ProotHost.GUEST_ENVIRONMENT.keys) assertNotNull(OwnVariables.problem(n, "x"), n)
    }

    @Test
    fun formatRoundTripAndDrift() {
        val vars = listOf(OwnVariables.Var("JAVA_OPTS", "-Xmx1g"), OwnVariables.Var("TZ", "UTC"))
        val text = OwnVariables.encode(vars)
        assertEquals("""{"format":"dpai.env-vars","vars":[{"name":"JAVA_OPTS","value":"-Xmx1g"},{"name":"TZ","value":"UTC"}],"version":1}""", text)
        assertEquals(vars, OwnVariables.decode(text).valueOrNull())
        // Строка, что больше не проходит проверку (правило ужесточили), — пропуск, а не отказ всему файлу.
        val drifted = text.replace("\"TZ\"", "\"GIT_DIR\"")
        assertEquals(listOf(vars[0]), OwnVariables.decode(drifted).valueOrNull())
        val other = OwnVariables.decode("""{"format":"dpai.other","version":1,"vars":[]}""")
        assertIs<Outcome.Failure>(other)
        assertEquals(DiagCode.FORMAT_MISMATCH, other.errors.single().code)
        assertIs<Outcome.Failure>(OwnVariables.decode("""{"format":"dpai.env-vars","version":2,"vars":[]}"""))
    }

    @Test
    fun checkAndMerge() {
        assertIs<Outcome.Failure>(OwnVariables.check(listOf(OwnVariables.Var("A", "1"), OwnVariables.Var("A", "2"))))
        assertIs<Outcome.Failure>(OwnVariables.check((0..OwnVariables.MAX).map { OwnVariables.Var("V$it", "x") }))
        val env = OwnVariables.merge(listOf(OwnVariables.Var("JAVA_OPTS", "-Xmx1g"), OwnVariables.Var("TZ", "UTC")), mapOf("PATH" to "/bin", "JAVA_OPTS" to "-Xmx2g"))
        assertEquals(mapOf("JAVA_OPTS" to "-Xmx2g", "TZ" to "UTC", "PATH" to "/bin"), env, "окружение профиля сильнее своих переменных")
    }
}
