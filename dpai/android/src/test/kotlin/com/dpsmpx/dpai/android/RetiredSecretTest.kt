package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Старые копии: снятый компонент пропускается, опечатка или чужой id не скрывает ошибку. */
class RetiredSecretTest {
    @Test
    fun onlyExplicitlyRetiredSecretIsSkipped() {
        val id = "test-backup-retired-component"
        val external = AppSecrets.External(id, "Проверка", null, { false },
            { Outcome.success(null) }, { _, _ -> Outcome.success(Unit) }, { Outcome.success(Unit) })
        try {
            AppSecrets.register(external)
            assertSame(external, assertIs<Outcome.Success<AppSecrets.External?>>(AppSecrets.externalForImport(id)).value)
            AppSecrets.retireExternal(id)
            assertNull(assertIs<Outcome.Success<AppSecrets.External?>>(AppSecrets.externalForImport(id)).value)
            assertIs<Outcome.Failure>(AppSecrets.externalForImport("test-backup-unknown-component"))
            AppSecrets.register(external)
            assertSame(external, assertIs<Outcome.Success<AppSecrets.External?>>(AppSecrets.externalForImport(id)).value)
        } finally {
            AppSecrets.retireExternal(id)
        }
    }
}
