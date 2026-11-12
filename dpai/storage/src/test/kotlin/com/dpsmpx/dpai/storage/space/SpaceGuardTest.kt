package com.dpsmpx.dpai.storage.space

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.space.SpaceGuard.Facts
import com.dpsmpx.dpai.storage.space.SpaceGuard.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Защита места (DPmetaApp:D-113): предел автора, `0` — всё свободное, жёсткий запас 1 ГБ. */
class SpaceGuardTest {
    private val gb = 1L shl 30
    private val mb = 1L shl 20

    @Test
    fun reserveOfOneGigabyteIsHard() {
        val f = Facts(free = 2 * gb, used = 5 * gb, limit = 0)
        assertIs<Outcome.Success<Unit>>(SpaceGuard.check(f, Kind.DOWNLOAD, gb, "модель"), "ровно 1 ГБ остаётся — можно")
        val r = assertIs<Outcome.Failure>(SpaceGuard.check(f, Kind.DOWNLOAD, gb + mb, "модель"))
        assertEquals(DiagCode.STORAGE_SPACE_LOW, r.errors.single().code)
        assertTrue("меньше запаса 1,0 ГБ" in r.errors.single().message, r.errors.single().message)
    }

    @Test
    fun zeroLimitMeansAllFreeSpace() {
        val f = Facts(free = 40 * gb, used = 30 * gb, limit = SpaceGuard.limitOf(0))
        assertIs<Outcome.Success<Unit>>(SpaceGuard.check(f, Kind.SERVICE, 980 * mb, "kotlin-lsp"))
        assertEquals(0, SpaceGuard.limitOf(-3))
    }

    @Test
    fun limitCountsTheWholeApp() {
        val f = Facts(free = 40 * gb, used = 19 * gb, limit = SpaceGuard.limitOf(20))
        assertIs<Outcome.Success<Unit>>(SpaceGuard.check(f, Kind.DOWNLOAD, 900 * mb, "pyright"))
        val r = assertIs<Outcome.Failure>(SpaceGuard.check(f, Kind.DOWNLOAD, 2 * gb, "модель 7B"))
        assertTrue("из предела 20,0 ГБ" in r.errors.single().message, r.errors.single().message)
        val unknown = assertIs<Outcome.Failure>(SpaceGuard.check(f.copy(used = null), Kind.DOWNLOAD, mb, "файл"))
        assertTrue("не удалось узнать" in unknown.errors.single().message, "неизвестное — не ноль")
    }

    @Test
    fun unknownSizeTakesTheKindEstimate() {
        val f = Facts(free = gb + 900 * mb, used = null, limit = 0)
        val r = assertIs<Outcome.Failure>(SpaceGuard.check(f, Kind.BUILD, null, "Galdhold"))
        assertTrue("оценка" in r.errors.single().message)
        assertIs<Outcome.Success<Unit>>(SpaceGuard.check(f, Kind.PACKAGES, null, "git"), "пакеты — 256 МБ оценки")
        assertEquals("1,5 ГБ", SpaceGuard.size(gb + gb / 2))
        assertEquals("1 МБ", SpaceGuard.size(1))
    }
}
