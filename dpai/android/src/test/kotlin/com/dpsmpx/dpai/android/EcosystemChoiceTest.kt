package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Источник манифеста экосистемы (D-114): выбор по умолчанию и смена — с выключением политики. */
class EcosystemChoiceTest {
    private val registry = EcosystemChoice.Source.REGISTRY
    private val file = EcosystemChoice.Source.FILE

    @Test
    fun defaultKeepsTheFileChosenBeforeTheRegistryExisted() {
        assertEquals(file, EcosystemChoice.defaultSource(registry = false, saved = registry, fileKept = false), "нет реестра — только файл")
        assertEquals(file, EcosystemChoice.defaultSource(registry = true, saved = null, fileKept = true), "файл выбран до D-114 — он и остаётся")
        assertEquals(registry, EcosystemChoice.defaultSource(registry = true, saved = null, fileKept = false))
        assertEquals(registry, EcosystemChoice.defaultSource(registry = true, saved = registry, fileKept = true), "выбор человека — главный")
    }

    @Test
    fun switchingSourceTurnsThePolicyOff() {
        var disabled = 0
        val ok = { disabled++; Outcome.success(Unit) }
        assertEquals(true to null, EcosystemChoice.switch(file, file, policyOn = true, disable = ok), "тот же источник — без действий")
        assertEquals(0, disabled)
        assertEquals(true to null, EcosystemChoice.switch(file, registry, policyOn = false, disable = ok), "политика выключена — выключать нечего")
        assertEquals(0, disabled)
        val (changed, note) = EcosystemChoice.switch(file, registry, policyOn = true, disable = ok)
        assertTrue(changed)
        assertEquals(1, disabled)
        assertTrue(note!!.contains("политика прогона выключена"))
        val (refused, why) = EcosystemChoice.switch(registry, file, policyOn = true) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "нет места") }
        assertFalse(refused, "политика не выключилась — источник не меняется")
        assertTrue(why!!.contains("нет места"))
        assertNull(EcosystemChoice.switch(registry, registry, policyOn = false) { error("не зовётся") }.second)
    }
}
