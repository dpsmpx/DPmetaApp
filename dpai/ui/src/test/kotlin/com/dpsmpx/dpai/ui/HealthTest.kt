package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверка приложения (DPmetaApp:D-123, D-158): пункты по фактам, шаги мастера, причины
 * отказа `apk` и автор коммитов. Факты — как в отчёте автора 13 ноября 2026: повреждённые
 * binutils, gcc, g++, Git без автора, токен GitHub не выдан Linux, медленный такт.
 */
class HealthTest {
    private val reported = HealthFacts(
        linux = LinuxFacts(
            installed = true,
            broken = listOf(BrokenPackage("binutils", "не все файлы"), BrokenPackage("gcc", "не все файлы"), BrokenPackage("g++", "не все файлы")),
            git = true, gitHelper = true, gitIdentity = false, claude = true,
        ),
        slots = 1, loggedIn = true, githubToken = true, githubForAi = false,
        tick = TickFacts(maxMs = 71_342, avgMs = 60_000, slowest = "шаг контура", slowestMs = 70_900),
        freeBytes = 13L shl 30,
    )

    @Test
    fun reportedProblemsBecomeItemsWithActions() {
        val items = Health.items(reported)
        assertEquals(listOf("linux.broken", "git.identity", "git.token", "tick.slow"), items.map { it.id })
        val broken = items.first()
        assertEquals(HealthLevel.ERROR, broken.level)
        assertEquals("Пакеты Linux повреждены: binutils, gcc, g++", broken.title)
        assertTrue("жёсткие ссылки" in broken.detail && "«N errors»" in broken.detail, broken.detail)
        assertEquals(listOf(HealthAction.REPAIR_PACKAGES), broken.actions)
        assertEquals(listOf(HealthAction.GIT_IDENTITY), items[1].actions)
        assertEquals(listOf(HealthAction.OPEN_SECRETS), items[2].actions)
        assertTrue("71 с" in items[3].detail && "шаг контура (71 с)" in items[3].detail, items[3].detail)
        assertTrue(items.drop(1).all { it.level == HealthLevel.WARNING })
    }

    @Test
    fun healthyAppHasNoItems() {
        val ok = reported.copy(
            linux = reported.linux!!.copy(broken = emptyList(), gitIdentity = true),
            githubForAi = true, tick = TickFacts(120, 15),
        )
        assertEquals(emptyList(), Health.items(ok))
        assertTrue(Health.setupDone(ok))
        // Без замера — тоже ничего.
        assertEquals(emptyList(), Health.items(ok.copy(tick = null)))
        // Токен не сохранён — не пункт проверки, а шаг мастера.
        assertEquals(emptyList(), Health.items(ok.copy(githubToken = false, githubForAi = false)))
        // GitHub — по желанию (DPmetaApp:D-150): без токена настройка закончена.
        assertTrue(Health.setupDone(ok.copy(githubToken = false)))
        assertTrue(Health.setup(ok).single { it.id == "github" }.optional)
    }

    @Test
    fun linuxNotInstalledIsASetupStepNotAnError() {
        val fresh = HealthFacts(linux = LinuxFacts(installed = false), githubToken = false)
        assertEquals(emptyList(), Health.items(fresh))
        val steps = Health.setup(fresh)
        assertEquals(listOf("linux", "claude", "login", "github", "git"), steps.map { it.id })
        assertEquals(HealthAction.INSTALL_LINUX, steps[0].action)
        assertEquals(false, steps[0].done)
        assertNull(steps[1].action, "Claude Code — после Linux")
        assertEquals(HealthAction.OPEN_SECRETS, steps[3].action)
        assertFalse(Health.setupDone(fresh))
        // Не arm64: Linux к устройству не относится — шаг не мешает «настроено».
        val noLinux = Health.setup(HealthFacts(linux = null, loggedIn = true, githubToken = true))
        assertNull(noLinux[0].done)
        assertNull(noLinux[0].action)
        assertTrue(Health.setupDone(HealthFacts(linux = null, loggedIn = true, githubToken = true)))
    }

    @Test
    fun dnsForeignGitConfigAndLowSpace() {
        val items = Health.items(HealthFacts(linux = LinuxFacts(installed = true, git = true, gitIdentity = true, gitConfigForeign = true, dns = false), freeBytes = 1L shl 30))
        assertEquals(listOf("linux.dns", "git.config", "space.low"), items.map { it.id })
        assertTrue("1.0 ГБ" in items[2].detail, items[2].detail)
    }

    @Test
    fun apkReasonNamesTheCause() {
        assertEquals("нет связи с репозиторием Alpine (сеть или DNS) — подключитесь к сети и повторите",
            Health.apkReason("ERROR: mpc1-1.3.1-r1: DNS: transient error (try again later)\n3 errors; 740.4 MiB in 86 packages"))
        assertEquals("Android не дал записать файлы пакета (Permission denied)",
            Health.apkReason("ERROR: binutils-2.45.1-r1: failed to extract usr/bin/ar: Permission denied"))
        assertEquals("кончилось место", Health.apkReason("write error: No space left on device"))
        assertEquals("3 errors; 777.0 MiB in 94 packages", Health.apkReason("(1/3) Upgrading git\n\u001b[1m3 errors; 777.0 MiB in 94 packages\u001b[0m\n\n"))
        assertEquals("причина не названа", Health.apkReason(""))
    }

    @Test
    fun gitIdentityNeedsNameAndEmailInUserSection() {
        assertTrue(Health.gitIdentity("[user]\n\tname = Автор\n\temail = a@b.c\n"))
        assertTrue(Health.gitIdentity("[core]\n\teditor = vi\n[User]\nname=\"A B\" # комментарий\nEmail = a@b\n"))
        assertFalse(Health.gitIdentity("[user]\n\tname = Автор\n"))
        assertFalse(Health.gitIdentity("[core]\n\tname = x\n\temail = a@b\n"))
        assertFalse(Health.gitIdentity("[user]\n\tname =\n\temail = a@b\n"))
        assertFalse(Health.gitIdentity(""))
    }

    @Test
    fun identityFieldsAreChecked() {
        assertNull(Health.identityNameProblem("Автор Проекта"))
        assertNotNull(Health.identityNameProblem(" "))
        assertNotNull(Health.identityNameProblem("a\nb"))
        assertNotNull(Health.identityNameProblem("x".repeat(101)))
        assertNull(Health.identityEmailProblem("author@users.noreply.github.com"))
        for (bad in listOf("", "a b@c", "a@b@c", "@b", "a@", "noat", "a@b\n")) assertNotNull(Health.identityEmailProblem(bad), bad)
    }
}
