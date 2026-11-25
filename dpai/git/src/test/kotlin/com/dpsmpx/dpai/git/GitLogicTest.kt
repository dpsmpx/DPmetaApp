package com.dpsmpx.dpai.git

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import com.dpsmpx.dpai.testkit.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Логика Git без процессов: разбор статуса, политика пересечений, команды (ТЗ, §30, §31, §70). */
class GitLogicTest {
    @Test
    fun forcedOperationsKeepTheHighestSharedLevel() {
        // Каталог рисков независимо от вычисляемых destructive/writes:
        // перенос типа не должен незаметно разрешить force-команду без одобрения.
        assertEquals(
            setOf(GitOp.RESET_HARD, GitOp.CLEAN_FORCE, GitOp.PUSH_FORCE,
                GitOp.BRANCH_DELETE_FORCE, GitOp.WORKTREE_REMOVE_FORCE),
            GitOp.entries.filter { it.level == ActionLevel.DANGEROUS }.toSet(),
        )
    }

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(61))

    @Test fun developmentDiffUsesLiteralPathsExactHeadAndNoProjectExecutables() {
        val c = GitCommands(ApprovalLedger())
        val argv = c.argv(GitOp.DIFF_FILES, "/project", listOf("a".repeat(40), "./*"), clock.now()).valueOrNull()!!
        for (flag in listOf("--no-optional-locks", "--literal-pathspecs", "--no-ext-diff", "--no-textconv", "--ignore-submodules=all")) assertTrue(flag in argv)
        assertEquals(listOf("a".repeat(40), "--", "./*"), argv.takeLast(3))
        assertIs<Outcome.Failure>(c.argv(GitOp.DIFF_FILES, "/project", listOf("HEAD", "file"), clock.now()))
        assertIs<Outcome.Failure>(c.argv(GitOp.DIFF_FILES, "/project", listOf("a".repeat(40)), clock.now()))
        assertEquals(ActionLevel.READ, GitOp.DIFF_FILES.level)
    }

    @Test
    fun porcelainV2IsParsedStrictly() {
        val lines = listOf(
            "# branch.oid 0123456789abcdef0123456789abcdef01234567",
            "# branch.head task/a",
            "# branch.upstream origin/task/a",
            "# branch.ab +2 -1",
            "1 .M N... 100644 100644 100644 aaa bbb src/Main.kt",
            "2 R. N... 100644 100644 100644 aaa bbb R100 src/New.kt\tsrc/Old.kt",
            "u UU N... 100644 100644 100644 100644 a b c conflict.txt",
            "? notes with space.txt",
        )
        val s = GitStatus.parse(lines).valueOrNull()!!
        assertEquals("task/a", s.branch)
        assertEquals(Measure.observed(2), s.ahead)
        assertEquals(listOf("src/Main.kt", "src/New.kt"), s.changed)
        assertEquals(listOf("conflict.txt"), s.conflicted)
        assertEquals(listOf("notes with space.txt"), s.untracked)
        assertIs<Measure.Unknown>(GitStatus.parse(listOf("# branch.head main")).valueOrNull()!!.ahead, "без upstream — неизвестно, а не 0")
        assertEquals(DiagCode.GIT_OUTPUT_INVALID, (GitStatus.parse(listOf("garbage")) as Outcome.Failure).errors.single().code)
    }

    @Test
    fun overlapPolicy() {
        val p = OverlapPolicy()
        val a = ids.next(IdKinds.SESSION)
        val b = ids.next(IdKinds.SESSION)
        assertEquals(OverlapVerdict.ALLOW, p.evaluate(setOf("src/a/X.kt"), mapOf(a to setOf("notes/Y.txt"))).verdict)
        assertEquals(OverlapVerdict.WARN, p.evaluate(setOf("src/a/X.kt"), mapOf(a to setOf("src/a/Z.kt"))).verdict)
        val same = p.evaluate(setOf("src/a/X.kt"), mapOf(a to setOf("src/a/X.kt"), b to setOf("notes/Y.txt")))
        assertEquals(OverlapVerdict.SERIALIZE, same.verdict)
        assertEquals(mapOf(a to setOf("src/a/X.kt")), same.with)
        assertEquals(OverlapVerdict.REJECT, p.evaluate(setOf("gradle/libs.versions.toml"), mapOf(a to setOf("gradle/libs.versions.toml"))).verdict)
    }

    /** Свойство: вердикт не слабее вердикта по каждой сессии и не зависит от порядка сессий. */
    @Test
    fun overlapIsWorstOfPairsAndOrderFree() {
        val files = listOf("a/1", "a/2", "b/1", "build.gradle.kts", "c/migrations/1.sql")
        val set = Gen.list(Gen.element(files), 0..3).map { it.toSet() }
        Property.forAll(Gen.list(set, 1..4)) { sets ->
            val touched = sets.first()
            val others = sets.drop(1).associateBy { ids.next(IdKinds.SESSION) }
            val p = OverlapPolicy()
            val all = p.evaluate(touched, others).verdict
            for ((s, f) in others) assertTrue(all.ordinal >= p.evaluate(touched, mapOf(s to f)).verdict.ordinal)
            assertEquals(all, p.evaluate(touched, others.entries.reversed().associate { it.key to it.value }).verdict)
        }
    }

    @Test
    fun commandsAreSafeByConstruction() {
        val c = GitCommands(ApprovalLedger())
        val add = c.argv(GitOp.ADD, "/w", listOf("a.txt"), clock.now()).valueOrNull()!!
        assertEquals(listOf("git") + GitCommands.HARDENING + listOf("-C", "/w", "add", "--", "a.txt"), add)
        assertEquals(DiagCode.VALUE_INVALID, (c.argv(GitOp.STATUS, "-oops", emptyList(), clock.now()) as Outcome.Failure).errors.single().code)
        // Чтение — без необязательного index.lock и с путями в UTF-8; запись — без этого (ревью 17 октября 2026).
        val status = c.argv(GitOp.STATUS, "/w", emptyList(), clock.now()).valueOrNull()!!
        assertEquals(listOf("git", "--no-optional-locks") + GitCommands.HARDENING + listOf("-c", "core.quotePath=false", "-C", "/w", "status", "--porcelain=v2", "--branch"), status)
        assertTrue("--no-optional-locks" !in add)
        // Карта кода (D-171): чтение без блокировки, пути в UTF-8, выражение — после `-e`, пути — после `--`.
        val grep = c.argv(GitOp.GREP, "/w", listOf("(class|fun)", "src", "app/src"), clock.now()).valueOrNull()!!
        assertEquals(listOf("git", "--no-optional-locks") + GitCommands.HARDENING + listOf("-c", "core.quotePath=false", "-C", "/w", "grep", "-n", "-I", "--no-color", "--full-name", "-w", "-E", "-e", "(class|fun)", "--", "src", "app/src"), grep)
        assertIs<Outcome.Failure>(c.argv(GitOp.GREP, "/w", listOf("(class)", "--exec=x"), clock.now()), "путь-параметр отвергнут")
        for (op in GitOp.entries.filter { it.destructive }) {
            val r = c.argv(op, "/w", if (op == GitOp.BRANCH_DELETE_FORCE || op == GitOp.WORKTREE_REMOVE_FORCE || op == GitOp.PUSH_FORCE) listOf("x") else emptyList(), clock.now())
            assertEquals(DiagCode.APPROVAL_REQUIRED, (r as Outcome.Failure).errors.single().code, op.name)
        }
        // Без разрушительных флагов в обычных операциях.
        for (op in GitOp.entries.filter { !it.destructive }) {
            val args = when (op) {
                GitOp.COMMIT, GitOp.WORKTREE_REMOVE, GitOp.MERGE, GitOp.BRANCH_CREATE, GitOp.ADD, GitOp.PUSH, GitOp.GREP -> listOf("x")
                GitOp.WORKTREE_ADD -> listOf("/p", "b")
                GitOp.MERGE_BASE, GitOp.MERGES_BETWEEN -> listOf("main", "HEAD")
                GitOp.DIFF_FROM, GitOp.DIFF_STAT_FROM, GitOp.REV_PARSE -> listOf("main")
                GitOp.DIFF_FILES -> listOf("a".repeat(40), "./file")
                else -> emptyList()
            }
            val argv = c.argv(op, "/w", args, clock.now()).valueOrNull() ?: error("$op")
            assertTrue(argv.none { it in setOf("--hard", "--force", "-f", "-D", "-fd", "--force-with-lease") }, "$op: $argv")
        }
    }
}
