package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Служба клонирования (D-78) поднимается, только когда синхронизация
 * клонирует: среди отмеченных есть клон, которого на телефоне ещё нет.
 */
class MissingClonesTest {
    private val root = Files.createTempDirectory("pult-clones-test").toFile()
    private val clones = java.io.File(root, "clones")
    private val pool = Executors.newSingleThreadExecutor()
    private val repo = PultRepository(NioVfs(java.io.File(root, "state").toPath()), Clock.SYSTEM, pool, clones)

    @Test
    fun newCloneNeedsTheService() {
        assertFalse(repo.missingClones(emptySet()), "ничего не отмечено — служба не нужна")
        assertTrue(repo.missingClones(setOf("dpsmpx/Alpha")), "клона ещё нет — синхронизация клонирует")
    }

    @Test
    fun existingClonesDoNot() {
        java.io.File(clones, "dpsmpx/Alpha/.git").mkdirs()
        assertFalse(repo.missingClones(setOf("dpsmpx/Alpha")), "клон есть — обычная синхронизация, без службы и уведомления")
        assertTrue(repo.missingClones(setOf("dpsmpx/Alpha", "dpsmpx/Beta")), "один новый — уже клонирование")
        java.io.File(clones, "dpsmpx/Gamma").mkdirs()
        assertTrue(repo.missingClones(setOf("dpsmpx/Gamma")), "каталог без .git — не клон")
    }
}
