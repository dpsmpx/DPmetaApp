package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.files.TreeArtifacts.Artifact
import kotlin.test.Test
import kotlin.test.assertEquals

/** Подпись сборок (D-178): ключ проекта → глобальный → нет; какие APK подписать после сборки. */
class SigningChoiceTest {
    @Test
    fun projectKeyWinsThenGlobalThenNone() {
        assertEquals(SigningKeys.Choice.PROJECT, SigningKeys.choose(hasProject = true, hasGlobal = true))
        assertEquals(SigningKeys.Choice.PROJECT, SigningKeys.choose(hasProject = true, hasGlobal = false))
        assertEquals(SigningKeys.Choice.GLOBAL, SigningKeys.choose(hasProject = false, hasGlobal = true))
        assertEquals(SigningKeys.Choice.NONE, SigningKeys.choose(hasProject = false, hasGlobal = false))
    }

    @Test
    fun onlyFreshUnsignedApksOfTheBuild() {
        val started = 1_000_000L
        val items = listOf(
            Artifact("app/build/outputs/apk/debug/app-debug.apk", 10, started + 50),
            Artifact("app/build/outputs/apk/release/app-release.apk", 10, started + 60),
            Artifact("app/build/outputs/apk/release/app-release-signed.apk", 10, started + 70),
            Artifact("lib/build/old.apk", 10, started - 1),
            Artifact("app/build/outputs/bundle/app.aab", 10, started + 80),
            Artifact("other/new.apk", 10, started + 90),
            Artifact("other/new-signed.apk", 10, started + 10),
        )
        assertEquals(listOf("app/build/outputs/apk/debug/app-debug.apk", "other/new.apk"), SignAfterBuild.candidates(items, started).map { it.path },
            "свежие APK; подписанная копия новее — уже готово; старше начала сборки, AAB и сами -signed — мимо")
        assertEquals(emptyList(), SignAfterBuild.candidates(items, null), "начало сборки неизвестно — без спроса не подписывать")
        val many = (1..15).map { Artifact("out/a$it.apk", 1, started + it) }
        assertEquals(SignAfterBuild.MAX_APKS, SignAfterBuild.candidates(many, started).size)
        assertEquals("out/app-signed.apk", SignAfterBuild.signedPath("out/app.apk"))
        assertEquals("app-signed.apk", SignAfterBuild.signedPath("app.apk"))
    }
}
