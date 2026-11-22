package com.dpsmpx.dpmeta.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** D-176: рабочая модель не приносит реестр и роли автора в приложение. */
class RetiredRegistryModelTest {
    @Test
    fun authorRegistryTypesAreAbsentFromRuntime() {
        for (name in listOf("Registry", "ProjectEntry", "Place", "IdSource")) {
            assertFailsWith<ClassNotFoundException>(name) {
                Class.forName("com.dpsmpx.dpmeta.model.$name")
            }
        }
    }
}
