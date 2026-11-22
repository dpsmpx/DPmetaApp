package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpmeta.app.screens.PublishActions
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpai.core.git.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Опубликовать на GitHub» (D-150): имя, команда уровня 1 и адрес только GitHub. */
class PublishActionsTest {
    private val project = Screens.LocalProject("prj-1", "hello-app", "телефон", "/root/projects/local/hello-app", selected = true)

    @Test
    fun nameRules() {
        assertNull(PublishActions.invalid("hello-app"))
        assertNull(PublishActions.invalid("My.Game_2"))
        for (bad in listOf("", " ", ".", "..", "a b", "x.git", "а", "a".repeat(101))) assertNotNull(PublishActions.invalid(bad), bad)
    }

    @Test
    fun commandIsLevelOneWithPrivateRepository() {
        val repo = RepoRef.of("example", "hello-app").valueOrNull()!!
        val c = PublishActions.command(repo, project)
        assertEquals(ActionKind.REPO_CREATE, c.kind)
        assertEquals(ActionLevel.REVERSIBLE, c.level, "уровень 1: окно экрана, без PIN")
        assertEquals("example/hello-app", c.target.display)
        assertEquals("https://github.com/example/hello-app.git", PublishActions.url(repo))
        assertTrue("hello-app" in PublishActions.description(c))
        val preview = PublishActions.preview(project, "example", "hello-app").joinToString("\n")
        assertTrue("закрытый репозиторий example/hello-app" in preview && "Administration: write" in preview && "main" in preview, preview)
    }
}
