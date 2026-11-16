package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Пакеты Alpine гостя (D-191): синтетическая база в формате `lib/apk/db/installed`
 * apk-tools 3 (поля сверены с базой Alpine 3.24). Живая сверка с `apk del -s` —
 * [AlpinePackagesLiveTest].
 */
class AlpinePackagesTest {
    @TempDir
    lateinit var dir: Path

    private val db = """
        C:Q1musl=
        P:musl
        V:1.2.6-r3
        A:aarch64
        S:400000
        I:800000
        T:the musl c library
        U:https://musl.libc.org/
        L:MIT
        o:musl
        t:1776707195
        p:so:libc.musl-aarch64.so.1=1
        F:lib
        R:ld-musl-aarch64.so.1

        C:Q1busybox=
        P:busybox
        V:1.37.0-r31
        I:900000
        D:so:libc.musl-aarch64.so.1

        C:Q1binsh=
        P:busybox-binsh
        V:1.37.0-r31
        I:100
        D:busybox=1.37.0-r31
        F:bin
        R:sh

        P:apk-tools
        V:3.0.8-r0
        I:200000
        D:musl libapk=3.0.8-r0

        P:libapk
        V:3.0.8-r0
        I:300000
        D:musl

        P:alpine-baselayout
        V:3.7.2-r1
        I:10000
        D:/bin/sh

        P:alpine-keys
        V:2.6-r0
        I:20000

        C:Q1KlCtqvgihl9rC6XaaIME3EjkW8c=
        P:git
        V:2.54.0-r0
        A:aarch64
        S:3546724
        I:7032728
        T:Distributed version control system
        U:https://www.git-scm.com/
        L:GPL-2.0-only
        D:so:libc.musl-aarch64.so.1 so:libcurl.so.4 pcre2>=10.40
        p:cmd:git=2.54.0-r0

        P:libcurl
        V:8.23.0-r0
        I:600000
        D:musl
        p:so:libcurl.so.4=4.8.0

        P:pcre2
        V:10.49-r0
        I:700000

        P:git-bash-completion
        V:2.54.0-r0
        I:1000
        i:git bash-completion

        P:bash-completion
        V:2.16-r0
        I:2000

        P:nodejs
        V:24.9.0-r0
        I:40000000
        D:musl

        P:npm
        V:11.12.1-r0
        I:8000000
        D:nodejs

        P:lonely
        V:1.0-r0
    """.trimIndent()

    private val world = "alpine-baselayout alpine-keys apk-tools busybox git bash-completion nodejs npm\n"

    private fun inv(text: String = db, w: String = world) = AlpinePackages.parse(text, w, "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n# заметка\nhttps://dl-cdn.alpinelinux.org/alpine/v3.24/community\n", "3.24.3\n")

    @Test
    fun parsesFieldsWorldAndRepositories() {
        val i = inv()
        assertEquals("3.24.3", i.release)
        assertEquals(2, i.repositories.size, "заметка — не репозиторий")
        val git = i.byName["git"]!!
        assertEquals("2.54.0-r0", git.version)
        assertEquals(7032728L, git.installedSize)
        assertEquals("Q1KlCtqvgihl9rC6XaaIME3EjkW8c=", git.checksum)
        assertEquals("GPL-2.0-only", git.license)
        assertEquals(listOf("so:libc.musl-aarch64.so.1", "so:libcurl.so.4", "pcre2"), git.depends.map { it.name })
        assertEquals(">=" to "10.40", git.depends[2].op to git.depends[2].version)
        assertNull(i.byName["lonely"]!!.installedSize, "нет поля I: — размер неизвестен, а не ноль")
        assertTrue("git" in i.worldNames && "pcre2" !in i.worldNames)
        assertEquals(listOf("git"), i.requiredBy["pcre2"])
        assertEquals(listOf("busybox-binsh"), i.satisfying(AlpinePackages.dependency("/bin/sh")!!).map { it.name }, "зависимость от файла — по владельцу файла")
    }

    @Test
    fun dependencySyntax() {
        val d = AlpinePackages.dependency("!foo")!!
        assertTrue(d.conflict)
        assertEquals("foo", d.name)
        assertEquals(Triple("python3", "~", "3.12"), AlpinePackages.dependency("python3~3.12")!!.let { Triple(it.name, it.op, it.version) })
        assertEquals("llvm", AlpinePackages.dependency("llvm@edge")!!.name, "метка репозитория — не часть имени")
        assertTrue(AlpinePackages.dependency("/bin/sh")!!.path)
        assertNull(AlpinePackages.dependency("a b"))
    }

