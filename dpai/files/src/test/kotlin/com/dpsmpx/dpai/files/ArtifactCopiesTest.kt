package com.dpsmpx.dpai.files

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ArtifactCopiesTest {
    @TempDir lateinit var dir: Path

    @Test fun sameBasenameDoesNotMixProjectsTargetsOrVersions() {
        val a = ArtifactCopies.relative("target", "/project-a", "out/app.apk", 4, 1000)
        assertNotEquals(a, ArtifactCopies.relative("target", "/project-b", "out/app.apk", 4, 1000))
        assertNotEquals(a, ArtifactCopies.relative("another", "/project-a", "out/app.apk", 4, 1000))
        assertNotEquals(a, ArtifactCopies.relative("target", "/project-a", "out/app.apk", 4, 2000))
        assertEquals(a, ArtifactCopies.relative("target", "/project-a", "out/app.apk", 4, 1000))
    }

    @Test fun completedCopySurvivesNextLookupAndPartialOrUnknownVersionIsNotReused() {
        val rel = ArtifactCopies.relative("t", "/p", "out/app.apk", 4, 1000)
        val path = dir.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path.resolveSibling(".part-app.apk"), "data")
        assertNull(ArtifactCopies.local(dir.toFile(), "t", "/p", "out/app.apk", 4, 1000))
        Files.move(path.resolveSibling(".part-app.apk"), path)
        assertEquals(path.toFile(), ArtifactCopies.local(dir.toFile(), "t", "/p", "out/app.apk", 4, 1000))
        Files.writeString(path.resolveSibling(".part-orphan"), "incomplete")
        ArtifactCopies.clearIncomplete(dir.toFile())
        assertEquals("data", Files.readString(path))
        assertEquals(false, Files.exists(path.resolveSibling(".part-orphan")))
        assertNull(ArtifactCopies.local(dir.toFile(), "t", "/p", "out/app.apk", 4, 2000))
        assertNull(ArtifactCopies.local(dir.toFile(), "t", "/p", "out/app.apk", 4, 0))
        Files.writeString(path, "bad")
        assertNull(ArtifactCopies.local(dir.toFile(), "t", "/p", "out/app.apk", 4, 1000))
    }
}
