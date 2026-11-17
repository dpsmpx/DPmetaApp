package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Копия секретов не обходит вход, если окно Activity уже недоступно. */
class BackupUnlockTest {
    @Test
    fun missingWindowRefusesBothExportAndRestore() {
        for (restore in listOf(false, true)) {
            var calls = 0
            var result: Outcome<Unit>? = null
            AppSecrets.unlockAll(null, includeAbsent = restore) { calls++; result = it }
            assertEquals(1, calls)
            assertEquals(DiagCode.DEVICE_AUTH_REQUIRED, assertIs<Outcome.Failure>(result).errors.first().code)
        }
    }
}