    @Test
    fun consistentDatabaseHasNoProblems() {
        assertEquals(emptyList(), AlpinePackages.problems(inv()))
        assertEquals(listOf("lonely"), AlpinePackages.orphans(inv()).map { it.name })
    }

    @Test
    fun problemsBetweenPackages() {
        val broken = db
            .replace("D:so:libc.musl-aarch64.so.1 so:libcurl.so.4 pcre2>=10.40", "D:so:libc.musl-aarch64.so.1 so:libcurl.so.4 pcre2>=11 !nodejs")
            .replace("P:npm\nV:11.12.1-r0\nI:8000000\nD:nodejs", "P:npm\nV:11.12.1-r0\nI:8000000\nD:nodejs libuv\nf:f")
        val p = AlpinePackages.problems(inv(broken, "$world python3\n"))
        val kinds = p.map { it.kind to it.pkg }
        assertTrue(AlpinePackages.ProblemKind.VERSION to "git" in kinds, p.toString())
        assertTrue(AlpinePackages.ProblemKind.CONFLICT to "git" in kinds, p.toString())
        assertTrue(AlpinePackages.ProblemKind.MISSING to "npm" in kinds, p.toString())
        assertTrue(AlpinePackages.ProblemKind.BROKEN to "npm" in kinds, p.toString())
        assertTrue(AlpinePackages.ProblemKind.WORLD to "python3" in kinds, p.toString())
        assertTrue(p.first { it.kind == AlpinePackages.ProblemKind.VERSION }.detail.contains("pcre2 10.49-r0"))
    }

    @Test
    fun unverifiableVersionIsNotAProblem() {
        val odd = db.replace("pcre2>=10.40", "pcre2>=not.a.version")
        assertEquals(emptyList(), AlpinePackages.problems(inv(odd)), "неверная запись версии — «не сверено», а не «несовместимо»")
    }

    @Test
    fun removalTakesOnlyWhatNothingElseNeeds() {
        val i = inv()
        val git = AlpinePackages.removal(i, "git")
        assertTrue(git.allowed)
        assertEquals(listOf("git", "pcre2", "libcurl", "git-bash-completion"), git.gone.map { it.name }, "больший первым; install_if уходит вместе с git")
        assertEquals(7032728L + 700000 + 600000 + 1000, git.freed)
        val node = AlpinePackages.removal(i, "nodejs")
        assertTrue(node.allowed && node.gone.isEmpty(), "nodejs нужен npm — остаётся")
        assertEquals(listOf("npm"), node.keptFor)
        assertFalse(AlpinePackages.removal(i, "busybox").allowed, "часть системы")
        val dep = AlpinePackages.removal(i, "pcre2")
        assertFalse(dep.allowed)
        assertTrue(dep.reason!!.contains("git"))
        assertNull(dep.freed)
        val unknown = AlpinePackages.removal(inv(db.replace("P:pcre2\nV:10.49-r0\nI:700000", "P:pcre2\nV:10.49-r0")), "git")
        assertNull(unknown.freed, "у кого-то размер неизвестен — итог неизвестен")
    }

    @Test
    fun readsFromRootfsAndRefusesSymlinks() {
        val vfs = NioVfs(dir)
        assertNull((AlpinePackages.read(vfs, "rootfs") as Outcome.Success).value, "базы нет — среды нет")
        Files.createDirectories(dir.resolve("rootfs/lib/apk/db"))
        Files.createDirectories(dir.resolve("rootfs/etc/apk"))
        Files.writeString(dir.resolve("rootfs/lib/apk/db/installed"), db)
        Files.writeString(dir.resolve("rootfs/etc/apk/world"), world)
        Files.writeString(dir.resolve("rootfs/etc/alpine-release"), "3.24.3\n")
        val ok = (AlpinePackages.read(vfs, "rootfs") as Outcome.Success).value!!
        assertEquals(15, ok.packages.size)
        assertTrue(ok.repositories.isEmpty(), "нет файла репозиториев — пустой список")
        Files.writeString(dir.resolve("secret"), "токен")
        Files.delete(dir.resolve("rootfs/etc/apk/world"))
        val link = runCatching { Files.createSymbolicLink(dir.resolve("rootfs/etc/apk/world"), dir.resolve("secret")) }.isSuccess
        assumeTrue(link)
        val r = AlpinePackages.read(vfs, "rootfs")
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, r.errors.single().code)
    }
}
