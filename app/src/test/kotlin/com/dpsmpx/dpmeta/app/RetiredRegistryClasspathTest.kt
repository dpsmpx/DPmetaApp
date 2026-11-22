package com.dpsmpx.dpmeta.app

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Исторические кодеки корпуса не становятся зависимостями приложения. */
class RetiredRegistryClasspathTest {
    @Test
    fun authorProcessCodecsAreAbsentFromApplicationDependencies() {
        for (name in listOf("RegistryFormat", "DecisionLog", "TodoList", "Roadmap")) {
            assertFailsWith<ClassNotFoundException>(name) {
                Class.forName("com.dpsmpx.dpmeta.formats.$name")
            }
        }
    }
}
